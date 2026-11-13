package ai.airlink.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin AudioTrack / AudioRecord wrapper.
 *
 * Playback asks for USAGE_MEDIA + CONTENT_TYPE_SPEECH so the system does not
 * apply voice-call processing, and capture prefers UNPROCESSED so automatic
 * gain control and noise suppression cannot chew up the tones.
 */
class AudioEngine {

    private var record: AudioRecord? = null
    private var scope: CoroutineScope? = null
    private var captureJob: Job? = null

    @Volatile
    private var capturing = false

    val isCapturing: Boolean get() = capturing

    fun pickSampleRate(preferred: Int): Int {
        val candidates = intArrayOf(preferred, 48000, 44100)
        for (r in candidates) {
            val min = AudioRecord.getMinBufferSize(
                r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (min > 0) return r
        }
        return 44100
    }

    /** Runs [block] with an open, already-playing AudioTrack. */
    fun <T> withTrack(rate: Int, block: (AudioTrack) -> T): T {
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf.coerceAtLeast(0) * 2, rate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            return block(track)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(rate: Int, onBlock: (ShortArray, Int) -> Unit): Boolean {
        stopCapture()
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val sources = intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        var rec: AudioRecord? = null
        for (src in sources) {
            val r = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 4, 16384))
                    .build()
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r
                break
            }
            r?.release()
        }
        val active = rec ?: return false

        record = active
        capturing = true
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        captureJob = s.launch {
            active.startRecording()
            val buf = ShortArray(2048)
            try {
                while (isActive && capturing) {
                    val n = active.read(buf, 0, buf.size)
                    if (n > 0) onBlock(buf, n)
                }
            } finally {
                runCatching { active.stop() }
            }
        }
        return true
    }

    fun stopCapture() {
        capturing = false
        captureJob?.cancel()
        captureJob = null
        scope?.cancel()
        scope = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }
}
