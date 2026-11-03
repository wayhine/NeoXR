package com.neoxr.player

import jcifs.SmbRandomAccess
import java.io.IOException

/** Bounded read-ahead for Android's seekable thumbnail descriptor; never writes a video copy. */
internal class SmbRangeReader(private val source: SmbRandomAccess, private val budget: Long = 64L * 1024 * 1024) : NetworkRangeReader {
    override val size: Long = source.length()
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
                source.seek(offset)
                filled = source.read(buffer, 0, minOf(buffer.size.toLong(), size - offset, budget - transferred).toInt())
                if (filled <= 0) break
                start = offset
                transferred += filled
            }
            val inBuffer = (offset - start).toInt()
            val count = minOf(wanted - copied, filled - inBuffer)
            System.arraycopy(buffer, inBuffer, data, copied, count)
            copied += count
        }
        return copied
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; source.close() }
    }
}
