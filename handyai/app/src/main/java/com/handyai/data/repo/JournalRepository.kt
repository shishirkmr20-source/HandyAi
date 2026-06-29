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

import com.handyai.data.db.dao.JournalDao
import com.handyai.data.db.entity.JournalEntryEntity
import com.handyai.data.model.JournalEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class JournalRepository(private val dao: JournalDao) {

    fun observeAll(): Flow<List<JournalEntry>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun getRecent(limit: Int = 5): List<JournalEntry> =
        dao.getRecent(limit).map { it.toDomain() }

    suspend fun getById(id: Long): JournalEntry? = dao.getById(id)?.toDomain()

    suspend fun save(entry: JournalEntry): Long {
        val now = System.currentTimeMillis()
        val entity = JournalEntryEntity(
            id = entry.id,
            title = entry.title,
            content = entry.content,
            mood = entry.mood,
            createdAt = if (entry.id == 0L) now else entry.createdAt,
            updatedAt = now
        )
        return dao.insert(entity)
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    private fun JournalEntryEntity.toDomain() =
        JournalEntry(id, title, content, mood, createdAt, updatedAt)
}
