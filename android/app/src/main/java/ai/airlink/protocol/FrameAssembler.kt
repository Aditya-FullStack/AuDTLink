package ai.airlink.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte stream in, validated frames out.
 *
 * Anything that fails magic, version, header CRC, length sanity or payload CRC
 * is dropped and the scanner moves one byte on, so a receiver that joins a
 * transmission mid-frame picks up at the next one.
 */
class FrameAssembler(private val maxPayload: Int = FrameCodec.MAX_PAYLOAD) {

    private var buf = ByteArray(8192)
    private var size = 0

    var framesAccepted: Long = 0L; private set
    var badHeader: Long = 0L; private set
    var badCrc: Long = 0L; private set

    fun reset() {
        size = 0
        framesAccepted = 0
        badHeader = 0
        badCrc = 0
    }

    fun feed(data: ByteArray, count: Int = data.size): List<Frame> {
        append(data, count)
        val out = ArrayList<Frame>(2)
        var pos = 0
        while (true) {
            val syncAt = indexOfSync(pos)
            if (syncAt < 0) {
                pos = maxOf(pos, size - (FrameCodec.PREAMBLE.size + FrameCodec.SYNC.size - 1))
                break
            }
            val start = syncAt - FrameCodec.PREAMBLE.size
            if (start < 0 || !hasPreamble(start, syncAt)) {
                pos = syncAt + 1
                continue
            }
            val bodyAt = syncAt + 2
            if (size < bodyAt + FrameCodec.HEADER_LEN) { pos = maxOf(start, pos); break }

            if (buf[bodyAt] != FrameCodec.MAGIC_0 ||
                buf[bodyAt + 1] != FrameCodec.MAGIC_1 ||
                buf[bodyAt + 2].toInt() != FrameCodec.VERSION
            ) {
                badHeader++
                pos = syncAt + 1
                continue
            }
            val bb = ByteBuffer.wrap(buf, bodyAt, 10).order(ByteOrder.LITTLE_ENDIAN)
            val type = buf[bodyAt + 3].toInt() and 0xFF
            val seq = bb.getShort(bodyAt + 4).toInt() and 0xFFFF
            val plen = bb.getShort(bodyAt + 6).toInt() and 0xFFFF
            val flags = buf[bodyAt + 8].toInt() and 0xFF
            val txid = buf[bodyAt + 9].toInt() and 0xFF

            if (plen > maxPayload) { badHeader++; pos = syncAt + 1; continue }

            val hcrc = (buf[bodyAt + 10].toInt() and 0xFF) or ((buf[bodyAt + 11].toInt() and 0xFF) shl 8)
            if (hcrc != Crc16.of(buf, bodyAt, 10)) { badHeader++; pos = syncAt + 1; continue }

            val end = bodyAt + FrameCodec.HEADER_LEN + plen + 2
            if (size < end) { pos = maxOf(start, pos); break }

            val payload = buf.copyOfRange(bodyAt + 12, bodyAt + 12 + plen)
            val crc = (buf[bodyAt + 12 + plen].toInt() and 0xFF) or
                ((buf[bodyAt + 13 + plen].toInt() and 0xFF) shl 8)
            val full = Crc16.ofParts(buf.copyOfRange(bodyAt, bodyAt + 10), payload)
            if (crc != full) { badCrc++; pos = syncAt + 1; continue }

            out.add(Frame(type, seq, payload, flags, txid))
            framesAccepted++
            pos = end
        }
        compact(pos)
        return out
    }

    private fun append(data: ByteArray, count: Int) {
        val n = minOf(count, data.size)
        if (size + n > buf.size) {
            var cap = buf.size
            while (cap < size + n) cap *= 2
            buf = buf.copyOf(cap)
        }
        System.arraycopy(data, 0, buf, size, n)
        size += n
    }

    private fun indexOfSync(from: Int): Int {
        var i = maxOf(from, 0)
        while (i + 1 < size) {
            if (buf[i] == FrameCodec.SYNC[0] && buf[i + 1] == FrameCodec.SYNC[1]) return i
            i++
        }
        return -1
    }

    private fun hasPreamble(start: Int, end: Int): Boolean {
        for (i in start until end) if (buf[i] != 0x55.toByte()) return false
        return true
    }

    private fun compact(pos: Int) {
        if (pos <= 0) return
        val keep = size - pos
        if (keep > 0) System.arraycopy(buf, pos, buf, 0, keep)
        size = keep
    }
}
