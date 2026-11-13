package ai.airlink.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ai.airlink.ChatItem
import ai.airlink.Direction
import ai.airlink.LinkViewModel
import ai.airlink.audio.AudioProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirlinkScreen(vm: LinkViewModel) {
    val state by vm.state.collectAsState()
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
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.startReceiving() else mic.launch(Manifest.permission.RECORD_AUDIO)
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
                    Column {
                        Text("Airlink", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        Text(
                            "sound only · no Wi-Fi · no Bluetooth · no pairing",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraft = { draft = it },
                sending = state.sending,
                receiving = state.receiving,
                onSend = {
                    if (draft.isNotBlank()) {
                        vm.sendText(draft)
                        draft = ""
                    }
                },
                onAttach = { picker.launch(arrayOf("*/*")) },
                onToggleReceive = { toggleReceive() }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ProfilePicker(selected = state.profile, onSelect = vm::setProfile)
            ProfileCard(state.profile, state.sampleRate)
            StatsStrip(state)
            state.notice?.let { note ->
                NoticeBanner(note) { vm.dismissNotice() }
            }
            Box(Modifier.weight(1f)) {
                if (state.items.isEmpty()) {
                    EmptyState(state.profile)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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
private fun ProfilePicker(selected: AudioProfile, onSelect: (AudioProfile) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AudioProfile.entries.forEach { p ->
            FilterChip(
                selected = p == selected,
                onClick = { onSelect(p) },
                label = { Text(p.title, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun ProfileCard(profile: AudioProfile, rate: Int) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(profile.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "%.0f B/s · %d Hz capture · %s".format(
                    profile.bytesPerSecond, rate,
                    if (profile.audible) "audible" else "near-silent"
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                profile.minimodemRxCommand(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun StatsStrip(state: ai.airlink.UiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${state.framesOk} frames · ${state.framesBad} bad · ${state.bytes} B",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        SignalBar(state.signal, state.receiving)
        Spacer(Modifier.weight(1f))
        Text(
            state.status,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun SignalBar(level: Float, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val on = active && level > (i + 1) * 0.18f
            Box(
                Modifier
                    .padding(end = 2.dp)
                    .width(3.dp)
                    .height((4 + i * 2).dp)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
            .clickable { onDismiss() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        Text(
            "dismiss",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EmptyState(profile: AudioProfile) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (profile.audible) "◍" else "◌",
            fontSize = 44.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Hold two phones near each other, pick the same profile,\nthen tap the mic on one and send on the other.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    val outgoing = item.direction == Direction.OUT
    val accent = when (item.kind) {
        "FILE" -> Color(0xFF7C9CFF)
        "COMMAND" -> Color(0xFFFFC46B)
        "ERROR" -> MaterialTheme.colorScheme.error
        "ACK", "NACK" -> Color(0xFF9BE07A)
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (outgoing) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.kind, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    clock.format(Date(item.at)),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (outgoing) {
                    Spacer(Modifier.width(6.dp))
                    Text("↑", fontSize = 9.sp, color = accent)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                item.text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = if (item.kind == "COMMAND") FontFamily.Monospace else FontFamily.Default
            )
            item.detail?.let {
                Spacer(Modifier.height(3.dp))
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
    receiving: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onToggleReceive: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp, max = 120.dp),
                placeholder = { Text("Message over sound…", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(14.dp),
                maxLines = 4
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onAttach, enabled = !sending) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = "Send a file",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onSend,
                enabled = draft.isNotBlank() && !sending,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (draft.isNotBlank() && !sending) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = if (draft.isNotBlank() && !sending) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onToggleReceive,
                enabled = !sending,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (receiving) MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Icon(
                    if (receiving) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (receiving) "Stop listening" else "Listen",
                    tint = if (receiving) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    sending -> "Transmitting — hold the phones still"
                    receiving -> "Listening… keep the speaker 10–30 cm away"
                    else -> "Tap the mic to listen, or type and send"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
