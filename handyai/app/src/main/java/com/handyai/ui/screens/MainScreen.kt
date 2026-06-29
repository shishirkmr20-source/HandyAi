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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handyai.HandyAiApp
import com.handyai.R
import com.handyai.data.model.Chat
import com.handyai.data.model.Role
import com.handyai.llm.LlmState
import com.handyai.ui.components.MessageBubble
import com.handyai.ui.theme.handyAiPalette
import com.handyai.ui.theme.sendButtonBrush
import com.handyai.ui.viewmodel.ChatListViewModel
import com.handyai.ui.viewmodel.ChatListViewModelFactory
import com.handyai.ui.viewmodel.ChatViewModel
import com.handyai.ui.viewmodel.ChatViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ChatGPT/Claude-style main screen: a single chat view with a slide-out
 * drawer for chat history. Replaces the previous two-screen chat-list + chat
 * flow so the user lands directly in a chat on launch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenHabits: () -> Unit
) {
    val app = HandyAiApp.instance
    val listVm: ChatListViewModel = viewModel(factory = ChatListViewModelFactory(
        app.chatRepository, app.llmEngine, app.settingsRepository
    ))
    val chats by listVm.chats.collectAsStateWithLifecycle(emptyList())

    // Currently active chat id — persisted across rotation via rememberSaveable.
    // -1L means "none yet"; we'll auto-create one on first launch.
    var activeChatId by rememberSaveable { mutableStateOf(-1L) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // ── Models drawer (right side) ────────────────────────────────────
    // Independent of the chat-history drawer (left side). When open, the
    // Models panel slides in from the right edge covering ~88% of the
    // screen width; a scrim dims the rest. Tapping the scrim or swiping
    // right closes it.
    //
    // Callers (ChatPane's top-bar icon + swipe-left gesture) are
    // responsible for hiding their own soft keyboard before invoking
    // openModelsDrawer — MainScreen itself has no keyboard controller
    // because the input field lives inside ChatPane.
    var modelsDrawerOpen by rememberSaveable { mutableStateOf(false) }
    val openModelsDrawer: () -> Unit = { modelsDrawerOpen = true }
    val closeModelsDrawer: () -> Unit = { modelsDrawerOpen = false }

    // First-run: if there are no chats yet, create one and select it.
    // If there are chats but activeChatId is unset, pick the most recent.
    LaunchedEffect(chats.isEmpty(), activeChatId) {
        if (activeChatId == -1L) {
            if (chats.isEmpty()) {
                listVm.createChat { id -> activeChatId = id }
            } else {
                activeChatId = chats.first().id
            }
        }
    }

    // If active chat was deleted, fall back to most recent or create new
    LaunchedEffect(chats) {
        if (activeChatId != -1L && chats.none { it.id == activeChatId }) {
            activeChatId = if (chats.isNotEmpty()) chats.first().id else -1L
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                chats = chats,
                activeChatId = activeChatId,
                onNewChat = {
                    scope.launch {
                        listVm.createChat { id ->
                            activeChatId = id
                            scope.launch { drawerState.close() }
                        }
                    }
                },
                onOpenChat = { id ->
                    activeChatId = id
                    scope.launch { drawerState.close() }
                },
                onRename = listVm::rename,
                onDelete = listVm::delete,
                onOpenJournal = {
                    scope.launch { drawerState.close() }
                    onOpenJournal()
                },
                onOpenHabits = {
                    scope.launch { drawerState.close() }
                    onOpenHabits()
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                }
            )
        }
    ) {
        if (activeChatId == -1L) {
            // Brief loading state while the first chat is being created
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // key() ensures a fresh ChatViewModel is created when activeChatId changes
            key(activeChatId) {
                ChatPane(
                    chatId = activeChatId,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenModels = openModelsDrawer
                )
            }
        }

        // ── Models drawer overlay (right side) ───────────────────────
        // Rendered on top of the chat content. Animated slide-in from
        // the right edge + scrim fade-in. We use animateFloatAsState so
        // the open/close transitions are smooth and reversible.
        ModelsDrawer(
            isOpen = modelsDrawerOpen,
            onClose = closeModelsDrawer
        )
    }
}

/**
 * Right-side slide-in drawer hosting the [ModelsPanel]. Independent of
 * the left [ModalNavigationDrawer] (chat history) so both drawers can
 * coexist — left = chats, right = models.
 *
 * - Width: 88% of screen (leaves a sliver of chat visible as a close affordance)
 * - Scrim: black 50% alpha, taps close the drawer
 * - Animation: 280ms ease-out slide + scrim fade
 * - Swipe-right to close: a horizontal drag detector on the panel itself
 *   lets the user fling it shut just like the chat drawer.
 */
