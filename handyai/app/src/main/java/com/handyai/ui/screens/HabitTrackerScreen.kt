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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handyai.HandyAiApp
import com.handyai.data.model.Habit
import com.handyai.data.model.HabitCategories
import com.handyai.data.model.HabitStatus
import com.handyai.ui.theme.handyAiPalette
import com.handyai.ui.viewmodel.HabitViewModel
import com.handyai.ui.viewmodel.HabitViewModelFactory
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitTrackerScreen(onBack: () -> Unit) {
    val app = HandyAiApp.instance
    val vm: HabitViewModel = viewModel(factory = HabitViewModelFactory(app.habitRepository))
    val habits by vm.habits.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    // Habit currently being edited (null when no edit dialog is open).
    // Set by the Edit icon on each HabitRow; rendered as HabitFormDialog
    // with the row's existing values pre-populated.
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Habits") },
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
            // Prominent gradient "Add" FAB — matches the Journal screen
            // and the chat Send button for visual consistency.
            val palette = handyAiPalette()
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(palette.mint, palette.indigo)
                        )
                    )
                    .clickable { creating = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add, "New habit",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { padding ->
        if (creating) {
            HabitFormDialog(
                editing = null,
                onSave = { name, desc, category, date, time, status ->
                    vm.save(Habit(
                        id = 0,
                        name = name,
                        description = desc,
                        category = category,
                        targetDate = date,
                        targetTime = time,
                        status = status,
                        colorHex = "#1A8FE3",
                        createdAt = System.currentTimeMillis(),
                        archived = false
                    ))
                    creating = false
                    android.widget.Toast.makeText(context, "Habit added", android.widget.Toast.LENGTH_SHORT).show()
                },
                onCancel = { creating = false }
            )
        }

        // Edit dialog — pre-populated with the habit being edited.
        // The same HabitFormDialog is reused; passing a non-null `editing`
        // switches the title to "Edit habit" and the save button to "Save",
        // and the onSave callback preserves the original id + createdAt +
        // colorHex + archived flag so the REPLACE upsert updates the row
        // in place instead of creating a duplicate.
        editingHabit?.let { h ->
            HabitFormDialog(
                editing = h,
                onSave = { name, desc, category, date, time, status ->
                    vm.save(h.copy(
                        name = name,
                        description = desc,
                        category = category,
                        targetDate = date,
                        targetTime = time,
                        status = status
                    ))
                    editingHabit = null
                    android.widget.Toast.makeText(context, "Habit updated", android.widget.Toast.LENGTH_SHORT).show()
                },
                onCancel = { editingHabit = null }
            )
        }

        if (habits.isEmpty()) {
            EmptyHabitState()
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
                    Text(
                        "Today — $today",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }
                items(habits, key = { it.id }) { habit ->
                    HabitRow(
                        habit = habit,
                        onToggle = { vm.toggleToday(habit.id) },
                        onEdit = { editingHabit = habit },
                        onDelete = { vm.delete(habit) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyHabitState() {
    val palette = com.handyai.ui.theme.handyAiPalette()
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(palette.mint.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.FitnessCenter, null, modifier = Modifier.size(40.dp),
                    tint = palette.mint)
            }
            Spacer(Modifier.height(16.dp))
            Text("No habits yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Track daily habits like reading, exercise, or meditation. Tap + to add one. HandyAi can see your streaks to give encouragement.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun HabitRow(
    habit: Habit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var checked by remember { mutableStateOf(false) }
    val app = HandyAiApp.instance
    LaunchedEffect(habit.id) {
        checked = app.habitRepository.getCheckin(habit.id, LocalDate.now()) != null
    }

    val accentColor = try {
        Color(android.graphics.Color.parseColor(habit.colorHex))
    } catch (_: Throwable) {
        MaterialTheme.colorScheme.primary
    }

    // Color the row border by status — Active (green), Paused (amber), Completed (blue), Archived (gray)
    val statusColor = when (habit.status) {
        "Active" -> Color(0xFF2E7D32)
        "Paused" -> Color(0xFFFF8F00)
        "Completed" -> Color(0xFF1565C0)
        "Archived" -> Color(0xFF757575)
        else -> MaterialTheme.colorScheme.outline
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Check circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (checked) accentColor else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    checked = !checked
                    onToggle()
                }, modifier = Modifier.fillMaxSize()) {
                    if (checked) {
                        Icon(Icons.Default.Check, "Done", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(habit.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (habit.description.isNotBlank()) {
                    Text(habit.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Metadata chips: category, status, date, time
                val chips = buildList {
                    if (habit.category.isNotBlank()) add(habit.category)
                    if (habit.status.isNotBlank() && habit.status != "Active") add(habit.status)
                    if (habit.targetDate.isNotBlank()) add(habit.targetDate)
                    if (habit.targetTime.isNotBlank()) add(habit.targetTime)
                }
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.take(4).forEach { label ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (label == habit.targetTime) {
                                        Icon(Icons.Default.Schedule, null,
                                            modifier = Modifier.size(10.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(2.dp))
                                    }
                                    if (label == habit.targetDate) {
                                        Icon(Icons.Default.DateRange, null,
                                            modifier = Modifier.size(10.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(2.dp))
                                    }
                                    Text(label, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            // Status color dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(8.dp))
            // Edit (pencil) icon — opens HabitFormDialog pre-populated
            // with this row's values. Sits next to the Delete icon so the
            // two row-level actions are visually grouped.
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Edit habit", modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitFormDialog(
    editing: Habit?,
    onSave: (name: String, description: String, category: String, targetDate: String, targetTime: String, status: String) -> Unit,
    onCancel: () -> Unit
) {
    // When `editing` is non-null, pre-populate all fields from the
    // existing habit so the user can edit instead of re-entering.
    // key(editing?.id) ensures a fresh state when switching between
    // different rows' edit dialogs without stale field values leaking.
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var desc by remember(editing?.id) { mutableStateOf(editing?.description ?: "") }
    var category by remember(editing?.id) { mutableStateOf(editing?.category?.ifBlank { HabitCategories.DEFAULT } ?: HabitCategories.DEFAULT) }
    var date by remember(editing?.id) { mutableStateOf(editing?.targetDate ?: "") }
    var time by remember(editing?.id) { mutableStateOf(editing?.targetTime ?: "") }
    var status by remember(editing?.id) { mutableStateOf(editing?.status?.ifBlank { HabitStatus.DEFAULT } ?: HabitStatus.DEFAULT) }

    // Picker state
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (editing == null) "New habit" else "Edit habit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Habit name (e.g. Drink water)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Category dropdown
                Box {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showCategoryMenu = !showCategoryMenu }) {
                                Icon(Icons.Default.ArrowDropDown, "Select category")
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = { showCategoryMenu = false }
                    ) {
                        HabitCategories.STANDARD_VALUES.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value) },
                                onClick = {
                                    category = value
                                    showCategoryMenu = false
                                }
                            )
                        }
                    }
                }

                // Status dropdown
                Box {
                    OutlinedTextField(
                        value = status,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Status") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showStatusMenu = !showStatusMenu }) {
                                Icon(Icons.Default.ArrowDropDown, "Select status")
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = showStatusMenu,
                        onDismissRequest = { showStatusMenu = false }
                    ) {
                        HabitStatus.STANDARD_VALUES.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value) },
                                onClick = {
                                    status = value
                                    showStatusMenu = false
                                }
                            )
                        }
                    }
                }

                // Date picker (opens DatePickerDialog when tapped)
                val dateInteractionSource = remember { MutableInteractionSource() }
                LaunchedEffect(dateInteractionSource) {
                    dateInteractionSource.interactions.collect { interaction ->
                        if (interaction is PressInteraction.Release) {
                            showDatePicker = true
                        }
                    }
                }
                OutlinedTextField(
                    value = date,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Target date (optional)") },
                    placeholder = { Text("Tap to pick a date") },
                    singleLine = true,
                    enabled = true,
                    interactionSource = dateInteractionSource,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, "Pick date")
                        }
                    }
                )

                // Time picker
                val timeInteractionSource = remember { MutableInteractionSource() }
                LaunchedEffect(timeInteractionSource) {
                    timeInteractionSource.interactions.collect { interaction ->
                        if (interaction is PressInteraction.Release) {
                            showTimePicker = true
                        }
                    }
                }
                OutlinedTextField(
                    value = time,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Target time (optional)") },
                    placeholder = { Text("Tap to pick a time") },
                    singleLine = true,
                    enabled = true,
                    interactionSource = timeInteractionSource,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showTimePicker = true }) {
                            Icon(Icons.Default.Schedule, "Pick time")
                        }
                    }
                )

                // Quick-clear row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (date.isNotBlank()) {
                        TextButton(onClick = { date = "" }) { Text("Clear date") }
                    }
                    if (time.isNotBlank()) {
                        TextButton(onClick = { time = "" }) { Text("Clear time") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), desc.trim(), category, date, time, status) },
                enabled = name.isNotBlank()
            ) { Text(if (editing == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )

    // Material3 DatePicker dialog
    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = if (date.isNotBlank()) {
                try {
                    LocalDate.parse(date).toEpochDay() * 24L * 60L * 60L * 1000L
                } catch (_: Throwable) { null }
            } else null
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // millis is UTC midnight — convert to LocalDate
                        val ld = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        date = ld.format(DateTimeFormatter.ISO_DATE)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }

    // Material3 TimePicker dialog
    if (showTimePicker) {
        val initial = try {
            if (time.isNotBlank()) LocalTime.parse(time) else LocalTime.now()
        } catch (_: Throwable) { LocalTime.now() }
        val state = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    time = String.format("%02d:%02d", state.hour, state.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = state) }
        )
    }
}
