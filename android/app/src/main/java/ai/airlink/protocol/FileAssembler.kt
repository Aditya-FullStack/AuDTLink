package ai.airlink.protocol

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Puts a FILE_META / FILE_CHUNK* / FILE_END run back together on disk.
 * A file is only written once its declared CRC32 matches, so a half-received
 * transfer never appears in the inbox.
 */
class FileAssembler(private val dir: File) {

    /**
     * UDP-style delivery. When true, a transfer that loses chunks is still
     * written out — what arrived is kept, the gaps are left as zeros — instead
     * of being thrown away because the CRC no longer matches. Nothing is
     * retransmitted either way; this only changes what happens on loss.
     */
    var bestEffort: Boolean = false

    data class Saved(
        val name: String,
        val file: File,
        val bytes: Long,
        val total: Long = 0L,
        val partial: Boolean = false
    )

    private class Xfer(val txid: Int, val name: String, val size: Int, val crc: Long) {
        val buf = ByteArray(size)
        var received = 0
        var touched = System.currentTimeMillis()
    }

    private val active = HashMap<Int, Xfer>()

    var onSaved: ((Saved) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    fun add(frame: Frame): Saved? {
        when (frame.type) {
            FrameType.FILE_META -> {
                val meta = parseMeta(frame.payload) ?: run {
                    onFailed?.invoke("malformed FILE_META")
                    return null
                }
                if (meta.size > MAX_BYTES) {
                    onFailed?.invoke("${meta.name}: ${meta.size} bytes is over the ${MAX_BYTES / (1024 * 1024)} MB limit")
                    return null
                }
                active[meta.txid] = Xfer(meta.txid, meta.name, meta.size, meta.crc)
            }

            FrameType.FILE_CHUNK -> {
                if (frame.payload.size < 8) return null
                val bb = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                val txid = bb.getInt(0)
                val off = bb.getInt(4)
                val x = active[txid] ?: return null
                val end = minOf(off + (frame.payload.size - 8), x.size)
                if (off in 0 until x.size && end > off) {
                    System.arraycopy(frame.payload, 8, x.buf, off, end - off)
                    x.received += end - off
                    x.touched = System.currentTimeMillis()
                }
            }

            FrameType.FILE_END -> {
                if (frame.payload.size < 8) return null
                val bb = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                val x = active.remove(bb.getInt(0)) ?: return null
                val want = bb.getInt(4).toLong() and 0xFFFFFFFFL
                val crc = CRC32().apply { update(x.buf) }.value
                val intact = crc == want && x.received == x.size

                if (!intact && !bestEffort) {
                    onFailed?.invoke(
                        "%s: incomplete (%d/%d bytes)".format(x.name, x.received, x.size)
                    )
                    return null
                }

                dir.mkdirs()
                val file = uniqueFile(dir, if (intact) x.name else x.name + ".partial")
                file.writeBytes(x.buf)
                val saved = Saved(
                    name = file.name,
                    file = file,
                    bytes = if (intact) x.size.toLong() else x.received.toLong(),
                    total = x.size.toLong(),
                    partial = !intact
                )
                onSaved?.invoke(saved)
                return saved
            }
        }
        return null
    }

    fun cancelAll() = active.clear()

    private data class Meta(val txid: Int, val name: String, val size: Int, val chunk: Int, val crc: Long)

    private fun parseMeta(payload: ByteArray): Meta? {
        if (payload.size < 12) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val txid = bb.getInt(0)
        val size = bb.getInt(4)
        val chunk = bb.getShort(8).toInt() and 0xFFFF
        val nameLen = bb.getShort(10).toInt() and 0xFFFF
        if (size < 0 || 12 + nameLen + 4 > payload.size) return null
        val name = String(payload, 12, nameLen, Charsets.UTF_8)
        val crc = bb.getInt(12 + nameLen).toLong() and 0xFFFFFFFFL
        return Meta(txid, sanitize(name), size, chunk, crc)
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024

        fun sanitize(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val clean = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(120)
            return clean.ifEmpty { "received.bin" }
        }

        fun uniqueFile(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 1
            while (f.exists()) {
                f = File(dir, "$stem-$i$ext")
                i++
            }
            return f
        }
    }
}
