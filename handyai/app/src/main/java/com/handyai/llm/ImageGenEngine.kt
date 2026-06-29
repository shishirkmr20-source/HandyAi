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
     * v1.4.7: The user's chosen default Pollinations model id (e.g.
     * "flux", "flux-realism", "turbo"). Set from the Models page via
     * [setActiveModelId]. When null/blank, defaults to "flux".
     *
     * Volatile — read on the IO thread, written from the UI thread.
     * No synchronization needed because Pollinations endpoints are
     * stateless; a torn read just falls back to "flux" for one call.
     */
    @Volatile
    private var activeModelId: String = "flux"

    /**
     * v1.4.7: Set the default image-gen model. Called by
     * ModelSettingsViewModel.activate() when the user picks an IMAGE_GEN
     * model from the Models page. The id is the catalog ModelSpec.id,
     * which we map to the Pollinations model id via ModelCatalog.
     */
    fun setActiveModelId(cloudModelId: String) {
        activeModelId = cloudModelId.ifBlank { "flux" }
        android.util.Log.i(TAG, "Active image-gen model set to '$activeModelId'")
    }

    /** v1.4.7: Returns the currently-active Pollinations model id. */
    fun activeModelId(): String = activeModelId

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
     *
     * Uses the default model (flux, 512x512 square). For other models /
     * aspect ratios, use [generateWithOptions].
     */
    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        generateWithOptions(prompt, ImageGenOptions())
    }

    /**
     * v1.4.7: Generate with full control over model + dimensions.
     *
     * Pollinations supports several free models:
     *   - "flux"         — default, best quality
     *   - "flux-realism" — photorealistic
     *   - "flux-anime"   — anime style
     *   - "flux-3d"      — 3D render style
     *   - "turbo"        — fastest (~3-5s), lower quality
     *
     * Aspect ratio presets are encoded as (width, height) pairs.
     *
     * v1.4.7: When [opts].model is the default "flux" AND the user has
     * picked a different model on the Models page, the user's pick wins.
     * Per-draw flags (--turbo etc.) still override the Models-page default.
     */
    suspend fun generateWithOptions(prompt: String, opts: ImageGenOptions): Result<String> = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Prompt is empty"))
        }

        // v1.4.7: Resolve the effective model. Per-draw flags (opts.model
        // explicitly set to something other than the catalog default "flux")
        // take priority. Otherwise, use the Models-page selection. Fall
        // back to "flux" if neither is set.
        val effectiveModel = when {
            opts.model != "flux" -> opts.model  // per-draw override
            activeModelId.isNotBlank() -> activeModelId  // Models-page default
            else -> "flux"  // hard fallback
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
                Log.i(TAG, "Attempt $attempt/$MAX_ATTEMPTS for prompt (${prompt.length} chars) model=$effectiveModel size=${opts.width}x${opts.height}")
                val attemptSeed = seed + attempt  // vary seed to bypass cache on retry

                val urlStr = buildString {
                    append("https://image.pollinations.ai/prompt/").append(encoded)
                    append("?width=").append(opts.width)
                    append("&height=").append(opts.height)
                    append("&nologo=true")
                    append("&seed=").append(attemptSeed)
                    append("&model=").append(effectiveModel)
                    if (opts.enhance) append("&enhance=true")
                }

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

/**
 * v1.4.7: Options for image generation. Allows the user to pick a model
 * variant and aspect ratio via /draw flags (e.g. `/draw --turbo wide: a
 * landscape at sunset`).
 *
 * Defaults match the previous behavior: flux model, 512x512 square.
 */
data class ImageGenOptions(
    /** Pollinations model id. See ImageGenEngine kdoc for the full list. */
    val model: String = "flux",
    val width: Int = 512,
    val height: Int = 512,
    /**
     * When true, Pollinations runs the prompt through an LLM to expand it
     * with extra detail before generating. Slower but often higher
     * quality. Off by default for speed.
     */
    val enhance: Boolean = false
) {
    companion object {
        /**
         * Parse /draw options from a raw user-typed prompt. Recognizes:
         *   --turbo            use turbo model (faster, lower quality)
         *   --realism          use flux-realism
         *   --anime            use flux-anime
         *   --3d               use flux-3d
         *   --enhance          turn on prompt enhancement
         *   --wide             16:9 landscape (768x432)
         *   --tall             9:16 portrait (432x768)
         *   --square           1:1 (512x512) — default, explicit form
         *   --size WxH         explicit dimensions, e.g. --size 1024x768
         *
         * Anything not recognized as a flag is left in the returned
         * prompt string. Flags can appear in any order, anywhere in the
         * input.
         */
        fun parse(rawPrompt: String): Pair<String, ImageGenOptions> {
            var model = "flux"
            var width = 512
            var height = 512
            var enhance = false

            val tokens = rawPrompt.split(Regex("\\s+")).toMutableList()
            val kept = mutableListOf<String>()
            val iter = tokens.iterator()
            while (iter.hasNext()) {
                val tok = iter.next()
                when (tok.lowercase()) {
                    "--turbo" -> model = "turbo"
                    "--realism", "--photo" -> model = "flux-realism"
                    "--anime" -> model = "flux-anime"
                    "--3d", "--render" -> model = "flux-3d"
                    "--enhance", "--detail" -> enhance = true
                    "--wide", "--landscape", "--16:9" -> { width = 768; height = 432 }
                    "--tall", "--portrait", "--9:16" -> { width = 432; height = 768 }
                    "--square", "--1:1" -> { width = 512; height = 512 }
                    "--size" -> {
                        if (iter.hasNext()) {
                            val sizeSpec = iter.next()
                            val m = Regex("(\\d+)[x×](\\d+)").find(sizeSpec)
                            if (m != null) {
                                width = m.groupValues[1].toIntOrNull()?.coerceIn(128, 1024) ?: 512
                                height = m.groupValues[2].toIntOrNull()?.coerceIn(128, 1024) ?: 512
                            } else {
                                kept.add(tok); kept.add(sizeSpec)
                            }
                        } else {
                            kept.add(tok)
                        }
                    }
                    else -> kept.add(tok)
                }
            }
            val cleanPrompt = kept.joinToString(" ").trim()
            return cleanPrompt to ImageGenOptions(model, width, height, enhance)
        }
    }
}
