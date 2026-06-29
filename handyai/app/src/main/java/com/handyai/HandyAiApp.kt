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
package com.handyai

import android.app.Application
import com.handyai.data.db.HandyAiDatabase
import com.handyai.data.repo.AttachmentCache
import com.handyai.data.repo.ChatRepository
import com.handyai.data.repo.SettingsRepository
import com.handyai.llm.LlmEngine
import com.handyai.tts.TtsEngine
import com.handyai.files.FileTextExtractor
import com.handyai.files.ImageAnalyzer
import com.handyai.net.WebSearchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI container — no Hilt to keep the build light and the APK small.
 */
class HandyAiApp : Application() {

    // Lazy singletons — created on first access, reused thereafter.
    val database by lazy { HandyAiDatabase.get(this) }
    val chatRepository by lazy { ChatRepository(database.chatDao(), database.messageDao()) }
    val journalRepository by lazy { com.handyai.data.repo.JournalRepository(database.journalDao()) }
    val habitRepository by lazy { com.handyai.data.repo.HabitRepository(database.habitDao()) }
    val settingsRepository by lazy { SettingsRepository(this) }
    val llmEngine by lazy { LlmEngine(this) }
    /** On-device text-to-image engine (Stable Diffusion 1.5 via MediaPipe
     *  Image Generator). Separate from [llmEngine] — the two tasks use
     *  different .task file formats and cannot share a session. */
    val imageGenEngine by lazy { com.handyai.llm.ImageGenEngine(this) }
    /** Map-reduce summarization pipeline for large attached documents.
     *  Uses [llmEngine] under the hood but with tiny focused prompts
     *  that even 0.5B models can handle reliably. */
    val attachmentSummarizer by lazy { com.handyai.llm.AttachmentSummarizer(llmEngine) }
    val ttsEngine by lazy { TtsEngine(this) }
    val sttEngine by lazy { com.handyai.stt.SttEngine(this) }

    /** Session-scoped cache for extracted document/image text.
     *  Wiped on every app launch — see onCreate(). */
    val attachmentCache by lazy { AttachmentCache(database.extractedAttachmentDao()) }

    val fileExtractor by lazy {
        FileTextExtractor(
            context = this,
            imageAnalyzer = ImageAnalyzer(this),
            cloudImageAnalyzer = com.handyai.files.CloudImageAnalyzer(this),
            cache = attachmentCache
        )
    }
    val webSearch by lazy { WebSearchService() }
    val modelDownloader by lazy { com.handyai.llm.ModelDownloader(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.handyai.ui.viewmodel.HandyAiAppResolver.resolver = contentResolver

        // Install the global crash logger FIRST, before any other code runs.
        // This captures JVM-level crashes (OOM, NPE, etc.) to a file in the
        // app's internal storage so the user can view + share it from
        // Settings → "Crash log". Native crashes (SIGSEGV) bypass this —
        // see CrashLogger.kt docs for why and what that means for diagnosis.
        com.handyai.CrashLogger.install(this)

        // PDFBox-android requires a one-time init before any PDDocument.load()
        // call. Without this, every PDF parse silently fails with an exception
        // that FileTextExtractor catches and returns as "[PDF parse error: ...]".
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
            android.util.Log.i("HandyAi/App", "PDFBox resource loader initialized")
        } catch (t: Throwable) {
            android.util.Log.e("HandyAi/App", "PDFBox init failed", t)
        }

        // Wipe the attachment cache. The user wanted "once the app is closed
        // the stored data is deleted" — Android doesn't reliably signal app
        // close, so we clear-on-launch instead. Effect: extracted text from
        // the previous session is gone, and any new extractions this session
        // live only until the next app launch.
        appScope.launch {
            try {
                val n = attachmentCache.clearAll()
                android.util.Log.i("HandyAi/App", "Attachment cache cleared on launch")
            } catch (t: Throwable) {
                android.util.Log.w("HandyAi/App", "Cache clear failed: ${t.message}")
            }
        }

        // Auto-load the last active model so the user doesn't have to
        // manually unload + reload every time they reopen the app.
        // Image generation is now cloud-based (Pollinations.ai) and
        // doesn't need auto-loading.
        com.handyai.llm.ModelAutoLoader.autoLoad(this, llmEngine, settingsRepository)
    }

    companion object {
        lateinit var instance: HandyAiApp
            private set
    }
}
