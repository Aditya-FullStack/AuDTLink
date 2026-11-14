package ai.airlink.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Proprietary 4-FSK modem — app to app only, NOT minimodem compatible.
 *
 * Four tones, two bits per symbol, gray coded, at 2400 baud. Twice the
 * throughput of the 2-FSK mode for the same symbol rate, inside the passband a
 * phone speaker and microphone can actually carry.
 *
 *     tone 0  1300 Hz -> 00
 *     tone 1  2700 Hz -> 01
 *     tone 2  4100 Hz -> 11
 *     tone 3  5500 Hz -> 10
 *
 * There are no start/stop bits to re-sync a byte on, so a transmission opens
 * with an alternating-symbol preamble that gives the receiver a clock, then a
 * constant-tone sync word marks the payload. Timing is tracked per block of
 * symbols by searching the sample offset that makes each block sharpest — a
 * constant tone gives a flat correlator within a symbol, so a sample-by-sample
 * PLL has almost nothing to steer on.
 *
 * The algorithm is a port of tools/airlink/mfsk.py, which is covered by
 * tools/tests/test_mfsk.py.
 */
object Mfsk4 {
    val TONES = doubleArrayOf(1300.0, 2700.0, 4100.0, 5500.0)
    const val BAUD = 2400.0
    const val PREAMBLE_SYMS = 40
    val SYNC_SYMS = intArrayOf(3, 3, 3, 3)
    const val TRAILER_SYMS = 8
    const val BLOCK = 16

    /** symbol index -> the two data bits it carries */
    val SYM_BITS = intArrayOf(0b00, 0b01, 0b11, 0b10)

    /** two-bit value -> symbol index (inverse of [SYM_BITS]) */
    val BITS_SYM = intArrayOf(0, 1, 3, 2)
}

class Mfsk4Modulator(
    private val sampleRate: Int,
    private val amplitude: Float = 0.72f
) {
    private var phase = 0.0
    private val dphi = 2.0 * PI / sampleRate

    fun render(bytes: ByteArray): ShortArray {
        val syms = IntArray(Mfsk4.PREAMBLE_SYMS + Mfsk4.SYNC_SYMS.size +
            ((bytes.size * 8 + 1) / 2) + Mfsk4.TRAILER_SYMS)
        var k = 0
        for (i in 0 until Mfsk4.PREAMBLE_SYMS) syms[k++] = i % 2
        for (s in Mfsk4.SYNC_SYMS) syms[k++] = s

        // LSB-first bits, two per symbol
        var bitIdx = 0
        var acc = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            for (i in 0 until 8) {
                val bit = (v shr i) and 1
                if (bitIdx % 2 == 0) {
                    acc = bit shl 1
                } else {
                    acc = acc or bit
                    syms[k++] = Mfsk4.BITS_SYM[acc]
                }
                bitIdx++
            }
        }
        if (bitIdx % 2 == 1) syms[k++] = Mfsk4.BITS_SYM[acc]
        while (k < syms.size) syms[k++] = 0

        val spb = sampleRate / Mfsk4.BAUD
        val n = (syms.size * spb).roundToInt()
        val out = ShortArray(n)
        val gain = amplitude * 32767.0
        for (i in 0 until n) {
            var si = (i / spb).toInt()
            if (si >= syms.size) si = syms.size - 1
            phase += dphi * Mfsk4.TONES[syms[si]]
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            out[i] = (sin(phase) * gain).toInt().toShort()
        }
        return out
    }

    /** Digital silence — the gap that lets the receiver re-acquire per frame. */
    fun silence(ms: Int): ShortArray = ShortArray(sampleRate * ms / 1000)

    /** Idle tone, used as a lead-in and to mark the end of a burst. */
    fun idle(ms: Int): ShortArray {
        val n = sampleRate * ms / 1000
        val out = ShortArray(n)
        val gain = amplitude * 32767.0
        for (i in 0 until n) {
            phase += dphi * Mfsk4.TONES[0]
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            out[i] = (sin(phase) * gain).toInt().toShort()
        }
        return out
    }
}

