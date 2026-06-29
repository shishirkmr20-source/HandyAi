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
package com.handyai.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Cloud-based image generation via Pollinations.ai.
 *
 * WHY CLOUD, NOT ON-DEVICE
 * ========================
 * MediaPipe does NOT ship a public `ImageGenerator` API for Android
 * (the class exists in source but is omitted from the released Maven AAR
 * — experimental). The previous attempt used `com.google.mediapipe.tasks
 * .vision.imagegenerator.ImageGenerator`, which broke the release build
 * with "Unresolved reference" errors.
 *
 * The SD 1.5 .task file URL on HuggingFace (`mediapipe/stable_diffusion_1_5`)
 * returns HTTP 401 — the repo is gated/private. There is no public,
 * downloadable, MediaPipe-compatible image-gen .task file as of 2026-06.
 *
 * Pollinations.ai is the robust fallback:
 *   - Free, no API key, no rate limit (soft limit ~100 req/min)
 *   - Returns a JPEG directly via a simple HTTP GET
 *   - URL: https://image.pollinations.ai/prompt/{encoded}?width=512&height=512
 *   - Works on any Android with internet
 *   - Generation takes 5-15 seconds
 *
 * ROBUSTNESS FEATURES (v1.2.5)
 * ============================
 * Earlier versions sometimes failed silently because:
 *   - Default Java User-Agent was blocked by some firewalls/CDNs
 *   - Pollinations intermittently returns 502/503 under load
 *   - HttpURLConnection had buggy redirect handling on certain Android versions
 *
 * We now use OkHttp (already a project dependency) with:
 *   - Realistic browser User-Agent header
 *   - Referer header (Pollinations sometimes requires this)
 *   - Accept header for image content negotiation
 *   - Retry with exponential backoff on 5xx and timeout
 *   - Up to 3 attempts before giving up
 *
 * ARCHITECTURE
 * ============
 * This engine is always "ready" — there's no model to download or load.
 * The user types `/draw <prompt>` in any chat and the engine:
 *   1. URL-encodes the prompt
 *   2. HTTP GETs the Pollinations URL (with retry)
 *   3. Saves the returned JPEG to filesDir/generated_images/<timestamp>.png
 *   4. Returns the absolute path so ChatViewModel can persist it on the
 *      assistant message (MessageBubble renders it inline)
 *
 * The user can then long-press / tap the save icon on the image message
 * to download it to their gallery (handled in MessageBubble via MediaStore).
 */
class ImageGenEngine(private val context: Context) {

    private val _state = MutableStateFlow<ImageGenState>(ImageGenState.Ready)
    val state: StateFlow<ImageGenState> = _state.asStateFlow()

    /**
     * Shared OkHttp client. Configured with generous timeouts because
     * Pollinations generation can take 10-20 seconds under load.
     * Follows redirects (Pollinations sometimes 302s to a CDN URL).
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)   // generation can be slow
            .callTimeout(120, TimeUnit.SECONDS)  // hard cap per attempt
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Always true — cloud image gen needs no model loading. */
    fun isModelLoaded(): Boolean = true

    fun activeModelPath(): String? = null

