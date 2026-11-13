package ai.airlink.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ai.airlink.BuildConfig
import ai.airlink.ChatItem
import ai.airlink.Direction
import ai.airlink.LinkViewModel
import ai.airlink.Screen
import ai.airlink.UiState
import ai.airlink.audio.AudioProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
private const val REPO_URL = "https://github.com/Aditya-FullStack/AuDTLink"

/** "AuDTLink v4" for 4.0, "AuDTLink v4.1" for 4.1 — same rule as tools/bump_version.py. */
private val APP_TITLE: String =
    "AuDTLink v" + BuildConfig.VERSION_NAME.removeSuffix(".0")

@Composable
fun AirlinkScreen(vm: LinkViewModel) {
    val state by vm.state.collectAsState()
    if (state.screen == Screen.ABOUT) {
        BackHandler { vm.closeAbout() }
        AboutScreen(state, vm)
    } else {
        ChatScreen(state, vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(state: UiState, vm: LinkViewModel) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.sendFile(it) }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startReceiving() else vm.onMicDenied()
    }

    fun toggleReceive() {
        if (state.receiving) {
            vm.stopReceiving()
        } else {
            val ok = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (ok) vm.startReceiving() else mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Text(APP_TITLE, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                },
                actions = {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(
                            Icons.Filled.Delete, "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.openAbout() }) {
                        Icon(
                            Icons.Filled.Settings, "Settings and about",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraft = { draft = it },
                sending = state.sending,
                paused = state.paused,
                receiving = state.receiving,
                onSend = {
                    if (draft.isNotBlank()) {
                        vm.sendText(draft)
                        draft = ""
                    }
                },
                onAttach = { picker.launch(arrayOf("*/*")) },
                onToggleReceive = { toggleReceive() },
                onTogglePause = { vm.togglePause() }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ModeList(state.profile) { vm.setProfile(it) }

            Text(
                state.status,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )

            state.notice?.let { note ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { vm.dismissNotice() }
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                if (state.items.isEmpty()) {
                    EmptyState(state)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.items, key = { it.id }) { item -> Bubble(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeList(selected: AudioProfile, onSelect: (AudioProfile) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AudioProfile.entries.forEach { p ->
            val isSelected = p == selected
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(p) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.title,
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (p.beta) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "BETA",
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (isSelected) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(state: UiState) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (state.profile.audible) "🔊" else "📡", fontSize = 40.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Tap the mic on one phone, then send from the other.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.profile.audible) "Hold them 10–30 cm apart. You will hear the data."
            else "Ultrasound needs them closer — about 10 cm — and a quiet room.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    val outgoing = item.direction == Direction.OUT
    val accent = when (item.kind) {
        "FILE" -> Color(0xFF3B5BDB)
        "COMMAND" -> Color(0xFFB8860B)
        "ERROR" -> MaterialTheme.colorScheme.error
        "ACK", "NACK" -> Color(0xFF2F855A)
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 290.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (outgoing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.kind, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.width(6.dp))
                Text(
                    clock.format(Date(item.at)),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(item.text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            item.detail?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    sending: Boolean,
    paused: Boolean,
    receiving: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onToggleReceive: () -> Unit,
    onTogglePause: () -> Unit
) {
    val actionIcon = when {
        sending -> if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause
        receiving -> Icons.Filled.Stop
        else -> Icons.Filled.Mic
    }
    val actionLabel = when {
        sending -> if (paused) "Resume transmission" else "Pause transmission"
        receiving -> "Stop listening"
        else -> "Listen"
    }
    val actionColor = when {
        receiving -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(actionColor)
                    .clickable { if (sending) onTogglePause() else onToggleReceive() },
                contentAlignment = Alignment.Center
            ) {
                Icon(actionIcon, contentDescription = actionLabel, tint = MaterialTheme.colorScheme.onPrimary)
            }

            Spacer(Modifier.width(8.dp))

            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 50.dp, max = 110.dp),
                placeholder = { Text("Message", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(14.dp),
                maxLines = 3
            )

            IconButton(onClick = onAttach, enabled = !sending) {
                Icon(
                    Icons.Filled.AttachFile, "Send a file",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (draft.isNotBlank() && !sending) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(enabled = draft.isNotBlank() && !sending) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Send, "Send",
                    tint = if (draft.isNotBlank() && !sending) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------- about

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(state: UiState, vm: LinkViewModel) {
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = { vm.closeAbout() }) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                title = { Text("About", fontWeight = FontWeight.SemiBold, fontSize = 20.sp) }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                Text("AuDTLink", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "version ${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("What this is") {
                Text(
                    "Two phones, no network. Messages and files travel between " +
                        "them as sound — frequency-shift keyed tones picked up by " +
                        "the microphone. No Wi-Fi, no Bluetooth, no pairing, no account.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            SectionCard("Updates") {
                val upd = state.update
                Text(
                    "Installed: ${BuildConfig.VERSION_NAME}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))

                when {
                    state.updateChecking -> Text(
                        "Checking GitHub…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    state.updateError != null -> Text(
                        state.updateError,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )

                    upd != null && state.updateAvailable -> {
                        Text(
                            "Version ${upd.tag} is available",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (upd.notes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                upd.notes.take(400),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    upd != null -> Text(
                        "You are on the latest version.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.checkForUpdates() },
                        enabled = !state.updateChecking
                    ) {
                        Text(if (state.updateChecking) "Checking…" else "Check for updates", fontSize = 12.sp)
                    }
                    val target = upd?.apkUrl ?: upd?.pageUrl
                    if (target != null) {
                        OutlinedButton(onClick = { openUrl(context, target) }) {
                            Text(
                                if (upd?.apkUrl != null) "Download" else "Release page",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            SectionCard("Use it from a computer") {
                val p = state.profile
                Text(
                    "No desktop app needed. Airlink frames are plain 8N1 FSK, so " +
                        "minimodem decodes them as-is on Linux, macOS, BSD or WSL.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    p.wireSummary(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                CommandRow("Receive (phone \u2192 computer)", p.minimodemRxCommand())
                Spacer(Modifier.height(8.dp))
                CommandRow("Transmit (computer \u2192 phone)", p.minimodemTxCommand())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Frames are binary, so write them to a file and let the repo's " +
                        "tools/airlink_cli.py decode turn them into messages and files. " +
                        "Switch mode on the main screen to see another frequency pair.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("Permissions") {
                Text(
                    "Microphone — to hear the other phone.\n" +
                        "Internet — used only by the update check above. Nothing is " +
                        "sent, and no message ever leaves the device.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            SectionCard("Source") {
                Text(
                    REPO_URL,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openUrl(context, REPO_URL) }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CommandRow(label: String, command: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Text(
                    command,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "Copy command",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
