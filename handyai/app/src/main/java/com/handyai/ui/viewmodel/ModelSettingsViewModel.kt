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
        // All catalog entries are LLM models now — image generation is
        // a built-in cloud feature (Pollinations.ai) and doesn't need
        // a model card or activation.
        val result = llm.setActiveModel(path, spec.id, spec.paramCountB)
        result.onSuccess {
            settings.setActiveModel(path, spec.displayName)
        }
    }

    fun unload() = viewModelScope.launch {
        llm.unload()
        imageGen.unload()
        settings.setActiveModel(null, null)
    }

    /** Delete the downloaded model file so it can be re-downloaded fresh. */
    fun delete(spec: ModelSpec) = viewModelScope.launch {
        if (llm.activeModelName() == spec.displayName) {
            llm.unload()
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
            imageGen = app.imageGenEngine,
            downloader = app.modelDownloader
        ) as T
}
