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

import com.handyai.data.db.dao.ChatDao
import com.handyai.data.db.dao.MessageDao
import com.handyai.data.db.entity.ChatEntity
import com.handyai.data.db.entity.MessageEntity
import com.handyai.data.model.Chat
import com.handyai.data.model.Message
import com.handyai.data.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
) {
    fun observeChats(): Flow<List<Chat>> =
        chatDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeChat(id: Long): Flow<Chat?> =
        chatDao.observeById(id).map { it?.toDomain() }

    /** Synchronous read — used when building the LLM system prompt to
     *  avoid stale StateFlow values immediately after attachFile(). */
    suspend fun getChat(id: Long): Chat? = chatDao.getById(id)?.toDomain()

    fun observeMessages(chatId: Long): Flow<List<Message>> =
        messageDao.observeByChat(chatId).map { rows -> rows.map { it.toDomain() } }

    suspend fun getMessages(chatId: Long): List<Message> =
        messageDao.getByChat(chatId).map { it.toDomain() }

    suspend fun createChat(title: String = "New chat"): Long {
        val now = System.currentTimeMillis()
        return chatDao.insert(ChatEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun rename(id: Long, title: String) = chatDao.rename(id, title)

    suspend fun delete(chat: Chat) = chatDao.delete(
        ChatEntity(
            id = chat.id, title = chat.title,
            createdAt = chat.createdAt, updatedAt = chat.updatedAt
        )
    )

    suspend fun setContext(id: Long, context: String?, label: String?) =
        chatDao.setContext(id, context, label)

    /**
     * Clear only the contextLabel on a chat row, leaving the [context]
     * (extracted file text) intact. Used after a user sends a message
     * that "carried" an attachment — the chip moves to the message bubble
     * (via MessageEntity.attachmentLabel) and the chat row's label is
     * cleared so the input bar no longer shows the chip.
     *
     * The file text in [context] is preserved so the LLM can still read
     * it for follow-up questions in the same chat.
     */
    suspend fun clearContextLabel(id: Long) =
        chatDao.clearContextLabel(id)

    /**
     * Clear BOTH the label AND the file text from the chat row.
     *
     * Called after the user sends a message that consumed an attachment.
     * The attachment label has already been carried to the message row
     * (so the chip still shows on the sent bubble), and the file content
     * was inlined into that one LLM call. We do NOT want the file content
     * to persist on the chat row — otherwise every subsequent message in
     * the same chat would re-inline the file content, causing the LLM to
     * keep referring to a doc the user thinks is long gone.
     *
     * If the user wants to ask another question about the same file,
     * they re-attach it. This matches the UX of ChatGPT and most other
     * modern chat apps.
     */
    suspend fun clearContext(id: Long) =
        chatDao.clearContext(id)

    suspend fun touch(id: Long) = chatDao.touch(id)

    suspend fun appendMessage(
        chatId: Long,
        role: Role,
        content: String,
        tokens: Int = 0,
        isError: Boolean = false,
        imagePath: String? = null,
        attachmentLabel: String? = null
    ): Long {
        val id = messageDao.insert(
            MessageEntity(
                chatId = chatId,
                role = role.value,
                content = content,
                tokens = tokens,
                isError = isError,
                imagePath = imagePath,
                attachmentLabel = attachmentLabel
            )
        )
        chatDao.touch(chatId)
        return id
    }

    suspend fun updateMessageContent(id: Long, content: String) =
        messageDao.updateContent(id, content)

    suspend fun deleteMessage(message: Message) = messageDao.delete(
        MessageEntity(
            id = message.id, chatId = message.chatId,
            role = message.role.value, content = message.content,
            createdAt = message.createdAt, tokens = message.tokens,
            isError = message.isError,
            imagePath = message.imagePath,
            attachmentLabel = message.attachmentLabel
        )
    )

    private fun ChatEntity.toDomain() = Chat(id, title, createdAt, updatedAt, context, contextLabel)
    private fun MessageEntity.toDomain() =
        Message(id, chatId, Role.from(role), content, createdAt, tokens, isError, imagePath, attachmentLabel)
}
