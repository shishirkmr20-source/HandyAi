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
package com.handyai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handyai.HandyAiApp
import com.handyai.data.model.JournalEntry
import com.handyai.ui.theme.handyAiPalette
import com.handyai.ui.viewmodel.JournalViewModel
import com.handyai.ui.viewmodel.JournalViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(onBack: () -> Unit) {
    val app = HandyAiApp.instance
    val vm: JournalViewModel = viewModel(factory = JournalViewModelFactory(app.journalRepository))
    val entries by vm.entries.collectAsStateWithLifecycle(emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current

    var editing by remember { mutableStateOf<JournalEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
                // No top-bar + button — the bottom FAB is the only "Add"
                // entry point, per user request. Keeps the top bar clean
                // and avoids two competing CTAs for the same action.
            )
        },
        floatingActionButton = {
            // ── HIDE FAB WHEN EDITOR IS OPEN ─────────────────────────────
            // Previous bug: the FAB stayed visible even when the JournalEditor
            // was open. The user would fill in title/content/mood, then see
            // the floating "+" icon in the corner and tap it expecting
            // "save" — but the FAB's onClick just re-set `creating = true`
            // (a no-op) and the entry was NEVER saved. The actual Save
            // button is the full-width gradient button at the BOTTOM of the
            // JournalEditor (its own Scaffold's bottomBar).
            //
            // Fix: only show the FAB when we're displaying the list. When
            // the editor is open, the FAB is hidden so there's no
            // competing "plus" affordance to confuse the user.
            if (creating.not() && editing == null) {
                val palette = handyAiPalette()
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(palette.indigo, palette.lavender)
                            )
                        )
                        .clickable { creating = true; editing = null },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add, "New entry",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { padding ->
        if (creating || editing != null) {
            JournalEditor(
                entry = editing,
                onSave = { title, content, mood ->
                    val e = JournalEntry(
                        id = editing?.id ?: 0,
                        title = title,
                        content = content,
                        mood = mood,
                        createdAt = editing?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    vm.save(e)
                    creating = false
                    editing = null
                    android.widget.Toast.makeText(
                        context,
                        if (e.id == 0L) "Entry saved" else "Entry updated",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                onCancel = { creating = false; editing = null }
            )
        } else {
            if (entries.isEmpty()) {
                EmptyJournalState()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        JournalCard(
                            entry = entry,
                            onEdit = { editing = entry },
                            onDelete = { vm.delete(entry.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyJournalState() {
    val palette = handyAiPalette()
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.coral.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Book, null, modifier = Modifier.size(40.dp),
                    tint = palette.coral)
            }
            Spacer(Modifier.height(16.dp))
            Text("Your journal is empty", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Write about your day, thoughts, or feelings. HandyAi can read your recent entries to give more personal responses.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun JournalCard(
    entry: JournalEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        dateFmt.format(Date(entry.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entry.mood?.let { mood ->
                    AssistChip(onClick = {}, label = { Text(mood, style = MaterialTheme.typography.labelSmall) })
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                entry.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalEditor(
    entry: JournalEntry?,
    onSave: (title: String, content: String, mood: String?) -> Unit,
    onCancel: () -> Unit
) {
    // Intercept the system back button so it closes the editor instead of
    // popping the entire JournalScreen off the nav stack.
    BackHandler(enabled = true) { onCancel() }

    var title by remember { mutableStateOf(entry?.title ?: "") }
    var content by remember { mutableStateOf(entry?.content ?: "") }
    var mood by remember { mutableStateOf(entry?.mood ?: "") }

    val palette = handyAiPalette()
    val canSave = content.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entry == null) "New entry" else "Edit entry") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                }
            )
        },
        bottomBar = {
            // ── Prominent Save button at the bottom ────────────────────
            // The previous design only had a tiny Check icon in the top
            // app bar — users looked for a "plus" or "save" button at the
            // bottom (where the FAB is on the parent screen) and couldn't
            // find it. This full-width gradient button matches the chat
            // Send button's style so the "commit" action is consistent
            // across the app.
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            if (canSave) Brush.horizontalGradient(
                                listOf(palette.indigo, palette.lavender)
                            )
                            else Brush.horizontalGradient(
                                listOf(palette.indigo.copy(alpha = 0.35f),
                                       palette.lavender.copy(alpha = 0.35f))
                            )
                        )
                        .clickable(enabled = canSave) {
                            onSave(title.trim(), content.trim(), mood.ifBlank { null })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            "Save",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (entry == null) "Save entry" else "Update entry",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = mood,
                onValueChange = { mood = it },
                label = { Text("Mood (optional, e.g. happy / calm / anxious)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Entry *") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                shape = RoundedCornerShape(12.dp)
            )
            if (content.isBlank()) {
                Text(
                    "The entry body is required to save.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
