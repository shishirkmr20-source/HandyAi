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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.handyai.data.db.entity.ExtractedAttachmentEntity

@Dao
interface ExtractedAttachmentDao {

    @Query("SELECT * FROM extracted_attachments WHERE uri = :uri LIMIT 1")
    suspend fun get(uri: String): ExtractedAttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExtractedAttachmentEntity)

    @Query("DELETE FROM extracted_attachments WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM extracted_attachments")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM extracted_attachments")
    suspend fun count(): Int
}
