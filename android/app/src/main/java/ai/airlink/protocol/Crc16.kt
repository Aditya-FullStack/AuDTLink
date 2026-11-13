package ai.airlink.protocol

/** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no xor-out. */
object Crc16 {
    private val TABLE = IntArray(256) { i ->
        var crc = i shl 8
        for (b in 0 until 8) {
            crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
            crc = crc and 0xFFFF
        }
        crc
    }

    fun of(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, init: Int = 0xFFFF): Int {
        var crc = init and 0xFFFF
        for (i in offset until offset + length) {
            val idx = ((crc shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor TABLE[idx]) and 0xFFFF
        }
        return crc
    }

    fun ofParts(vararg parts: ByteArray): Int {
        var crc = 0xFFFF
        for (p in parts) {
            for (b in p) {
                val idx = ((crc shr 8) xor (b.toInt() and 0xFF)) and 0xFF
                crc = ((crc shl 8) xor TABLE[idx]) and 0xFFFF
            }
        }
        return crc
    }
}