@Composable
private fun ModelsDrawer(
    isOpen: Boolean,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val drawerWidthPx = screenWidthPx * 0.88f

    val targetOffset by animateFloatAsState(
        targetValue = if (isOpen) 0f else drawerWidthPx,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "modelsDrawerOffset"
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isOpen) 0.5f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "modelsDrawerScrim"
    )

    // Swipe-to-close state — accumulates horizontal drag on the panel.
    // When the finger lifts and the net drag is > 120dp to the right,
    // we close the drawer.
    val swipeCloseThresholdPx = with(density) { 120f.dp.toPx() }
    val swipeState = remember {
        object {
            var accumulator = 0f
            var handled = false
        }
    }

    // Don't render anything when fully closed and animation has settled,
    // so the drawer doesn't intercept touches meant for the chat content.
    val fullyClosed = !isOpen && targetOffset >= drawerWidthPx - 1f
    if (fullyClosed && scrimAlpha <= 0.01f) return

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim — only visible while opening or open
        if (scrimAlpha > 0.01f) {
            // Empty interactionSource + null indication → no ripple on tap,
            // which is what we want for a transparent scrim.
            val scrimInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = scrimInteraction,
                        indication = null
                    ) { onClose() }
            )
        }

        // Drawer panel — anchored to the right edge, translated by
        // targetOffset (0 = fully open, drawerWidthPx = fully closed).
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(with(density) { drawerWidthPx.toDp() })
                .offset { IntOffset(x = targetOffset.roundToInt(), y = 0) }
                .background(MaterialTheme.colorScheme.surface)
                .shadow(elevation = 8.dp)
                // Swipe-right-to-close — only active when the drawer is
                // open. We don't need to handle left swipes here (those
                // would just push the panel further off-screen which the
                // offset animation already handles).
                .pointerInput(isOpen) {
                    if (!isOpen) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            swipeState.accumulator = 0f
                            swipeState.handled = false
                        },
                        onDragEnd = {
                            if (!swipeState.handled && swipeState.accumulator >= swipeCloseThresholdPx) {
                                swipeState.handled = true
                                onClose()
                            }
                            swipeState.accumulator = 0f
                        },
                        onDragCancel = {
                            swipeState.accumulator = 0f
                            swipeState.handled = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeState.accumulator += dragAmount
                            if (!swipeState.handled && swipeState.accumulator >= swipeCloseThresholdPx) {
                                swipeState.handled = true
                                onClose()
                            }
                        }
                    )
                }
        ) {
            ModelsPanel(onClose = onClose)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Drawer                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun ChatHistoryDrawer(
    chats: List<Chat>,
    activeChatId: Long,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Chat) -> Unit,
    onOpenJournal: () -> Unit,
    onOpenHabits: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val palette = handyAiPalette()
    ModalDrawerSheet {
        // Brand header — gradient strip behind the logo, gives the
        // drawer a more polished first impression.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(palette.indigo.copy(alpha = 0.18f), palette.lavender.copy(alpha = 0.18f))
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(palette.indigo.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Three-bubble mark — replaced the Gemini-like
                    // AutoAwesome sparkle so the app has its own
                    // conversation-themed brand icon.
                    Icon(
                        painter = painterResource(R.drawable.ic_three_bubbles),
                        contentDescription = null,
                        tint = palette.indigo
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("HandyAi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "On-device AI",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        HorizontalDivider()

        // New chat button — gradient-tinted to feel like the primary CTA
        Surface(
            color = palette.indigo.copy(alpha = 0.10f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(onClick = onNewChat)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = palette.indigo)
                Spacer(Modifier.width(10.dp))
                Text("New chat", style = MaterialTheme.typography.labelLarge, color = palette.indigo, fontWeight = FontWeight.SemiBold)
            }
        }

        // Quick tools row — Journal + Habits, each in their own pastel chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = onOpenJournal,
                leadingIcon = {
                    Icon(Icons.Default.Book, null, modifier = Modifier.size(16.dp), tint = palette.coral)
                },
                label = { Text("Journal", style = MaterialTheme.typography.labelMedium) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = palette.coral.copy(alpha = 0.15f)
                ),
                modifier = Modifier.weight(1f)
            )
            AssistChip(
                onClick = onOpenHabits,
                leadingIcon = {
                    Icon(Icons.Default.FitnessCenter, null, modifier = Modifier.size(16.dp), tint = palette.mint)
                },
                label = { Text("Habits", style = MaterialTheme.typography.labelMedium) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = palette.mint.copy(alpha = 0.18f)
                ),
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider()

        // Chat history list — fills available space, pushing Settings to the bottom
        Box(modifier = Modifier.weight(1f, fill = true)) {
            if (chats.isEmpty()) {
                Text(
                    "No chats yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(chats, key = { it.id }) { chat ->
                        DrawerChatRow(
                            chat = chat,
                            isActive = chat.id == activeChatId,
                            onOpen = { onOpenChat(chat.id) },
                            onRename = { newTitle -> onRename(chat.id, newTitle) },
                            onDelete = { onDelete(chat) }
                        )
                    }
                }
            }
        }

        // Settings — pinned to the bottom of the drawer
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Settings") },
            leadingContent = { Icon(Icons.Default.Settings, null) },
            modifier = Modifier.clickable(onClick = onOpenSettings)
        )
    }
}

