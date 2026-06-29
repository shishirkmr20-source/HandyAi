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
package com.handyai.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Wraps Android's built-in SpeechRecognizer for voice-to-text input.
 * - Uses the system default speech engine (on-device on most modern phones)
 * - Reports partial results so the UI can show live transcription
 * - Reports listening state for mic-button UI feedback
 *
 * Requires RECORD_AUDIO permission (declared in manifest, requested at runtime).
 *
 * ── v1.3.2 RECOGNIZER LIFECYCLE FIX ─────────────────────────────────
 * Previous versions created a NEW SpeechRecognizer on every startListening()
 * call (after stop()-ing the previous one). The Android SpeechRecognizer
 * service binding is asynchronous — destroying one instance and creating
 * another in the same frame causes the new instance to fail with
 * ERROR_CLIENT or ERROR_RECOGNIZER_BUSY on the very next startListening().
 * This produced the "tap mic, works; tap again, error; tap again, works"
 * pattern the user reported.
 *
 * Fix: keep a SINGLE shared SpeechRecognizer for the lifetime of this
 * engine. startListening() now calls cancel() (not destroy) on any
 * in-flight session and reuses the existing instance. destroy() is only
 * called by [shutdown] which is intended for app-level teardown.
 *
 * SpeechRecognizer must be created on the main thread — all calls are
 * marshalled to the main looper via [mainHandler].
 */
class SttEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Single shared recognizer instance. Lazily created on the main thread
     * the first time [startListening] is called, then reused for every
     * subsequent call. Set to null only by [shutdown].
     */
    @Volatile
    private var recognizer: SpeechRecognizer? = null

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    /** Callback invoked when a final result is available. */
    @Volatile
    private var onResult: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Lazily create the shared SpeechRecognizer on the main thread and
     * attach the recognition listener. Idempotent — if an instance
     * already exists, it is reused.
     */
    private fun ensureRecognizer() {
        if (recognizer != null) return
        mainHandler.post {
            if (recognizer != null) return@post
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() { _listening.value = true }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { _listening.value = false }
                        override fun onError(error: Int) {
                            _listening.value = false
                            _errors.value = mapError(error)
                        }
                        override fun onResults(results: Bundle?) {
                            _listening.value = false
                            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = list?.firstOrNull()?.takeIf { it.isNotBlank() } ?: ""
                            _partial.value = text
                            onResult?.invoke(text)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = list?.firstOrNull() ?: ""
                            if (text.isNotBlank()) _partial.value = text
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            } catch (t: Throwable) {
                _errors.value = "Speech recognizer init failed: ${t.message}"
            }
        }
    }

    /**
     * Start listening. [onFinal] is invoked once with the recognized text
     * when recognition completes. Calling this again while already
     * listening will cancel the previous session and start a new one.
     */
    fun startListening(onFinal: (String) -> Unit) {
        if (!isAvailable()) {
            _errors.value = "Speech recognition not available on this device"
            return
        }
        // Cancel any in-flight session WITHOUT destroying the recognizer.
        // cancel() leaves the instance ready to receive a new startListening()
        // call — destroy() would force a recreate and trigger the
        // ERROR_CLIENT race this class was suffering from in v1.3.1.
        cancelInFlight()
        onResult = onFinal
        _partial.value = ""
        _errors.value = null

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        // Recognizer creation + startListening must both run on the main
        // thread. ensureRecognizer() posts to the main handler; we then
        // post the startListening call after it. If the recognizer was
        // already created, this still works (just an extra post).
        mainHandler.post {
            ensureRecognizer()
            // ensureRecognizer() may itself post to create the recognizer
            // asynchronously. Post startListening after a tiny delay so
            // the recognizer is guaranteed to exist when we call it.
            mainHandler.post {
                try {
                    recognizer?.startListening(intent)
                    _listening.value = true
                } catch (t: Throwable) {
                    _listening.value = false
                    _errors.value = "Failed to start listening: ${t.message}"
                    // If startListening threw, the recognizer is in a bad
                    // state — destroy it so the next call creates a fresh one.
                    destroyRecognizer()
                }
            }
        }
    }

    /**
     * Stop listening but keep the recognizer instance alive for reuse.
     * This is the right call when the user taps the mic button to stop,
     * or when the chat screen is navigated away from.
     */
    fun stop() {
        _listening.value = false
        cancelInFlight()
    }

    /** Clear the last reported error so the UI doesn't keep showing it. */
    fun clearError() { _errors.value = null }

    /**
     * Cancel any in-flight recognition session without destroying the
     * underlying recognizer instance. Safe to call when not listening.
     */
    private fun cancelInFlight() {
        mainHandler.post {
            try { recognizer?.cancel() } catch (_: Throwable) {}
        }
    }

    /**
     * Destroy the recognizer instance. Called internally when
     * startListening throws (to force a fresh recreate next time), and
     * externally by [shutdown] during app teardown.
     */
    private fun destroyRecognizer() {
        mainHandler.post {
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
        }
    }

    /**
     * Tear down the recognizer entirely. Intended for app-level cleanup
     * (e.g. if we ever expose this from HandyAiApp.onTerminate). The
     * shared SttEngine instance lives for the app's lifetime, so this
     * is rarely called in practice.
     */
    fun shutdown() {
        _listening.value = false
        destroyRecognizer()
    }

    private fun mapError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech heard"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Speech recognition error ($code)"
    }
}
