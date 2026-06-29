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
package com.handyai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.handyai.data.db.dao.ChatDao
import com.handyai.data.db.dao.ExtractedAttachmentDao
import com.handyai.data.db.dao.HabitDao
import com.handyai.data.db.dao.JournalDao
import com.handyai.data.db.dao.MessageDao
import com.handyai.data.db.entity.ChatEntity
import com.handyai.data.db.entity.ExtractedAttachmentEntity
import com.handyai.data.db.entity.HabitCheckInEntity
import com.handyai.data.db.entity.HabitEntity
import com.handyai.data.db.entity.JournalEntryEntity
import com.handyai.data.db.entity.MessageEntity

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        JournalEntryEntity::class,
        HabitEntity::class,
        HabitCheckInEntity::class,
        ExtractedAttachmentEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class HandyAiDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun journalDao(): JournalDao
    abstract fun habitDao(): HabitDao
    abstract fun extractedAttachmentDao(): ExtractedAttachmentDao

    companion object {
        @Volatile private var INSTANCE: HandyAiDatabase? = null

        fun get(context: Context): HandyAiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HandyAiDatabase::class.java,
                    "handyai.db"
                )
                    // Migration v3 → v4: add status column to habits table.
                    // Existing rows get status="Active" (the DEFAULT value).
                    .addMigrations(
                        object : androidx.room.migration.Migration(3, 4) {
                            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL(
                                    "ALTER TABLE habits ADD COLUMN status TEXT NOT NULL DEFAULT 'Active'"
                                )
                            }
                        }
                    )
                    // Migration v4 → v5: add the extracted_attachments table
                    // for session-scoped caching of parsed document text.
                    // No data migration needed — the table is new and starts
                    // empty. It's also wiped on every app launch, so even if
                    // the user has stale rows from a beta build, they get
                    // cleared the first time HandyAiApp.onCreate runs.
                    .addMigrations(
                        object : androidx.room.migration.Migration(4, 5) {
                            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS extracted_attachments (
                                        uri TEXT NOT NULL PRIMARY KEY,
                                        displayName TEXT NOT NULL,
                                        mime TEXT NOT NULL,
                                        sizeBytes INTEGER NOT NULL,
                                        lastModified INTEGER NOT NULL,
                                        extractedText TEXT NOT NULL,
                                        label TEXT NOT NULL,
                                        truncated INTEGER NOT NULL,
                                        method TEXT NOT NULL,
                                        createdAt INTEGER NOT NULL
                                    )
                                    """.trimIndent()
                                )
                            }
                        }
                    )
                    // Migration v5 → v6: add imagePath column to messages
                    // table for image-generation results (Stable Diffusion
                    // 1.5 via MediaPipe Image Generator). Nullable — existing
                    // rows get NULL and render as normal text bubbles. New
                    // image-gen messages store the absolute path to a PNG in
                    // app-private storage.
                    .addMigrations(
                        object : androidx.room.migration.Migration(5, 6) {
                            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL(
                                    "ALTER TABLE messages ADD COLUMN imagePath TEXT"
                                )
                            }
                        }
                    )
                    // Migration v6 → v7: add attachmentLabel column to
                    // messages table. Nullable — existing rows get NULL and
                    // render as normal text bubbles. New user messages that
                    // "carry" an attachment store the label here so the chip
                    // can be rendered under the message bubble. The chat
                    // row's contextLabel is cleared on send (see
                    // ChatRepository.clearContextLabel) so the input-bar chip
                    // disappears — the attachment "moves" to the message.
                    .addMigrations(
                        object : androidx.room.migration.Migration(6, 7) {
                            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL(
                                    "ALTER TABLE messages ADD COLUMN attachmentLabel TEXT"
                                )
                            }
                        }
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
