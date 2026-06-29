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

data class Chat(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val context: String? = null,
    val contextLabel: String? = null
)

data class Message(
    val id: Long,
    val chatId: Long,
    val role: Role,
    val content: String,
    val createdAt: Long,
    val tokens: Int = 0,
    val isError: Boolean = false,
    /** Absolute path to a generated PNG, or null for plain text messages. */
    val imagePath: String? = null,
    /**
     * Label of the attachment carried by this user message (e.g. "file:report.pdf"
     * or "image:photo.jpg"). Null for assistant messages and for user messages
     * sent without an attachment. Rendered as a chip below the message text.
     */
    val attachmentLabel: String? = null
)

enum class Role(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun from(v: String) = entries.firstOrNull { it.value == v } ?: USER
    }
}
