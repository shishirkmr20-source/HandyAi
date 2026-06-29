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

import com.handyai.data.db.dao.ExtractedAttachmentDao
import com.handyai.data.db.entity.ExtractedAttachmentEntity

/**
 * Session-scoped cache for extracted attachment text.
 *
 * Wraps ExtractedAttachmentDao. The cache is wiped on app launch
 * (HandyAiApp.onCreate → clearAll) so extracted document text never
 * persists across sessions — the user wanted "once the app is closed
 * the stored data is deleted", and Android doesn't give us a reliable
 * app-close signal, so clear-on-launch is the equivalent.
 *
 * Cache hit policy: a hit must match BOTH the URI and the file's
 * (sizeBytes, lastModified) fingerprint. If either differs, the user
 * replaced the file at that URI — we treat it as a miss and re-extract.
 */
class AttachmentCache(private val dao: ExtractedAttachmentDao) {

    data class CacheHit(
        val text: String,
        val label: String,
        val truncated: Boolean,
        val method: String
    )

    suspend fun get(uri: String, sizeBytes: Long, lastModified: Long): CacheHit? {
        val row = dao.get(uri) ?: return null
        if (row.sizeBytes != sizeBytes || row.lastModified != lastModified) {
            // File changed since we cached it — invalidate.
            dao.delete(uri)
            return null
        }
        return CacheHit(
            text = row.extractedText,
            label = row.label,
            truncated = row.truncated,
            method = row.method
        )
    }

    suspend fun put(
        uri: String,
        displayName: String,
        mime: String,
        sizeBytes: Long,
        lastModified: Long,
        text: String,
        label: String,
        truncated: Boolean,
        method: String
    ) {
        dao.upsert(
            ExtractedAttachmentEntity(
                uri = uri,
                displayName = displayName,
                mime = mime,
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                extractedText = text,
                label = label,
                truncated = truncated,
                method = method
            )
        )
    }

    /** Wipe the entire cache. Called once from HandyAiApp.onCreate. */
    suspend fun clearAll() = dao.clearAll()
}
