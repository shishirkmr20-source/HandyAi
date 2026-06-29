/*
 * HandyAi — on-device AI chat for Android.
 * Copyright 2026 HandyAi Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.handyai.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Cloud-powered image analysis fallback for when the device is online.
 *
 * WHY THIS EXISTS
 * ───────────────
 * The on-device [ImageAnalyzer] uses ML Kit, which is fast and free but
 * limited: it produces short labels (single words like "Plant", "Coffee")
 * and OCR text only. It cannot describe scenes, interpret charts, or
 * understand what's happening in a photo.
 *
 * When the user is online, this class calls a free cloud vision model
 * (BLIP image captioning on HuggingFace Inference API) to produce a
 * natural-language description of the image. The description is then
 * fed to the on-device LLM as part of the attachment context — so the
 * LLM can answer "what's in this image?" with a real description
 * instead of just a list of labels.
 *
 * NETWORK STRATEGY
 * ────────────────
 *   - Endpoint: https://api-inference.huggingface.co/models/Salesforce/blip-image-captioning-base
 *   - Method: POST with the image bytes as the raw body
 *   - Auth: none required for low-volume use (rate-limited per IP)
 *   - Response: JSON array [{"generated_text": "..."}]
 *
 * The model is small (BLIP-base, ~250MB) so inference typically takes
 * 2-5 seconds. HuggingFace caches the model after the first cold start.
 *
 * FALLBACK
 * ────────
 * If the network call fails (timeout, rate limit, no internet), this
 * class returns null — the caller ([FileTextExtractor]) then falls
 * back to the on-device [ImageAnalyzer]. The user gets the best
 * available result without ever seeing an error from this class.
 *
 * PRIVACY
 * ───────
 * When online AND the user has attached an image, the image bytes are
 * uploaded to HuggingFace for inference. The user has implicitly
 * consented to this by enabling internet mode (the toggle in the chat
 * input bar). When internet is OFF, this class is never called — the
 * on-device analyzer runs instead and nothing leaves the phone.
 */
class CloudImageAnalyzer(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)  // BLIP can take 10-15s on cold start
        .build()

    /**
     * Analyze [uri] using the cloud vision model. Returns a natural-language
     * description, or null if the call failed for any reason (network,
     * rate-limit, parse error).
     *
     * The description is wrapped in the same "---IMAGE CONTENT START---"
     * markers the on-device analyzer uses, so the ChatViewModel's prompt
     * builder doesn't need to know which path produced it.
     */
    suspend fun analyze(uri: Uri, displayName: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Load + downscale the bitmap before uploading. HuggingFace
            //    rejects images > ~1MB on the free tier. A 1024px JPEG at
            //    quality 85 is ~150KB — well within limits and visually
            //    sufficient for captioning.
            val bitmap = loadAndDownscale(uri, maxDim = 1024)
                ?: return@withContext null

            // 2. JPEG-encode to a byte array
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val imageBytes = baos.toByteArray()
            try { bitmap.recycle() } catch (_: Throwable) {}

            // 3. POST to HuggingFace Inference API (no auth header = anonymous)
            val req = Request.Builder()
                .url("https://api-inference.huggingface.co/models/Salesforce/blip-image-captioning-base")
                .post(imageBytes.toRequestBody("image/jpeg".toMediaType()))
                .header("Accept", "application/json")
                .build()

            val responseBody = client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    android.util.Log.i(TAG,
                        "Cloud vision HTTP ${res.code} — falling back to on-device")
                    return@use ""
                }
                res.body?.string() ?: ""
            }
            if (responseBody.isBlank()) return@withContext null

            // 4. Parse JSON: expected format is [{"generated_text":"..."}]
            //    HuggingFace sometimes returns an error object instead —
            //    {"error":"Model is currently loading..."} — which we
            //    treat as a transient failure (return null → caller falls back).
            val caption = parseCaptionResponse(responseBody)
            if (caption.isNullOrBlank()) return@withContext null

            android.util.Log.i(TAG, "Cloud vision OK: \"$caption\" for $displayName")

            // 5. Wrap in the same format as the on-device analyzer so the
            //    prompt builder doesn't need a separate code path.
            buildString {
                appendLine("---IMAGE CONTENT START---")
                appendLine("File: $displayName")
                appendLine()
                appendLine("Image description (cloud vision): $caption")
                appendLine("---IMAGE CONTENT END---")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Cloud vision failed: ${t.message}")
            null
        }
    }

    /**
     * Load the bitmap from [uri], then downscale so the longest edge is
     * at most [maxDim] pixels. Returns null if the bitmap can't be decoded.
     */
    private fun loadAndDownscale(uri: Uri, maxDim: Int): Bitmap? {
        return try {
            // First pass: just read bounds
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Compute sample size so the decoded bitmap is ≤ maxDim on its
            // longest edge. This avoids decoding a 4000×3000 photo at full
            // resolution just to downscale it afterward.
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > maxDim) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return null

            // If still larger than maxDim, do a final scale via Bitmap.createScaledBitmap
            if (maxOf(decoded.width, decoded.height) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
                val newW = (decoded.width * scale).toInt()
                val newH = (decoded.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(decoded, newW, newH, true)
                if (scaled !== decoded) decoded.recycle()
                scaled
            } else decoded
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Bitmap load failed: ${t.message}")
            null
        }
    }

    /**
     * Parse HuggingFace's response. Expected: `[{"generated_text":"a cat sitting on a couch"}]`
     * Also handles the model-loading error response.
     */
    private fun parseCaptionResponse(body: String): String? {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                if (arr.length() == 0) return null
                val obj = arr.optJSONObject(0) ?: return null
                obj.optString("generated_text").ifBlank { null }
            } else if (trimmed.startsWith("{")) {
                // Error object: {"error":"..."} or {"estimated_time":20}
                val obj = JSONObject(trimmed)
                if (obj.has("error")) null else null
            } else null
        } catch (_: Throwable) { null }
    }

    companion object {
        private const val TAG = "HandyAi/CloudImageAnalyzer"
    }
}
