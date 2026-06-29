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
package com.handyai.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Wraps Android's built-in TextToSpeech engine.
 * - Uses the system default TTS voice (user can change in system settings)
 * - Streams long text in chunks to avoid buffer issues
 * - Reports speaking state for UI feedback
 */
class TtsEngine(context: Context) {

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
        }
    }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
                _currentId.value = utteranceId
            }
            override fun onDone(utteranceId: String?) {
                _speaking.value = false
                _currentId.value = null
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _speaking.value = false
                _currentId.value = null
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                _speaking.value = false
                _currentId.value = null
            }
        })
    }

    /**
     * Speak the given text. Replaces any current speech.
     * [utteranceId] is propagated via [currentId] so UI can highlight the message.
     *
     * ── MARKDOWN SANITIZATION (v1.4.1) ─────────────────────────────────
     * Before sending to TextToSpeech, the text is passed through
     * [TtsSpeechSanitizer.sanitize] which:
     *   - Converts markdown tables (| col | col |) into spoken prose
     *     ("Columns: Name, Age. Alice 30. Bob 25.")
     *   - Strips **bold**, #headings, `code`, [links](url), bullet markers
     *   - Drops the table separator row (|---|---|) entirely
     *
     * Without this, the TTS would read pipes and hyphens verbatim:
     *   "pipe Name pipe Age pipe pipe hyphen hyphen hyphen…"
     * which is unlistenable and wastes time. The user explicitly asked
     * for this to be suppressed when voice is enabled.
     */
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()) {
        if (text.isBlank()) return
        // Sanitize markdown syntax BEFORE splitting into chunks so the
        // chunker sees clean prose and doesn't split mid-table-row.
        val sanitized = TtsSpeechSanitizer.sanitize(text)
        if (sanitized.isBlank()) return
        val chunks = splitIntoChunks(sanitized, maxLen = 280)
        chunks.forEachIndexed { i, chunk ->
            val id = if (i == 0) utteranceId else "$utteranceId-$i"
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunk, mode, null, id)
        }
    }

    fun stop() {
        tts.stop()
        _speaking.value = false
        _currentId.value = null
    }

    fun isSpeaking(): Boolean = _speaking.value

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[.!?。！？])\\s+"))
        val sb = StringBuilder()
        for (s in sentences) {
            if ((sb.length + s.length + 1) > maxLen && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
