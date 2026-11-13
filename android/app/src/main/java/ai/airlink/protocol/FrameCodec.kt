package ai.airlink.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frame <-> bytes.
 *
 *   PREAMBLE 16 x 0x55 | SYNC D3 91 | HEADER 12 | PAYLOAD | CRC16 2
 */
object FrameCodec {
    val PREAMBLE = ByteArray(16) { 0x55.toByte() }
    val SYNC = byteArrayOf(0xD3.toByte(), 0x91.toByte())
    const val MAGIC_0 = 0xA7.toByte()
    const val MAGIC_1 = 0x1C.toByte()
    const val VERSION = 0x01
    const val HEADER_LEN = 12
    const val MAX_PAYLOAD = 4096

    fun encode(frame: Frame): ByteArray {
        require(frame.payload.size <= MAX_PAYLOAD) { "payload too large" }
        val body = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC_0)
            put(MAGIC_1)
            put(VERSION)
            put(frame.type.toByte())
            putShort(frame.seq.toShort())
            putShort(frame.payload.size.toShort())
            put(frame.flags.toByte())
            put(frame.txid.toByte())
        }.array()

        val out = ByteArray(PREAMBLE.size + SYNC.size + HEADER_LEN + frame.payload.size + 2)
        var p = 0
        System.arraycopy(PREAMBLE, 0, out, p, PREAMBLE.size); p += PREAMBLE.size
        System.arraycopy(SYNC, 0, out, p, SYNC.size); p += SYNC.size
        System.arraycopy(body, 0, out, p, 10); p += 10
        val hcrc = Crc16.of(body)
        out[p++] = (hcrc and 0xFF).toByte()
        out[p++] = ((hcrc shr 8) and 0xFF).toByte()
        System.arraycopy(frame.payload, 0, out, p, frame.payload.size); p += frame.payload.size
        val crc = Crc16.ofParts(body, frame.payload)
        out[p++] = (crc and 0xFF).toByte()
        out[p] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    fun textFrame(text: String, seq: Int = 0, txid: Int = 0): Frame =
        Frame(FrameType.TEXT, seq, text.toByteArray(Charsets.UTF_8), 0, txid)

    fun commandFrame(cmd: String, seq: Int = 0, txid: Int = 0): Frame =
        Frame(FrameType.COMMAND, seq, cmd.toByteArray(Charsets.UTF_8), 0, txid)
}
