package ai.airlink.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ai.airlink.ChatItem
import ai.airlink.Direction
import ai.airlink.LinkViewModel
import ai.airlink.UiState
import ai.airlink.audio.AudioProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

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
                    Text("AuDTLink v2", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                },
                actions = {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(
                            Icons.Filled.Delete, "Clear",
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AudioProfile.entries.forEach { p ->
                    ModeCard(
                        profile = p,
                        selected = p == state.profile,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setProfile(p) }
                    )
                }
            }

            Text(
                state.status,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
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
private fun ModeCard(
    profile: AudioProfile,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                profile.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            if (profile.beta) {
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
        Text(
            if (state.profile.audible) "🔊" else "📡",
            fontSize = 40.sp
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Tap the mic on one phone, then send from the other.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.profile.audible)
                "Hold them 10–30 cm apart. You will hear the data."
            else
                "Ultrasound needs them closer — about 10 cm — and a quiet room.",
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
    receiving: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onToggleReceive: () -> Unit
) {
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
                    .background(
                        if (receiving) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    .clickable(enabled = !sending) { onToggleReceive() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (receiving) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (receiving) "Stop listening" else "Listen",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
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
