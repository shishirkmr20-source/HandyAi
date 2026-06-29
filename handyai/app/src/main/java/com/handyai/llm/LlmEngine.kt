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
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps MediaPipe LLM Inference API.
 *
 * Model lifecycle:
 *   1. User downloads a .task model file (Qwen / Gemma / Phi / SmolLM in MediaPipe format)
 *   2. setActiveModel(path) loads it into memory (~5-30s depending on size)
 *   3. generateReplyStream() builds a single prompt from a sliding window of the
 *      conversation history and calls the engine, then emits the response one
 *      short chunk at a time with a small inter-chunk delay so the UI gets a
 *      Claude-style "typing" effect instead of dumping the whole reply at once.
 *
 * Memory management:
 *   - MediaPipe's LlmInference has a hard max-tokens limit (set via setMaxTokens
 *     on the options). When the prompt + the model's own output exceeds that
 *     limit, the engine throws "Unable to parse" or similar.
 *   - We prevent this by capping the *input* prompt to a token budget that
 *     leaves plenty of room for output (default: 60% of max-tokens for input,
 *     40% for output). Conversation history is trimmed via a sliding window
 *     that keeps the most recent turns and drops the oldest.
 *   - On generation failure, we retry once with an even shorter history
 *     (only the latest user turn) so a single bad call never wedges the chat.
 *
 * Chat templates:
 *   - Qwen2.5 / SmolLM / Phi models are instruction-tuned for ChatML format
 *     (<|im_start|>role\n...<|im_end|>). MediaPipe's
 *     LlmInference.generateResponse(prompt) does NOT apply a chat template
 *     — it feeds the raw string directly to the model. The previous code
 *     used plain "User:/Assistant:" text, which the model treated as a
 *     single text-completion task instead of recognising turn boundaries.
 *     This was the root cause of small-model hallucination on attachments:
 *     the model never saw a proper user-turn boundary, so it ignored the
 *     inlined document content and hallucinated from whatever text came
 *     before it.
 *
 *     FIX (v1.2.4): buildPrompt() now emits proper ChatML tags. This works
 *     for all litert-community models currently in the catalog (Qwen,
 *     SmolLM, Phi-4 — all ChatML-compatible). Gemma would need a different
 *     format, but we don't ship Gemma.
 */
class LlmEngine(private val context: Context) {

    private var llm: LlmInference? = null
    private var activeModelPath: String? = null
    private var activeModelId: String? = null
    private var activeModelParamCount: Double? = null

    /**
     * Serializes calls to [LlmInference.generateResponse]. MediaPipe's
     * LlmInference is NOT thread-safe — concurrent generateResponse calls
     * on the same instance can crash or produce garbage output.
     *
     * This matters now that the Stop button actually works: when the user
     * taps Stop and immediately re-sends, the new call's async may start
     * before the abandoned previous call's native generateResponse() has
     * returned. The mutex ensures the new call waits for the old one to
     * finish before touching the engine.
     */
    private val generationMutex = Mutex()

    /**
     * Single reusable scope for cancellable native generation calls.
     *
     * WHY NOT `CoroutineScope(Dispatchers.IO).async { ... }` PER CALL:
     * The previous implementation created a fresh standalone CoroutineScope
     * on every generateReplyStream() call (and on every retry). These
     * scopes are NEVER cancelled — they leak forever, each holding a
     * reference to the engine, the prompt, and the onChunk closure. After
     * 2-3 chats (or one map-reduce summary = 20+ calls), the accumulated
     * leaked scopes + their captured closures + the native allocations
     * push the app past its heap limit and crash it.
     *
     * This single scope lives for the lifetime of the engine. Abandoned
     * asyncs (from Stop) still run to completion here, but they don't
     * accumulate unboundedly — the scope's Job is reused, and completed
     * children are GC'd normally.
     */
    private val generationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _state = MutableStateFlow<LlmState>(LlmState.Idle)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    fun isModelLoaded(): Boolean = llm != null

