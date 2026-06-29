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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handyai.HandyAiApp
import com.handyai.llm.DownloadState
import com.handyai.llm.ModelCatalog
import com.handyai.llm.ModelSpec
import com.handyai.ui.viewmodel.CombinedEngineState
import com.handyai.ui.viewmodel.ModelSettingsViewModel
import com.handyai.ui.viewmodel.ModelSettingsViewModelFactory
import com.handyai.llm.ModelType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(onBack: () -> Unit) {
    val app = HandyAiApp.instance
    val vm: ModelSettingsViewModel = viewModel(
        factory = ModelSettingsViewModelFactory(
            app.settingsRepository,
            app.llmEngine,
            app // context provider
        )
    )
    val activeName by vm.activeModelName.collectAsStateWithLifecycle(null)
    val downloadStates by vm.downloader.states.collectAsStateWithLifecycle(emptyMap())
    val combinedState by vm.combinedState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status banner — adapts to whichever engine is active
            when (val s = combinedState) {
                is CombinedEngineState.LlmReady -> InfoBanner(
                    text = "Active: ${activeName ?: "model"}",
                    icon = Icons.Default.CheckCircle,
                    color = MaterialTheme.colorScheme.secondaryContainer
                )
                is CombinedEngineState.ImageGenReady -> InfoBanner(
                    text = "Image generator ready: ${activeName ?: "model"} — type /draw <prompt> in any chat",
                    icon = Icons.Default.Image,
                    color = MaterialTheme.colorScheme.secondaryContainer
                )
                is CombinedEngineState.LlmError -> ErrorBanner(message = s.message)
                is CombinedEngineState.ImageGenError -> ErrorBanner(message = s.message)
                is CombinedEngineState.LlmGenerating -> InfoBanner(
                    text = "Generating reply…",
                    icon = Icons.Default.MoreHoriz,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                )
                is CombinedEngineState.ImageGenGenerating -> InfoBanner(
                    text = "Generating image… this can take 30–60 seconds.",
                    icon = Icons.Default.Image,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                )
                is CombinedEngineState.LlmLoading -> InfoBanner(
                    text = "Loading model into memory… this can take 5-30 seconds.",
                    icon = Icons.Default.Memory,
                    color = MaterialTheme.colorScheme.primaryContainer
                )
                is CombinedEngineState.ImageGenLoading -> InfoBanner(
                    text = "Loading image model into memory… this can take 10-30 seconds.",
                    icon = Icons.Default.Image,
                    color = MaterialTheme.colorScheme.primaryContainer
                )
                else -> InfoBanner(
                    text = "No model loaded. Pick one below to get started.",
                    icon = Icons.Default.Info,
                    color = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Text(
                "Available models",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
            Text(
                "Models are downloaded once and stored on your device. The recommended option works on most phones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            ModelCatalog.ALL.forEach { spec ->
                val cardState: DownloadState = downloadStates[spec.id] ?: DownloadState.Idle
                ModelCard(
                    spec = spec,
                    isActive = activeName == spec.displayName,
                    downloadState = cardState,
                    isDownloaded = vm.isDownloaded(spec),
                    combinedState = combinedState,
                    onDownload = {
                        vm.resetState(spec)
                        scope.launch { vm.download(spec) }
                    },
                    onActivate = { scope.launch { vm.activate(spec) } },
                    onUnload = { scope.launch { vm.unload() } },
                    onDelete = { scope.launch { vm.delete(spec) } }
                )
            }

            Spacer(Modifier.height(16.dp))
            ImageGenTestCard()
            Spacer(Modifier.height(8.dp))
            Text(
                "Image generation is built in — type /draw <prompt> in any chat. " +
                    "It uses Pollinations.ai (cloud, free, no API key). " +
                    "Requires internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Need a different LLM? Any MediaPipe-format .task file will work — " +
                    "drop it in the app's files/models/directory manually and tap " +
                    "“Activate” once added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun InfoBanner(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(color = color, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Error banner with an expandable "Show details" section that reveals the
 * full error message (including the exception class name) so the user can
 * copy-paste it back to the developer for debugging.
 */
@Composable
private fun ErrorBanner(message: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Model error",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show details")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Long-press the text above to copy. Paste it into a chat with the developer to help diagnose the issue.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    isActive: Boolean,
    downloadState: DownloadState,
    isDownloaded: Boolean,
    combinedState: CombinedEngineState,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    val isImageGen = spec.modelType == ModelType.IMAGE_GEN
    val isLoading = combinedState is CombinedEngineState.LlmLoading ||
        combinedState is CombinedEngineState.ImageGenLoading
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isImageGen) Icons.Default.Image else Icons.Default.Memory,
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(spec.displayName, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                if (spec.recommended) {
                    AssistChip(onClick = {}, label = { Text("Recommended") },
                        leadingIcon = { Icon(Icons.Default.Star, null, modifier = Modifier.size(14.dp)) })
                }
                if (isImageGen) {
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = {}, label = { Text("Image") },
                        leadingIcon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(14.dp)) })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(spec.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                "Size: ${spec.sizeMb} MB · RAM: ${spec.ramMb} MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Action button — depends on state
            when {
                isActive -> {
                    OutlinedButton(onClick = onUnload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PowerSettingsNew, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Unload model")
                    }
                }
                isLoading -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Text("Loading model into memory…",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp))
                }
                downloadState is DownloadState.Downloading -> {
                    val pct = (downloadState.fraction * 100).toInt()
                    LinearProgressIndicator(
                        progress = { downloadState.fraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Text("Downloading… $pct%", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp))
                }
                downloadState is DownloadState.Error -> {
                    Text(
                        "Download failed: ${downloadState.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retry download (${spec.sizeMb} MB)")
                    }
                }
                isDownloaded -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onActivate, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (isImageGen) "Load image model" else "Load model")
                        }
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, null)
                        }
                    }
                }
                else -> {
                    OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Download (${spec.sizeMb} MB)")
                    }
                }
            }
        }
    }
}

/**
 * A self-contained test card for image generation. Lets the user verify
 * Pollinations.ai is reachable and image generation actually works, without
 * having to go to a chat and type /draw.
 *
 * Shows:
 *   - A "Generate test image" button
 *   - Progress text while generating
 *   - The generated image preview (or an error message)
 *   - A "Save to gallery" button on the preview
 */
@Composable
private fun ImageGenTestCard() {
    val app = HandyAiApp.instance
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Image generation test",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                "Generate a test image of a cute cat to verify Pollinations.ai is reachable " +
                    "from your device. Takes 5-15 seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                )
            }

            imagePath?.let { path ->
                val bitmap = remember(path) {
                    try {
                        android.graphics.BitmapFactory.decodeFile(path)
                    } catch (_: Throwable) { null }
                }
                bitmap?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Test image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }

            Button(
                onClick = {
                    if (isRunning) return@Button
                    isRunning = true
                    status = "Generating… (attempt 1/3)"
                    imagePath = null
                    scope.launch {
                        val result = app.imageGenEngine.generate("a cute fluffy kitten, studio photo, high detail")
                        isRunning = false
                        result.onSuccess { path ->
                            imagePath = path
                            status = "✓ Success! Image saved to app storage. Tap /draw in chat to create your own."
                        }.onFailure { err ->
                            status = "✗ Failed: ${err.message ?: "unknown error"}"
                        }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Image, null)
                Spacer(Modifier.width(6.dp))
                Text(if (isRunning) "Generating…" else "Generate test image")
            }
        }
    }
}
