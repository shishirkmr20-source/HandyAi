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
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,  // "user" | "assistant" | "system"
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val tokens: Int = 0,
    val isError: Boolean = false,
    /**
     * Absolute path to a generated image file (PNG) in app-private storage.
     *
     * Non-null only for assistant messages produced by the image generator
     * (Stable Diffusion 1.5 via MediaPipe Image Generator). For all other
     * messages this is null and the bubble renders [content] as text.
     *
     * When non-null, [content] holds the prompt that was used to generate
     * the image (shown as a caption under the image in the bubble).
     */
    val imagePath: String? = null,
    /**
     * Label of the attachment that was "carried" by this user message when
     * it was sent. Format matches ChatEntity.contextLabel:
     *   "file:report.pdf"  /  "image:photo.jpg"
     *
     * When the user picks a file, it's stored on the chat row as
     * contextLabel (so the input bar shows a chip). On send, the label is
     * COPIED to the user message being persisted, then CLEARED from the
     * chat row — so the chip "moves" from the input bar to underneath the
     * sent message bubble. The file's extracted text stays on the chat row
     * (in `context`) so the LLM can still read it for follow-up questions.
     *
     * Null for assistant messages and for user messages sent without an
     * attachment. Rendered as a small chip below the message text in
     * MessageBubble.
     */
    val attachmentLabel: String? = null
)