@Composable
private fun DrawerChatRow(
    chat: Chat,
    isActive: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        if (renaming) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                IconButton(onClick = {
                    if (renameText.isNotBlank()) onRename(renameText.trim())
                    renaming = false
                }) {
                    Icon(Icons.Default.Check, "Save")
                }
                IconButton(onClick = { renaming = false }) {
                    Icon(Icons.Default.Close, "Cancel")
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    chat.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showMenu = false
                                renameText = chat.title
                                renaming = true
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Chat pane                                                           */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPane(
    chatId: Long,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit
) {
    val app = HandyAiApp.instance
    // Use a unique key per chatId so a fresh ViewModel is created for each chat.
    // Without this, viewModel() returns the same VM instance and messages from
    // the previous chat leak into the new one.
    val vm: ChatViewModel = viewModel(
        key = "chat-$chatId",
        factory = ChatViewModelFactory(
            chatId = chatId,
            chatRepo = app.chatRepository,
            llm = app.llmEngine,
            imageGen = app.imageGenEngine,
            tts = app.ttsEngine,
            settings = app.settingsRepository,
            fileExtractor = app.fileExtractor,
            webSearch = app.webSearch,
            journalRepo = app.journalRepository,
            habitRepo = app.habitRepository,
            summarizer = app.attachmentSummarizer,
            preferenceLearner = app.preferenceLearner,
            contextCache = app.contextCache,
            visionLlm = app.visionLlm
        )
    )

    val messages by vm.messages.collectAsStateWithLifecycle(emptyList())
    val chat by vm.chat.collectAsStateWithLifecycle()
    val internetOn by vm.internetEnabled.collectAsStateWithLifecycle(false)
    val ttsOn by vm.ttsEnabled.collectAsStateWithLifecycle(false)
    val streamingChunk by vm.streamingChunk.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    // v1.4.5: LiteRT-LM engine removed — only the MediaPipe LlmEngine state
    // drives the Send/Stop button now. The previous dual-engine dispatch
    // (vision model + text model) is gone; vision is cloud-only.
    val llmState by app.llmEngine.state.collectAsStateWithLifecycle()
    val activeEngineState = llmState
    val ttsSpeaking by app.ttsEngine.speaking.collectAsStateWithLifecycle()
    val ttsCurrentId by app.ttsEngine.currentId.collectAsStateWithLifecycle()

    // STT (speech-to-text) state
    val sttListening by app.sttEngine.listening.collectAsStateWithLifecycle()
    val sttPartial by app.sttEngine.partial.collectAsStateWithLifecycle()
    val sttError by app.sttEngine.errors.collectAsStateWithLifecycle()

    var input by remember(chatId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    // ── Swipe-left → Models navigation ────────────────────────────────
    // The ModalNavigationDrawer already handles right-swipe-from-left-edge
    // to open the chat history drawer. We add a left-swipe (finger moves
    // right→left) detector on the chat content so the user can flip from
    // a chat directly to the Models screen.
    //
    // -150dp total horizontal drag (less than ~one-third of a typical
    // phone width) is the trigger threshold. This is high enough that
    // ordinary scrolling won't fire it, but low enough that a deliberate
    // flick gets there in one motion.
    //
    // The state lives in a plain holder (not mutableStateOf) because it
    // is only ever read inside the pointerInput coroutine — making it
    // state would trigger pointless recompositions on every drag frame.
    val swipeThresholdPx = with(density) { (-150f).dp.toPx() }
    val swipeState = remember {
        object {
            var accumulator = 0f
            var handled = false
        }
    }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch { vm.attachFile(uri) }
        }
    }

    // Image picker — uses PickVisualMedia so the user gets the system
    // photo picker (no permission needed on Android 13+; graceful
    // fallback to a content picker on older versions). Image URIs are
    // routed through the same attachFile() pipeline — the
    // FileTextExtractor detects image MIME and delegates to the
    // ML Kit ImageAnalyzer for OCR + image labeling.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch { vm.attachFile(uri) }
        }
    }

    // Microphone permission launcher
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            app.sttEngine.startListening { text ->
                if (text.isNotBlank()) {
                    input = if (input.isBlank()) text else "$input $text"
                }
            }
        }
    }

    // ── Chat scroll behaviour (v1.4.5 — rewritten to fix scroll-lock bug) ──
    //
    // BUG (v1.4.3 + v1.4.4):
    //   The user reported: "when the llm chat becomes too long then it
    //   doesn't let the user scroll up or down while the llm is writing."
    //
    // ROOT CAUSE:
    //   • `LaunchedEffect(canScrollForward)` set `userScrolledUp = true`
    //     whenever the list could scroll forward AND we weren't mid-
    //     auto-scroll. But between streaming chunks there are brief
    //     windows where `autoScrolling.value == false` and
    //     `canScrollForward == true` (new content arrived, next auto-
    //     scroll hasn't started). The effect fired in those windows and
    //     set `userScrolledUp = true`, which then BLOCKED the next auto-
    //     scroll. The user got stuck — couldn't scroll up (auto-scroll
    //     was fighting them) or down (the flag was stuck).
    //   • `LaunchedEffect(messages.size, streamingChunk)` re-ran on every
    //     chunk (potentially 20+ times per second), each time calling
    //     `listState.scrollToItem(...)`. That constant interruption
    //     fought any user scroll gesture.
    //
    // FIX (v1.4.5):
    //   1. Drop the `userScrolledUp` sticky flag entirely. Use the
    //      LazyListState's layout info as the source of truth.
    //   2. Compute `isAtBottom` from `listState.layoutInfo.visibleItemsInfo`:
    //      true when the last visible item index == totalItems - 1.
    //      This is the natural "am I at the bottom?" check — no flag
    //      manipulation, no race between effects.
    //   3. Auto-scroll on chunk changes ONLY when `isAtBottom` is true.
    //      If the user scrolled up, `isAtBottom` is false → no auto-scroll.
    //      The instant they scroll back to the bottom, `isAtBottom`
    //      becomes true → auto-scroll resumes.
    //   4. THROTTLE auto-scrolls to at most once per 120ms. Streaming
    //      chunks arrive every ~50ms but the human eye can't perceive
    //      scroll updates faster than ~8/sec, so 120ms (≈8/sec) is the
    //      sweet spot. Without throttling, every chunk kicks a scroll,
    //      which fights user gestures.
    //   5. When the user is actively scrolling (`listState.isScrollInProgress`),
    //      NEVER auto-scroll — even if `isAtBottom` is briefly true
    //      during a scroll gesture. This is the "never fight the user"
    //      rule.
    //   6. New-message-added: ALWAYS force-scroll to the bottom (the user
    //      expects to see their own message + the start of the reply).
    //   7. Scroll-to-bottom FAB: visible when `!isAtBottom` AND not
    //      actively generating (during generation the FAB is hidden
    //      because the user already sees the latest content streaming in).
    var lastMsgCount by remember { mutableStateOf(0) }
    // Throttle: track the last auto-scroll time so we don't kick more
    // than one scroll per 120ms. Plain Long holder (not State — only
    // read inside coroutines, so no recomposition trigger needed).
    val lastAutoScrollMs = remember {
        object {
            @Volatile var value = 0L
        }
    }
    // Force-scroll flag — set to true when a NEW message is added (user
    // sent a message). Cleared after the force-scroll completes.
    var forceScrollToBottom by remember { mutableStateOf(false) }

    // Source-of-truth: is the last visible item the last item in the list?
    // Uses layoutInfo so it stays accurate even during rapid chunk emission.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf true
            // The "total" the LazyListState knows about — this includes
            // the streaming/thinking bubbles that we add as `item {}`
            // blocks. visibleItemsInfo.last().index is the actual last
            // visible item. If that equals totalItemsCount - 1, we're
            // at the bottom.
            visible.last().index >= info.totalItemsCount - 1
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) {
            lastMsgCount = 0
            return@LaunchedEffect
        }
        // New message added (user sent a message) — force-scroll to
        // bottom regardless of current position. The user expects to
        // see their own message + the start of the reply.
        if (messages.size > lastMsgCount) {
            forceScrollToBottom = true
        }
        lastMsgCount = messages.size
    }

    LaunchedEffect(forceScrollToBottom, messages.size) {
        if (!forceScrollToBottom) return@LaunchedEffect
        // Compute the expected total including thinking + streaming items.
        val showThinking = (activeEngineState is LlmState.Generating) &&
            streamingChunk.isEmpty() &&
            (statusText.isBlank() || statusText.startsWith("Generating"))
        val expectedTotal = messages.size +
            (if (showThinking) 1 else 0) +
            (if (streamingChunk.isNotEmpty()) 1 else 0)
        if (expectedTotal > 0) {
            try {
                listState.scrollToItem(expectedTotal)
            } catch (_: Throwable) {}
        }
        forceScrollToBottom = false
    }

    LaunchedEffect(streamingChunk) {
        // Don't auto-scroll on every chunk — only when:
        //   1. We're at the bottom (user hasn't scrolled up)
        //   2. The user isn't actively scrolling right now
        //   3. At least 120ms have passed since the last auto-scroll
        //      (throttle — chunks arrive every ~50ms but the eye can't
        //      perceive updates faster than ~8/sec)
        if (!isAtBottom) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastAutoScrollMs.value < 120L) return@LaunchedEffect
        lastAutoScrollMs.value = now

        // Compute the expected total including thinking + streaming items.
        val showThinking = (activeEngineState is LlmState.Generating) &&
            streamingChunk.isEmpty() &&
            (statusText.isBlank() || statusText.startsWith("Generating"))
        val expectedTotal = messages.size +
            (if (showThinking) 1 else 0) +
            (if (streamingChunk.isNotEmpty()) 1 else 0)
        if (expectedTotal > 0) {
            try {
                // Use INSTANT scroll (no animation). Animations cancel
                // each other when chunks arrive every ~50ms, which was
                // the root cause of the v1.4.2 stutter.
                listState.scrollToItem(expectedTotal)
            } catch (_: Throwable) {}
        }
    }

    // Surface STT errors via Snackbar
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.errors.collectLatest { msg ->
            snackbarHost.showSnackbar(msg)
        }
    }
    LaunchedEffect(sttError) {
        sttError?.let {
            snackbarHost.showSnackbar(it)
            app.sttEngine.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        chat?.title?.ifBlank { "New chat" } ?: "HandyAi",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, "Chat history")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Default.Memory, "Models")
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                text = input,
                onTextChange = { input = it },
                onSend = {
                    val msg = input.trim()
                    if (msg.isNotEmpty()) {
                        input = ""
                        scope.launch { vm.sendUserMessage(msg) }
                    }
                },
                onAttachFile = {
                    filePicker.launch(arrayOf(
                        // Plain text family
                        "text/plain", "text/markdown", "text/csv", "text/x-csv",
                        "application/json", "text/x-log", "application/xml", "text/yaml",
                        "text/x-yaml", "text/html",
                        // PDF
                        "application/pdf",
                        // Word
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/msword",
                        "application/rtf",
                        // Excel
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                        // PowerPoint
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/vnd.ms-powerpoint",
                        // OpenDocument
                        "application/vnd.oasis.opendocument.text",
                        // Images — also accepted here as a fallback to the
                        // dedicated image picker button. The system picker
                        // will let the user choose any of these MIMEs.
                        "image/jpeg", "image/png", "image/webp", "image/bmp", "image/gif",
                        // Catch-all for any other type (the picker will still let the user
                        // select files even if the MIME list doesn't include them — Android
                        // matches loosely)
                        "*/*"
                    ))
                },
                onAttachImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onMic = {
                    if (sttListening) {
                        app.sttEngine.stop()
                    } else {
                        // Request mic permission if not granted
                        val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            app.sttEngine.startListening { text ->
                                if (text.isNotBlank()) {
                                    input = if (input.isBlank()) text else "$input $text"
                                }
                            }
                        } else {
                            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onClearAttachment = { vm.clearAttachment() },
                internetOn = internetOn,
                onToggleInternet = { scope.launch { vm.toggleInternet(!internetOn) } },
                ttsOn = ttsOn,
                onToggleTts = { scope.launch { vm.toggleTts(!ttsOn) } },
                onStop = { vm.stopGeneration() },
                enabled = activeEngineState !is LlmState.Generating && activeEngineState !is LlmState.Loading,
                isGenerating = activeEngineState is LlmState.Generating,
                isListening = sttListening,
                statusText = statusText,
                attachmentLabel = chat?.contextLabel
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // ── Tap outside the input field → dismiss keyboard ───────
                // detectTapGestures fires for taps anywhere on the chat
                // content that aren't consumed by an inner clickable
                // (e.g. the TTS button inside a message bubble). Tapping
                // on a message bubble, the empty-state hero, or the
                // doodle background all collapse the soft keyboard —
                // matching WhatsApp/Telegram behavior.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { keyboard?.hide() })
                }
                // ── Swipe left → Models ─────────────────────────────────
                // detectHorizontalDragGestures accumulates the net
                // horizontal drag. When the finger lifts and the total
                // is below the negative threshold (i.e. a leftward flick
                // of at least 150dp), we navigate to Models. Rightward
                // swipes are intentionally NOT consumed here — they're
                // left to the ModalNavigationDrawer's own edge-swipe
                // detector so the chat-history drawer still opens from
                // the left edge.
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            swipeState.accumulator = 0f
                            swipeState.handled = false
                        },
                        onDragEnd = {
                            if (!swipeState.handled && swipeState.accumulator <= swipeThresholdPx) {
                                swipeState.handled = true
                                keyboard?.hide()
                                onOpenModels()
                            }
                            swipeState.accumulator = 0f
                        },
                        onDragCancel = {
                            swipeState.accumulator = 0f
                            swipeState.handled = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeState.accumulator += dragAmount
                            // Early-trigger: if the user has already
                            // flung far enough, fire once during the
                            // drag so the nav feels instant instead of
                            // waiting for the finger to lift.
                            if (!swipeState.handled && swipeState.accumulator <= swipeThresholdPx) {
                                swipeState.handled = true
                                keyboard?.hide()
                                onOpenModels()
                            }
                        }
                    )
                }
        ) {
            // WhatsApp-style translucent doodle background.
            // Drawn behind everything; bubbles sit on top with their
            // own backgrounds so the doodle is only visible in the
            // gutters between messages.
            DoodleBackground(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.10f)
            )

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // If no model is loaded, show a friendly prompt to set one up.
                if (activeEngineState is LlmState.Idle || activeEngineState is LlmState.Error) {
                    NoModelBanner(onOpenModels = onOpenModels)
                }

                if (messages.isEmpty() && streamingChunk.isEmpty() && activeEngineState !is LlmState.Generating) {
                    EmptyChatState()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                onSpeak = {
                                    if (ttsCurrentId == msg.id.toString() && ttsSpeaking) {
                                        app.ttsEngine.stop()
                                    } else {
                                        app.ttsEngine.speak(msg.content, msg.id.toString())
                                    }
                                },
                                isSpeaking = ttsSpeaking && ttsCurrentId == msg.id.toString(),
                                showSpeakButton = msg.role == Role.ASSISTANT && msg.content.isNotBlank()
                            )
                        }
                        // ── Thinking bubble ─────────────────────────────────────
                        // Shown while the LLM is generating but no tokens have
                        // streamed yet (the model is still "thinking" — building
                        // the first token). Also shown during long-running
                        // statusText phases (web search, file parse, summarize
                        // chunks) so the user always sees a live indicator that
                        // *something* is happening, not a frozen screen.
                        //
                        // Conditions:
                        //   - llmState is Generating (the engine is mid-call)
                        //   - streamingChunk is empty (no text yet)
                        //   - AND (statusText is blank OR statusText is one of
                        //     the summarization progress messages, which are
                        //     already shown in the input bar's status row —
                        //     we don't duplicate them inside the chat list)
                        //
                        // Once the first token arrives, the streaming bubble
                        // takes over (it has the orange caret + live text).
                        val showThinking = (activeEngineState is LlmState.Generating) &&
                            streamingChunk.isEmpty() &&
                            (statusText.isBlank() || statusText.startsWith("Generating"))
                        if (showThinking) {
                            item { ThinkingBubble() }
                        }
                        // ── v1.4.3: guard StreamingBubble with engine state ──
                        // Previously, StreamingBubble rendered whenever
                        // streamingChunk was non-empty — even AFTER the user
                        // tapped Stop (the engine state had already flipped
                        // to Ready but the abandoned ProgressListener kept
                        // appending to streamingChunk for a second). The
                        // user saw a zombie bubble that kept filling with
                        // newlines. Now we only render it when the engine
                        // is actively Generating, so the bubble vanishes
                        // the instant Stop is tapped.
                        if (streamingChunk.isNotEmpty() &&
                            activeEngineState is LlmState.Generating) {
                            item { StreamingBubble(streamingChunk) }
                        }
                    }
                }
            }

            // ── "Scroll to bottom" button (v1.4.5) ──────────────────────────
            // Shows when the user is NOT at the bottom (`!isAtBottom`).
            // v1.4.5 removed the old `userScrolledUp` sticky flag (which
            // was the root cause of the scroll-lock bug — it got stuck
            // true during streaming). Now we just check the layout-info
            // truth: if the last visible item isn't the last item, the
            // user has scrolled up and we show the FAB.
            //
            // Tapping the FAB force-scrolls to the bottom and the FAB
            // vanishes the instant `isAtBottom` becomes true again.
            if (!isAtBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val showThinkingNow = (activeEngineState is LlmState.Generating) &&
                                streamingChunk.isEmpty() &&
                                (statusText.isBlank() || statusText.startsWith("Generating"))
                            val total = messages.size +
                                (if (showThinkingNow) 1 else 0) +
                                (if (streamingChunk.isNotEmpty()) 1 else 0)
                            if (total > 0) {
                                try {
                                    listState.scrollToItem(total)
                                } catch (_: Throwable) {}
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to latest message")
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Sub-components                                                      */
/* ------------------------------------------------------------------ */

@Composable
private fun NoModelBanner(onOpenModels: () -> Unit) {
    val palette = handyAiPalette()
    Surface(
        color = palette.sun.copy(alpha = 0.22f),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Memory, null, tint = palette.sun)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "No model loaded",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Tap to download and load a model to start chatting.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onOpenModels) { Text("Set up") }
        }
    }
}

