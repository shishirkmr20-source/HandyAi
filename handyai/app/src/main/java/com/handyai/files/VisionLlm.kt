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
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * True cloud multimodal Vision LLM (v1.4.7).
 *
 * ── WHAT THIS IS ──────────────────────────────────────────────────────────
 * The existing CloudImageAnalyzer returns a *caption* ("a cat on a couch")
 * plus OCR text. That's useful as context for the text LLM, but it can't
 * answer user questions like:
 *
 *   - "What's the model number on the device in this photo?"
 *   - "Is this food safe for someone with nut allergies?"
 *   - "What's funny about this meme?"
 *   - "Translate the sign in this image."
 *
 * A real VLM (Vision-Language Model) takes the image + the user's
 * question and returns a natural-language ANSWER. That's what this class
 * does — it's a chat-style multimodal LLM call, not just a captioner.
 *
 * ── API ──────────────────────────────────────────────────────────────────
 * Uses HuggingFace's free anonymous Inference API with the
 * `chat-completions` style endpoint. The conversation payload has the
 * form OpenAI-style multimodal models use:
 *
 *   {
 *     "model": "<vlm-id>",
 *     "messages": [
 *       {
 *         "role": "user",
 *         "content": [
 *           { "type": "text",      "text": "<question>" },
 *           { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,…" } }
 *         ]
 *       }
 *     ],
 *     "max_tokens": 512,
 *     "temperature": 0.3
 *   }
 *
 * Endpoint: https://api-inference.huggingface.co/models/<vlm-id>/v1/chat/completions
 *
 * ── MODEL FALLBACK CHAIN ────────────────────────────────────────────────
 * HF free tier keeps each model in/out of memory independently. To
 * maximize the chance that AT LEAST ONE model is warm, we try in order:
 *
 *   1. meta-llama/Llama-3.2-11B-Vision-Instruct   (strongest open VLM;
 *      11B params; usually warm on HF free tier)
 *   2. llava-hf/llava-v1.6-mistral-7b-hf          (Mistral-7B base, very
 *      reliable, smaller)
 *   3. llava-hf/llava-1.5-7b-hf                   (older LLaVA 1.5, often
 *      warm as a fallback)
 *
 * Each model gets up to 2 cold-start retries (sleeping
 * `estimated_time` seconds, capped at 25s).
 *
 * ── PRIVACY ──────────────────────────────────────────────────────────────
 * Image bytes are uploaded to HuggingFace for inference. Only called
 * when the user has internet mode ON. When OFF, ChatViewModel skips
 * this class entirely and falls back to the existing
 * CloudImageAnalyzer-caption + on-device-OCR pipeline (which itself
 * runs the on-device-only path when offline).
 */
class VisionLlm(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)  // VLMs are slower than captioners
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * VLM models tried in order. See class kdoc for the rationale.
     */
    private val vlmModels = listOf(
        "meta-llama/Llama-3.2-11B-Vision-Instruct",
        "llava-hf/llava-v1.6-mistral-7b-hf",
        "llava-hf/llava-1.5-7b-hf"
    )

    /**
     * Ask a [question] about the image at [uri]. Returns a natural-language
     * answer from the VLM, or null if all models failed (caller should
     * fall back to the caption+OCR pipeline).
     *
     * The question is wrapped with a brief system instruction so even
     * models without a strong system-prompt capability (LLaVA 1.5) get
     * the "answer the user's question about the image" framing.
     */
    suspend fun ask(uri: Uri, question: String): String? = withContext(Dispatchers.IO) {
        if (question.isBlank()) return@withContext null

        // 1. Load + downscale the bitmap. VLMs handle ~768px images well;
        //    larger just wastes bandwidth and slows the call.
        val bitmap = loadAndDownscale(uri, maxDim = 768)
            ?: return@withContext null

        // 2. JPEG-encode to bytes, then Base64 for the data URL.
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val imageBytes = baos.toByteArray()
        try { bitmap.recycle() } catch (_: Throwable) {}

        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$b64"

        // 3. Build the OpenAI-style multimodal chat payload.
        val payload = buildPayload(question, dataUrl)

        // 4. Try each VLM model in order with cold-start retries.
        for (modelId in vlmModels) {
            val answer = tryVlmCall(modelId, payload)
            if (!answer.isNullOrBlank()) {
                if (modelId != vlmModels.first()) {
                    android.util.Log.i(TAG, "VLM answer from backup model: $modelId")
                }
                return@withContext answer
            }
        }
        android.util.Log.w(TAG, "All VLM models failed for question (${question.length} chars)")
        null
    }

    /**
     * Build the JSON payload for the chat-completions call.
     */
    private fun buildPayload(question: String, dataUrl: String): String {
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "$question\n\nLook at the attached image carefully and answer the question above. If the question can't be answered from the image alone, say so briefly.")
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", dataUrl))
            })
        }
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
        }
        val payload = JSONObject().apply {
            put("model", "")  // overwritten per-call
            put("messages", messages)
            put("max_tokens", 512)
            put("temperature", 0.3)
        }
        return payload.toString()
    }

    /**
     * Call one VLM model. Cold-start retries up to [MAX_COLDSTART_RETRIES]
     * times. Returns the assistant's text answer, or null on failure.
     */
    private suspend fun tryVlmCall(modelId: String, payloadTemplate: String): String? {
        // Inject the model id into the JSON payload (faster than re-encoding
        // each time — we just string-replace the empty "model" field).
        val payload = payloadTemplate.replace(
            "\"model\":\"\"",
            "\"model\":\"$modelId\""
        )

        var attempt = 0
        while (attempt <= MAX_COLDSTART_RETRIES) {
            try {
                val req = Request.Builder()
                    .url("https://api-inference.huggingface.co/models/$modelId/v1/chat/completions")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .build()

                val responseBody = client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        android.util.Log.i(TAG,
                            "$modelId HTTP ${res.code} (attempt ${attempt + 1})")
                        // Read body even on failure — HF returns JSON error
                        // in the body for 503 cold-starts.
                        return@use res.body?.string() ?: ""
                    }
                    res.body?.string() ?: ""
                }
                if (responseBody.isBlank()) return null

                // Check for cold-start error response BEFORE trying to
                // parse as success.
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
                    android.util.Log.i(TAG,
                        "$modelId still cold-starting after $MAX_COLDSTART_RETRIES retries — giving up")
                    return null
                }

                return parseChatCompletion(responseBody)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "$modelId VLM call failed: ${t.message}")
                return null
            }
        }
        return null
    }

    /**
     * Parse a chat-completions response. Expected:
     *   {
     *     "choices": [
     *       {
     *         "message": { "role": "assistant", "content": "The image shows…" },
     *         "finish_reason": "stop"
     *       }
     *     ],
     *     "usage": {…}
     *   }
     *
     * Returns the assistant message content, or null on parse failure.
     */
    private fun parseChatCompletion(body: String): String? {
        return try {
            val obj = JSONObject(body)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val first = choices.optJSONObject(0) ?: return null
            val message = first.optJSONObject("message") ?: return null
            message.optString("content").ifBlank { null }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "parseChatCompletion failed: ${t.message}")
            null
        }
    }

    /**
     * If [body] is a HuggingFace "model loading" error response, return
     * the suggested wait time in seconds. Otherwise return null.
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
     * Load the bitmap from [uri], downscale so the longest edge is at
     * most [maxDim] pixels. Returns null on decode failure.
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
        private const val TAG = "HandyAi/VisionLlm"

        /** Number of cold-start retries per VLM model. */
        private const val MAX_COLDSTART_RETRIES = 2

        /** Cap on a single cold-start sleep (seconds). */
        private const val COLDSTART_WAIT_CAP_SECONDS = 25
    }
}
