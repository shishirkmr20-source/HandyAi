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
package com.handyai.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.handyai.R
import com.handyai.data.model.Message
import com.handyai.data.model.Role
import com.handyai.files.SaveImageHelper
import com.handyai.ui.theme.handyAiPalette
import com.handyai.ui.theme.userBubbleBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    onSpeak: () -> Unit,
    isSpeaking: Boolean,
    showSpeakButton: Boolean
) {
    val isUser = message.role == Role.USER
    val isError = message.isError
    val hasImage = message.imagePath != null
    val palette = handyAiPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Permission launcher for WRITE_EXTERNAL_STORAGE (Android 8/9 only).
    // Android 10+ uses scoped storage and doesn't need this permission.
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && message.imagePath != null) {
            scope.launch {
                saveImageToGallery(context, message.imagePath!!)
            }
        } else {
            Toast.makeText(
                context,
                "Storage permission denied. Can't save image.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Tail-aware asymmetric corner radius — small corner points to sender.
    // User: tail on bottom-right.  Assistant: tail on bottom-left.
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Shadow + background pair. We use a soft elevation shadow for
        // depth (Material3 default is too flat) and a colored border on
        // the assistant bubble for the "card on a patterned background"
        // feel that WhatsApp / Telegram use.
        val bubbleModifier = Modifier
            .widthIn(max = 340.dp)
            .shadow(
                elevation = if (isUser) 2.dp else 1.dp,
                shape = bubbleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            )

        Surface(
            color = when {
                isError -> MaterialTheme.colorScheme.errorContainer
                isUser -> Color.Transparent // gradient is painted manually below
                else -> palette.aiBubble
            },
            shape = bubbleShape,
            border = if (!isUser && !isError) {
                androidx.compose.foundation.BorderStroke(1.dp, palette.aiBubbleBorder)
            } else null,
            modifier = bubbleModifier
        ) {
            Box(
                modifier = if (isUser && !isError) {
                    Modifier
                        .clip(bubbleShape)
                        .background(userBubbleBrush(palette))
                } else {
                    Modifier
                }
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // ─── Image rendering ────────────────────────────────────
                    // If imagePath is non-null, render the PNG at the top of
                    // the bubble, then the prompt as a caption below.
                    //
                    // The bitmap is loaded synchronously from disk on the
                    // main thread. This is acceptable because:
                    //   - Image-gen produces a single PNG per message (~1MB)
                    //   - The PNG is already in app-private storage (no
                    //     ContentResolver round-trip)
                    //   - The chat history is usually short enough that no
                    //     more than a handful of bitmaps are decoded at once
                    //
                    // If the file is missing or corrupt, we show a
                    // "broken image" placeholder instead of crashing.
                    if (hasImage) {
                        val imagePath = message.imagePath!!
                        val bitmap = remember(imagePath) {
                            try {
                                val file = File(imagePath)
                                if (file.exists()) {
                                    BitmapFactory.decodeFile(imagePath)
                                } else null
                            } catch (_: Throwable) { null }
                        }
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Generated image: ${message.content}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 312.dp)
                                    .heightIn(max = 360.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                            // Spacer between image and caption
                            Spacer(Modifier.height(8.dp))
                            // ─── Save to gallery button ────────────────────────────
                            // Only on assistant messages (the user's own /draw
                            // command doesn't have an image attached). Saves
                            // the PNG to Pictures/HandyAi via MediaStore.
                            if (!isUser) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            if (SaveImageHelper.hasPermission(context)) {
                                                scope.launch {
                                                    saveImageToGallery(context, imagePath)
                                                }
                                            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                                // Request permission for Android 8/9
                                                storagePermissionLauncher.launch(
                                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                                )
                                            } else {
                                                // Should never happen — scoped storage
                                                scope.launch {
                                                    saveImageToGallery(context, imagePath)
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Download,
                                            contentDescription = "Save to gallery",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Save",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        } else {
                            // File missing or unreadable — show a placeholder
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.BrokenImage,
                                    contentDescription = "Image not found",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Image file not found",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    // ─── Text content ───────────────────────────────────────
                    // For image messages, [content] is the prompt — shown as a
                    // caption. For normal messages, it's the full text body.
                    //
                    // We render the raw (sanitized) text as-is for BOTH user
                    // and assistant messages. No **bold** markdown parsing —
                    // see MarkdownParser.kt for why parsing was removed
                    // (it caused a hard crash after the 4th chat in a session
                    // due to a layout/parsed-text length mismatch race in
                    // StreamingBubble's caret positioning).
                    //
                    // Long-press anywhere on the text copies the full message
                    // content to the system clipboard and shows a "Copied"
                    // toast. This matches the convention used by WhatsApp /
                    // Telegram / ChatGPT mobile.
                    if (message.content.isNotBlank()) {
                        val text = if (hasImage) "🎨 ${message.content}" else message.content
                        val style = if (hasImage) MaterialTheme.typography.labelMedium
                                    else MaterialTheme.typography.bodyMedium
                        val baseColor = when {
                            isError -> MaterialTheme.colorScheme.onErrorContainer
                            isUser -> Color(0xFF0E1340)  // deep indigo text on light periwinkle
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        val copyModifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                // Copy the raw message.content (not the image
                                // caption variant) so pasting into another app
                                // yields the original text without the 🎨 emoji.
                                clipboard.setText(AnnotatedString(message.content))
                                Toast.makeText(
                                    context,
                                    "Copied",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        Text(
                            text = MarkdownParser.sanitize(text),
                            style = style,
                            color = baseColor,
                            modifier = copyModifier
                        )
                    }
                    // ─── Attachment chip below user message ────────────────
                    // When a user message "carried" an attachment (file or
                    // image), the attachment label is stored on the message
                    // row. Render a small chip below the text so the user
                    // can see which file was attached to this message —
                    // matches the input-bar chip's look for consistency.
                    val attLabel = message.attachmentLabel
                    if (isUser && attLabel != null) {
                        Spacer(Modifier.height(6.dp))
                        val isImg = attLabel.startsWith("image:")
                        val chipColor = if (isImg) palette.lavender else palette.mint
                        AssistChip(
                            onClick = {},  // display-only
                            leadingIcon = {
                                Icon(
                                    if (isImg) Icons.Filled.Image
                                    else Icons.Filled.AttachFile,
                                    null,
                                    modifier = Modifier.size(14.dp),
                                    tint = chipColor
                                )
                            },
                            label = {
                                Text(
                                    attLabel.removePrefix("file:").removePrefix("image:"),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = chipColor.copy(alpha = 0.18f),
                                labelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                    if (showSpeakButton) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onSpeak,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    if (isSpeaking) Icons.Filled.Stop
                                    else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (isSpeaking) "Stop speaking" else "Speak",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (isSpeaking) "Stop" else "Speak",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            // ── Copy button ─────────────────────────────────────
                            // Sits beside Speak so the two text actions on an
                            // assistant message are grouped together. Copies
                            // the raw message.content (not the markdown-parsed
                            // AnnotatedString) so pasting into another app
                            // yields the original plain text including the
                            // **bold** markers — same behaviour as the existing
                            // long-press-to-copy on the bubble body, just
                            // exposed as a visible affordance for users who
                            // don't discover long-press.
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(message.content))
                                    Toast.makeText(
                                        context,
                                        "Copied",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Copy",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Save the image at [imagePath] to the public gallery (Pictures/HandyAi).
 * Runs on a background thread because MediaStore I/O can be slow on
 * Android 10+ (it scans + indexes the file).
 *
 * Shows a toast on success or failure — this is the simplest UX for
 * a one-tap action. We deliberately don't use a snackbar because the
 * LazyColumn is rebuilt frequently and snackbars would get dismissed.
 */
private suspend fun saveImageToGallery(context: android.content.Context, imagePath: String) {
    val result = withContext(Dispatchers.IO) {
        SaveImageHelper.saveToGallery(context, imagePath)
    }
    result.onSuccess {
        Toast.makeText(
            context,
            "Saved to Pictures/HandyAi",
            Toast.LENGTH_SHORT
        ).show()
    }.onFailure { err ->
        Toast.makeText(
            context,
            "Could not save: ${err.message ?: "unknown error"}",
            Toast.LENGTH_LONG
        ).show()
    }
}
