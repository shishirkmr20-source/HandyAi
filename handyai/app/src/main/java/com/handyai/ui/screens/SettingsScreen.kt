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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handyai.HandyAiApp
import com.handyai.ui.theme.HandyAiThemeId
import com.handyai.ui.theme.HandyAiThemes
import com.handyai.ui.viewmodel.SettingsViewModel
import com.handyai.ui.viewmodel.SettingsViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = HandyAiApp.instance
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(
        app.settingsRepository, app.llmEngine
    ))
    val internetOn by vm.internetEnabled.collectAsStateWithLifecycle(false)
    val ttsOn by vm.ttsEnabled.collectAsStateWithLifecycle(false)
    val currentTheme by vm.themeId.collectAsStateWithLifecycle()
    val activeModelName by vm.activeModelName.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // About card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("HandyAi", style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Version 1.4.0", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "All inference and document parsing happen on your device. " +
                            "Your conversations, files, and extracted text never leave your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Section: Chat
            SectionHeader("Chat")
            SwitchRow(
                icon = Icons.Default.Wifi,
                title = "Enable internet",
                subtitle = "When on, HandyAi searches the web for fresh info to ground its answers. Queries go directly to DuckDuckGo — no tracking.",
                checked = internetOn,
                onToggle = { scope.launch { vm.setInternet(it) } }
            )
            SwitchRow(
                icon = Icons.Default.RecordVoiceOver,
                title = "Auto-read replies aloud",
                subtitle = "Speaks each assistant reply using Android's text-to-speech. You can still tap Speak on individual messages even when this is off.",
                checked = ttsOn,
                onToggle = { scope.launch { vm.setTts(it) } }
            )

            Spacer(Modifier.height(8.dp))

            // Section: Appearance
            SectionHeader("Appearance")
            ThemePickerCard(
                currentThemeId = currentTheme,
                onPick = { scope.launch { vm.setTheme(it) } }
            )

            Spacer(Modifier.height(8.dp))

            // Section: Document parsing
            SectionHeader("Documents")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("On-device document parser", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "HandyAi extracts text from PDFs, DOCX, PPTX, XLSX, HTML, RTF, ODT, " +
                            "and images (OCR) directly on your phone using PDFBox-Android, " +
                            "Apache POI, and ML Kit. No server, no upload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Extracted text is cached in a session-scoped database table so re-opening " +
                            "a chat with the same attachment is instant. The cache is wiped every " +
                            "time the app launches — your document text never persists across sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Section: Model
            SectionHeader("AI Model")
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active model", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            activeModelName ?: "No model loaded",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Section: Diagnostics
            SectionHeader("Diagnostics")
            CrashLogCard()

            Spacer(Modifier.height(24.dp))
            Text(
                "HandyAi is open-source software. No accounts, No analytics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, top = 8.dp)
    )
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

/**
 * Crash log viewer card.
 *
 * Shows the most recent JVM-level crash captured by [com.handyai.CrashLogger].
 * If no crash has been recorded, shows a reassuring "No crashes recorded"
 * message. If a crash log exists, shows:
 *   - A header with a warning icon + "Crash log found"
 *   - The first ~30 lines of the crash report in a monospace scrollable box
 *   - Two buttons: "Share" (opens Android share sheet with full log text)
 *   - and "Clear" (deletes the log file)
 *
 * WHY THIS EXISTS
 * ───────────────
 * The user reported the app "crashes after a few chats" on their phone but
 * works fine on their tablet. Without a crash log, we can only guess at the
 * cause (OOM? native fault? race condition?). This card lets the user
 * capture + share the JVM-level stack trace so we can diagnose remotely.
 *
 * If the app crashes NATIVELY (SIGSEGV from MediaPipe), this handler will
 * NOT fire — the lack of a log entry after a crash is itself a signal that
 * the crash was native (likely OOM-kill or a MediaPipe fault).
 */
@Composable
private fun CrashLogCard() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf<String?>(null) }
    var showFullLog by remember { mutableStateOf(false) }

    // Refresh the log every time this composable enters composition.
    // We can't use a Flow here because CrashLogger writes to a plain file.
    LaunchedEffect(Unit) {
        logText = com.handyai.CrashLogger.readCurrentLog(context)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (logText != null) Icons.Default.Warning else Icons.Default.CheckCircle,
                    null,
                    tint = if (logText != null) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (logText != null) "Crash log found" else "No crashes recorded",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(8.dp))
            if (logText == null) {
                Text(
                    "If HandyAi ever crashes unexpectedly, a detailed report " +
                        "(with stack trace, device info, and memory status) will " +
                        "appear here so you can share it back for diagnosis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "The most recent JVM-level crash was captured. Tap Share to " +
                        "send the full report to the developer. Note: if the app " +
                        "crashed but NO log appears here, the crash was likely " +
                        "native (memory pressure / MediaPipe fault) — restart the " +
                        "app and try a smaller model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                // Preview of the crash log (first ~30 lines or full if expanded)
                val preview = if (showFullLog) logText!!
                              else logText!!.lines().take(30).joinToString("\n")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(12.dp)
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                if (logText!!.lines().size > 30) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showFullLog = !showFullLog }) {
                        Text(if (showFullLog) "Show less" else "Show full log " +
                            "(${logText!!.lines().size} lines)")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT,
                                    "HandyAi crash report")
                                putExtra(android.content.Intent.EXTRA_TEXT,
                                    logText ?: "")
                            }
                            context.startActivity(android.content.Intent.createChooser(intent,
                                "Share crash log"))
                        }
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                    OutlinedButton(
                        onClick = {
                            com.handyai.CrashLogger.clearCurrentLog(context)
                            logText = null
                            showFullLog = false
                        }
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clear")
                    }
                }
            }
        }
    }
}

