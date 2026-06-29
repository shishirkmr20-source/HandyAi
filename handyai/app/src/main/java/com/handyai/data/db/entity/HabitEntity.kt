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
package com.handyai.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A habit the user wants to track — e.g. "Drink water", "Exercise",
 * "Read 10 pages". The AI can see recent check-in stats to give
 * encouragement or suggestions.
 *
 * Fields:
 *   - name:        short label
 *   - description: free-form notes
 *   - category:    grouping label, one of HabitCategories.STANDARD_VALUES
 *                  (Health & Fitness, Productivity, etc.) — blank means
 *                  uncategorized
 *   - targetDate:  ISO date string (YYYY-MM-DD) the user wants to start
 *                  or focus on this habit — optional, blank means daily
 *   - targetTime:  ISO time string (HH:MM) for time-of-day reminders —
 *                  optional, blank means no specific time
 *   - status:      lifecycle state, one of HabitStatus.STANDARD_VALUES
 *                  (Active, Paused, Completed, Archived)
 *   - colorHex:    UI accent color
 *   - createdAt:   epoch millis
 *   - archived:    soft-delete flag (legacy; superseded by status="Archived"
 *                  but kept for backward-compat with existing rows)
 */
@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val category: String = "",
    val targetDate: String = "",
    val targetTime: String = "",
    val status: String = "Active",
    val colorHex: String = "#1A8FE3",
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false
)
