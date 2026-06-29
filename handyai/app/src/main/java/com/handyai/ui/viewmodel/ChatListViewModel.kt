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
import com.handyai.data.model.Chat
import com.handyai.data.repo.ChatRepository
import com.handyai.data.repo.SettingsRepository
import com.handyai.llm.LlmEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatListViewModel(
    private val chatRepo: ChatRepository,
    private val llm: LlmEngine,
    private val settings: SettingsRepository
) : ViewModel() {

    val chats: StateFlow<List<Chat>> = chatRepo.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Create a new chat and invoke [onCreated] with the new chat id.
     * Suspends internally so the caller doesn't need runBlocking.
     */
    suspend fun createChat(onCreated: (Long) -> Unit) {
        val id = chatRepo.createChat()
        onCreated(id)
    }

    fun rename(id: Long, title: String) = viewModelScope.launch {
        chatRepo.rename(id, title)
    }

    fun delete(chat: Chat) = viewModelScope.launch {
        chatRepo.delete(chat)
    }
}

class ChatListViewModelFactory(
    private val chatRepo: ChatRepository,
    private val llm: LlmEngine,
    private val settings: SettingsRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ChatListViewModel(chatRepo, llm, settings) as T
}