/**
 * Theme picker — renders one card per available theme, each showing:
 *   - Theme name (e.g. "Cream Pastel")
 *   - Light/Dark badge
 *   - A row of 5 color swatches previewing the theme's personality
 *   - A radio indicator on the right (selected = filled, else empty)
 *
 * Tapping a card calls [onPick] with the theme's [HandyAiThemeId].
 * The theme is applied app-wide immediately (MainActivity observes
 * the same StateFlow).
 *
 * The selected card gets a 2dp primary-colored border so the user
 * can see at a glance which theme is active even when scrolling
 * quickly.
 */
@Composable
private fun ThemePickerCard(
    currentThemeId: HandyAiThemeId,
    onPick: (HandyAiThemeId) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Theme", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick a color identity. Each theme is fully designed — light " +
                    "or dark is part of the theme, not a separate toggle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // One row per theme. We use Column + Card-per-theme rather
            // than a LazyColumn because there are only 5 themes —
            // LazyColumn's lazy inflation overhead isn't worth it for
            // such a small list, and a plain Column nests cleanly
            // inside the parent scroll.
            HandyAiThemes.all.forEach { spec ->
                val selected = spec.id == currentThemeId
                Surface(
                    onClick = { onPick(spec.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ── Swatches ──────────────────────────────────────
                        // Render the theme's swatch colors as a row of small
                        // circles. This gives the user an at-a-glance preview
                        // of the theme's personality without needing to apply
                        // it first. The first three swatches are the theme's
                        // primary/secondary/tertiary accents; the last two
                        // are the background and surface tones.
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            spec.swatches.forEachIndexed { idx, color ->
                                Box(
                                    modifier = Modifier
                                        .size(if (idx < 3) 20.dp else 16.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape
                                        )
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        // ── Name + Light/Dark badge ───────────────────────
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                spec.id.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            // Tiny pill badge indicating light vs dark.
                            // Helps the user scan the list for "I want a
                            // dark theme" without reading every name.
                            val badgeColor = if (spec.id.isDark)
                                Color(0xFF1F2230) else Color(0xFFFFFBF4)
                            val badgeTextColor = if (spec.id.isDark)
                                Color(0xFFE6E8F2) else Color(0xFF5A4A1A)
                            Surface(
                                color = badgeColor,
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Text(
                                    if (spec.id.isDark) "Dark" else "Light",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeTextColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        // ── Selected indicator ────────────────────────────
                        RadioButton(
                            selected = selected,
                            onClick = { onPick(spec.id) }
                        )
                    }
                }
            }
        }
    }
}

