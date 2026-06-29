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
import android.util.Log
import com.handyai.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Auto-reloads the last active model on app start so the user doesn't
 * have to manually unload + reload the model every time they close and
 * reopen the app.
 *
 * This was a user-reported pain point: "When I open the app after closing
 * then again I have to load the already loaded model by unloading and
 * then loading."
 *
 * The fix is to persist the active model's path in DataStore (already
 * done by SettingsRepository) and re-load it on app start. We launch
 * this from HandyAiApp.onCreate() so the model is loaded in the
 * background while the UI is rendering.
 *
 * Failure handling:
 *   - If the saved path is null, do nothing (first run / after unload)
 *   - If the file no longer exists, clear the saved path silently
 *   - If loading throws, the LlmEngine surfaces the error via its
 *     StateFlow, which the UI already displays
 *
 * Note: Image generation is now cloud-based (Pollinations.ai) and
 * doesn't require model loading, so this only handles LLM models.
 */
object ModelAutoLoader {

    private const val TAG = "HandyAi/ModelAutoLoader"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Should be called once from HandyAiApp.onCreate(). Reads the saved
     * model path from DataStore and, if non-null and the file still
     * exists, asks the appropriate engine to load it.
     *
     * Dispatch by file extension:
     *   - `.task`       → MediaPipe LlmEngine (text-only models)
     *   - `.litertlm`   → LiteRT-LM LiteRtlmEngine (vision models)
     */
    fun autoLoad(
        context: Context,
        llm: LlmEngine,
        liteRtlm: com.handyai.llm.LiteRtlmEngine,
        settings: SettingsRepository
    ) {
        scope.launch {
            try {
                val savedPath = settings.activeModelPath.first()
                if (savedPath.isNullOrBlank()) {
                    Log.i(TAG, "No saved model path — skipping auto-load (first run or after unload).")
                    return@launch
                }
                val file = File(savedPath)
                if (!file.exists()) {
                    Log.w(TAG, "Saved model file no longer exists: $savedPath — clearing saved path.")
                    settings.setActiveModel(null, null)
                    return@launch
                }
                // Look up the param count from the catalog (matched by
                // displayName) so the small-model inline-content strategy
                // works after restart.
                val savedName = settings.activeModelName.first()
                val spec = ModelCatalog.ALL.firstOrNull { it.displayName == savedName }

                // ── DISPATCH BY EXTENSION ──────────────────────────────────
                // .task → MediaPipe; .litertlm → LiteRT-LM. The two runtimes
                // can't load each other's files.
                val isLitertlm = savedPath.endsWith(".litertlm", ignoreCase = true)
                if (isLitertlm) {
                    // ── v1.4.3: skip auto-loading .litertlm models ─────────
                    // LiteRT-LM alpha05 crashes natively in eng.initialize()
                    // on arm64-v8a. If we auto-load here, the app crashes on
                    // every launch until the user manually clears the saved
                    // model path. Instead, silently clear the saved path and
                    // let the user pick a text model from the Models screen.
                    Log.w(TAG, "Saved model is .litertlm (vision) — skipping auto-load " +
                        "(LiteRT-LM alpha05 native crash). Clearing saved path.")
                    settings.setActiveModel(null, null)
                    return@launch
                } else {
                    if (llm.isModelLoaded()) {
                        Log.i(TAG, "MediaPipe model already loaded — skipping.")
                        return@launch
                    }
                    Log.i(TAG, "Auto-loading MediaPipe model: $savedPath (${file.length()} bytes)")
                    val result = llm.setActiveModel(savedPath, spec?.id, spec?.paramCountB)
                    result.onSuccess { Log.i(TAG, "MediaPipe auto-load succeeded.") }
                        .onFailure { err -> Log.e(TAG, "MediaPipe auto-load failed.", err) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Auto-load unexpected error.", t)
            }
        }
    }
}
