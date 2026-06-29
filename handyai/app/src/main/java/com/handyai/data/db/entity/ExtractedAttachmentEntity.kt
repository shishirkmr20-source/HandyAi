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
 * Session-scoped cache of extracted attachment text.
 *
 * When the user attaches a file (PDF/DOCX/image/...), FileTextExtractor
 * extracts its text ONCE and stores the result here. Subsequent reads of
 * the same URI (e.g. when the user re-opens the chat) hit the cache
 * instead of re-parsing the document.
 *
 * IMPORTANT — this table is wiped on every app start (see HandyAiApp.onCreate
 * → AttachmentCache.clearAll). The user wanted "once the app is closed
 * the stored data is deleted". Android doesn't reliably signal app-close,
 * so we clear-on-launch instead, which gives the same observable
 * behavior: the cache never persists across sessions.
 *
 * The cache is keyed by the file's content URI. We also store the
 * display name + size + last-modified as a cheap "did the file change?"
 * check — if the user replaces the file at the same URI with a different
 * one, the cache entry is invalidated.
 */
@Entity(tableName = "extracted_attachments")
data class ExtractedAttachmentEntity(
    /** The ContentResolver URI string (e.g. content://com.android.providers.../foo.pdf). */
    @PrimaryKey val uri: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val extractedText: String,
    val label: String,           // "file:foo.pdf" or "image:bar.jpg"
    val truncated: Boolean,
    val method: String,          // "pdfbox" / "poi-docx" / "mlkit-ocr" / etc.
    val createdAt: Long = System.currentTimeMillis()
)
