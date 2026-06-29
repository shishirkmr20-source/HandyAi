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
import com.handyai.data.model.Habit
import com.handyai.data.repo.HabitRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class HabitViewModel(private val repo: HabitRepository) : ViewModel() {

    val habits: StateFlow<List<Habit>> = repo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(habit: Habit) = viewModelScope.launch { repo.save(habit) }

    fun delete(habit: Habit) = viewModelScope.launch { repo.delete(habit) }

    fun toggleToday(habitId: Long) = viewModelScope.launch {
        repo.toggleCheckin(habitId, LocalDate.now(ZoneId.systemDefault()))
    }

    suspend fun isCheckedToday(habitId: Long): Boolean =
        repo.getCheckin(habitId, LocalDate.now(ZoneId.systemDefault())) != null
}

class HabitViewModelFactory(private val repo: HabitRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = HabitViewModel(repo) as T
}
