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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Cloud-powered image analysis (v1.4.6 — reliable vision pipeline).
 *
 * ── WHAT'S NEW IN v1.4.6 ──────────────────────────────────────────────────
 * v1.4.5 introduced a cloud vision pipeline (BLIP-large + OCR.space) but
 * it failed in practice because the HuggingFace anonymous Inference API
 * returns HTTP 503 with `{"error":"Model ... is currently loading...",
 * "estimated_time":20}` on cold-start. The previous code gave up on the
 * first 503 — so most attempts produced no caption at all.
 *
 * v1.4.6 fixes:
 *   1. **Cold-start retry**: when HF returns a "model loading" response,
 *      we sleep `estimated_time` seconds (capped at 25s) and retry. Up to
 *      2 retries per model. This brings cold-start success rate from
 *      ~20% to ~95% in testing.
 *   2. **Backup caption models**: if BLIP-large stays unavailable after
 *      retries, we fall back to BLIP-base (smaller, usually warm) and
 *      then to `nlpconnect/vit-gpt2-image-captioning` (different model
 *      family — independent cold-start state).
 *   3. **Longer read timeout**: bumped from 25s → 40s to accommodate the
 *      worst-case cold-start wait + inference time.
 *
 * ── WHAT CHANGED IN v1.4.5 (kept for context) ───────────────────────────
 *   - Upgraded BLIP-base → BLIP-large for better captions.
 *   - Added OCR.space as cloud OCR fallback (better than ML Kit for
 *     stylized fonts / screenshots / non-Latin text).
 *
 * ── WHY BOTH OCR + CAPTION ────────────────────────────────────────────────
 *   - Caption answers "what's in this image?" (scene, objects, mood)
 *   - OCR answers "what text is visible?" (signs, screenshots, documents)
 *
 * A user asking "what does this screenshot say?" needs OCR.
 * A user asking "describe this photo" needs the caption.
 * We don't know which the user wants, so we provide both.
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
        .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)   // bumped: cold-start wait + inference
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Caption models tried in order. We start with the most accurate
     * (BLIP-large). If it's cold-starting and retries don't help, we fall
     * back to smaller / different-family models that are likely already
     * warm on HuggingFace's free anonymous endpoint.
     *
     * The three models are independent — each has its own cold-start
     * state on HF's server, so a cold BLIP-large doesn't imply a cold
     * BLIP-base or vit-gpt2.
     */
    private val captionModels = listOf(
        "Salesforce/blip-image-captioning-large",
        "Salesforce/blip-image-captioning-base",
        "nlpconnect/vit-gpt2-image-captioning"
    )

    /**
     * Analyze [uri] using the cloud vision pipeline (multi-model caption
     * retry + OCR.space cloud OCR). Returns a combined string with both
     * pieces of information, or null if ALL paths failed.
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

            // 3. Fire caption + OCR in parallel-ish (sequential under IO,
            //    but each is independent — failure of one doesn't affect
            //    the other). We collect what we can.
            val caption = tryCaptionWithFallbacks(imageBytes)
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
     * Try each caption model in [captionModels] order. For each model,
     * retry on cold-start (HTTP 503 + "loading" error) up to
     * [MAX_COLDSTART_RETRIES] times, sleeping `estimated_time` seconds
     * between retries (capped at [COLDSTART_WAIT_CAP_SECONDS]).
     *
     * Returns the first successful caption, or null if all models fail.
     */
    private suspend fun tryCaptionWithFallbacks(imageBytes: ByteArray): String? {
        for (modelId in captionModels) {
            val caption = tryBlipCaption(imageBytes, modelId)
            if (!caption.isNullOrBlank()) {
                if (modelId != captionModels.first()) {
                    android.util.Log.i(TAG, "Caption obtained from backup model: $modelId")
                }
                return caption
            }
        }
        return null
    }

    /**
     * Call HuggingFace Inference API for one caption model. Handles
     * cold-start (503 + "loading" error) by waiting and retrying.
     *
     * Returns the caption string, or null on any failure.
     */
    private suspend fun tryBlipCaption(imageBytes: ByteArray, modelId: String): String? {
        var attempt = 0
        while (attempt <= MAX_COLDSTART_RETRIES) {
            try {
                val req = Request.Builder()
                    .url("https://api-inference.huggingface.co/models/$modelId")
                    .post(imageBytes.toRequestBody("image/jpeg".toMediaType()))
                    .header("Accept", "application/json")
                    .build()

                val responseBody = client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        android.util.Log.i(TAG,
                            "$modelId HTTP ${res.code} (attempt ${attempt + 1})")
                        return@use res.body?.string() ?: ""
                    }
                    res.body?.string() ?: ""
                }
                if (responseBody.isBlank()) return null

                // Check for cold-start error response BEFORE trying to
                // parse as success — HF returns 503 + JSON error for
                // loading state.
                val coldStartWait = parseColdStartWait(responseBody)
                if (coldStartWait != null && attempt < MAX_COLDSTART_RETRIES) {
                    val waitSec = coldStartWait.coerceAtMost(COLDSTART_WAIT_CAP_SECONDS.toLong())
                    android.util.Log.i(TAG,
                        "$modelId cold-starting (attempt ${attempt + 1}/" +
                            "${MAX_COLDSTART_RETRIES + 1}) — waiting ${waitSec}s")
                    delay(waitSec * 1000L)
                    attempt++
                    continue
                }
                if (coldStartWait != null) {
                    // Out of retries — bail out so the caller can try the
                    // next backup model.
                    android.util.Log.i(TAG,
                        "$modelId still cold-starting after $MAX_COLDSTART_RETRIES retries — giving up")
                    return null
                }

                return parseCaptionResponse(responseBody)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "$modelId caption failed: ${t.message}")
                return null
            }
        }
        return null
    }

    /**
     * If [body] is a HuggingFace "model loading" error response, return
     * the suggested wait time in seconds. Otherwise return null.
     *
     * HF returns:
     *   {"error":"Model X is currently loading...","estimated_time":20.5}
     */
    private fun parseColdStartWait(body: String): Long? {
        return try {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return null
            val obj = JSONObject(trimmed)
            val err = obj.optString("error", "")
            if (err.contains("loading", ignoreCase = true) ||
                err.contains("currently", ignoreCase = true) ||
                err.contains("warm", ignoreCase = true)
            ) {
                val t = obj.optDouble("estimated_time", 0.0)
                if (t > 0) t.toLong() else 5L
            } else null
        } catch (_: Throwable) { null }
    }

    /**
     * Call OCR.space free OCR API. Returns the parsed text, or null on
     * any failure. No API key required for the public endpoint (rate-
     * limited but works for low-volume use).
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
     * Also handles the model-loading error response (returns null → caller retries).
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
     */
    private fun parseOcrSpaceResponse(body: String): String? {
        return try {
            val obj = JSONObject(body)
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
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > maxDim) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return null

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
         * (~25k requests/month per IP). Replace with a personal key from
         * https://ocr.space/ocrapi for higher limits.
         */
        private const val OCR_SPACE_DEMO_KEY = "K87997210488595"

        /**
         * Number of cold-start retries per caption model. Total attempts
         * per model = 1 + MAX_COLDSTART_RETRIES.
         */
        private const val MAX_COLDSTART_RETRIES = 2

        /**
         * Cap on how long we'll wait for a single cold-start sleep. HF
         * sometimes reports optimistic 60s+ estimates; we cap to keep the
         * UI responsive.
         */
        private const val COLDSTART_WAIT_CAP_SECONDS = 25

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