@Composable
private fun EmptyChatState() {
    val palette = handyAiPalette()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pastel halo + icon — gives the empty state more
            // personality than a single monochrome icon.
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(palette.indigo.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                // Three-bubble mark — same brand icon used in the
                // drawer header, scaled up for the empty-chat hero.
                Icon(
                    painter = painterResource(R.drawable.ic_three_bubbles), null,
                    tint = palette.indigo,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "How can I help you today?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Type a message, attach a file or photo, or use voice input to begin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    val palette = handyAiPalette()
    // Blinking animation for the orange caret at the end of streaming text.
    // Alpha oscillates between 0.3f and 1.0f — visible enough to be noticeable
    // but not strobe-y. The 600ms duration matches typical terminal-cursor blink.
    val transition = rememberInfiniteTransition(label = "caret")
    val caretAlpha by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caretAlpha"
    )

    // Capture the Text's layout result so we can position the caret at the
    // exact pixel position of the cursor right AFTER the last character.
    // This is what makes the caret "follow" the text — the old Row-based
    // layout placed the caret as a sibling after the Text, which left it
    // pinned to the right edge of the bubble when text wrapped to multiple
    // lines. With getCursorRect(text.length) we get the (x, y) of where
    // the next character would appear, even if that's on a new line.
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current

    Surface(
        color = palette.aiBubble,
        shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.aiBubbleBorder),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .widthIn(max = 340.dp)
            .shadow(1.dp, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
    ) {
        // Box (overlay layout) instead of Row — the caret is drawn on top
        // of the text at the cursor's pixel position. The Box and Text share
        // the same padding so the caret's offset coordinates are in the same
        // coordinate space as the Text's layout result.
        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(max = 312.dp)
        ) {
            // ── v1.4.4: RICH FORMATTING in streaming bubble ──────────────
            // Earlier versions rendered raw sanitized text here. Now we use
            // parseToAnnotatedString so **bold**, ### headings, and SmolLM
            // tags render with proper formatting DURING streaming — not just
            // after the message is persisted.
            //
            // SAFETY: The caret code below uses `lr.layoutInput.text.length`
            // (the layout's OWN text length), which is always in-bounds for
            // that layout. Since Text() receives the AnnotatedString directly,
            // `lr.layoutInput.text` IS the AnnotatedString, so lengths always
            // match. The v1.2.9 crash (which used `parsed.length` on a stale
            // layout) cannot recur with this code.
            val annotated = com.handyai.ui.components.MarkdownParser.parseToAnnotatedString(
                text, isUser = false
            )
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                onTextLayout = { layoutResult = it }
            )

            // ── Orange blinking caret — positioned at cursor rect ──────
            // Small vertical rectangle (2dp wide) in orangish color,
            // blinks at 600ms intervals. Positioned at the pixel
            // coordinates returned by getCursorRect(...) — the point where
            // the NEXT character would be inserted. This means the caret
            // always hugs the last character, even when text wraps across
            // multiple lines (the old Row layout left it stranded at the
            // right edge of the bubble).
            //
            // The caret height is set to the line height from the layout
            // result (rect.height) so it matches the visual height of the
            // text line the cursor sits on.
            //
            // SAFE BOUNDS: We use `lr.layoutInput.text.length` (the length
            // of the text the layout was actually computed for) as the
            // offset. Since we no longer parse markdown, this is always
            // equal to `sanitized.length`. The layoutResult may lag one
            // frame behind the current `sanitized` string (onTextLayout
            // fires AFTER recomposition), but using the layout's OWN text
            // length is always valid for that layout — getCursorRect will
            // never go out of bounds. The caret position visually lags by
            // one frame when text grows — invisible to the user (the
            // layout catches up on the next frame, ~16ms later).
            val lr = layoutResult
            if (lr != null && lr.layoutInput.text.isNotEmpty()) {
                val rect = lr.getCursorRect(lr.layoutInput.text.length)
                Box(
                    modifier = Modifier
                        // offset { IntOffset } takes pixels — no density
                        // conversion needed since getCursorRect returns px.
                        .offset {
                            IntOffset(
                                x = rect.left.toInt(),
                                y = rect.top.toInt()
                            )
                        }
                        .width(2.dp)
                        .height(with(density) { rect.height.toDp() })
                        .background(
                            color = Color(0xFFFF7A1A).copy(alpha = caretAlpha),
                            shape = RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

/**
 * A blank assistant bubble shown while the LLM is "thinking" — i.e. the
 * generation has started but no tokens have streamed yet. The bubble
 * shows the word "Thinking" followed by 1, 2, then 3 dots cycling back
 * to 1, in the rhythm of a typical typing indicator.
 *
 * The cycle period is 1200 ms total (400 ms per dot). This is the same
 * rhythm ChatGPT/Claude/WhatsApp use for their "typing…" indicators —
 * slow enough to feel calm, fast enough that the user sees it move on
 * a quick glance.
 *
 * Visually it uses the same bubble style as [StreamingBubble] and the
 * persisted assistant MessageBubble so the transition thinking → streaming
 * → persisted is seamless (the bubble shape and color don't jump).
 *
 * Layout:
 *   - Bubble on the left (assistant side), max width 340 dp
 *   - Inside: Row { "Thinking" + dots }
 *   - The dots are 4dp circles spaced 3dp apart, vertically centered
 *     with the text baseline. As the animation cycles 0→1→2→3, dots 0..n-1
 *     are full-opacity and the rest are dimmed.
 */
@Composable
private fun ThinkingBubble() {
    val palette = handyAiPalette()

    // 0 → 1 → 2 → 3 → 0 → 1 → ... (one full cycle = 1200 ms)
    // We use animateFloat with a 1200 ms tween and derive the dot count
    // from the float value: count = floor(progress * 3.999) so we hit
    // 0, 1, 2, 3 exactly once each per cycle.
    val transition = rememberInfiniteTransition(label = "thinking")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinkingProgress"
    )
    // Number of dots currently visible (0..3). Using toInt() on a float
    // that goes 0→4 (exclusive) gives 0,1,2,3 — exactly the cycle the
    // user asked for ("1, 2, 3, then 1 again").
    val visibleDots = progress.toInt().coerceIn(0, 3)

    Surface(
        color = palette.aiBubble,
        shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.aiBubbleBorder),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .widthIn(max = 340.dp)
            .shadow(1.dp, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                "Thinking",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(6.dp))
            // Three dots — render all three always, but only the first
            // `visibleDots` are at full opacity. The rest are at 20% so
            // they're invisible-ish (gives a subtle "trail" effect rather
            // than a hard on/off).
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in 0 until 3) {
                    val alpha = if (i < visibleDots) 1f else 0.18f
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                                shape = RoundedCornerShape(50)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachFile: () -> Unit,
    onAttachImage: () -> Unit,
    onMic: () -> Unit,
    onClearAttachment: () -> Unit,
    internetOn: Boolean,
    onToggleInternet: () -> Unit,
    ttsOn: Boolean,
    onToggleTts: () -> Unit,
    onStop: () -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    isListening: Boolean,
    statusText: String,
    attachmentLabel: String?
) {
    val palette = handyAiPalette()
    // True when the attached item is an image (vs. a regular file).
    // contextLabel format: "image:filename.jpg" or "file:filename.txt"
    val isImageAttachment = attachmentLabel?.startsWith("image:") == true

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()  // <- keeps the input visible above the keyboard
                .navigationBarsPadding()
        ) {
            // Attachment chip — visible only when a file or image is attached
            // to this chat. Shows a different icon + tint for images so the
            // user can tell at a glance that the LLM has been given OCR text
            // + image labels.
            if (attachmentLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val chipColor = if (isImageAttachment) palette.lavender else palette.mint
                    AssistChip(
                        onClick = {},
                        leadingIcon = {
                            Icon(
                                if (isImageAttachment) Icons.Default.Image
                                else Icons.Default.AttachFile,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = chipColor
                            )
                        },
                        label = {
                            Text(
                                attachmentLabel.removePrefix("file:").removePrefix("image:"),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close, "Remove attachment",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onClearAttachment() }
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = chipColor.copy(alpha = 0.18f),
                            labelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }

            // Status row — visible when web search / file parsing is running
            if (statusText.isNotBlank() || isListening) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isListening) {
                        BlinkingDot()
                        Spacer(Modifier.width(8.dp))
                        Text("Listening… ${if (text.isNotBlank()) text else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Quick toggles row above the input — pastel-tinted chips
            // (lavender for Internet, mint for Voice) and distinct icon
            // buttons for file vs. image attachment. Each action gets
            // its own light color so the bar feels lively instead of
            // monochrome.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File attach — coral tint
                IconButton(onClick = onAttachFile, enabled = enabled, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.AttachFile, "Attach file",
                        tint = palette.coral
                    )
                }
                // Image attach — lavender tint (NEW in v1.1.5)
                IconButton(onClick = onAttachImage, enabled = enabled, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.AddPhotoAlternate, "Attach image",
                        tint = palette.lavender
                    )
                }
                FilterChip(
                    selected = internetOn,
                    onClick = onToggleInternet,
                    leadingIcon = {
                        Icon(
                            if (internetOn) Icons.Default.Wifi else Icons.Default.WifiOff,
                            null, modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Internet", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.lavender.copy(alpha = 0.30f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = ttsOn,
                    onClick = onToggleTts,
                    leadingIcon = {
                        Icon(
                            if (ttsOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            null, modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Voice", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.mint.copy(alpha = 0.35f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (text.isEmpty()) "Message HandyAi…  (try /draw cat)"
                            else "Message HandyAi…"
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    enabled = enabled,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.indigo,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Spacer(Modifier.width(6.dp))
                // Mic button — speech-to-text, soft coral tint
                FilledTonalIconButton(
                    onClick = onMic,
                    enabled = enabled || isListening,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = palette.sun.copy(alpha = 0.35f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        if (isListening) "Stop listening" else "Voice input"
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (isGenerating) {
                    // ── Stop button ──────────────────────────────────────────
                    // While the LLM is generating, the Send button morphs into
                    // a Stop button so the user can cancel an in-flight reply
                    // mid-stream. Same pill shape + size as the Send button so
                    // the layout doesn't shift, but painted in a warm red
                    // instead of the indigo→lavender gradient to signal that
                    // it's a destructive/interruptive action.
                    //
                    // On tap, calls vm.stopGeneration() which cancels the
                    // current coroutine Job. The cancellation propagates into
                    // the engine's onFailure handler, where the partial streamed
                    // text is persisted as an assistant message (so the user
                    // doesn't lose the tokens they already saw).
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(Color(0xFFE94B3C), Color(0xFFFF7A1A))
                                )
                            )
                            .clickable(onClick = onStop),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Stop, "Stop generation",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    // Send button — gradient (indigo → lavender), pill-shaped.
                    // Box + Icon instead of FilledIconButton so we can paint
                    // a horizontal gradient brush behind the icon.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                brush = if (text.isNotBlank() && enabled)
                                    sendButtonBrush(palette)
                                else
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.outlineVariant,
                                            MaterialTheme.colorScheme.outlineVariant
                                        )
                                    )
                            )
                            .clickable(enabled = text.isNotBlank() && enabled, onClick = onSend),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, "Send",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlinkingDot() {
    val transition = rememberInfiniteTransition(label = "blink")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinkAlpha"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                shape = RoundedCornerShape(50)
            )
    )
}

/**
 * WhatsApp-style translucent doodle background for the chat canvas.
 *
 * Loads the vector drawable (`chat_doodle_pattern.xml`) once and tiles
 * it across the available area using a Canvas. The composable that
 * hosts this background is expected to wrap it in `Modifier.alpha(...)`
 * (typically 0.08–0.12) so the doodle sits subtly behind the bubbles
 * without competing with the conversation.
 *
 * We avoid `Modifier.paint(painterResource(...))` because that scales
 * a single instance of the painter to fill the bounds — it doesn't
 * tile. Drawing the painter in a manual grid via Canvas gives us a
 * true tiled pattern that looks like the WhatsApp chat backdrop.
 */
@Composable
private fun DoodleBackground(modifier: Modifier = Modifier) {
    val painter: Painter = painterResource(R.drawable.chat_doodle_pattern)
    val intrinsic = painter.intrinsicSize
    // The vector drawable is 240x240dp; fall back to that if the
    // painter doesn't expose an intrinsic size.
    val tileW = if (intrinsic.width > 0) intrinsic.width else 240f
    val tileH = if (intrinsic.height > 0) intrinsic.height else 240f
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tileWpx = with(density) { tileW.dp.toPx() }
    val tileHpx = with(density) { tileH.dp.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                translate(left = x, top = y) {
                    with(painter) {
                        draw(size = androidx.compose.ui.geometry.Size(tileWpx, tileHpx))
                    }
                }
                x += tileWpx
            }
            y += tileHpx
        }
    }
}

