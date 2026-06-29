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
package com.handyai.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.handyai.data.db.entity.HabitCheckInEntity
import com.handyai.data.db.entity.HabitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {

    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY createdAt ASC")
    suspend fun getAll(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getById(id: Long): HabitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(habit: HabitEntity): Long

    @Update
    suspend fun update(habit: HabitEntity)

    @Delete
    suspend fun delete(habit: HabitEntity)

    @Query("UPDATE habits SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    // --- check-ins ---

    @Query("SELECT * FROM habit_checkins WHERE habitId = :habitId ORDER BY epochDay DESC")
    fun observeCheckins(habitId: Long): Flow<List<HabitCheckInEntity>>

    @Query("SELECT * FROM habit_checkins WHERE habitId = :habitId AND epochDay = :epochDay LIMIT 1")
    suspend fun getCheckin(habitId: Long, epochDay: Long): HabitCheckInEntity?

    @Query("SELECT * FROM habit_checkins WHERE epochDay = :epochDay")
    suspend fun getCheckinsForDay(epochDay: Long): List<HabitCheckInEntity>

    @Query("""
        SELECT * FROM habit_checkins
        WHERE habitId = :habitId AND epochDay >= :fromEpochDay
        ORDER BY epochDay ASC
    """)
    suspend fun getCheckinsSince(habitId: Long, fromEpochDay: Long): List<HabitCheckInEntity>

    @Query("""
        SELECT * FROM habit_checkins
        WHERE habitId = :habitId AND epochDay >= :fromEpochDay AND completed = 1
        ORDER BY epochDay ASC
    """)
    suspend fun getCompletedSince(habitId: Long, fromEpochDay: Long): List<HabitCheckInEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckin(checkin: HabitCheckInEntity): Long

    @Query("DELETE FROM habit_checkins WHERE habitId = :habitId AND epochDay = :epochDay")
    suspend fun deleteCheckin(habitId: Long, epochDay: Long)
}
