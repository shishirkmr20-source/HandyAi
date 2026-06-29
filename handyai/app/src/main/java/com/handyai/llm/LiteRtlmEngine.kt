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
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Wraps Google's LiteRT-LM runtime (alpha05) — the *other* on-device LLM
 * engine alongside MediaPipe. LiteRT-LM is the only way to run `.litertlm`
 * model files, which is the format HuggingFace's `litert-community` ships
 * vision-language models in (e.g. Apple FastVLM-0.5B).
 *
 *   - [LlmEngine]       → MediaPipe  → `.task` files    → text-only
 *   - [LiteRtlmEngine]  → LiteRT-LM  → `.litertlm`      → text + vision
 *
 * The ChatViewModel dispatches to whichever engine matches the active
 * model's file extension.
 *
 * NATIVE VISION (no OCR/labels needed)
 * ────────────────────────────────────
 * When FastVLM is active, image attachments are passed DIRECTLY to the
 * model as `Content.ImageBytes` — no ML Kit OCR, no image labeling, no
 * cloud BLIP. The model's vision encoder reads the pixels and produces
 * a real natural-language answer.
 *
 * KV CACHE (PocketPal-style instant replies)
 * ──────────────────────────────────────────
 * LiteRT-LM maintains the KV cache inside its `Conversation` object
 * automatically. We keep a single long-lived conversation for the
 * lifetime of the loaded model, so each new user message only pays
 * the prefill cost for the NEW tokens — not the whole history.
 *
 * SYNC API (v1.4.0 limitation)
 * ────────────────────────────
 * LiteRT-LM alpha05 has an async `sendMessageAsync(message, callback)`
 * API but its completion semantics are unclear (the callback has
 * onMessage + onError but no documented "done" signal). For reliability
 * we use the SYNC `sendMessage(message)` API which blocks until the
 * full response is ready. 60-second hard timeout.
 *
 * After the sync call returns, we emit the response word-by-word via
 * [onChunk] to give a typing effect.
 *
 * ALPHA05 API NOTES
 * ──────────────────
 * The alpha05 AAR has a DIFFERENT API than the current `main` branch
 * of LiteRT-LM. Specifically:
 *   - `Backend` is an ENUM (Backend.CPU / Backend.GPU / Backend.NPU),
 *     NOT a sealed class with CPU()/GPU() instances.
 *   - `ConversationConfig` takes `systemMessage: Message?` (not
 *     `systemInstruction: Contents?`). There is no `Contents` class.
 *   - `Message.contents` returns `List<Content>` directly (not a
 *     `Contents` wrapper).
 *   - `Message.of(...)` factory methods exist (later deprecated in
 *     favor of `Message.user(...)` etc., but valid in alpha05).
 *
 * If upgrading to a newer LiteRT-LM version, these call sites need
 * updating.
 */
class LiteRtlmEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var activeModelPath: String? = null
    private var currentSystemPrompt: String? = null

    private val _state = MutableStateFlow<LlmState>(LlmState.Idle)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    fun isModelLoaded(): Boolean = engine != null

    fun activeModelName(): String? =
        activeModelPath?.let { File(it).nameWithoutExtension }

    /**
     * Load a `.litertlm` model file. Attempts GPU vision backend first
     * (much faster for image processing), falls back to CPU if GPU fails.
     * Creates a fresh conversation with the given system prompt baked in.
     */
    suspend fun setActiveModel(
        path: String,
        systemPrompt: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val file = File(path)
        Log.i(TAG, "Loading LiteRT-LM model: $path (${file.length()} bytes)")
        if (!file.exists()) {
            val msg = "Model file not found: $path"
            _state.value = LlmState.Error(msg)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }
        if (file.length() < 100 * 1024) {
            val msg = "Model file is too small (${file.length()} bytes) — likely a corrupted download."
            _state.value = LlmState.Error(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        // Pre-flight: detect HTML error pages.
        try {
            file.inputStream().use { input ->
                val head = ByteArray(8)
                val read = input.read(head)
                if (read >= 1 && head[0] == '<'.code.toByte()) {
                    val msg = "Model file is HTML text, not a valid .litertlm binary — download was corrupted."
                    _state.value = LlmState.Error(msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Pre-flight check failed", t)
        }

        try {
            // Tear down existing engine first.
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            engine = null
            conversation = null
            _state.value = LlmState.Loading

            val sp = systemPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
            val eng = createEngine(path, preferGpu = true)
            eng.initialize()
            engine = eng
            activeModelPath = path
            currentSystemPrompt = sp

            conversation = createConversation(eng, sp)
            Log.i(TAG, "LiteRT-LM model loaded successfully: ${file.name}")
            _state.value = LlmState.Ready
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "LiteRT-LM GPU load failed", t)
            val msg = t.message ?: ""
            val gpuFailed = msg.contains("gpu", ignoreCase = true) ||
                msg.contains("opencl", ignoreCase = true) ||
                msg.contains("delegate", ignoreCase = true) ||
                msg.contains("backend", ignoreCase = true) ||
                t is UnsatisfiedLinkError
            if (gpuFailed) {
                Log.w(TAG, "GPU init failed — retrying with CPU-only backend")
                try {
                    runCatching { conversation?.close() }
                    runCatching { engine?.close() }
                    val eng = createEngine(path, preferGpu = false)
                    eng.initialize()
                    engine = eng
                    activeModelPath = path
                    val sp = systemPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
                    currentSystemPrompt = sp
                    conversation = createConversation(eng, sp)
                    Log.i(TAG, "Model loaded with CPU-only backend (GPU fallback)")
                    _state.value = LlmState.Ready
                    return@withContext Result.success(Unit)
                } catch (cpuErr: Throwable) {
                    Log.e(TAG, "CPU-only retry also failed", cpuErr)
                    _state.value = LlmState.Error(cpuErr.message ?: "CPU load failed")
                    return@withContext Result.failure(cpuErr)
                }
            }
            _state.value = LlmState.Error(msg)
            Result.failure(t)
        }
    }

    /**
     * Build an EngineConfig. In alpha05, `Backend` is an ENUM — so
     * `Backend.CPU` and `Backend.GPU` are enum constants, not classes.
     * (In newer LiteRT-LM versions, Backend is a sealed class and these
     * would need to be `Backend.CPU()` / `Backend.GPU()` instances.)
     */
    private fun createEngine(path: String, preferGpu: Boolean): Engine {
        val config = EngineConfig(
            modelPath = path,
            backend = Backend.CPU,
            visionBackend = if (preferGpu) Backend.GPU else Backend.CPU,
            audioBackend = Backend.CPU,
            maxNumTokens = 2048
        )
        return Engine(config)
    }

    /**
     * Create a Conversation with the system prompt baked in.
     *
     * In alpha05, ConversationConfig takes `systemMessage: Message?`
     * (not `systemInstruction: Contents?` — that's a newer API).
     */
    private fun createConversation(eng: Engine, systemPrompt: String): Conversation {
        val systemMessage = Message.of(systemPrompt)
        val convConfig = ConversationConfig(
            systemMessage = systemMessage,
            tools = emptyList(),
            samplerConfig = SamplerConfig(
                /* topK = */ 40,
                /* topP = */ 0.95,
                /* temperature = */ 0.8,
                /* seed = */ 0
            )
        )
        return eng.createConversation(convConfig)
    }

    /**
     * Update the system prompt. Recreates the conversation (and discards
     * the KV cache) if the new prompt differs from the current one.
     */
    fun updateSystemPrompt(newPrompt: String?) {
        val sp = newPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
        if (sp == currentSystemPrompt) return
        val eng = engine ?: return
        currentSystemPrompt = sp
        runCatching { conversation?.close() }
        try {
            conversation = createConversation(eng, sp)
            Log.i(TAG, "Conversation recreated (system prompt changed, KV cache reset)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to recreate conversation after system prompt change", t)
        }
    }

    fun unload() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        engine = null
        conversation = null
        activeModelPath = null
        currentSystemPrompt = null
        System.gc()
        _state.value = LlmState.Idle
    }

    fun markReady() {
        if (_state.value is LlmState.Generating || _state.value is LlmState.Loading) {
            _state.value = LlmState.Ready
        }
    }

    /**
     * Send a message and stream the response chunk-by-chunk.
     *
     * If [imageUri] is non-null, the image is loaded, downscale-encoded
     * to JPEG, and passed to the model as `Content.ImageBytes` alongside
     * the text. The model's vision encoder reads the pixels directly —
     * no OCR, no labels, no hallucination.
     *
     * Internally uses the SYNC `sendMessage` API with a 60s timeout.
     * After the sync call returns, the full response is emitted word-by-
     * word via [onChunk] to give a typing effect.
     */
    suspend fun generateReplyStream(
        userText: String,
        imageUri: Uri? = null,
        onChunk: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val conv = conversation
        if (conv == null) {
            _state.value = LlmState.Error("No model loaded")
            return@withContext Result.failure(IllegalStateException("No model loaded"))
        }
        _state.value = LlmState.Generating
        try {
            // Build the message: optional image + required text.
            // In alpha05, Message.of(List<Content>) is the multi-content
            // factory. (Deprecated in newer versions but valid here.)
            val contents = mutableListOf<Content>()
            if (imageUri != null) {
                val imageBytes = loadAndDownscaleImage(imageUri, maxDim = 1024)
                if (imageBytes != null) {
                    contents.add(Content.ImageBytes(imageBytes))
                    Log.i(TAG, "Image attached: ${imageBytes.size} bytes JPEG (max 1024px)")
                } else {
                    Log.w(TAG, "Image load failed — sending text-only message")
                }
            }
            contents.add(Content.Text(userText))
            val message = Message.of(contents)

            // Sync call with 60s timeout. sendMessage blocks until the
            // model finishes generating, then returns the complete Message.
            val response: Message? = withTimeoutOrNull(60_000L) {
                conv.sendMessage(message)
            }
            if (response == null) {
                _state.value = LlmState.Error("Generation timed out after 60 seconds")
                return@withContext Result.failure(IllegalStateException("Generation timed out"))
            }

            val fullText = messageToText(response)
            Log.i(TAG, "Generation complete: ${fullText.length} chars")

            // Fake typing effect — emit the response word-by-word with a
            // small delay so the UI feels responsive. Split on whitespace
            // boundaries, preserving the whitespace in each emitted chunk
            // so the bubble renders newlines + indentation correctly.
            //
            // Regex: match word + trailing whitespace. Each match is one
            // chunk (word + its trailing space/newline/tab).
            val chunkRegex = Regex("\\S+\\s*|\\s+")
            for (match in chunkRegex.findAll(fullText)) {
                val chunk = match.value
                if (chunk.isEmpty()) continue
                onChunk(chunk)
                // 8ms per chunk — fast enough to not annoy but visible
                // enough to feel "alive".
                delay(8L)
            }

            _state.value = LlmState.Ready
            Result.success(fullText)
        } catch (ce: CancellationException) {
            Log.i(TAG, "Generation cancelled by user")
            _state.value = LlmState.Ready
            Result.failure(ce)
        } catch (t: Throwable) {
            Log.e(TAG, "Generation failed", t)
            _state.value = LlmState.Error(t.message ?: "Generation failed")
            Result.failure(t)
        }
    }

    /**
     * Convert a LiteRT-LM Message back to a plain string.
     *
     * In alpha05, `Message.contents` returns `List<Content>` directly
     * (not a `Contents` wrapper like in newer versions). We filter for
     * `Content.Text` instances and concatenate their text.
     */
    private fun messageToText(message: Message): String {
        return try {
            message.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        } catch (t: Throwable) {
            Log.w(TAG, "messageToText failed", t)
            ""
        }
    }

    /**
     * Load an image from [uri], downscale so the longest edge is at most
     * [maxDim] pixels, and JPEG-encode to a byte array.
     */
    private fun loadAndDownscaleImage(uri: Uri, maxDim: Int): ByteArray? {
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

            val scaled = if (maxOf(decoded.width, decoded.height) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
                val newW = (decoded.width * scale).toInt()
                val newH = (decoded.height * scale).toInt()
                Bitmap.createScaledBitmap(decoded, newW, newH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else decoded

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            try { scaled.recycle() } catch (_: Throwable) {}
            baos.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "Image load failed: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "HandyAi/LiteRtlmEngine"

        /**
         * Default system prompt for vision models. Tells the model to be
         * concise, helpful, and to actually USE its vision capability
         * instead of saying "I can't see images".
         */
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are HandyAi's vision assistant, running fully on-device via LiteRT-LM. " +
            "You can see images the user attaches — describe them, answer questions about them, " +
            "and reference what's visible. Be concise, friendly, and helpful. " +
            "Reply in plain text. Markdown tables (with pipes |) are OK for tabular data."
    }
}
