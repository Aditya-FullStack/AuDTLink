package ai.airlink.audio

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns bytes into 8N1 BFSK PCM.
 *
 * The oscillator phase is carried across bits and across calls to [render], so
 * there is never a discontinuity inside a message — clicks are what you get
 * when you restart the tone at every bit, and clicks cost you decoder sync.
 */
class BfskModulator(
    private val profile: AudioProfile,
    private val sampleRate: Int,
    private val amplitude: Float = 0.72f
) {
    private var phase = 0.0
    private val dphi = 2.0 * PI / sampleRate

    fun render(bytes: ByteArray, leaderBits: Int = 40, trailerBits: Int = 40): ShortArray {
        val totalBits = leaderBits + 10 * bytes.size + trailerBits
        val bits = ByteArray(totalBits) { 1.toByte() }   // line idles at mark
        var k = leaderBits
        for (b in bytes) {
            bits[k] = 0                                 // start bit = space
            val v = b.toInt()
            for (i in 0 until 8) {
                bits[k + 1 + i] = ((v shr i) and 1).toByte()   // LSB first
            }
            bits[k + 9] = 1                             // stop bit = mark
            k += 10
        }

        val spb = sampleRate / profile.baud
        val n = (totalBits * spb).roundToInt()
        val out = ShortArray(n)
        val gain = amplitude * 32767.0
        for (i in 0 until n) {
            var bi = (i / spb).toInt()
            if (bi >= totalBits) bi = totalBits - 1
            val f = if (bits[bi].toInt() == 1) profile.markHz else profile.spaceHz
            phase += dphi * f
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            out[i] = (sin(phase) * gain).toInt().toShort()
        }
        return out
    }

    /** Pure mark tone — used as a lead-in / keep-alive. */
    fun idle(ms: Int): ShortArray {
        val n = sampleRate * ms / 1000
        val out = ShortArray(n)
        val gain = amplitude * 32767.0
        for (i in 0 until n) {
            phase += dphi * profile.markHz
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            out[i] = (sin(phase) * gain).toInt().toShort()
        }
        return out
    }
}
