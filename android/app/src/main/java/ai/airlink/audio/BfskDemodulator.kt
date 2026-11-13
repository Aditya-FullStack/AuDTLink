package ai.airlink.audio

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming 8N1 BFSK receiver.
 *
 * Two quadrature correlators run over a sliding one-bit window; their energy
 * difference `d` is the soft bit value (positive = mark, negative = space).
 * A byte starts at every falling edge of `d` (mark -> space, i.e. the start
 * bit), and the ten bits are then sampled at their centres. Because every byte
 * re-synchronises on its own start bit, a clock mismatch between the two
 * phones never accumulates past one character.
 *
 * The maths here is a line-for-line port of tools/airlink/modem.py, which is
 * covered by the loopback tests in tools/tests.
 */
class BfskDemodulator(
    private val profile: AudioProfile,
    private val sampleRate: Int
) {
    private val spb: Double = sampleRate / profile.baud
    private val nWin: Int = maxOf(8, Math.round(spb).toInt())
    private val off: Double = (nWin - 1) / 2.0

    private val ringIm = DoubleArray(nWin)
    private val ringQm = DoubleArray(nWin)
    private val ringIs = DoubleArray(nWin)
    private val ringQs = DoubleArray(nWin)
    private var ringPos = 0
    private var filled = 0

    private var sumIm = 0.0
    private var sumQm = 0.0
    private var sumIs = 0.0
    private var sumQs = 0.0

    private val dPhiM = TWO_PI * profile.markHz / sampleRate
    private val dPhiS = TWO_PI * profile.spaceHz / sampleRate
    private var phaseM = 0.0
    private var phaseS = 0.0

    private val histCap: Int = (11.0 * spb).toInt() + nWin + 32
    private val hist = FloatArray(histCap)
    private var histStart = 0
    private var histCount = 0
    private var histHead = 0L

    private var prevD = 0f
    private val pending = ArrayDeque<Long>()
    private var nextFree = -1.0

    private var levelEma = 0.0

    /** 0..1 meter value for the UI. */
    var signalLevel: Float = 0f
        private set
    var bytesDecoded: Long = 0L
        private set
    var framingErrors: Long = 0L
        private set

    fun reset() {
        ringIm.fill(0.0); ringQm.fill(0.0); ringIs.fill(0.0); ringQs.fill(0.0)
        ringPos = 0; filled = 0
        sumIm = 0.0; sumQm = 0.0; sumIs = 0.0; sumQs = 0.0
        phaseM = 0.0; phaseS = 0.0
        histStart = 0; histCount = 0; histHead = 0L
        prevD = 0f; pending.clear(); nextFree = -1.0
        levelEma = 0.0; signalLevel = 0f
        bytesDecoded = 0L; framingErrors = 0L
    }

    fun feed(samples: ShortArray, count: Int = samples.size): ByteArray {
        val out = ByteArrayOutputStream(64)
        val n = minOf(count, samples.size)
        for (i in 0 until n) {
            val x = samples[i] / 32768.0

            phaseM += dPhiM
            if (phaseM >= TWO_PI) phaseM -= TWO_PI
            phaseS += dPhiS
            if (phaseS >= TWO_PI) phaseS -= TWO_PI
            val iM = (phaseM * INV_TWO_PI * TABLE).toInt() and MASK
            val iS = (phaseS * INV_TWO_PI * TABLE).toInt() and MASK

            val pm = x * COS[iM]
            val qm = x * SIN[iM]
            val ps = x * COS[iS]
            val qs = x * SIN[iS]

            sumIm += pm - ringIm[ringPos]
            sumQm += qm - ringQm[ringPos]
            sumIs += ps - ringIs[ringPos]
            sumQs += qs - ringQs[ringPos]
            ringIm[ringPos] = pm
            ringQm[ringPos] = qm
            ringIs[ringPos] = ps
            ringQs[ringPos] = qs
            ringPos++
            if (ringPos == nWin) ringPos = 0

            if (filled < nWin) {
                filled++
                if (filled < nWin) continue
            }

            val d = (sumIm * sumIm + sumQm * sumQm) - (sumIs * sumIs + sumQs * sumQs)
            levelEma += (sqrt(kotlin.math.abs(d)) - levelEma) * 0.002
            pushD(d, out)
        }
        signalLevel = (levelEma / nWin).coerceIn(0.0, 1.0).toFloat()
        return out.toByteArray()
    }

    private fun pushD(dv: Double, out: ByteArrayOutputStream) {
        val d = dv.toFloat()
        hist[(histStart + histCount) % histCap] = d
        if (histCount < histCap) histCount++ else histStart = (histStart + 1) % histCap
        val abs = histHead
        histHead++

        if (prevD >= 0f && d < 0f && pending.size < 64) pending.addLast(abs)
        prevD = d

        while (pending.isNotEmpty()) {
            val e = pending.first()
            if (histHead < e + Math.round(9.5 * spb) + 3L) break
            pending.removeFirst()
            val byte = decodeAt(e)
            if (byte >= 0) {
                out.write(byte)
                bytesDecoded++
            } else {
                framingErrors++
            }
        }
    }

    /** Returns the byte, or -1 when this edge was not a real start bit. */
    private fun decodeAt(e: Long): Int {
        val start = e + off
        if (start < nextFree) return -1
        val v = DoubleArray(10)
        for (k in 0 until 10) {
            v[k] = histAt(e + (k + 0.5) * spb) ?: return -1
        }
        if (v[0] >= 0.0 || v[9] <= 0.0) return -1      // start must be space, stop must be mark
        var byte = 0
        for (k in 0 until 8) if (v[k + 1] > 0.0) byte = byte or (1 shl k)
        nextFree = start + 10 * spb - spb * 0.5
        return byte
    }

    private fun histAt(q: Double): Double? {
        val j = floor(q).toLong()
        val firstAbs = histHead - histCount
        if (j < firstAbs || j + 1 >= histHead) return null
        val f = q - j
        val a = hist[((histStart + (j - firstAbs).toInt()) % histCap)].toDouble()
        val b = hist[((histStart + (j + 1 - firstAbs).toInt()) % histCap)].toDouble()
        return a * (1.0 - f) + b * f
    }

    private companion object {
        const val TABLE = 4096
        const val MASK = TABLE - 1
        const val TWO_PI = 2.0 * PI
        const val INV_TWO_PI = 1.0 / TWO_PI
        val COS = FloatArray(TABLE) { cos(TWO_PI * it / TABLE).toFloat() }
        val SIN = FloatArray(TABLE) { sin(TWO_PI * it / TABLE).toFloat() }
    }
}
