package ai.airlink

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.airlink.audio.AudioEngine
import ai.airlink.audio.AudioProfile
import ai.airlink.audio.BfskDemodulator
import ai.airlink.audio.BfskModulator
import ai.airlink.protocol.FileAssembler
import ai.airlink.protocol.Frame
import ai.airlink.protocol.FrameAssembler
import ai.airlink.protocol.FrameCodec
import ai.airlink.protocol.FrameType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

enum class Direction { IN, OUT }

enum class Screen { CHAT, ABOUT }

data class ChatItem(
    val id: Long,
    val direction: Direction,
    val kind: String,
    val text: String,
    val detail: String? = null,
    val at: Long = System.currentTimeMillis()
)

data class UiState(
    val profile: AudioProfile = AudioProfile.AUDIBLE,
    val items: List<ChatItem> = emptyList(),
    val status: String = "Ready",
    val sending: Boolean = false,
    val receiving: Boolean = false,
    val paused: Boolean = false,
    val signal: Float = 0f,
    val framesOk: Int = 0,
    val framesBad: Int = 0,
    val bytes: Int = 0,
    val sampleRate: Int = 48000,
    val notice: String? = null,
    val screen: Screen = Screen.CHAT,
    val updateChecking: Boolean = false,
    val update: ReleaseInfo? = null,
    val updateAvailable: Boolean = false,
    val updateError: String? = null
)

class LinkViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = AudioEngine()
    private var demod: BfskDemodulator? = null
    private var assembler = FrameAssembler()
    private var sendJob: Job? = null

    private val inboxDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "Airlink")
    private val files = FileAssembler(inboxDir)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private companion object {
        /** ~43 ms of audio at 48 kHz — the pause granularity. */
        const val BLOCK_SAMPLES = 2048
    }

    private val itemIds = AtomicLong(1)
    private var txCounter = 1

    @Volatile
    private var pauseRequested = false

    init {
        files.onSaved = { saved ->
            addItem(
                ChatItem(
                    itemIds.getAndIncrement(), Direction.IN, "FILE", saved.name,
                    "${saved.bytes} bytes · verified CRC32 · saved to Airlink/${saved.name}"
                )
            )
        }
        files.onFailed = { msg -> addItem(ChatItem(itemIds.getAndIncrement(), Direction.IN, "ERROR", msg)) }
    }

    // ------------------------------------------------------------- settings

    fun setProfile(profile: AudioProfile) {
        if (profile == _state.value.profile) return
        val wasReceiving = _state.value.receiving
        stopReceiving()
        _state.update { it.copy(profile = profile, status = "Ready") }
        if (wasReceiving) startReceiving()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun onMicDenied() = _state.update {
        it.copy(notice = "Microphone permission is required to receive.")
    }

    fun openAbout() = _state.update { it.copy(screen = Screen.ABOUT) }

    fun closeAbout() = _state.update { it.copy(screen = Screen.CHAT) }

    // ---------------------------------------------------------------- pause

    fun togglePause() {
        if (!_state.value.sending) return
        pauseRequested = !pauseRequested
        _state.update {
            it.copy(paused = pauseRequested, status = if (pauseRequested) "Paused" else "Resuming…")
        }
    }

    // --------------------------------------------------------------- update

    fun checkForUpdates() {
        if (_state.value.updateChecking) return
        _state.update { it.copy(updateChecking = true, updateError = null, update = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            }
            result.onSuccess { release ->
                val available = UpdateChecker.isNewer(release.tag, BuildConfig.VERSION_NAME)
                _state.update {
                    it.copy(
                        updateChecking = false,
                        update = release,
                        updateAvailable = available,
                        updateError = null
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        updateChecking = false,
                        updateError = e.message ?: "Could not reach GitHub"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ transmit

    fun sendText(text: String) {
        val body = text.trim()
        if (body.isEmpty() || _state.value.sending) return
        val txid = nextTxid()
        val payload = FrameCodec.encode(FrameCodec.textFrame(body, 0, txid))
        addItem(ChatItem(itemIds.getAndIncrement(), Direction.OUT, "TEXT", body))
        transmit(listOf(payload), "message")
    }

    fun sendCommand(cmd: String) {
        val body = cmd.trim()
        if (body.isEmpty() || _state.value.sending) return
        val txid = nextTxid()
        val payload = FrameCodec.encode(FrameCodec.commandFrame(body, 0, txid))
        addItem(ChatItem(itemIds.getAndIncrement(), Direction.OUT, "COMMAND", body))
        transmit(listOf(payload), "command")
    }

    fun sendFile(uri: Uri) {
        if (_state.value.sending) return
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = displayName(uri)
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: run {
                _state.update { it.copy(notice = "Could not read $name") }
                return@launch
            }
            if (bytes.size > FileAssembler.MAX_BYTES) {
                _state.update { it.copy(notice = "$name is larger than the 16 MB limit") }
                return@launch
            }
            val chunk = _state.value.profile.chunkBytes
            val txid = nextTxid()
            val frames = buildFileFrames(name, bytes, chunk, txid)
            val seconds = bytes.size / _state.value.profile.bytesPerSecond
            addItem(
                ChatItem(
                    itemIds.getAndIncrement(), Direction.OUT, "FILE", name,
                    "%d bytes · %d chunks · about %s on the air"
                        .format(bytes.size, frames.size, human(seconds))
                )
            )
            transmit(frames, "file")
        }
    }

    private fun buildFileFrames(name: String, data: ByteArray, chunk: Int, txid: Int): List<ByteArray> {
        val safe = FileAssembler.sanitize(name)
        val nameBytes = safe.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val out = ArrayList<ByteArray>((data.size / chunk) + 2)

        val meta = java.nio.ByteBuffer.allocate(12 + nameBytes.size + 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(txid).putInt(data.size).putShort(chunk.toShort())
            .putShort(nameBytes.size.toShort()).put(nameBytes).putInt(crc.toInt())
            .array()
        out.add(FrameCodec.encode(Frame(FrameType.FILE_META, 0, meta, 0, txid)))

        var seq = 1
        var off = 0
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            val body = ByteArray(8 + n)
            java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(txid).putInt(off)
            System.arraycopy(data, off, body, 8, n)
            out.add(FrameCodec.encode(Frame(FrameType.FILE_CHUNK, seq, body, 0, txid)))
            off += n
            seq++
        }
        val end = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(txid).putInt(crc.toInt()).array()
        out.add(FrameCodec.encode(Frame(FrameType.FILE_END, seq, end, 0, txid)))
        return out
    }

    private fun transmit(payloads: List<ByteArray>, label: String) {
        stopReceiving()
        sendJob?.cancel()
        pauseRequested = false
        _state.update { it.copy(paused = false) }

        sendJob = viewModelScope.launch(Dispatchers.IO) {
            val profile = _state.value.profile
            val rate = engine.pickSampleRate(48000)
            _state.update {
                it.copy(sending = true, status = "Transmitting $label…", sampleRate = rate)
            }
            try {
                engine.withTrack(rate) { track ->
                    val mod = BfskModulator(profile, rate)
                    val lead = mod.idle(150)
                    track.write(lead, 0, lead.size)

                    for (index in payloads.indices) {
                        if (!isActive) break

                        val pcm = mod.render(payloads[index])
                        var written = 0
                        var announced = false

                        // Write in small blocks so a pause is felt within ~40 ms
                        // instead of at the end of the frame. A single text
                        // message is one frame, so a between-frames check would
                        // never fire in time to be useful.
                        while (written < pcm.size) {
                            if (pauseRequested) {
                                if (!announced) {
                                    _state.update {
                                        it.copy(status = "Paused at ${index + 1}/${payloads.size}")
                                    }
                                    announced = true
                                }
                                while (pauseRequested && isActive) delay(40)
                                if (!isActive) break
                                // Re-send this frame from the top: half a frame
                                // is useless to the receiver, so resuming
                                // rewinds rather than continues.
                                written = 0
                                announced = false
                                _state.update {
                                    it.copy(status = "Transmitting $label ${index + 1}/${payloads.size}…")
                                }
                            }
                            val n = track.write(pcm, written, minOf(BLOCK_SAMPLES, pcm.size - written))
                            if (n <= 0) break
                            written += n
                        }

                        if (!isActive) break
                        _state.update {
                            it.copy(status = "Transmitting $label ${index + 1}/${payloads.size}…")
                        }
                    }

                    val tail = mod.idle(120)
                    track.write(tail, 0, tail.size)
                }
                _state.update { it.copy(sending = false, paused = false, status = "Sent $label") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        sending = false,
                        paused = false,
                        status = "Transmit failed",
                        notice = e.message ?: "audio error"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- receive

    fun startReceiving() {
        if (_state.value.receiving) return
        sendJob?.cancel()
        _state.update { it.copy(sending = false, paused = false) }

        val profile = _state.value.profile
        val rate = engine.pickSampleRate(48000)
        val d = BfskDemodulator(profile, rate)
        d.reset()
        demod = d
        assembler = FrameAssembler()

        val ok = engine.startCapture(rate) { samples, count ->
            val bytes = d.feed(samples, count)
            var framesOk = _state.value.framesOk
            var framesBad = _state.value.framesBad
            if (bytes.isNotEmpty()) {
                val frames = assembler.feed(bytes)
                frames.forEach { handle(it) }
                framesOk = assembler.framesAccepted.toInt()
                framesBad = (assembler.badHeader + assembler.badCrc).toInt()
            }
            _state.update {
                it.copy(
                    signal = d.signalLevel,
                    bytes = it.bytes + bytes.size,
                    framesOk = framesOk,
                    framesBad = framesBad
                )
            }
        }

        if (!ok) {
            _state.update { it.copy(notice = "Could not open the microphone") }
            return
        }
        _state.update {
            it.copy(receiving = true, status = "Listening — ${profile.title}")
        }
    }

    fun stopReceiving() {
        if (!_state.value.receiving) return
        engine.stopCapture()
        demod = null
        _state.update { it.copy(receiving = false, signal = 0f, status = "Ready") }
    }

    private fun handle(frame: Frame) {
        files.add(frame)
        when (frame.type) {
            FrameType.TEXT -> addItem(ChatItem(itemIds.getAndIncrement(), Direction.IN, "TEXT", frame.text()))
            FrameType.COMMAND ->
                addItem(ChatItem(itemIds.getAndIncrement(), Direction.IN, "COMMAND", frame.text()))
            FrameType.HELLO ->
                addItem(ChatItem(itemIds.getAndIncrement(), Direction.IN, "HELLO", frame.text()))
            FrameType.FILE_META -> {
                val len = java.nio.ByteBuffer.wrap(frame.payload)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4)
                _state.update { it.copy(status = "Receiving file · $len bytes") }
            }
            FrameType.ACK ->
                addItem(ChatItem(itemIds.getAndIncrement(), Direction.IN, "ACK", "peer acknowledged txid ${frame.txid}"))
            FrameType.NACK ->
                addItem(ChatItem(itemIds.getAndIncrement(), Direction.IN, "NACK", "peer rejected txid ${frame.txid}"))
            else -> Unit
        }
    }

    // --------------------------------------------------------------- misc

    fun clear() = _state.update {
        it.copy(items = emptyList(), framesOk = 0, framesBad = 0, bytes = 0)
    }

    private fun addItem(item: ChatItem) = _state.update { it.copy(items = it.items + item) }

    private fun nextTxid(): Int {
        txCounter = (txCounter % 255) + 1
        return txCounter
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment ?: "file.bin"
    }

    private fun human(seconds: Double): String = when {
        seconds < 1 -> "under a second"
        seconds < 90 -> "%.0f s".format(seconds)
        else -> "%.1f min".format(seconds / 60.0)
    }

    override fun onCleared() {
        engine.stopCapture()
        sendJob?.cancel()
        super.onCleared()
    }
}
