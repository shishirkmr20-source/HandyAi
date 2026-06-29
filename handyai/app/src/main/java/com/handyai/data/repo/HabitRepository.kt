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
package com.handyai.data.repo

import com.handyai.data.db.dao.HabitDao
import com.handyai.data.db.entity.HabitCheckInEntity
import com.handyai.data.db.entity.HabitEntity
import com.handyai.data.model.Habit
import com.handyai.data.model.HabitCheckIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

class HabitRepository(private val dao: HabitDao) {

    fun observeActive(): Flow<List<Habit>> =
        dao.observeActive().map { rows -> rows.map { it.toDomain() } }

    suspend fun getAll(): List<Habit> = dao.getAll().map { it.toDomain() }

    suspend fun getById(id: Long): Habit? = dao.getById(id)?.toDomain()

    suspend fun save(habit: Habit): Long {
        val entity = HabitEntity(
            id = habit.id,
            name = habit.name,
            description = habit.description,
            category = habit.category,
            targetDate = habit.targetDate,
            targetTime = habit.targetTime,
            status = habit.status,
            colorHex = habit.colorHex,
            createdAt = habit.createdAt,
            archived = habit.archived
        )
        return dao.insert(entity)
    }

    suspend fun delete(habit: Habit) = dao.delete(
        HabitEntity(
            id = habit.id, name = habit.name, description = habit.description,
            category = habit.category, targetDate = habit.targetDate,
            targetTime = habit.targetTime, status = habit.status,
            colorHex = habit.colorHex, createdAt = habit.createdAt,
            archived = habit.archived
        )
    )

    suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived)

    // --- check-ins ---

    fun observeCheckins(habitId: Long): Flow<List<HabitCheckIn>> =
        dao.observeCheckins(habitId).map { rows -> rows.map { it.toDomain() } }

    suspend fun getCheckin(habitId: Long, day: LocalDate): HabitCheckIn? {
        val epochDay = day.toEpochDay()
        return dao.getCheckin(habitId, epochDay)?.toDomain()
    }

    suspend fun toggleCheckin(habitId: Long, day: LocalDate) {
        val epochDay = day.toEpochDay()
        val existing = dao.getCheckin(habitId, epochDay)
        if (existing != null) {
            dao.deleteCheckin(habitId, epochDay)
        } else {
            dao.insertCheckin(HabitCheckInEntity(habitId = habitId, epochDay = epochDay, completed = true))
        }
    }

    /**
     * Returns a compact summary string of the last [days] days for all habits.
     * Used as LLM context so the AI can comment on the user's progress.
     */
    suspend fun summaryForAi(days: Int = 7): String {
        val habits = dao.getAll().filter { !it.archived }
        if (habits.isEmpty()) return ""
        val today = LocalDate.now(ZoneId.systemDefault())
        val fromEpochDay = today.minusDays(days.toLong() - 1).toEpochDay()
        val sb = StringBuilder()
        sb.appendLine("Habit tracker — last $days days (today = $today):")
        habits.forEach { h ->
            val checkins = dao.getCompletedSince(h.id, fromEpochDay)
            val completedDays = checkins.map { it.epochDay }.toSet()
            val done = completedDays.size
            val streak = buildString {
                for (i in 0 until days) {
                    val day = today.minusDays((days - 1 - i).toLong())
                    append(if (day.toEpochDay() in completedDays) "✓" else "·")
                }
            }
            val meta = buildList {
                if (h.category.isNotBlank()) add("category: ${h.category}")
                if (h.status.isNotBlank()) add("status: ${h.status}")
                if (h.targetDate.isNotBlank()) add("target date: ${h.targetDate}")
                if (h.targetTime.isNotBlank()) add("target time: ${h.targetTime}")
            }.joinToString(", ")
            val metaStr = if (meta.isBlank()) "" else "  ($meta)"
            sb.appendLine("  • ${h.name}$metaStr: $done/$days days  [$streak]")
        }
        return sb.toString().trim()
    }

    private fun HabitEntity.toDomain() =
        Habit(id, name, description, category, targetDate, targetTime, status, colorHex, createdAt, archived)

    private fun HabitCheckInEntity.toDomain() =
        HabitCheckIn(id, habitId, epochDay, completed, note, createdAt)
}
