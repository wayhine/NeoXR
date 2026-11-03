package com.neoxr.player

import java.io.Closeable
import java.io.IOException

internal interface NetworkRangeReader : Closeable {
    val size: Long
    fun readAt(position: Long, length: Int, data: ByteArray): Int
}

/** Shared preview limits with SMB: bounded read-ahead, two readers, no video download. */
internal class WebDavRangeReader(private val url: String, knownSize: Long,
    private val budget: Long = 64L * 1024 * 1024) : NetworkRangeReader {
    override val size = knownSize.takeIf { it > 0 } ?: WebDav.length(url)
    private val buffer = ByteArray(256 * 1024)
    private var start = -1L
    private var filled = 0
    private var transferred = 0L
    private var closed = false

    @Synchronized override fun readAt(position: Long, length: Int, data: ByteArray): Int {
        if (closed || position < 0 || length < 0 || length > data.size) throw IOException("Invalid preview read")
        if (length == 0 || position >= size) return 0
        val wanted = minOf(length.toLong(), size - position).toInt()
        var copied = 0
        while (copied < wanted) {
            val offset = position + copied
            if (offset < start || offset >= start + filled) {
                if (transferred >= budget) throw IOException("Preview read budget exceeded")
                val count = minOf(buffer.size.toLong(), size - offset, budget - transferred).toInt()
                WebDav.open(url, offset, count.toLong()).use { response ->
                    val input = response.body?.byteStream() ?: throw IOException("Empty preview")
                    filled = 0
                    while (filled < count) {
                        val n = input.read(buffer, filled, count - filled)
                        if (n < 0) break
                        filled += n
                    }
                }
                if (filled == 0) throw IOException("Incomplete preview")
                start = offset; transferred += filled
            }
            val inBuffer = (offset - start).toInt()
            val count = minOf(wanted - copied, filled - inBuffer)
            System.arraycopy(buffer, inBuffer, data, copied, count)
            copied += count
        }
        return copied
    }
    @Synchronized override fun close() { closed = true }
}