/**
 * Batch receiver: collects a transmission, then decodes it once the carrier
 * stops. Simpler and more robust than a sample-at-a-time state machine, and
 * the latency (one burst) is irrelevant for file transfer.
 */
class Mfsk4Demodulator(private val sampleRate: Int) {

    private val spb = sampleRate / Mfsk4.BAUD
    private val nWin = spb.roundToInt()

    private var buf = FloatArray(sampleRate * 4)
    private var len = 0

    private var quietSamples = 0
    private var peak = 0.0

    var framesDecoded: Long = 0L
        private set

    fun reset() {
        len = 0
        quietSamples = 0
        peak = 0.0
        framesDecoded = 0
    }

    /** Returns decoded bytes once a burst ends, otherwise an empty array. */
    fun feed(samples: ShortArray, count: Int = samples.size): ByteArray {
        val n = minOf(count, samples.size)
        if (len + n > buf.size) {
            var cap = buf.size
            while (cap < len + n) cap *= 2
            buf = buf.copyOf(cap)
        }
        var energy = 0.0
        for (i in 0 until n) {
            val s = samples[i] / 32768.0
            buf[len + i] = s.toFloat()
            energy += s * s
        }
        len += n
        energy /= n

        if (energy > peak) peak = energy
        val floor = peak * 0.05
        if (energy < floor) {
            quietSamples += n
        } else {
            quietSamples = 0
        }

        // end of burst: quiet for ~250 ms, or a buffer that has grown huge
        val quietLimit = sampleRate / 4
        if (len > sampleRate / 2 && (quietSamples > quietLimit || len > sampleRate * 8)) {
            val out = decode()
            len = 0
            quietSamples = 0
            peak = 0.0
            if (out.isNotEmpty()) framesDecoded++
            return out
        }
        return ByteArray(0)
    }

    /**
     * Decoding continuously across a frame boundary makes the block timing
     * search slip a symbol, which corrupts the next frame's sync word. Frames
     * are sent as separate bursts with a silence gap, so each one is decoded
     * independently and re-acquires its own clock.
     */
    private fun decode(): ByteArray {
        val out = java.io.ByteArrayOutputStream(1024)
        for (b in splitBursts()) {
            val seg = FloatArray(b.second - b.first) { buf[b.first + it] }
            val decoded = decodeOne(seg)
            if (decoded.isNotEmpty()) out.write(decoded)
        }
        return out.toByteArray()
    }

    private fun splitBursts(): List<Pair<Int, Int>> {
        if (len < nWin * 4) return emptyList()
        val k = maxOf(1, sampleRate / 1000)
        val env = FloatArray(len)
        var acc = 0f
        for (i in 0 until len) {
            acc += kotlin.math.abs(buf[i])
            if (i >= k) acc -= kotlin.math.abs(buf[i - k])
            env[i] = acc / k
        }
        val sample = ArrayList<Float>(len / 64 + 1)
        var i = 0
        while (i < len) { sample.add(env[i]); i += 64 }
        sample.sort()
        val lo = sample[(sample.size * 0.10).toInt().coerceIn(0, sample.size - 1)]
        val hi = sample[(sample.size * 0.95).toInt().coerceIn(0, sample.size - 1)]
        val thr = lo + 0.25f * (hi - lo)

        val minGap = sampleRate * 10 / 1000
        val minBurst = sampleRate / 50
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        var run = 0
        for (j in 0 until len) {
            if (env[j] < thr) {
                run++
                if (start >= 0 && run >= minGap) {
                    val end = j - run
                    if (end - start > minBurst) out.add(start to end)
                    start = -1
                }
            } else {
                if (start < 0) start = j
                run = 0
            }
        }
        if (start >= 0 && len - start > minBurst) out.add(start to len)
        return out
    }

