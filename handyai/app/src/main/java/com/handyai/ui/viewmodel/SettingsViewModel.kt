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
import com.handyai.data.repo.SettingsRepository
import com.handyai.llm.LlmEngine
import com.handyai.ui.theme.HandyAiThemeId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val llm: LlmEngine
) : ViewModel() {

    val internetEnabled: StateFlow<Boolean> = settings.internetEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val ttsEnabled: StateFlow<Boolean> = settings.ttsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * The user's chosen theme id. Null until either (a) the user has
     * explicitly picked a theme, or (b) the migration has run.
     *
     * The Settings UI binds to this and calls [setTheme] on tap. The
     * MainActivity also binds to this (via the repository directly) so
     * the theme switch is reflected app-wide immediately.
     */
    val themeId: StateFlow<HandyAiThemeId> = settings.themeId
        .map { HandyAiThemeId.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HandyAiThemeId.CREAM)

    val activeModelName: StateFlow<String?> = settings.activeModelName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setInternet(on: Boolean) = viewModelScope.launch { settings.setInternet(on) }
    fun setTts(on: Boolean) = viewModelScope.launch { settings.setTts(on) }
    fun setTheme(id: HandyAiThemeId) = viewModelScope.launch { settings.setThemeId(id.id) }
}

class SettingsViewModelFactory(
    private val settings: SettingsRepository,
    private val llm: LlmEngine
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(settings, llm) as T
}
