package ai.airlink.audio

/**
 * The three ways Airlink can talk.
 *
 * All of them are plain 8N1 BFSK, which is exactly what `minimodem -8` speaks,
 * so a Linux box can always be the other end. There is no frequency fiddling
 * in the app on purpose — pick a mode, both phones agree, done.
 */
enum class AudioProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val baud: Double,
    val markHz: Double,
    val spaceHz: Double,
    val audible: Boolean,
    val beta: Boolean,
    /** True when the mode is app-to-app only and minimodem cannot decode it. */
    val proprietary: Boolean,
    val chunkBytes: Int,
    val minimodemRxArgs: List<String>
) {
    /** Bell 202. The classic dial-up tone: audible, tolerant, ~120 B/s. */
    AUDIBLE(
        id = "audible",
        title = "Audible",
        subtitle = "Bell 202 · 1200 baud · ~120 B/s",
        baud = 1200.0,
        markHz = 1200.0,
        spaceHz = 2200.0,
        audible = true,
        beta = false,
        proprietary = false,
        chunkBytes = 512,
        minimodemRxArgs = listOf("1200", "-M", "1200", "-S", "2200")
    ),

    /**
     * 4-FSK, app to app only. Two bits per symbol at the same 2400 baud the
     * old 2-FSK mode used, so twice the throughput — and no minimodem
     * compatibility, because minimodem has no 4-FSK mode to speak of.
     */
    FAST(
        id = "fast",
        title = "Fast",
        subtitle = "4-FSK · 2400 baud · ~480 B/s · app to app",
        baud = 2400.0,
        markHz = 1300.0,
        spaceHz = 5500.0,
        audible = true,
        beta = false,
        proprietary = true,
        chunkBytes = 512,
        minimodemRxArgs = emptyList()
    ),

    /** 18.5/19.5 kHz. Silent to most adults; short range, slower. */
    ULTRASOUND(
        id = "ultrasound",
        title = "Ultrasound",
        subtitle = "18.5/19.5 kHz · 300 baud · ~30 B/s",
        baud = 300.0,
        markHz = 18500.0,
        spaceHz = 19500.0,
        audible = false,
        beta = true,
        proprietary = false,
        chunkBytes = 96,
        minimodemRxArgs = listOf("300", "-M", "18500", "-S", "19500", "-b", "1400")
    );

    /** 8 data bits + start + stop, so 10 bits on the wire per byte. */
    val bytesPerSecond: Double get() = baud / 10.0

    /** Transmit needs no receive filter, so the -b tail is dropped. */
    val minimodemTxArgs: List<String> get() = minimodemRxArgs.take(5)

    fun minimodemRxCommand(): String =
        if (proprietary) "not minimodem compatible — app to app only"
        else "minimodem --rx -q " + minimodemRxArgs.joinToString(" ")

    fun minimodemTxCommand(): String =
        if (proprietary) "install AuDTLink on the other end"
        else "minimodem --tx -q " + minimodemTxArgs.joinToString(" ")

    /** One-line summary of what a decoder on the other end must be told. */
    fun wireSummary(): String =
        if (proprietary) "%s · 4-FSK · 1300/2700/4100/5500 Hz · 2400 baud · 2 bits/symbol".format(title)
        else "%s · %.0f baud · mark %.0f Hz · space %.0f Hz · 8N1".format(title, baud, markHz, spaceHz)

    companion object {
        fun fromId(id: String?): AudioProfile = entries.firstOrNull { it.id == id } ?: AUDIBLE
    }
}
