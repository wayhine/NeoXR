package com.neoxr.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/** One streamed HTTP range per player open/seek; no full-file staging. */
@androidx.media3.common.util.UnstableApi
class WebDavSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        try {
            val result = WebDav.open(spec.uri.toString(), spec.position, spec.length)
            response = result
            val body = result.body ?: throw IOException("WebDAV 未返回视频内容")
            input = body.byteStream()
            remaining = if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else body.contentLength()
            uri = spec.uri
            transferStarted(spec)
            return remaining
        } catch (e: Exception) {
            close()
            throw IOException(WebDav.explain(e))
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = input?.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()) ?: -1
        if (count < 0) return C.RESULT_END_OF_INPUT
        if (remaining > 0) remaining -= count
        bytesTransferred(count)
        return count
    }
    override fun getUri() = uri
    override fun close() {
        response?.close(); response = null; input = null
        if (uri != null) { uri = null; transferEnded() }
    }
    class Factory : DataSource.Factory { override fun createDataSource(): DataSource = WebDavSource() }
}
