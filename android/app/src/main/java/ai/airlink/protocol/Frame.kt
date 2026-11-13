package ai.airlink.protocol

import java.nio.charset.StandardCharsets

/** Airlink frame types — see docs/PROTOCOL.md. */
object FrameType {
    const val HELLO = 0x01
    const val TEXT = 0x10
    const val COMMAND = 0x11
    const val FILE_META = 0x20
    const val FILE_CHUNK = 0x21
    const val FILE_END = 0x22
    const val ACK = 0x30
    const val NACK = 0x31

    fun name(t: Int): String = when (t) {
        HELLO -> "HELLO"
        TEXT -> "TEXT"
        COMMAND -> "COMMAND"
        FILE_META -> "FILE_META"
        FILE_CHUNK -> "FILE_CHUNK"
        FILE_END -> "FILE_END"
        ACK -> "ACK"
        NACK -> "NACK"
        else -> "0x%02X".format(t)
    }
}

data class Frame(
    val type: Int,
    val seq: Int = 0,
    val payload: ByteArray = ByteArray(0),
    val flags: Int = 0,
    val txid: Int = 0
) {
    val typeName: String get() = FrameType.name(type)

    fun text(): String = String(payload, StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is Frame && other.type == type && other.seq == seq && other.txid == txid &&
            other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * type + 17 * seq + payload.contentHashCode()
}
