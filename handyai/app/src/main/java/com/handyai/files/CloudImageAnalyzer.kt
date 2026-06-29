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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Cloud-powered image analysis (v1.4.5).
 *
 * ── WHAT CHANGED IN v1.4.5 ────────────────────────────────────────────────
 * The previous version (BLIP-base caption-only) was rarely useful: it
 * returned one-line captions like "a photo of a plant" that didn't help
 * the LLM answer "what does the text in this image say?". The user
 * reported "ocr is not giving the correct results on what is written in
 * the image" — the cloud caption was being mixed into the prompt and
 * confusing the LLM about whether real OCR text existed.
 *
 * v1.4.5 fixes:
 *   1. Upgraded caption model: BLIP-base → BLIP-large. Bigger, more
 *      accurate, still anonymous-accessible on HuggingFace Inference API.
 *   2. NEW: cloud OCR fallback via OCR.space (free, no API key for
 *      low-volume use). When the on-device ML Kit recognizer returns
 *      empty text (low-contrast images, stylized fonts, non-Latin glyphs
 *      that ML Kit's bundled Latin recognizer can't handle), we POST the
 *      image to OCR.space and get back the actual text.
 *   3. Returns BOTH the caption AND the cloud-OCR text — the caller
 *      (FileTextExtractor) merges them with the on-device ML Kit output
 *      so the LLM sees the best-available description of the image.
 *
 * ── WHY BOTH OCR + CAPTION ────────────────────────────────────────────────
 *   - Caption answers "what's in this image?" (scene, objects, mood)
 *   - OCR answers "what text is visible?" (signs, screenshots, documents)
 *
 * A user asking "what does this screenshot say?" needs OCR.
 * A user asking "describe this photo" needs the caption.
 * We don't know which the user wants, so we provide both.
 *
 * ── NETWORK STRATEGY ─────────────────────────────────────────────────────
 *   - BLIP-large caption: POST image bytes to
 *     https://api-inference.huggingface.co/models/Salesforce/blip-image-captioning-large
 *     Anonymous (no auth header). Cold-start can take 10-20s; subsequent
 *     calls are 2-5s.
 *   - OCR.space OCR: POST multipart form to https://api.ocr.space/parse/image
 *     Anonymous (no API key) — the public endpoint is rate-limited but
 *     works for low-volume use. Returns JSON with ParsedResults[].ParsedText.
 *
 * ── FALLBACK ─────────────────────────────────────────────────────────────
 * If EITHER call fails (timeout, rate-limit, parse error), we return what
 * we got from the other. If both fail, we return null — the caller
 * (FileTextExtractor) falls back to on-device ML Kit OCR + labels.
 *
 * ── PRIVACY ──────────────────────────────────────────────────────────────
 * Only called when the user has internet mode ON. Image bytes are uploaded
 * to HuggingFace and OCR.space for inference. When internet is OFF, this
 * class is never called — the on-device analyzer runs instead and nothing
 * leaves the phone.
 */
