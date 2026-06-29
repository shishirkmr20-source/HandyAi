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
 * v1.4.5: Removed the LiteRT-LM dispatch branch. All downloadable models
 * are now MediaPipe `.task` files (FastVLM `.litertlm` removed due to
 * native crashes — see ModelCatalog.kt).
 *
 * Note: Image generation is cloud-based (Pollinations.ai) and doesn't
 * require model loading, so this only handles LLM models.
 */
object ModelAutoLoader {

    private const val TAG = "HandyAi/ModelAutoLoader"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Should be called once from HandyAiApp.onCreate(). Reads the saved
     * model path from DataStore and, if non-null and the file still
     * exists, asks the MediaPipe LlmEngine to load it.
     *
     * v1.4.5: Only handles `.task` files. If the saved path ends in
     * `.litertlm` (leftover from a previous FastVLM activation before
     * the upgrade), silently clear the saved path — the runtime that
     * could load those files has been removed.
     */
    fun autoLoad(
        context: Context,
        llm: LlmEngine,
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
                // v1.4.5: leftover .litertlm path from a pre-upgrade FastVLM
                // activation — clear it so the user picks a fresh text model.
                if (savedPath.endsWith(".litertlm", ignoreCase = true)) {
                    Log.w(TAG, "Saved model is .litertlm (vision) — LiteRT-LM runtime removed in v1.4.5. Clearing saved path.")
                    settings.setActiveModel(null, null)
                    return@launch
                }
                // Look up the param count from the catalog (matched by
                // displayName) so the small-model inline-content strategy
                // works after restart.
                val savedName = settings.activeModelName.first()
                val spec = ModelCatalog.ALL.firstOrNull { it.displayName == savedName }

                if (llm.isModelLoaded()) {
                    Log.i(TAG, "MediaPipe model already loaded — skipping.")
                    return@launch
                }
                Log.i(TAG, "Auto-loading MediaPipe model: $savedPath (${file.length()} bytes)")
                val result = llm.setActiveModel(savedPath, spec?.id, spec?.paramCountB)
                result.onSuccess { Log.i(TAG, "MediaPipe auto-load succeeded.") }
                    .onFailure { err -> Log.e(TAG, "MediaPipe auto-load failed.", err) }
            } catch (t: Throwable) {
                Log.e(TAG, "Auto-load unexpected error.", t)
            }
        }
    }
}
