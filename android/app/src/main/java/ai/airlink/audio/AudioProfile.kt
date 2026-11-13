package ai.airlink.audio

/**
 * The two ways Airlink can talk.
 *
 * Both are plain 8N1 BFSK, which is exactly what `minimodem -8` speaks, so a
 * Linux box can always be the other end. There is no frequency fiddling in
 * the app on purpose — pick a mode, both phones agree, done.
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
    /** Bell 202. The classic dial-up modem tone — fastest, and audible. */
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
        // -b widens minimodem's receive filter: its 50 Hz default is narrower
        // than the 1 kHz shift this mode uses.
        minimodemRxArgs = listOf("300", "-M", "18500", "-S", "19500", "-b", "1400")
    );

    /** 8 data bits + start + stop, so 10 bits on the wire per byte. */
    val bytesPerSecond: Double get() = baud / 10.0

    fun minimodemRxCommand(): String = "minimodem --rx -q " + minimodemRxArgs.joinToString(" ")

    companion object {
        fun fromId(id: String?): AudioProfile = entries.firstOrNull { it.id == id } ?: AUDIBLE
    }
}
