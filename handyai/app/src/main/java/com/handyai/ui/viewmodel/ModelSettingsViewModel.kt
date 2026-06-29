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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelSettingsViewModel(
    private val settings: SettingsRepository,
    private val llm: LlmEngine,
    private val imageGen: ImageGenEngine,
    val downloader: ModelDownloader
) : ViewModel() {

    val activeModelName: StateFlow<String?> = settings.activeModelName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** v1.4.7: id of the cloud VLM the user picked. null = use recommended default. */
    val activeVisionModelId: StateFlow<String?> = settings.activeVisionModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** v1.4.7: id of the cloud image-gen model the user picked. null = use recommended default. */
    val activeImgGenModelId: StateFlow<String?> = settings.activeImgGenModelId
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

    fun isDownloaded(spec: ModelSpec): Boolean = when (spec.modelType) {
        // Cloud + on-device-bundled models — never "downloaded" in the file sense.
        // They're always available, so report true so the UI shows the "Activate"
        // button instead of the "Download" button.
        ModelType.VISION, ModelType.IMAGE_GEN,
        ModelType.ON_DEVICE_VISION, ModelType.ON_DEVICE_IMAGE_GEN -> true
        ModelType.LLM -> downloader.isDownloaded(spec)
    }

    fun download(spec: ModelSpec) = viewModelScope.launch {
        // Cloud + on-device-bundled models have nothing to download.
        // Only LLM (.task file) actually downloads.
        if (spec.modelType != ModelType.LLM) return@launch
        downloader.download(spec)
    }

    fun activate(spec: ModelSpec) = viewModelScope.launch {
        // ── DISPATCH BY MODEL TYPE ────────────────────────────────────
        // v1.4.9: Five model types now:
        //   - LLM                 → on-device .task file, requires prior download
        //   - VISION              → cloud VLM, no download, persist selection in settings
        //   - IMAGE_GEN           → cloud Pollinations, no download, persist selection
        //                          + apply to ImageGenEngine immediately
        //   - ON_DEVICE_VISION    → ML Kit-based, no download, persist selection
        //                          (ChatViewModel routes to OnDeviceVisionAnalyzer)
        //   - ON_DEVICE_IMAGE_GEN → procedural, no download, persist selection
        //                          (ChatViewModel routes to ProceduralArtEngine)
        try {
            when (spec.modelType) {
                ModelType.LLM -> {
                    val path = downloader.localPath(spec).absolutePath
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
                ModelType.VISION, ModelType.ON_DEVICE_VISION -> {
                    // Cloud OR on-device vision — both persist selection in
                    // settings. ChatViewModel reads activeVisionModelId and
                    // routes to VisionLlm (cloud) or OnDeviceVisionAnalyzer
                    // (on-device) based on the id prefix.
                    settings.setActiveVisionModel(spec.id)
                    android.util.Log.i("HandyAi/ModelSettingsVM",
                        "Active vision model set to ${spec.id} (${spec.cloudModelId})")
                }
                ModelType.IMAGE_GEN, ModelType.ON_DEVICE_IMAGE_GEN -> {
                    // Cloud OR on-device image gen — both persist selection.
                    // For cloud models, also push the Pollinations model id
                    // to the engine immediately. For on-device procedural,
                    // the id prefix tells ChatViewModel to use ProceduralArtEngine.
                    settings.setActiveImgGenModel(spec.id)
                    if (spec.modelType == ModelType.IMAGE_GEN) {
                        imageGen.setActiveModelId(spec.cloudModelId)
                    }
                    android.util.Log.i("HandyAi/ModelSettingsVM",
                        "Active image-gen model set to ${spec.id} (${spec.cloudModelId})")
                }
            }
        } catch (t: Throwable) {
            // ── v1.4.2: top-level safety net ────────────────────────────
            // If anything escapes the per-engine try-catches above, surface
            // a friendly error instead of letting the ViewModel crash.
            android.util.Log.e("HandyAi/ModelSettingsVM",
                "Model activation top-level failure", t)
            llm.surfaceError(t.message ?: t.javaClass.simpleName
                ?: "Could not activate model — unknown error")
        }
    }

    fun unload() = viewModelScope.launch {
        llm.unload()
        imageGen.unload()
        settings.setActiveModel(null, null)
        // v1.4.7: also clear cloud-model selections so the cards go back
        // to showing "Activate" instead of "Unload". The recommended
        // default is restored on next selection.
        settings.setActiveVisionModel(null)
        settings.setActiveImgGenModel(null)
        imageGen.setActiveModelId("flux")
    }

    /**
     * v1.4.7 / v1.4.9: Unload one specific model. For cloud OR on-device-bundled
     * models (VISION / IMAGE_GEN / ON_DEVICE_VISION / ON_DEVICE_IMAGE_GEN),
     * "unload" means "clear the user's selection" — there's no engine state
     * to clear. For LLM, it calls llm.unload() AND deletes the file.
     */
    fun delete(spec: ModelSpec) = viewModelScope.launch {
        when (spec.modelType) {
            ModelType.LLM -> {
                val activeName = llm.activeModelName()
                if (activeName == spec.displayName) {
                    llm.unload()
                    settings.setActiveModel(null, null)
                }
                downloader.localPath(spec).delete()
                downloader.reset(spec.id)
            }
            ModelType.VISION, ModelType.ON_DEVICE_VISION -> {
                val currentId = settings.activeVisionModelId.firstOrNull()
                if (currentId == spec.id) {
                    settings.setActiveVisionModel(null)
                }
            }
            ModelType.IMAGE_GEN, ModelType.ON_DEVICE_IMAGE_GEN -> {
                val currentId = settings.activeImgGenModelId.firstOrNull()
                if (currentId == spec.id) {
                    settings.setActiveImgGenModel(null)
                    imageGen.setActiveModelId("flux")
                }
            }
        }
    }

    /** Clear the saved download state for one model (e.g. dismiss an error). */
    fun resetState(spec: ModelSpec) {
        if (spec.modelType == ModelType.LLM) downloader.reset(spec.id)
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
            imageGen = app.imageGenEngine,
            downloader = app.modelDownloader
        ) as T
}