    /** No-op for API compatibility. Cloud gen always succeeds. */
    suspend fun setActiveModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i(TAG, "setActiveModel('$path') — cloud image gen, no-op")
        _state.value = ImageGenState.Ready
        Result.success(Unit)
    }

    /**
     * Generate an image from [prompt] via Pollinations.ai and save it as
     * a PNG to app-private storage. Returns the absolute path.
     *
     * Retries up to 3 times on transient failures (5xx, timeout, network
     * blip) with exponential backoff (1s, 2s, 4s) before giving up.
     */
    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Prompt is empty"))
        }

        _state.value = ImageGenState.Generating
        try {
            val encoded = URLEncoder.encode(prompt, "UTF-8")
                .replace("+", "%20")  // Pollinations expects %20 for spaces, not +
            val seed = Random.nextInt(1, 1_000_000)

            // Try up to MAX_ATTEMPTS times. Pollinations is usually reliable
            // but occasionally returns 502 under heavy load — a retry with
            // a fresh seed typically succeeds.
            var lastError: Throwable? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                Log.i(TAG, "Attempt $attempt/$MAX_ATTEMPTS for prompt (${prompt.length} chars)")
                val attemptSeed = seed + attempt  // vary seed to bypass cache on retry

                val urlStr = "https://image.pollinations.ai/prompt/$encoded" +
                    "?width=512&height=512&nologo=true&seed=$attemptSeed&model=flux"

                val result = tryFetchImage(urlStr)
                if (result.isSuccess) {
                    val bytes = result.getOrThrow()
                    val saved = saveImageBytes(bytes, prompt)
                    _state.value = ImageGenState.Ready
                    return@withContext Result.success(saved)
                }

                lastError = result.exceptionOrNull()
                Log.w(TAG, "Attempt $attempt failed: ${lastError?.message}")

                // Don't sleep after the last attempt
                if (attempt < MAX_ATTEMPTS) {
                    val backoffMs = (1000L * (1 shl (attempt - 1)))  // 1s, 2s, 4s…
                    Log.i(TAG, "Backing off ${backoffMs}ms before retry…")
                    delay(backoffMs)
                }
            }

            // All attempts failed
            val msg = describeError(lastError)
            Log.e(TAG, "All $MAX_ATTEMPTS attempts failed. Last: $msg")
            _state.value = ImageGenState.Error(msg)
            Result.failure(lastError ?: IllegalStateException("Image generation failed"))
        } catch (t: Throwable) {
            Log.e(TAG, "Generation failed", t)
            val msg = describeError(t)
            _state.value = ImageGenState.Error(msg)
            Result.failure(t)
        }
    }

    /**
     * Single HTTP fetch attempt. Returns the raw image bytes on success,
     * or a Result.failure with a descriptive error on failure.
     */
    private fun tryFetchImage(urlStr: String): Result<ByteArray> {
        return try {
            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Accept", "image/jpeg, image/png, image/webp, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(
                        IllegalStateException("Pollinations returned HTTP ${response.code}")
                    )
                }

                val contentType = response.header("Content-Type") ?: ""
                // Be permissive: Pollinations sometimes returns "application/octet-stream"
                // for CDN-cached images. We validate by decoding below.
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty()) {
                    return Result.failure(IllegalStateException("Pollinations returned 0 bytes"))
                }

                // Verify it's actually a decodable image
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    return Result.failure(
                        IllegalStateException(
                            "Pollinations returned invalid image data (${bytes.size} bytes, " +
                                "content-type=$contentType)"
                        )
                    )
                }

                Log.i(TAG, "Got image: ${bytes.size} bytes, ${bitmap.width}x${bitmap.height}, type=$contentType")
                Result.success(bytes)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Save raw image bytes (JPEG/PNG/WebP) to app-private storage as PNG.
     * Returns the absolute file path.
     */
    private fun saveImageBytes(bytes: ByteArray, prompt: String): String {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode image bytes")

        val dir = File(context.filesDir, "generated_images").apply { if (!exists()) mkdirs() }
        val outFile = File(dir, "img_${System.currentTimeMillis()}.png")
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        Log.i(TAG, "Image saved to ${outFile.absolutePath} (${outFile.length()} bytes)")
        return outFile.absolutePath
    }

    /**
     * Convert an exception into a user-friendly message.
     */
    private fun describeError(t: Throwable?): String {
        if (t == null) return "Image generation failed"
        val rawMsg = t.message ?: t.javaClass.simpleName ?: "Generation failed"
        return when {
            rawMsg.contains("Unable to resolve host", ignoreCase = true) ||
                rawMsg.contains("UnknownHost", ignoreCase = true) ||
                rawMsg.contains("nodename nor servname", ignoreCase = true) ->
                "No internet connection. Image generation requires internet (Pollinations.ai cloud)."
            rawMsg.contains("timeout", ignoreCase = true) ||
                rawMsg.contains("SocketTimeout", ignoreCase = true) ||
                rawMsg.contains("Read timed out", ignoreCase = true) ->
                "Image generation timed out after $MAX_ATTEMPTS attempts. Pollinations.ai may be busy — try again."
            rawMsg.contains("Connection refused", ignoreCase = true) ->
                "Pollinations.ai is not responding. Try again later."
            rawMsg.contains("HTTP 5", ignoreCase = true) ->
                "Pollinations.ai is temporarily overloaded (5xx). Try again in a minute."
            rawMsg.contains("HTTP 4", ignoreCase = true) ->
                "Pollinations.ai rejected the request: $rawMsg"
            else -> rawMsg
        }
    }

    fun unload() {
        _state.value = ImageGenState.Ready
    }

    companion object {
        private const val TAG = "HandyAi/ImageGenEngine"

        /** Max HTTP fetch attempts before giving up. */
        private const val MAX_ATTEMPTS = 3

        /**
         * Realistic browser User-Agent. Default Java User-Agent
         * ("Java/1.x") is blocked by some CDNs and firewalls.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * Referer header. Pollinations sometimes requires this to distinguish
         * browser traffic from bots.
         */
        private const val REFERER = "https://pollinations.ai/"
    }
}

sealed interface ImageGenState {
    data object Idle : ImageGenState
    data object Loading : ImageGenState
    data object Ready : ImageGenState
    data object Generating : ImageGenState
    data class Error(val message: String) : ImageGenState
}