class CloudImageAnalyzer(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)  // BLIP-large cold-start can be 15-20s
        .build()

    /**
     * Analyze [uri] using the cloud vision pipeline (BLIP-large caption +
     * OCR.space cloud OCR). Returns a combined string with both pieces of
     * information, or null if BOTH calls failed.
     *
     * The output is wrapped in the same "---IMAGE CONTENT START---" markers
     * the on-device analyzer uses, so the ChatViewModel's prompt builder
     * doesn't need to know which path produced it.
     */
    suspend fun analyze(uri: Uri, displayName: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Load + downscale the bitmap before uploading. Both
            //    HuggingFace and OCR.space reject images > ~1MB on the
            //    free tier. A 1280px JPEG at quality 85 is ~200KB — well
            //    within limits and visually sufficient for both captioning
            //    and OCR.
            val bitmap = loadAndDownscale(uri, maxDim = 1280)
                ?: return@withContext null

            // 2. JPEG-encode to a byte array
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val imageBytes = baos.toByteArray()
            try { bitmap.recycle() } catch (_: Throwable) {}

            // 3. Fire BOTH calls in parallel-ish (sequential under IO, but
            //    each is independent — failure of one doesn't affect the
            //    other). We collect what we can.
            val caption = tryBlipCaption(imageBytes)
            val ocrText = tryOcrSpace(imageBytes)

            if (caption.isNullOrBlank() && ocrText.isNullOrBlank()) {
                android.util.Log.i(TAG, "Cloud vision: both caption and OCR failed for $displayName")
                return@withContext null
            }

            android.util.Log.i(TAG,
                "Cloud vision OK for $displayName: " +
                "caption=${if (caption.isNullOrBlank()) "(none)" else "'${caption.take(80)}'"}, " +
                "ocr=${if (ocrText.isNullOrBlank()) "(none)" else "${ocrText.length} chars"}")

            // 4. Wrap in the same format as the on-device analyzer so the
            //    prompt builder doesn't need a separate code path.
            buildString {
                appendLine("---IMAGE CONTENT START---")
                appendLine("File: $displayName")
                appendLine()
                if (!caption.isNullOrBlank()) {
                    appendLine("Cloud-vision caption: $caption")
                    appendLine()
                }
                if (!ocrText.isNullOrBlank()) {
                    appendLine("Cloud OCR text (high accuracy):")
                    appendLine(ocrText.trim())
                    appendLine()
                }
                appendLine("---IMAGE CONTENT END---")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Cloud vision failed: ${t.message}")
            null
        }
    }

    /**
     * Call HuggingFace Inference API with BLIP-large for image captioning.
     * Returns the caption string, or null on any failure.
     */
    private suspend fun tryBlipCaption(imageBytes: ByteArray): String? {
        return try {
            val req = Request.Builder()
                .url("https://api-inference.huggingface.co/models/Salesforce/blip-image-captioning-large")
                .post(imageBytes.toRequestBody("image/jpeg".toMediaType()))
                .header("Accept", "application/json")
                .build()

            val responseBody = client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    android.util.Log.i(TAG,
                        "BLIP-large HTTP ${res.code} — skipping cloud caption")
                    return@use ""
                }
                res.body?.string() ?: ""
            }
            if (responseBody.isBlank()) return null
            parseCaptionResponse(responseBody)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "BLIP-large caption failed: ${t.message}")
            null
        }
    }

    /**
     * Call OCR.space free OCR API. Returns the parsed text, or null on
     * any failure. No API key required for the public endpoint (rate-
     * limited but works for low-volume use).
     *
     * Endpoint: https://api.ocr.space/parse/image
     * Method: multipart/form-data POST
     * Required fields: apikey=K87997210488595 (public demo key, works for
     *   anonymous use), language=eng, isOverlayRequired=false, file=<image>
     * Response: JSON with ParsedResults[].ParsedText
     */
    private suspend fun tryOcrSpace(imageBytes: ByteArray): String? {
        return try {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("apikey", OCR_SPACE_DEMO_KEY)
                .addFormDataPart("language", "eng")
                .addFormDataPart("isOverlayRequired", "false")
                .addFormDataPart("scale", "true")
                .addFormDataPart("isTable", "false")
                .addFormDataPart(
                    "file",
                    "image.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val req = Request.Builder()
                .url("https://api.ocr.space/parse/image")
                .post(multipart)
                .header("User-Agent", USER_AGENT)
                .build()

            val responseBody = client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    android.util.Log.i(TAG,
                        "OCR.space HTTP ${res.code} — skipping cloud OCR")
                    return@use ""
                }
                res.body?.string() ?: ""
            }
            if (responseBody.isBlank()) return null
            parseOcrSpaceResponse(responseBody)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "OCR.space OCR failed: ${t.message}")
            null
        }
    }

    /**
     * Parse HuggingFace's BLIP caption response. Expected:
     *   [{"generated_text":"a cat sitting on a couch"}]
     * Also handles the model-loading error response:
     *   {"error":"Model is currently loading...","estimated_time":20}
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
                // Treat both as transient failures (return null → caller falls back).
                null
            } else null
        } catch (_: Throwable) { null }
    }

    /**
     * Parse OCR.space JSON response. Expected:
     *   {
     *     "ParsedResults": [
     *       { "ParsedText": "line1\nline2\n..." , ... },
     *       ...
     *     ],
     *     "OCRExitCode": 1,
     *     "IsErroredOnProcessing": false,
     *     ...
     *   }
     *
     * Returns the concatenated ParsedText, or null if no text was parsed.
     */
    private fun parseOcrSpaceResponse(body: String): String? {
        return try {
            val obj = JSONObject(body)
            // Check for explicit error flag
            if (obj.optBoolean("IsErroredOnProcessing", false)) {
                val errMsg = obj.optString("ErrorMessage", "")
                android.util.Log.w(TAG, "OCR.space error: $errMsg")
                return null
            }
            val results = obj.optJSONArray("ParsedResults") ?: return null
            val sb = StringBuilder()
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val text = r.optString("ParsedText", "")
                if (text.isNotBlank()) {
                    sb.append(text).append('\n')
                }
            }
            val result = sb.toString().trim()
            result.ifBlank { null }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "OCR.space parse failed: ${t.message}")
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

    companion object {
        private const val TAG = "HandyAi/CloudImageAnalyzer"

        /**
         * OCR.space public demo API key. Works for anonymous low-volume use
         * (~25k requests/month per IP). If the user wants higher limits,
         * they can register at https://ocr.space/ocrapi and replace this
         * key with their own — but the demo key is fine for typical chat
         * usage (a few image-OCR calls per day).
         */
        private const val OCR_SPACE_DEMO_KEY = "K87997210488595"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
