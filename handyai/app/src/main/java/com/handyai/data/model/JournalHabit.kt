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
package com.handyai.data.model

data class JournalEntry(
    val id: Long,
    val title: String,
    val content: String,
    val mood: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class Habit(
    val id: Long,
    val name: String,
    val description: String,
    val category: String,
    val targetDate: String,
    val targetTime: String,
    val status: String,
    val colorHex: String,
    val createdAt: Long,
    val archived: Boolean
)

data class HabitCheckIn(
    val id: Long,
    val habitId: Long,
    val epochDay: Long,
    val completed: Boolean,
    val note: String?,
    val createdAt: Long
)
