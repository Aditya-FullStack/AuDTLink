package ai.airlink.audio

/**
 * One on-air configuration.
 *
 * All three profiles are plain 8N1 BFSK, which is exactly what
 * `minimodem -8` speaks, so a Linux box can always be the other end.
 *
 *   audible   Bell 202 — 1200 baud, 1200/2200 Hz. The classic.
 *   audible   Bell 103 — 300 baud, 1270/1070 Hz. Slower, but it survives
 *             speakers that mangle anything above 3 kHz.
 *   silent    300 baud, 18.5/19.5 kHz. Inaudible to most adults; keep the
 *             phones within ~30 cm because ultrasonic output rolls off fast.
 */
enum class AudioProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val baud: Double,
    val markHz: Double,
    val spaceHz: Double,
    val audible: Boolean,
    val chunkBytes: Int,
    val minimodemRxArgs: List<String>
) {
    BELL202(
        id = "bell202",
        title = "Audible",
        subtitle = "Bell 202 · 1200 baud · 1200/2200 Hz",
        baud = 1200.0,
        markHz = 1200.0,
        spaceHz = 2200.0,
        audible = true,
        chunkBytes = 512,
        minimodemRxArgs = listOf("1200", "-M", "1200", "-S", "2200")
    ),
    BELL103(
        id = "bell103",
        title = "Audible · slow",
        subtitle = "Bell 103 · 300 baud · 1270/1070 Hz",
        baud = 300.0,
        markHz = 1270.0,
        spaceHz = 1070.0,
        audible = true,
        chunkBytes = 128,
        minimodemRxArgs = listOf("300", "-M", "1270", "-S", "1070")
    ),
    ULTRASONIC(
        id = "ultrasonic",
        title = "Ultrasonic",
        subtitle = "300 baud · 18.5/19.5 kHz · near-silent",
        baud = 300.0,
        markHz = 18500.0,
        spaceHz = 19500.0,
        audible = false,
        chunkBytes = 96,
        // -b widens minimodem's receive filter: its 50 Hz default is narrower
        // than the 1 kHz shift this profile uses.
        minimodemRxArgs = listOf("300", "-M", "18500", "-S", "19500", "-b", "1400")
    );

    /** 8 data bits + start + stop, at 10 bits per byte on the wire. */
    val bytesPerSecond: Double get() = baud / 10.0

    fun minimodemRxCommand(): String = "minimodem --rx -q " + minimodemRxArgs.joinToString(" ")

    fun minimodemTxCommand(): String = "minimodem --tx -q " + minimodemRxArgs.take(6).joinToString(" ")

    companion object {
        fun fromId(id: String?): AudioProfile = entries.firstOrNull { it.id == id } ?: BELL202
    }
}
