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
package com.handyai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.handyai.data.model.Chat
import com.handyai.data.model.Message
import com.handyai.data.model.Role
import com.handyai.data.repo.ChatRepository
import com.handyai.data.repo.HabitRepository
import com.handyai.data.repo.JournalRepository
import com.handyai.data.repo.SettingsRepository
import com.handyai.files.FileTextExtractor
import com.handyai.llm.LlmEngine
import com.handyai.llm.LlmState
import com.handyai.net.WebSearchService
import com.handyai.tts.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val chatId: Long,
    private val chatRepo: ChatRepository,
    private val llm: LlmEngine,
    private val imageGen: com.handyai.llm.ImageGenEngine,
    private val tts: TtsEngine,
    private val settings: SettingsRepository,
    private val fileExtractor: FileTextExtractor,
    private val webSearch: WebSearchService,
    private val journalRepo: JournalRepository,
    private val habitRepo: HabitRepository,
    private val summarizer: com.handyai.llm.AttachmentSummarizer
) : ViewModel() {

    val messages: StateFlow<List<Message>> = chatRepo.observeMessages(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chat: StateFlow<Chat?> = chatRepo.observeChat(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val internetEnabled: StateFlow<Boolean> = settings.internetEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val ttsEnabled: StateFlow<Boolean> = settings.ttsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _streamingChunk = MutableStateFlow("")
    val streamingChunk: StateFlow<String> = _streamingChunk.asStateFlow()

    /** Visible status of long-running side operations (web search, file parse). */
    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    /**
     * Serializes attachFile() and sendUserMessage() so that an in-flight
     * attachment is fully saved to the database BEFORE a send reads the
     * chat context. Without this, the user can pick a file and
     * immediately hit Send — the send would then build its system prompt
     * from the OLD context (no file) and the LLM would deflect with
     * "I can't see the file" even though the file WAS attached.
     *
     * The mutex is per-ViewModel (per-chat), so attachments in different
     * chats don't block each other.
     */
    private val attachmentMutex = Mutex()

    /** True while attachFile() is running — used by the UI to disable
     *  the Send button so the user can't fire a send that would race
     *  with an in-flight attachment. */
    private val _attaching = MutableStateFlow(false)
    val attaching: StateFlow<Boolean> = _attaching.asStateFlow()

    /**
     * The coroutine Job currently running LLM generation (either a normal
     * reply or a map-reduce summary). Null when no generation is in flight.
     *
     * Used by [stopGeneration] so the user can cancel an in-flight LLM
     * response mid-stream. When cancelled, the partial streamed text is
     * persisted as an assistant message (see the onFailure handler in
     * sendUserMessage / handleMapReduceSummary).
     */
    private var currentGenJob: Job? = null

    /**
     * Stop the in-flight LLM generation (if any).
     *
     * Calls cancel() on the current generation Job, which propagates a
     * CancellationException into the generator's onFailure handler. There,
     * we persist whatever has been streamed so far as a partial assistant
     * message — so the user's mid-stream Stop doesn't throw away the
     * tokens they already saw.
     *
     * Safe to call when no generation is running (no-op).
     */
    fun stopGeneration() {
        val job = currentGenJob ?: return
        android.util.Log.i("HandyAi/ChatVM", "stopGeneration: cancelling current job")
        // Immediately flip the engine state to Ready so the UI swaps the
        // Stop button back to Send INSTANTLY — without this, the user has
        // to wait for the cancelled coroutine's catch block to run on
        // Dispatchers.Default and propagate the StateFlow update back to
        // Main (a dispatcher round-trip that makes Stop feel sluggish).
        llm.markReady()
        job.cancel()
    }

    fun toggleInternet(on: Boolean) = viewModelScope.launch {
        settings.setInternet(on)
        if (!on) tts.stop()
    }

    fun toggleTts(on: Boolean) = viewModelScope.launch {
        settings.setTts(on)
        if (!on) tts.stop()
    }

    /**
     * Attach a file to this chat — extracts text and stores it as the chat's
     * context payload. Future LLM calls in this chat will include this text
     * as a system preamble.
     *
     * Detects when the extractor returned an error marker (e.g.
     * "[PDF parse error: ...]") instead of real content and surfaces it to
     * the user via the errors channel. The chat context is still set so
     * the LLM can explain the failure if asked, but the user immediately
     * sees what went wrong instead of wondering why the LLM says "I can't
     * read this file".
     */
    suspend fun attachFile(uri: Uri) {
        _statusText.value = "Reading file…"
        try {
            val resolver = HandyAiAppResolver.resolver
            val displayName = withContext(Dispatchers.IO) {
                var name: String? = null
                try {
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && c.moveToFirst()) name = c.getString(ni)
                    }
                } catch (_: Throwable) {}
                name ?: "attachment"
            }
            android.util.Log.i("HandyAi/ChatVM",
                "attachFile: chatId=$chatId, uri=$uri, displayName=$displayName")
            val result = withContext(Dispatchers.IO) { fileExtractor.extract(uri, displayName) }
            val label = if (result.charsTruncated) "${result.label} (truncated)" else result.label
            android.util.Log.i("HandyAi/ChatVM",
                "attachFile: extracted ${result.text.length} chars, label=$label, setting context on chatId=$chatId")
            chatRepo.setContext(chatId, result.text, label)

            // Detect parse failures. The extractor wraps errors in
            // "[FORMAT parse error: ...]" markers so the LLM can still
            // reference them, but the user needs to know the parse failed
            // so they can try a different file or format.
            val text = result.text
            val isImage = label.startsWith("image:")
            if (!isImage && text.isNotBlank()) {
                val looksLikeError = text.contains("parse error", ignoreCase = true) ||
                    text.contains("could not extract", ignoreCase = true) ||
                    text.contains("Unknown file type", ignoreCase = true)
                if (looksLikeError) {
                    _errors.send("Could not parse '$displayName': ${text.take(200)}")
                } else if (text.length < 20) {
                    // Suspiciously short extraction — likely an empty or
                    // image-only PDF. Warn the user so they know the LLM
                    // won't have much to work with.
                    _errors.send("'$displayName' yielded very little text (${text.length} chars). The file may be image-based or empty.")
                }
            }
        } catch (t: Throwable) {
            _errors.send("Could not read file: ${t.message}")
        } finally {
            _statusText.value = ""
        }
    }

    /** Remove the file context from this chat. */
    fun clearAttachment() = viewModelScope.launch {
        chatRepo.setContext(chatId, null, null)
    }

    /**
     * Send the user's message and trigger LLM generation.
     * Handles:
     *   - Persisting user message
     *   - Detecting "add a habit" / "add a journal entry" intents and
     *     actually creating the row in the database BEFORE the LLM
     *     generates its reply (on-device models have no tool-use
     *     capability, so without this side-effect they would just
     *     hallucinate "I've added it" without doing anything).
     *   - Optionally fetching web context (if internet enabled and query looks fresh)
     *   - Building conversation history for the model
     *   - Streaming tokens into _streamingChunk
     *   - Persisting the final assistant message
     *   - Optionally reading it aloud via TTS
     */
    fun sendUserMessage(text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch

        // 0) Image-generation shortcut: "/draw <prompt>" or "/image <prompt>"
        //    Routes to ImageGenEngine (Pollinations.ai cloud image gen).
        //    No model download or activation needed — image gen is a
        //    built-in cloud feature. We persist the user's /draw command
        //    as a normal user message, then generate the image and persist
        //    it as an assistant message with imagePath set (the bubble
        //    renders the image + caption).
        val drawPrompt = parseDrawCommand(text)
        if (drawPrompt != null) {
            handleDrawCommand(drawPrompt, text)
            return@launch
        }

        // 0) Detect "create a habit" / "add a journal entry" intent and
        //    actually create the row in the database. This fixes the bug
        //    where the LLM would say "I've created the habit" but nothing
        //    actually happened (small on-device models have no tool-use
        //    capability and would hallucinate the action).
        val habitIntent = com.handyai.llm.HabitIntentParser.parse(text)
        var habitCreatedName: String? = null
        if (habitIntent != null) {
            val newHabit = com.handyai.data.model.Habit(
                id = 0,
                name = habitIntent.name,
                description = habitIntent.description,
                category = habitIntent.category.ifBlank { com.handyai.data.model.HabitCategories.DEFAULT },
                targetDate = habitIntent.targetDate,
                targetTime = habitIntent.targetTime,
                status = com.handyai.data.model.HabitStatus.DEFAULT,
                colorHex = "#1A8FE3",
                createdAt = System.currentTimeMillis(),
                archived = false
            )
            val newId = habitRepo.save(newHabit)
            habitCreatedName = newHabit.name
            android.util.Log.i("HandyAi/ChatVM", "Auto-created habit id=$newId name=${newHabit.name}")
        }

        val journalIntent = com.handyai.llm.JournalIntentParser.parse(text)
        var journalCreatedTitle: String? = null
        if (journalIntent != null) {
            val now = System.currentTimeMillis()
            val newEntry = com.handyai.data.model.JournalEntry(
                id = 0,
                title = journalIntent.title.ifBlank { "Journal — ${com.handyai.llm.JournalIntentParser.todayLabel()}" },
                content = journalIntent.content,
                mood = journalIntent.mood,
                createdAt = now,
                updatedAt = now
            )
            val newId = journalRepo.save(newEntry)
            journalCreatedTitle = newEntry.title
            android.util.Log.i("HandyAi/ChatVM", "Auto-created journal id=$newId title=${newEntry.title}")
        }

        // 1) Persist user message
        //
        // If a file/image is currently attached to the chat (contextLabel is
        // set), COPY that label onto the user message being persisted, then
        // CLEAR the chat row's contextLabel. This makes the attachment chip
        // "move" from the input bar to underneath the sent message bubble.
        // The chat row's `context` (extracted file text) is preserved so
        // the LLM can still read it for follow-up questions in this chat.
        val chatRowForLabel = chatRepo.getChat(chatId)
        val carriedLabel = chatRowForLabel?.contextLabel
        chatRepo.appendMessage(chatId, Role.USER, text, attachmentLabel = carriedLabel)
        if (carriedLabel != null) {
            android.util.Log.i("HandyAi/ChatVM",
                "Attachment moved: chatId=$chatId label=$carriedLabel → message (chip moves from input bar to message bubble)")
            chatRepo.clearContextLabel(chatId)
        }

        // 2) Title the chat if it's the first message
        val current = chat.value
        if (current != null && (current.title == "New chat" || current.title.isBlank())) {
            val newTitle = text.take(40).replace("\n", " ").trim().ifBlank { "Chat" }
            chatRepo.rename(chatId, newTitle)
        }

        // 3) Build history
        val history = chatRepo.getMessages(chatId)
            .filter { !it.isError }
            .map { it.role.value to it.content }

        // 3.5) Fetch the chat's current file/image context ONCE so both
        //      buildSystemPrompt (normal models) and the inline strategy
        //      (small models) can use it. Reading from DB avoids stale
        //      StateFlow values when an attachment was just added.
        val chatRow = chatRepo.getChat(chatId)
        val fileContext: Pair<String, Boolean>? = chatRow?.let { row ->
            val raw = row.context
            if (raw.isNullOrBlank()) null
            else raw to (row.contextLabel?.startsWith("image:") == true)
        }

        // 3.55) MAP-REDUCE SUMMARIZATION FOR LARGE FILES
        //
        // If the user is asking to summarize a large attached document,
        // bypass the normal single-shot LLM call entirely and use the
        // AttachmentSummarizer instead.
        //
        // WHY: Small models (0.5B, 1.5B) cannot read 3,500 chars of
        // document text buried in a system prompt — they hallucinate
        // from the filename instead. Map-reduce splits the document
        // into small chunks, summarizes each individually (forcing the
        // model to actually read each chunk), then combines them.
        //
        // Trigger conditions:
        //   - File context is present and NOT an image (images use the
        //     ML Kit OCR/label text, which is already short)
        //   - User's message looks like a summarization request
        //   - File content is > 2000 chars (smaller files go through
        //     the normal inline path — they're short enough for the
        //     model to handle in one shot)
        if (fileContext != null && !fileContext.second && isSummarizeRequest(text)) {
            val fileText = fileContext.first
            if (fileText.length > SUMMARIZE_MAPREDUCE_THRESHOLD) {
                handleMapReduceSummary(text, fileText)
                return@launch
            }
        }

        // 3.6) AGGRESSIVE INLINE STRATEGY — NOW FOR ALL MODELS
        //
        // Previously this was only for small models (≤0.7B). But testing
        // showed that even Qwen 1.5B ignores file content when it's
        // buried in a long system prompt alongside habit context,
        // journal context, tool-use preamble, and web search results.
        //
        // Fix: inline the file content into the latest user message for
        // ALL models when a file is attached. The system prompt is kept
        // minimal (no habit/journal/web context when a file is present)
        // so the model's limited attention goes to the file content.
        //
        // The persisted DB message is unchanged — the user sees only
        // their original text. We modify the in-memory history only.
        val hasFile = fileContext != null
        val effectiveHistory: List<Pair<String, String>> =
            if (hasFile && fileContext != null) {
                android.util.Log.i("HandyAi/ChatVM",
                    "Inline strategy: inlining ${fileContext.first.length} chars of file content into latest user turn (model=${llm.activeModelName()})")
                history.mapIndexed { idx, (role, content) ->
                    if (idx == history.lastIndex && role == "user") {
                        role to buildInlineUserMessage(content, fileContext.first, fileContext.second)
                    } else {
                        role to content
                    }
                }
            } else {
                history
            }

        // 4) Build system prompt.
        //    When a file is attached, use a MINIMAL system prompt — no
        //    habit context, no journal context, no web search, no tool-use
        //    preamble. This prevents the model's attention from being
        //    diluted across irrelevant context when it should be reading
        //    the file content.
        val systemPrompt = buildSystemPrompt(
            userText = text,
            habitCreatedName = habitCreatedName,
            journalCreatedTitle = journalCreatedTitle,
            includeFileContext = false,  // file content is inlined into user turn
            fileContext = null,          // don't duplicate it in system prompt
            minimalMode = hasFile        // strip habit/journal/web when file present
        )

        // 5) Stream response (typewriter-style chunk emission + memory-safe
        //    sliding-window history handled inside the engine).
        _streamingChunk.value = ""
        currentGenJob = coroutineContext[Job]
        val result = llm.generateReplyStream(
            history = effectiveHistory,
            systemPrompt = systemPrompt
        ) { chunk ->
            // Sanitize each chunk: small models sometimes emit the literal
            // 2-char sequence "\" + "n" instead of an actual newline. We
            // convert those to real newlines so the streaming bubble and
            // the persisted message both render cleanly.
            _streamingChunk.value += com.handyai.ui.components.MarkdownParser.sanitize(chunk)
        }

        // 6) Persist assistant message
        //
        // The persist + TTS calls below are suspending. When the user hits
        // Stop, this coroutine is already cancelled, and ANY suspending call
        // would throw CancellationException immediately — meaning the partial
        // text the user saw would be silently dropped. We wrap the whole
        // persist block in withContext(NonCancellable) so it runs to
        // completion even in a cancelled coroutine.
        //
        // Cancellation with NO partial text (user tapped Stop before the
        // model emitted any tokens) is a silent no-op — no error bubble, no
        // toast. The user chose to abort; showing "⚠️ Generation failed"
        // would be misleading.
        val isCancellation = result.isFailure &&
            result.exceptionOrNull() is kotlinx.coroutines.CancellationException
        val partial = _streamingChunk.value

        if (isCancellation && partial.isBlank()) {
            // Silent abort — user stopped before any text streamed.
            android.util.Log.i("HandyAi/ChatVM", "Generation stopped by user (no partial text)")
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                result.onSuccess { full ->
                    val sanitized = com.handyai.ui.components.MarkdownParser.sanitize(full)
                    val final = if (sanitized.isBlank()) partial.ifBlank { "(empty response)" } else sanitized
                    chatRepo.appendMessage(chatId, Role.ASSISTANT, final)
                    if (ttsEnabled.value) {
                        tts.speak(final, "auto-${System.currentTimeMillis()}")
                    }
                }.onFailure { err ->
                    if (isCancellation && partial.isNotBlank()) {
                        chatRepo.appendMessage(chatId, Role.ASSISTANT, partial)
                        android.util.Log.i("HandyAi/ChatVM",
                            "Generation stopped by user, persisted partial: ${partial.length} chars")
                    } else {
                        // Surface a user-friendly error. Native MediaPipe
                        // errors are often generic ("Unable to parse") so we
                        // add a hint about what likely went wrong and what
                        // the user can do.
                        val rawMsg = err.message ?: err.javaClass.simpleName ?: "Generation failed"
                        val friendly = when {
                            rawMsg.contains("parse", ignoreCase = true) ||
                                rawMsg.contains("token", ignoreCase = true) ||
                                rawMsg.contains("length", ignoreCase = true) ||
                                rawMsg.contains("overflow", ignoreCase = true) -> {
                                "The conversation got too long for this model. " +
                                    "Try sending a shorter message, or start a new chat.\n\n" +
                                    "($rawMsg)"
                            }
                            rawMsg.contains("memory", ignoreCase = true) ||
                                err is OutOfMemoryError -> {
                                "The model ran out of memory. Try a smaller model " +
                                    "(Settings → Models), or start a new chat.\n\n" +
                                    "($rawMsg)"
                            }
                            else -> rawMsg
                        }
                        _errors.send(friendly)
                        chatRepo.appendMessage(chatId, Role.ASSISTANT, "⚠️ $friendly", isError = true)
                    }
                }
            }
        }
        currentGenJob = null
        _streamingChunk.value = ""
    }

    private suspend fun buildSystemPrompt(
        userText: String,
        habitCreatedName: String? = null,
        journalCreatedTitle: String? = null,
        includeFileContext: Boolean = true,
        fileContext: Pair<String, Boolean>? = null,
        minimalMode: Boolean = false
    ): String {
        val sb = StringBuilder()
        sb.appendLine("You are HandyAi, a helpful assistant running fully on-device. Be concise and friendly.")
        sb.appendLine()
        // ── WHY NO BOLD INSTRUCTION ────────────────────────────────────
        // Earlier versions asked the model to wrap key terms in **double
        // asterisks** for bold rendering. We removed the MarkdownParser
        // that converted those markers into styled spans (it caused a
        // hard crash — see MarkdownParser.kt HISTORY block for details).
        // Without the parser, ** markers would show up as literal
        // asterisks in the chat bubble, which looks broken. So we no
        // longer ask the model to emit them.
        //
        // Small on-device models don't reliably follow formatting
        // instructions anyway — they would often bold the first term
        // then forget for the rest of the reply. Plain text is cleaner.
        sb.appendLine("Reply in plain text. Do not use Markdown, **asterisks**, #headings, or -lists. Just write natural sentences and paragraphs.")

        // ── MINIMAL MODE ──────────────────────────────────────────────
        // When a file is attached, strip ALL non-essential context so the
        // model's limited attention goes to the file content (which is
        // inlined into the user turn, not here). Small models get confused
        // when habit context, journal entries, web search results, and
        // tool-use preamble all compete with the file content.
        if (minimalMode) {
            sb.appendLine("The user has attached a document. Its content has been extracted to text and is included in your conversation as part of the latest user message — between 'Document content:' and 'Question:' markers.")
            sb.appendLine("Read that content carefully and answer the user's question based on it. Do NOT say you cannot see the document. Do NOT guess from the filename. Quote from the extracted text to prove you read it.")
            // Tool-use ack (kept even in minimal mode so the model confirms
            // actions the app already took)
            if (habitCreatedName != null) {
                sb.appendLine()
                sb.appendLine("[APP ACTION] A habit named \"$habitCreatedName\" was just created. Acknowledge in one short sentence.")
            }
            if (journalCreatedTitle != null) {
                sb.appendLine()
                sb.appendLine("[APP ACTION] A journal entry titled \"$journalCreatedTitle\" was just saved. Acknowledge in one short sentence.")
            }
            return sb.toString().trim()
        }

        // ── NORMAL MODE (no file attached) ────────────────────────────
        if (includeFileContext) {
            // Normal-model path: file context lives in the system prompt with
            // forceful instructions overriding the "I can't see attachments"
            // deflection that small on-device models default to.
            sb.appendLine("Attachment handling: when the user attaches a file or image, the app extracts its content to text on-device (PDF/DOCX/PPTX/XLSX/TXT/HTML via on-device parsers — PDFBox-Android + Apache POI; images via ML Kit OCR + image labeling) and includes it in this prompt between ---FILE CONTENT START---/END--- or ---IMAGE CONTENT START---/END--- markers. When those markers are present, you HAVE seen the content — do not claim you cannot read attachments, do not ask the user to paste content, do not say you can only see the filename. Quote from the extracted text when answering so the user can verify you read it.")
        } else {
            // Small-model path: file content is inlined into the latest user
            // message by the caller (see sendUserMessage step 3.6). The
            // system prompt just nudges the model to actually read what's
            // in the user's message instead of deflecting.
            sb.appendLine("If the user's message contains a 'Document content:' or 'Image content:' section, that IS the attachment's extracted text — read it and answer based on it. Do not say you cannot see files or images.")
        }
        // Tool-use preamble — tells the LLM about the side-effects that
        // already happened so it doesn't have to (and shouldn't try to)
        // perform them itself. Small on-device models have no function
        // calling support, so we run the parser BEFORE generation and
        // surface the result back via the system prompt.
        sb.appendLine()
        sb.appendLine("Tool capabilities — the app performs these actions for you; you do NOT need to and cannot perform them yourself:")
        sb.appendLine("- When the user explicitly asks to create/add/start a habit, the app creates it in the database and tells you the name. Just acknowledge briefly (one sentence) and continue the conversation naturally.")
        sb.appendLine("- When the user explicitly asks to add/write/save a journal entry, the app creates it and tells you the title. Just acknowledge briefly (one sentence) and continue the conversation naturally.")
        sb.appendLine("- Do NOT say \"I have created...\" or \"I've added...\" if the app did not actually do it. Only confirm what the system message below confirms.")
        if (habitCreatedName != null) {
            sb.appendLine()
            sb.appendLine("[APP ACTION] A habit named \"$habitCreatedName\" was just created in the user's habit tracker. Acknowledge this in one short sentence, then continue the conversation — do not navigate or repeat the action.")
        }
        if (journalCreatedTitle != null) {
            sb.appendLine()
            sb.appendLine("[APP ACTION] A journal entry titled \"$journalCreatedTitle\" was just saved to the user's journal. Acknowledge this in one short sentence, then continue the conversation — do not navigate or repeat the action.")
        }
        sb.appendLine("When the user asks about their journal or habits, use the context below to give personal responses.")
        // File / image context — only included when includeFileContext=true
        // (i.e. for non-small models). For small models the caller has
        // already inlined the content into the latest user message.
        if (includeFileContext && fileContext != null) {
            val rawFileCtx = fileContext.first
            val isImage = fileContext.second
            val fileCtx = if (rawFileCtx.length > FILE_CONTEXT_BUDGET) {
                rawFileCtx.substring(0, FILE_CONTEXT_BUDGET) +
                    "\n…[truncated, ${rawFileCtx.length - FILE_CONTEXT_BUDGET} more chars — ask the user to share specific sections if you need them]"
            } else {
                rawFileCtx
            }
            sb.appendLine()
            if (isImage) {
                sb.appendLine("ATTACHMENT CONTEXT — IMAGE:")
                sb.appendLine("The user has attached an image. The app has already analyzed it on-device using ML Kit: OCR extracted any visible text, and an image labeler detected the top objects/scenes/concepts. The combined result is included below between the markers.")
                sb.appendLine("You CAN see this image's content — it has been extracted to text and is sitting in this prompt right now. Do NOT claim you cannot see images, do NOT ask the user to upload again, do NOT say \"I can only see the filename\". The filename alone is NOT all you have — read the 'Visible text' and 'What the image shows' lines below and use them.")
                sb.appendLine("Describe what the image actually shows based on the 'What the image shows' line, and quote any text from the 'Visible text' line if relevant. If 'Visible text' says \"no legible text was detected\", the image has no readable text — describe what the labels tell you instead. If 'What the image shows' says \"no clear objects or scenes were detected\", the image was unclear — say so honestly.")
                sb.appendLine("---IMAGE CONTENT START---")
                sb.appendLine(fileCtx)
                sb.appendLine("---IMAGE CONTENT END---")
            } else {
                sb.appendLine("ATTACHMENT CONTEXT — FILE:")
                sb.appendLine("The user has attached a document. The app has already extracted its full text on-device (PDF / DOCX / PPTX / XLSX / TXT / HTML — whatever the format was, the text is now plain text). The extracted content is included below between the markers.")
                sb.appendLine("You CAN see this file's content — it has been extracted to text and is sitting in this prompt right now. Do NOT claim you cannot see files, do NOT claim you cannot read attachments, do NOT ask the user to paste the content, do NOT say \"I can only see the filename\". The filename alone is NOT all you have — read the text below and use it to answer.")
                sb.appendLine("If the user asks about something not in the extracted text, say so honestly — but first actually read the text and check. Quote from it directly when answering so the user can verify you read it.")
                sb.appendLine("---FILE CONTENT START---")
                sb.appendLine(fileCtx)
                sb.appendLine("---FILE CONTENT END---")
            }
        }
        // Journal context — last 3 entries
        try {
            val journal = journalRepo.getRecent(3)
            if (journal.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Recent journal entries by the user (for personal context):")
                journal.forEach { e ->
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(e.createdAt))
                    val body = e.content.take(600)
                    sb.appendLine("  [$date${if (e.mood != null) ", mood: ${e.mood}" else ""}] ${e.title.ifBlank { body.take(40) }}: $body")
                }
            }
        } catch (_: Throwable) {}
        // Habit context — last 7 days
        try {
            val habits = habitRepo.summaryForAi(7)
            if (habits.isNotBlank()) {
                sb.appendLine()
                sb.appendLine(habits)
            }
        } catch (_: Throwable) {}
        // Web context — only when internet is enabled
        if (internetEnabled.value) {
            _statusText.value = "Searching the web…"
            try {
                val webCtx = withContext(Dispatchers.IO) { webSearch.search(userText) }
                if (webCtx.isNotBlank()) {
                    sb.appendLine()
                    sb.appendLine("Recent web search results for the user's query:")
                    sb.appendLine(webCtx)
                }
            } catch (t: Throwable) {
                sb.appendLine()
                sb.appendLine("[Web search failed: ${t.message}]")
            } finally {
                _statusText.value = ""
            }
        }
        // ── FINAL REMINDER ───────────────────────────────────────────
        // Small on-device models lose instructions partway through long
        // system prompts (recency bias: they follow the LAST instruction
        // best). Repeat the plain-text rule here so the model doesn't
        // start emitting **bold** markers partway through its reply.
        sb.appendLine()
        sb.appendLine("REMINDER: Reply in plain text only. Do not use **asterisks**, #headings, or -lists.")
        return sb.toString().trim().takeIf { it.isNotEmpty() } ?: ""
    }

    /**
     * Build the inline user message for the aggressive inline strategy.
     *
     * Format (image):
     *   Image content (OCR + labels from ML Kit):
     *   <content>
     *
     *   Question: <user text>
     *
     * Format (file):
     *   Document content (extracted on-device):
     *   <content>
     *
     *   Question: <user text>
     *
     * IMPORTANT: The filename is deliberately NOT included here. Small
     * models hallucinate from the filename (e.g. "Class_11_History..."
     * → "This document appears to be from TNTextbooks...") instead of
     * reading the actual extracted text. By omitting the filename, the
     * model is forced to read the content to answer.
     *
     * The content is capped at SMALL_MODEL_INLINE_BUDGET chars. Since
     * we now use a minimal system prompt (no habit/journal/web context)
     * when a file is attached, we can afford a larger inline budget.
     */
    private fun buildInlineUserMessage(
        userText: String,
        fileContent: String,
        isImage: Boolean
    ): String {
        val cap = if (isImage) SMALL_MODEL_INLINE_BUDGET_IMAGE else SMALL_MODEL_INLINE_BUDGET_FILE
        val truncated = fileContent.length > cap
        val body = if (truncated) fileContent.substring(0, cap) else fileContent
        val truncationNote = if (truncated) "\n[... document continues, ${fileContent.length - cap} more chars not shown ...]" else ""
        val label = if (isImage) "Image content (analyzed on-device — visible text + detected objects)" else "Document content (extracted on-device)"
        return buildString {
            append(label).append(":").append('\n')
            append(body).append(truncationNote).append('\n')
            append('\n')
            append("Question: ").append(userText)
        }
    }

    /**
     * Detect the "/draw <prompt>" or "/image <prompt>" slash command.
     * Returns the prompt with the prefix stripped, or null if [text] is
     * not a draw command. Leading/trailing whitespace is trimmed from
     * the prompt; an empty prompt returns null (so "/draw" alone is
     * treated as a normal message and the LLM will respond).
     *
     * Supported prefixes (case-insensitive):
     *   /draw <prompt>
     *   /image <prompt>
     *   /draw: <prompt>      (with optional colon)
     *   /img <prompt>
     */
    private fun parseDrawCommand(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null
        val lower = trimmed.lowercase()
        val prefixes = listOf("/draw:", "/draw ", "/image:", "/image ", "/img:", "/img ")
        val match = prefixes.firstOrNull { lower.startsWith(it) } ?: return null
        val prompt = trimmed.substring(match.length).trim()
        return prompt.ifBlank { null }
    }

    /**
     * Run image generation for a /draw command and persist the result.
     *
     * Flow:
     *   1. Persist user's /draw command as a normal user message.
     *   2. Update chat title if first message.
     *   3. Call imageGen.generate(prompt), which hits Pollinations.ai
     *      (cloud, free, no API key) and saves a PNG to app-private
     *      storage. Takes 5–15 seconds depending on Pollinations load.
     *   4. Persist an assistant message with imagePath pointing at the
     *      saved PNG. The bubble renders the image + the prompt as caption.
     *
     * The statusText StateFlow shows "Generating image via Pollinations.ai…"
     * so the user knows what's happening during the generation call.
     */
    private suspend fun handleDrawCommand(prompt: String, rawCommand: String) {
        // 1) Persist user message
        chatRepo.appendMessage(chatId, Role.USER, rawCommand)

        // 2) Title the chat if first message
        val current = chat.value
        if (current != null && (current.title == "New chat" || current.title.isBlank())) {
            val newTitle = "🎨 ${prompt.take(40).replace("\n", " ").trim().ifBlank { "Image" }}"
            chatRepo.rename(chatId, newTitle)
        }

        // 3) Image gen is always available (cloud-based via Pollinations.ai).
        //    isModelLoaded() returns true by default — no model loading needed.

        // 4) Generate
        _statusText.value = "Generating image via Pollinations.ai… (5–15 seconds)"
        _streamingChunk.value = ""
        try {
            val result = imageGen.generate(prompt)
            result.onSuccess { imagePath ->
                // 5) Persist assistant message with the image
                chatRepo.appendMessage(
                    chatId = chatId,
                    role = Role.ASSISTANT,
                    content = prompt,  // shown as caption under the image
                    imagePath = imagePath
                )
            }.onFailure { err ->
                val msg = err.message ?: "Image generation failed"
                _errors.send(msg)
                chatRepo.appendMessage(chatId, Role.ASSISTANT, "⚠️ $msg", isError = true)
            }
        } finally {
            _statusText.value = ""
            _streamingChunk.value = ""
        }
    }

    /**
     * Detect whether the user's message is a summarization request.
     * Triggers map-reduce for large files.
     *
     * Matches phrases like:
     *   "summarize this", "summarise", "summary", "give me a summary",
     *   "what is this about", "what's this about", "give me the gist",
     *   "tldr", "tl;dr", "brief overview", "key points",
     *   "main points", "what are the main ideas"
     *
     * Case-insensitive, matches anywhere in the message.
     */
    private fun isSummarizeRequest(text: String): Boolean {
        val lower = text.lowercase().trim()
        val triggers = listOf(
            "summarize", "summarise", "summary", "tldr", "tl;dr",
            "give me the gist", "what is this about", "what's this about",
            "what is this document", "what's this document",
            "brief overview", "key points", "main points",
            "main ideas", "overview of this", "condense",
            "short version", "in short", "in brief"
        )
        return triggers.any { lower.contains(it) }
    }

    /**
     * Run map-reduce summarization on [fileText] and stream the result
     * into the chat as an assistant message.
     *
     * This bypasses the normal LLM call entirely — the summarizer makes
     * its own calls to the LlmEngine with tiny focused prompts (one per
     * chunk) that even 0.5B models can handle.
     *
     * The user sees a progress status ("Summarizing… part 3/20") while
     * the map stage runs, then the final combined summary is streamed
     * token-by-token like a normal reply.
     */
    private suspend fun handleMapReduceSummary(userText: String, fileText: String) {
        _streamingChunk.value = ""

        // Estimate chunk count for the status message
        val estChunks = (fileText.length / 800) + 1
        _statusText.value = "Summarizing document (~$estChunks parts)…"

        android.util.Log.i("HandyAi/ChatVM",
            "Map-reduce summary: fileText=${fileText.length} chars, estChunks=$estChunks, model=${llm.activeModelName()}")

        currentGenJob = coroutineContext[Job]
        val result = summarizer.summarize(
            fullText = fileText,
            userQuestion = userText
        ) { current, total ->
            _statusText.value = "Summarizing… part $current/$total"
        }

        _statusText.value = ""

        // Wrap persist + TTS in NonCancellable so a user-initiated Stop
        // (which cancels this coroutine) doesn't cause the suspending
        // chatRepo.appendMessage / _errors.send calls to throw
        // CancellationException before the partial text is saved.
        val isCancellation = result.isFailure &&
            result.exceptionOrNull() is CancellationException
        val partial = _streamingChunk.value

        if (isCancellation && partial.isBlank()) {
            android.util.Log.i("HandyAi/ChatVM",
                "Map-reduce summary stopped by user (no partial text)")
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                result.onSuccess { summary ->
                    val raw = if (summary.isBlank()) {
                        "(Could not generate a summary — the document may be too short or unparseable.)"
                    } else {
                        summary
                    }
                    val final = com.handyai.ui.components.MarkdownParser.sanitize(raw)
                    // Stream the final summary for a natural typing effect
                    try {
                        _streamingChunk.value = ""
                        for (word in final.split(" ")) {
                            _streamingChunk.value += "$word "
                            kotlinx.coroutines.delay(15L)
                        }
                    } catch (_: CancellationException) {
                        // User hit Stop during the post-summary typing —
                        // fall through and persist what was streamed so far.
                    }
                    val toPersist = _streamingChunk.value.ifBlank { final }
                    chatRepo.appendMessage(chatId, Role.ASSISTANT, toPersist)
                    if (ttsEnabled.value) {
                        tts.speak(toPersist, "auto-${System.currentTimeMillis()}")
                    }
                }.onFailure { err ->
                    if (isCancellation && partial.isNotBlank()) {
                        chatRepo.appendMessage(chatId, Role.ASSISTANT, partial)
                        android.util.Log.i("HandyAi/ChatVM",
                            "Map-reduce summary stopped by user, persisted partial: ${partial.length} chars")
                    } else {
                        val msg = err.message ?: "Summarization failed"
                        _errors.send(msg)
                        chatRepo.appendMessage(chatId, Role.ASSISTANT,
                            "⚠️ Could not summarize: $msg\n\nTip: try asking a specific question about the document instead of requesting a summary.",
                            isError = true)
                    }
                }
            }
        }

        currentGenJob = null
        _streamingChunk.value = ""
    }

    companion object {
        /**
         * Max chars of file context to inject into the system prompt
         * (normal-model path). Sized to fit comfortably within
         * LlmEngine.INPUT_CHAR_BUDGET alongside the system preamble +
         * recent history + latest user message.
         */
        private const val FILE_CONTEXT_BUDGET = 3500

        /**
         * Max chars of file/image content to inline into the user message.
         *
         * Increased from 1800→3000 (file) and 1000→1500 (image) in v1.2.2
         * because we now use a MINIMAL system prompt when a file is attached
         * (no habit/journal/web context), freeing up context window space.
         *
         * Larger files (>2000 chars) triggered by a summarize request go
         * through map-reduce instead — see [SUMMARIZE_MAPREDUCE_THRESHOLD].
         */
        private const val SMALL_MODEL_INLINE_BUDGET_FILE = 3000
        private const val SMALL_MODEL_INLINE_BUDGET_IMAGE = 1500

        /**
         * Files longer than this (in chars) trigger map-reduce
         * summarization when the user asks for a summary.
         *
         * Below this threshold, the file is short enough for the model
         * to read in a single inline call. Above it, the model will
         * hallucinate from the filename instead of reading the content,
         * so we split into chunks and summarize each individually.
         *
         * 2000 chars ≈ ~500 tokens, which is about the max a 0.5B model
         * can reliably attend to alongside an instruction.
         */
        private const val SUMMARIZE_MAPREDUCE_THRESHOLD = 2000
    }
}

class ChatViewModelFactory(
    private val chatId: Long,
    private val chatRepo: ChatRepository,
    private val llm: LlmEngine,
    private val imageGen: com.handyai.llm.ImageGenEngine,
    private val tts: TtsEngine,
    private val settings: SettingsRepository,
    private val fileExtractor: FileTextExtractor,
    private val webSearch: WebSearchService,
    private val journalRepo: JournalRepository,
    private val habitRepo: HabitRepository,
    private val summarizer: com.handyai.llm.AttachmentSummarizer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(chatId, chatRepo, llm, imageGen, tts, settings, fileExtractor, webSearch, journalRepo, habitRepo, summarizer) as T
}

/**
 * Tiny bridge for accessing ContentResolver from a suspend function
 * without passing Context through everywhere. Set once from HandyAiApp.
 */
object HandyAiAppResolver {
    lateinit var resolver: android.content.ContentResolver
}
