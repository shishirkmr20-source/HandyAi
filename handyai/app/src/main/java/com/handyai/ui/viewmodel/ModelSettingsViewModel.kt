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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.handyai.HandyAiApp
import com.handyai.data.repo.SettingsRepository
import com.handyai.llm.ImageGenEngine
import com.handyai.llm.ImageGenState
import com.handyai.llm.LlmEngine
import com.handyai.llm.LlmState
import com.handyai.llm.ModelDownloader
import com.handyai.llm.ModelSpec
import com.handyai.llm.ModelType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelSettingsViewModel(
    private val settings: SettingsRepository,
    private val llm: LlmEngine,
    private val liteRtlm: com.handyai.llm.LiteRtlmEngine,
    private val imageGen: ImageGenEngine,
    val downloader: ModelDownloader
) : ViewModel() {

    val activeModelName: StateFlow<String?> = settings.activeModelName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Combined state — drives the status banner at the top of ModelSettingsScreen.
     *  Shows whichever engine is currently busy (LLM takes precedence over
     *  image-gen if both are active somehow). */
    val combinedState: StateFlow<CombinedEngineState> = combine(
        llm.state, imageGen.state
    ) { ls, is_ ->
        when {
            ls is LlmState.Loading -> CombinedEngineState.LlmLoading
            ls is LlmState.Generating -> CombinedEngineState.LlmGenerating
            is_ is ImageGenState.Loading -> CombinedEngineState.ImageGenLoading
            is_ is ImageGenState.Generating -> CombinedEngineState.ImageGenGenerating
            ls is LlmState.Error -> CombinedEngineState.LlmError(ls.message)
            is_ is ImageGenState.Error -> CombinedEngineState.ImageGenError(is_.message)
            ls is LlmState.Ready -> CombinedEngineState.LlmReady
            is_ is ImageGenState.Ready -> CombinedEngineState.ImageGenReady
            else -> CombinedEngineState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CombinedEngineState.Idle)

    fun isDownloaded(spec: ModelSpec): Boolean = downloader.isDownloaded(spec)

    fun download(spec: ModelSpec) = viewModelScope.launch {
        downloader.download(spec)
    }

    fun activate(spec: ModelSpec) = viewModelScope.launch {
        val path = downloader.localPath(spec).absolutePath
        // ── DISPATCH BY MODEL TYPE ────────────────────────────────────
        // VISION_LITERTLM models (.litertlm files) go through LiteRtlmEngine;
        // everything else (.task files) goes through MediaPipe LlmEngine.
        try {
            when (spec.modelType) {
                ModelType.VISION_LITERTLM -> {
                    // LiteRT-LM alpha05 declares minSdk 31 (Android 12) in its
                    // manifest. We use tools:overrideLibrary so the APK installs
                    // on older Android, but the runtime will crash on activation
                    // below API 31. Guard here with a friendly error instead.
                    if (android.os.Build.VERSION.SDK_INT < 31) {
                        val msg = "Vision models (FastVLM) require Android 12 or newer. " +
                            "Your device is Android ${android.os.Build.VERSION.RELEASE} " +
                            "(API ${android.os.Build.VERSION.SDK_INT}). " +
                            "Use a text-only model (Qwen / Phi / SmolLM) instead — image " +
                            "attachments will still work via on-device ML Kit OCR + labels."
                        llm.surfaceError(msg)
                        return@launch
                    }
                    // Pre-flight: verify the file exists and is non-trivial
                    // before handing off to the native loader. A missing or
                    // truncated file causes a native crash that bypasses
                    // Java exception handling.
                    val modelFile = java.io.File(path)
                    if (!modelFile.exists() || modelFile.length() < 100 * 1024) {
                        val msg = "Vision model file is missing or too small " +
                            "(${modelFile.length()} bytes). Please delete and re-download."
                        llm.surfaceError(msg)
                        return@launch
                    }
                    // Unload the MediaPipe engine if a .task model was previously
                    // active (the two engines can't run simultaneously without
                    // eating ~5GB of RAM combined).
                    if (llm.isModelLoaded()) {
                        llm.unload()
                    }
                    // ── v1.4.2: extra defensive try-catch around the entire
                    // LiteRT-LM activation. The native library can throw
                    // UnsatisfiedLinkError or RuntimeException subclasses that
                    // wouldn't be caught by the engine's internal try-catch
                    // (they happen during class loading or native method
                    // registration, before setActiveModel's body runs).
                    val result = try {
                        liteRtlm.setActiveModel(path)
                    } catch (nativeErr: Throwable) {
                        android.util.Log.e("HandyAi/ModelSettingsVM",
                            "Vision model native activation threw", nativeErr)
                        llm.surfaceError(nativeErr.message ?: nativeErr.javaClass.simpleName
                            ?: "Vision model failed to load (native error)")
                        return@launch
                    }
                    result.onSuccess {
                        settings.setActiveModel(path, spec.displayName)
                    }.onFailure { err ->
                        llm.surfaceError(err.message ?: "Failed to activate vision model")
                    }
                }
                ModelType.LLM -> {
                    // Unload the LiteRT-LM engine if a .litertlm model was
                    // previously active.
                    if (liteRtlm.isModelLoaded()) {
                        liteRtlm.unload()
                    }
                    val result = try {
                        llm.setActiveModel(path, spec.id, spec.paramCountB)
                    } catch (t: Throwable) {
                        android.util.Log.e("HandyAi/ModelSettingsVM",
                            "LLM activation threw", t)
                        return@launch
                    }
                    result.onSuccess {
                        settings.setActiveModel(path, spec.displayName)
                    }
                }
                ModelType.IMAGE_GEN -> {
                    // Image generation is a built-in cloud feature now — no
                    // activation needed.
                }
            }
        } catch (t: Throwable) {
            // ── v1.4.2: top-level safety net ────────────────────────────
            // If anything escapes the per-engine try-catches above (e.g. a
            // NoClassDefFoundError if LiteRT-LM's classes aren't on the
            // classpath on an older device), surface a friendly error
            // instead of letting the ViewModel crash.
            android.util.Log.e("HandyAi/ModelSettingsVM",
                "Model activation top-level failure", t)
            llm.surfaceError(t.message ?: t.javaClass.simpleName
                ?: "Could not activate model — unknown error")
        }
    }

    fun unload() = viewModelScope.launch {
        llm.unload()
        liteRtlm.unload()
        imageGen.unload()
        settings.setActiveModel(null, null)
    }

    /** Delete the downloaded model file so it can be re-downloaded fresh. */
    fun delete(spec: ModelSpec) = viewModelScope.launch {
        val activeName = llm.activeModelName() ?: liteRtlm.activeModelName()
        if (activeName == spec.displayName) {
            when (spec.modelType) {
                ModelType.VISION_LITERTLM -> liteRtlm.unload()
                else -> llm.unload()
            }
            settings.setActiveModel(null, null)
        }
        downloader.localPath(spec).delete()
        downloader.reset(spec.id)
    }

    /** Clear the saved download state for one model (e.g. dismiss an error). */
    fun resetState(spec: ModelSpec) {
        downloader.reset(spec.id)
    }
}

/** Surfaced to the UI as a single enum so the status banner doesn't have
 *  to switch on which engine is in which state. */
sealed interface CombinedEngineState {
    data object Idle : CombinedEngineState
    data object LlmLoading : CombinedEngineState
    data object LlmGenerating : CombinedEngineState
    data object LlmReady : CombinedEngineState
    data class LlmError(val message: String) : CombinedEngineState
    data object ImageGenLoading : CombinedEngineState
    data object ImageGenGenerating : CombinedEngineState
    data object ImageGenReady : CombinedEngineState
    data class ImageGenError(val message: String) : CombinedEngineState
}

class ModelSettingsViewModelFactory(
    private val settings: SettingsRepository,
    private val llm: LlmEngine,
    private val app: HandyAiApp
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ModelSettingsViewModel(
            settings = settings,
            llm = llm,
            liteRtlm = app.liteRtlmEngine,
            imageGen = app.imageGenEngine,
            downloader = app.modelDownloader
        ) as T
}