    private fun decodeOne(x: FloatArray): ByteArray {
        val n = x.size
        if (n < 4 * nWin) return ByteArray(0)

        // per-sample energy of each tone over a one-symbol window
        val E = Array(Mfsk4.TONES.size) { FloatArray(n) }
        for (k in Mfsk4.TONES.indices) {
            val ci = DoubleArray(nWin)
            val cq = DoubleArray(nWin)
            var sr = 0.0
            var si = 0.0
            var pos = 0
            var phase = 0.0
            val dphi = 2.0 * PI * Mfsk4.TONES[k] / sampleRate
            for (i in 0 until n) {
                phase += dphi
                if (phase > 2.0 * PI) phase -= 2.0 * PI
                val pi = x[i] * cos(phase)
                val pq = x[i] * sin(phase)
                sr += pi - ci[pos]
                si += pq - cq[pos]
                ci[pos] = pi
                cq[pos] = pq
                pos++
                if (pos == nWin) pos = 0
                E[k][i] = if (i >= nWin - 1) (sr * sr + si * si).toFloat() else 0f
            }
        }

        // acquisition: the symbol offset that makes the preamble sharpest
        val win = (Mfsk4.PREAMBLE_SYMS * spb).toInt()
        if (n < win + spb) return ByteArray(0)
        var bestOff = 0.0
        var bestScore = -1.0
        var off = 0.0
        while (off < spb) {
            var score = 0.0
            for (j in 0 until Mfsk4.PREAMBLE_SYMS) {
                val i = (off + j * spb).toInt()
                if (i >= n) break
                var mx = 0f
                for (k in 0 until 4) if (E[k][i] > mx) mx = E[k][i]
                score += mx
            }
            score /= Mfsk4.PREAMBLE_SYMS
            if (score > bestScore) {
                bestScore = score
                bestOff = off
            }
            off += 0.5
        }

        // block-wise timing search
        val syms = ArrayList<Int>(n / nWin)
        var base = bestOff
        var cur = bestOff.roundToInt()
        val limit = n - 2
        val floor = 0.05f * bestScore.toFloat()

        while (true) {
            var bestO = Int.MIN_VALUE
            var bestS = -1.0f
            var bestIdx: IntArray? = null
            for (o in (cur - 2)..(cur + 2)) {
                val idx = ArrayList<Int>(Mfsk4.BLOCK)
                for (j in 0 until Mfsk4.BLOCK) {
                    val i = (base + o + j * spb).toInt()
                    if (i >= limit) break
                    idx.add(i)
                }
                if (idx.size < 2) continue
                var s = 0f
                for (i in idx) {
                    var mx = 0f
                    for (k in 0 until 4) if (E[k][i] > mx) mx = E[k][i]
                    s += mx
                }
                s /= idx.size
                if (s > bestS) {
                    bestS = s
                    bestO = o
                    bestIdx = idx.toIntArray()
                }
            }
            val idx = bestIdx
            if (idx == null || bestS < floor) break
            for (i in idx) {
                var bestK = 0
                var mx = E[0][i]
                for (k in 1 until 4) if (E[k][i] > mx) { mx = E[k][i]; bestK = k }
                syms.add(bestK)
            }
            cur = bestO
            base += Mfsk4.BLOCK * spb
            if (base + cur >= limit) break
        }

        // strip preamble, find the sync word, decode the payload
        var start = -1
        for (i in 0..(syms.size - Mfsk4.SYNC_SYMS.size)) {
            var ok = true
            for (j in Mfsk4.SYNC_SYMS.indices) {
                if (syms[i + j] != Mfsk4.SYNC_SYMS[j]) { ok = false; break }
            }
            if (ok) { start = i + Mfsk4.SYNC_SYMS.size; break }
        }
        if (start < 0) return ByteArray(0)

        val bits = ArrayList<Int>(syms.size * 2)
        for (i in start until syms.size) {
            val v = Mfsk4.SYM_BITS[syms[i]]
            bits.add((v shr 1) and 1)
            bits.add(v and 1)
        }
        val out = ByteArray(bits.size / 8)
        for (b in out.indices) {
            var v = 0
            for (k in 0 until 8) v = v or ((bits[b * 8 + k] and 1) shl k)
            out[b] = v.toByte()
        }
        return out
    }
}
