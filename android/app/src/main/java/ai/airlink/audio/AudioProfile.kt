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
        chunkBytes = 512,
        minimodemRxArgs = listOf("1200", "-M", "1200", "-S", "2200")
    ),

    /**
     * Twice the speed. Bell 202's 1 kHz tone spacing collapses at 2400 baud,
     * so the tones move apart to 2.0/4.4 kHz — wider shift, wider bandwidth,
     * less tolerant of a muffled microphone.
     */
    FAST(
        id = "fast",
        title = "Audible fast",
        subtitle = "2400 baud · 2000/4400 Hz · ~240 B/s",
        baud = 2400.0,
        markHz = 2000.0,
        spaceHz = 4400.0,
        audible = true,
        beta = false,
        chunkBytes = 512,
        // -b widens minimodem's receive filter: its 200 Hz default is far
        // narrower than this mode's 2.4 kHz shift.
        minimodemRxArgs = listOf("2400", "-M", "2000", "-S", "4400", "-b", "3400")
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
        chunkBytes = 96,
        minimodemRxArgs = listOf("300", "-M", "18500", "-S", "19500", "-b", "1400")
    );

    /** 8 data bits + start + stop, so 10 bits on the wire per byte. */
    val bytesPerSecond: Double get() = baud / 10.0

    /** Transmit needs no receive filter, so the -b tail is dropped. */
    val minimodemTxArgs: List<String> get() = minimodemRxArgs.take(5)

    fun minimodemRxCommand(): String = "minimodem --rx -q " + minimodemRxArgs.joinToString(" ")

    fun minimodemTxCommand(): String = "minimodem --tx -q " + minimodemTxArgs.joinToString(" ")

    /** One-line summary of what a decoder on the other end must be told. */
    fun wireSummary(): String =
        "%s · %.0f baud · mark %.0f Hz · space %.0f Hz · 8N1".format(
            title, baud, markHz, spaceHz
        )

    companion object {
        fun fromId(id: String?): AudioProfile = entries.firstOrNull { it.id == id } ?: AUDIBLE
    }
}