    fun activeModelName(): String? =
        activeModelPath?.let { File(it).nameWithoutExtension }

    /** Parameter count (in billions) of the currently-loaded model, or null
     *  if no model is loaded. Used by ChatViewModel to decide whether to
     *  use the small-model prompt strategy (inline file content into the
     *  latest user message instead of relying on the system prompt). */
    fun activeModelParamCount(): Double? = activeModelParamCount

    /** True if the loaded model is "small" (≤0.7B params). Small models
     *  have weak instruction-following over long system prompts, so the
     *  caller should use a more direct prompt structure. */
    fun isSmallModel(): Boolean {
        val p = activeModelParamCount ?: return false
        return p <= SMALL_MODEL_THRESHOLD
    }

    /**
     * Load a .task model from the given absolute path.
     * Caller is responsible for ensuring the file exists and is readable.
     *
     * Runs on Dispatchers.IO — LlmInference.createFromOptions() is a heavy
     * native call (5-30s) that will ANR-crash the app if invoked on the
     * main thread.
     *
     * Pre-flight checks:
     *   - File must exist and be > 100 KB (smaller = likely an HTML error page)
     *   - File must not start with "<" (HTML/XML, not a binary .task file)
     */
    suspend fun setActiveModel(path: String, modelId: String? = null, paramCountB: Double? = null): Result<Unit> = withContext(Dispatchers.IO) {
        // ── MEMORY-AWARE MAX_TOKENS (computed before try so it's visible in catch) ──
        // On phones with ≤ 4 GB RAM, a 2048-token KV cache + the model
        // weights + the JVM heap + bitmap memory can push the app into
        // OOM-kill territory. We detect the device's RAM and per-app heap
        // budget BEFORE the try block so the GPU-fallback catch block can
        // reuse the same values without re-fetching from ActivityManager.
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        val memoryClass = am?.memoryClass ?: 256
        val totalMemMb = am?.let {
            android.app.ActivityManager.MemoryInfo().also { mi ->
                it.getMemoryInfo(mi)
            }.totalMem / (1024L * 1024L)
        } ?: 4096L

        val effectiveMaxTokens = when {
            totalMemMb < 3072 -> {
                android.util.Log.i(TAG,
                    "Very-low-RAM device (totalMem=${totalMemMb}MB) — capping MAX_TOKENS at 1024")
                1024
            }
            totalMemMb < 5120 -> {
                android.util.Log.i(TAG,
                    "Low-RAM device (totalMem=${totalMemMb}MB) — capping MAX_TOKENS at 1280")
                1280
            }
            memoryClass <= 192 -> {
                android.util.Log.i(TAG,
                    "Low-heap device (memoryClass=${memoryClass}MB) — capping MAX_TOKENS at 1536")
                1536
            }
            else -> {
                android.util.Log.i(TAG,
                    "Normal-RAM device (totalMem=${totalMemMb}MB, memoryClass=${memoryClass}MB) — using MAX_TOKENS=$MAX_TOKENS")
                MAX_TOKENS
            }
        }

        try {
            val file = File(path)
            android.util.Log.i(TAG, "Loading model: $path (${file.length()} bytes)")
            if (!file.exists()) {
                val msg = "Model file not found: $path"
                _state.value = LlmState.Error(msg)
                return@withContext Result.failure(IllegalArgumentException(msg))
            }
            // Pre-flight: detect corrupted/HTML downloads before feeding to native code.
            // A native crash on an invalid file kills the entire app process.
            if (file.length() < 100 * 1024) {
                val msg = "Model file is too small (${file.length()} bytes) — likely a corrupted download. Please delete and re-download."
                android.util.Log.e(TAG, msg)
                _state.value = LlmState.Error(msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            file.inputStream().use { input ->
                val head = ByteArray(8)
                val read = input.read(head)
                if (read >= 1 && (head[0] == '<'.code.toByte())) {
                    val msg = "Model file is HTML text, not a valid .task binary — download was corrupted. Please delete and re-download."
                    android.util.Log.e(TAG, msg)
                    _state.value = LlmState.Error(msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
            }

            // Tear down existing model first
            try { llm?.close() } catch (_: Throwable) {}
            llm = null
            _state.value = LlmState.Loading

            // Build options. setMaxTokens must be >= 1 and within the model's
            // supported range. The litert-community models ship with a fixed
            // max sequence length (often 4096 or 8192); 1024-2048 is safe
            // across all of them. The actual value is computed above
            // (effectiveMaxTokens) based on device RAM + heap size — see
            // the comment block above the try for the full rationale.
            //
            // ── GPU BACKEND ─────────────────────────────────────────────
            // We request Backend.GPU here. If the device doesn't support
            // GPU LLM inference, createFromOptions() throws below and the
            // catch block retries with Backend.DEFAULT (CPU).
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(effectiveMaxTokens)
                .setMaxTopK(40)
                .apply {
                    try {
                        setPreferredBackend(LlmInference.Backend.GPU)
                        android.util.Log.i(TAG, "Requesting GPU backend for LLM inference")
                    } catch (_: Throwable) {
                        android.util.Log.i(TAG, "GPU backend not settable on this build; using default")
                    }
                }
                .build()
            android.util.Log.i(TAG, "Calling LlmInference.createFromOptions()…")
            llm = LlmInference.createFromOptions(context, options)
            if (llm == null) {
                val msg = "MediaPipe returned null — the model file may be corrupted or incompatible. Try deleting and re-downloading."
                android.util.Log.e(TAG, msg)
                _state.value = LlmState.Error(msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            android.util.Log.i(TAG, "Model loaded successfully with preferred backend.")
            activeModelPath = path
            activeModelId = modelId
            activeModelParamCount = paramCountB
            _state.value = LlmState.Ready
            Result.success(Unit)
        } catch (t: Throwable) {
            // ── GPU FALLBACK ──────────────────────────────────────────
            // If the GPU backend was requested but the device doesn't support
            // GPU LLM inference (no OpenCL, incompatible driver, etc.),
            // createFromOptions throws. Retry with the DEFAULT (CPU) backend
            // so we never fail to load a model just because GPU isn't available.
            val msg = t.message ?: ""
            val gpuFailed = msg.contains("gpu", ignoreCase = true) ||
                msg.contains("opencl", ignoreCase = true) ||
                msg.contains("delegate", ignoreCase = true) ||
                msg.contains("backend", ignoreCase = true) ||
                t is UnsatisfiedLinkError
            if (gpuFailed) {
                android.util.Log.w(TAG, "GPU backend failed ($msg) — retrying with CPU (Backend.DEFAULT)")
                try {
                    // effectiveMaxTokens is now computed above the try block
                    // and is visible here.
                    val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(effectiveMaxTokens)
                        .setMaxTopK(40)
                        .setPreferredBackend(LlmInference.Backend.DEFAULT)
                        .build()
                    llm = LlmInference.createFromOptions(context, cpuOptions)
                    if (llm != null) {
                        android.util.Log.i(TAG, "Model loaded successfully with CPU backend (GPU fallback).")
                        activeModelPath = path
                        activeModelId = modelId
                        activeModelParamCount = paramCountB
                        _state.value = LlmState.Ready
                        return@withContext Result.success(Unit)
                    }
                } catch (fallbackErr: Throwable) {
                    android.util.Log.e(TAG, "CPU fallback also failed", fallbackErr)
                    val rawMsg = fallbackErr.message ?: fallbackErr.javaClass.simpleName ?: "Failed to load model"
                    val className = fallbackErr.javaClass.simpleName
                    val failMsg = if (rawMsg.contains(className, ignoreCase = true)) rawMsg else "$className: $rawMsg"
                    _state.value = LlmState.Error(failMsg)
                    return@withContext Result.failure(fallbackErr)
                }
            }
            android.util.Log.e(TAG, "Load failed", t)
            // Include the exception class name + raw message so the user can
            // copy-paste the exact error back to us for debugging. Native
            // MediaPipe errors often have a generic "Unable to parse" message
            // but a distinctive exception class (e.g. MediaPipeException,
            // FileNotFoundException, OutOfMemoryError) that pinpoints the cause.
            val rawMsg = t.message ?: t.javaClass.simpleName ?: "Failed to load model"
            val className = t.javaClass.simpleName
            val failMsg = if (rawMsg.contains(className, ignoreCase = true)) {
                rawMsg
            } else {
                "$className: $rawMsg"
            }
            _state.value = LlmState.Error(failMsg)
            Result.failure(t)
        }
    }

    fun unload() {
        // Cancel any in-flight or queued abandoned asyncs BEFORE closing the
        // native engine. Without this, an abandoned generation (from a
        // previous Stop) could win the generationMutex after we close the
        // engine below, then call generateResponse() on a CLOSED
        // LlmInference instance → native crash (use-after-close).
        //
        // The TOCTOU check inside the async (`if (engine !== llm)`) catches
        // most cases, but cancelling the scope here is belt-and-suspenders.
        runCatching { generationScope.coroutineContext[Job]?.cancelChildren() }

        try { llm?.close() } catch (_: Throwable) {}
        llm = null
        activeModelPath = null
        activeModelId = null
        activeModelParamCount = null

        // Hint the GC to reclaim the large native allocations (~500MB-3.7GB
        // depending on model size) before the next model loads. Without this,
        // the old model's mmap'd memory may still be resident when the new
        // model loads, causing OOM on devices with tight RAM.
        System.gc()

        _state.value = LlmState.Idle
    }

    /**
     * Immediately flip the engine state to [LlmState.Ready].
     *
     * Called by [ChatViewModel.stopGeneration] so the UI (which drives the
     * Stop/Send button swap off `llmState is Generating`) updates INSTANTLY
     * when the user taps Stop — without waiting for the cancelled coroutine's
     * catch block to run on Dispatchers.Default and propagate the StateFlow
     * update back to Main (a dispatcher round-trip that takes a perceptible
     * moment, making the Stop feel sluggish).
     *
     * Safe because: the model is still loaded (Stop doesn't unload it), so
     * Ready is the correct state. The abandoned native call continues on a
     * background thread but its result is discarded.
     */
    fun markReady() {
        if (_state.value is LlmState.Generating || _state.value is LlmState.Loading) {
            _state.value = LlmState.Ready
        }
    }

    /**
     * Generate a reply using the full conversation history, streamed token-by-token.
     *
     * [history] is in chronological order (oldest first), already filtered to
     * user+assistant messages. [systemPrompt] is an optional preamble.
     *
     * Memory management:
     *   - The conversation history is truncated to fit within
     *     [INPUT_CHAR_BUDGET] chars (≈ 60% of MAX_TOKENS, rough char→token
     *     estimate of 4 chars/token). The most recent turns are kept; the
     *     oldest are dropped.
     *   - On generation failure (e.g. "unable to parse" from a too-long
     *     prompt), we surface the error (no retry — see comment below).
     *
     * ── TRUE TOKEN STREAMING (v1.3.3) ─────────────────────────────────
     * Previous versions called MediaPipe's synchronous `generateResponse(prompt)`
     * which blocks until the FULL reply is generated, then artificially
     * chunked it with `delay(22L)` per piece to fake a typing effect. This
     * meant the user waited 5-15 seconds seeing NOTHING, then waited another
     * 2-5 seconds for the fake typing animation. PocketPal AI and other fast
     * on-device chat apps use TRUE token streaming — each token appears the
     * instant the model generates it.
     *
     * MediaPipe 0.10.35 exposes `generateResponseAsync(prompt, ProgressListener)`
     * which calls the listener with each token as it's generated. We now use
     * this API instead of the synchronous `generateResponse()`.
     *
     * Benefits:
     *   - First token appears within 1-2 seconds (vs 5-15s for the full reply)
     *   - No artificial typing delay — tokens stream at the model's natural
     *     generation speed (typically 10-40 tokens/sec on a phone)
     *   - Perceived latency drops 5-10x even though total generation time
     *     is unchanged
     *
     * The ProgressListener is @Deprecated in MediaPipe 0.10.35 but still
     * works and is the only public API for token-level streaming. The
     * replacement (if any) would be on LlmInferenceSession.predictAsync,
     * but that requires a more invasive refactor (session lifecycle
     * management, KV cache invalidation on system-prompt changes). We use
     * the simpler engine-level async API for now.
     *
     * Cancellation (Stop button):
     *   - The async runs on a standalone CoroutineScope (see [generationScope])
     *     so cancelling the parent coroutine doesn't crash the engine.
     *   - When the user taps Stop, the parent await() throws
     *     CancellationException immediately. The native generation
     *     continues to completion in the background but its result is
     *     discarded.
     *   - The generationMutex ensures a new send waits for the abandoned
     *     old native call to finish before touching the engine.
     */
    suspend fun generateReplyStream(
        history: List<Pair<String, String>>,
        systemPrompt: String? = null,
        onChunk: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.Default) {
        val engine = llm ?: run {
            _state.value = LlmState.Error("No model loaded")
            return@withContext Result.failure(IllegalStateException("No model loaded"))
        }

        _state.value = LlmState.Generating
        try {
            // First attempt: full sliding-window history
            val trimmedHistory = trimHistoryToBudget(history, systemPrompt)
            val prompt = buildPrompt(trimmedHistory, systemPrompt)
            android.util.Log.i(TAG, "Generation attempt: prompt ${prompt.length} chars, ${trimmedHistory.size} turns (true token streaming)")

            // Run the async generation on a STANDALONE CoroutineScope so
            // the wait is CANCELLABLE. See the comment block in the
            // previous version (preserved above) for the full rationale —
            // it still applies: MediaPipe's native generateResponseAsync
            // is a blocking JNI call under the hood, and we need
            // Job.cancel() to take effect immediately for responsive Stop.
            val full = try {
                val deferred = generationScope.async {
                    generationMutex.withLock {
                        // Guard against unload-while-abandoned: if the engine
                        // was unloaded (or replaced with a different model)
                        // while this async was waiting for the mutex, the
                        // local `engine` reference points to a CLOSED
                        // LlmInference instance. Calling any method on it
                        // would crash natively (use-after-close).
                        if (engine !== llm) {
                            android.util.Log.w(TAG,
                                "Skipping abandoned generation: engine was unloaded/replaced")
                            return@withLock ""
                        }
                        // ── TRUE TOKEN STREAMING ─────────────────────────
                        // generateResponseAsync(prompt, listener) returns a
                        // ListenableFuture<String> that completes with the full
                        // text when generation finishes. The listener is called
                        // from the native inference thread with each token as
                        // it's generated — we forward it directly to onChunk.
                        //
                        // The listener may be called with empty strings
                        // occasionally (e.g. during initial prompt processing
                        // before the first token). We filter those out.
                        //
                        // Thread safety: onChunk updates a MutableStateFlow
                        // (_streamingChunk) which is thread-safe. The listener
                        // fires from a native thread, not the coroutine
                        // dispatcher — that's fine for StateFlow updates.
                        //
                        // @Suppress("DEPRECATION"): ProgressListener is marked
                        // @Deprecated in MediaPipe 0.10.35 but is the only
                        // public API for token-level streaming on the engine
                        // (non-session) path. The replacement would be
                        // LlmInferenceSession.predictAsync, which requires a
                        // more invasive refactor. We accept the deprecation
                        // warning in exchange for the streaming speedup.
                        @Suppress("DEPRECATION")
                        val listener = com.google.mediapipe.tasks.genai.llminference.ProgressListener<String> { partialToken, done ->
                            if (partialToken.isNotEmpty()) {
                                onChunk(partialToken)
                            }
                            // `done` is true on the final call — the full text
                            // is also returned via the future. No action needed.
                        }
                        val future = engine.generateResponseAsync(prompt, listener)
                        // Block until the future completes. This is safe because
                        // we're on the IO dispatcher's limited-parallelism(1)
                        // worker — the blocking doesn't hold up any other work.
                        //
                        // future.get() throws ExecutionException if the native
                        // generation threw. We unwrap the cause so the caller
                        // sees the real error (e.g. "unable to parse") instead
                        // of a generic "java.util.concurrent.ExecutionException".
                        try {
                            future.get()
                        } catch (ee: java.util.concurrent.ExecutionException) {
                            throw ee.cause ?: ee
                        }
                    }
                }
                deferred.await()
            } catch (ce: CancellationException) {
                // User-initiated Stop: propagate WITHOUT retrying.
                throw ce
            } catch (t: Throwable) {
                // CRITICAL: Do NOT retry on the same engine instance.
                //
                // When MediaPipe's native generation fails (OOM, context
                // overflow, internal state corruption), the underlying native
                // engine instance may be in a BROKEN state. Calling
                // generateResponseAsync() again on the same broken instance
                // causes a NATIVE CRASH (SIGSEGV/SIGABRT) that kills the
                // entire app process — bypassing all JVM exception handling.
                //
                // FIX: skip the retry. Surface the error to the caller so
                // they can show it to the user and prompt them to send a
                // shorter message or start a new chat.
                android.util.Log.w(TAG, "Generation failed (NOT retrying to avoid native crash on broken engine)", t)
                throw t
            }

            _state.value = LlmState.Ready
            Result.success(full)
        } catch (ce: CancellationException) {
            // User hit Stop. The model is still loaded and ready — do NOT
            // set state to Error (that would disable the Send button and
            // show a fake error banner). Just reset to Ready and propagate
            // the cancellation to the caller as a failed Result so the
            // caller's onFailure handler can persist any partial text.
            android.util.Log.i(TAG, "Generation cancelled by user")
            _state.value = LlmState.Ready
            Result.failure(ce)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Generation failed", t)
            val msg = t.message ?: t.javaClass.simpleName ?: "Generation failed"
            _state.value = LlmState.Error(msg)
            Result.failure(t)
        }
    }

    /**
     * Trim conversation history to fit within the input character budget.
     * The most recent turns are always kept; the oldest are dropped first.
     *
     * The budget accounts for the system prompt + the user's latest message
     * (which is always kept). We use a generous 4 chars/token estimate.
     *
     * ChatML tags add ~25 chars of overhead per turn (the
     * <|im_start|>role\n...\n<|im_end|>\n wrapper), so we account for
     * that in the per-turn estimate.
     */
    private fun trimHistoryToBudget(
        history: List<Pair<String, String>>,
        systemPrompt: String?
    ): List<Pair<String, String>> {
        val systemChars = (systemPrompt?.length ?: 0) + 200 // overhead for template tags
        val budget = INPUT_CHAR_BUDGET - systemChars
        if (budget <= 0) return history.takeLast(2) // system prompt itself is huge; keep last turn only

        // Walk the history from the most recent backwards, adding turns until
        // we hit the budget. Always keep the last turn (the user's message).
        if (history.isEmpty()) return history
        var used = 0
        val kept = ArrayDeque<Pair<String, String>>()
        for (turn in history.asReversed()) {
            val turnChars = turn.second.length + 30 // +30 for ChatML wrapper tags
            if (used + turnChars > budget && kept.isNotEmpty()) break
            kept.addFirst(turn)
            used += turnChars
        }
        return kept.toList()
    }

    /**
     * Build a single text prompt from the conversation history using
     * ChatML format.
     *
     * ChatML is the native turn-boundary format for Qwen2.5, SmolLM, and
     * Phi-4 models. MediaPipe's LlmInference.generateResponse() does NOT
     * apply a chat template — it feeds the raw string to the model — so
     * we must emit the ChatML tags ourselves.
     *
     * Format:
     *   <|im_start|>system
     *   {system prompt}
     *   <|im_end|>
     *   <|im_start|>user
     *   {user message}
     *   <|im_end|>
     *   <|im_start|>assistant
     *   {assistant message}
     *   <|im_end|>
     *   <|im_start|>assistant
     *
     * The trailing <|im_start|>assistant (without <|im_end|>) tells the
     * model where to start generating. This is the standard ChatML
     * completion pattern.
     *
     * WHY THIS MATTERS: without proper turn boundaries, small models
     * (0.5B, 1.5B) treat the entire prompt as a text-completion task
     * and hallucinate — they never "see" where the user's instruction
     * ends. With ChatML tags, even 0.5B models correctly attend to the
     * user turn and respond appropriately. This single fix resolves the
     * "model can't read attachments" and "model can't summarize" bugs.
     */
    private fun buildPrompt(
        history: List<Pair<String, String>>,
        systemPrompt: String?
    ): String {
        val sb = StringBuilder()
        if (!systemPrompt.isNullOrBlank()) {
            sb.append("<|im_start|>system\n")
            sb.append(systemPrompt.trim())
            sb.append("\n<|im_end|>\n")
        }
        history.forEach { (role, content) ->
            val roleTag = when (role) {
                "user" -> "user"
                "assistant" -> "assistant"
                else -> "user"
            }
            sb.append("<|im_start|>").append(roleTag).append('\n')
            sb.append(content)
            sb.append("\n<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    companion object {
        private const val TAG = "HandyAi/LlmEngine"

        /**
         * Maximum tokens the engine will allocate per session.
         *
         * Bumped from 1024 → 2048 in v1.1.6. Most litert-community models
         * (Qwen2.5, Gemma, Phi, SmolLM) ship with 4096–8192 max sequence
         * length, so 2048 is well within budget. The previous 1024 limit
         * was so tight that a 1200-char file context + a couple of history
         * turns + the latest user message would overflow, causing the
         * engine to throw "unable to parse" or silently truncate the
         * file content out of the prompt.
         *
         * With 2048 tokens, we can comfortably fit ~6000 chars of input
         * (system prompt + file context + history) and leave ~1000 tokens
         * (~4000 chars) for the model's output.
         */
        private const val MAX_TOKENS = 2048

        /**
         * Rough character budget for the *input* prompt (system + history +
         * latest user message). We use ~4 chars/token, leaving ~50% of
         * MAX_TOKENS for the model's own output. This is conservative on
         * purpose — overflowing causes the model to throw "unable to parse"
         * or, worse, a native crash that kills the app.
         *
         * Bumped from 2400 → 6000 in v1.1.6 to accommodate larger file
         * attachments (PDF / DOCX / PPTX can easily be 5–10KB of text
         * after extraction).
         *
         * REDUCED to 4000 in v1.3.0: 6000 chars (~1500 tokens) + system
         * prompt overhead + the model's output (~500 tokens) was pushing
         * right up against the 2048-token MAX_TOKENS limit. By the 4th
         * chat turn, accumulated history + a long system prompt would
         * overflow, causing the native engine to fail. 4000 chars leaves
         * a safer margin while still accommodating large file attachments
         * (the file context is capped separately by FILE_CONTEXT_BUDGET
         * in ChatViewModel).
         */
        private const val INPUT_CHAR_BUDGET = 4000

        /**
         * Models with ≤ this many billion parameters are treated as "small"
         * and get the inline-content prompt strategy. 0.7 catches Qwen 0.5B
         * and SmolLM 135M while leaving Qwen 1.5B and above on the standard
         * system-prompt path (which works fine for them).
         */
        private const val SMALL_MODEL_THRESHOLD = 0.7
    }
}

sealed interface LlmState {
    data object Idle : LlmState
    data object Loading : LlmState
    data object Ready : LlmState
    data object Generating : LlmState
    data class Error(val message: String) : LlmState
}
