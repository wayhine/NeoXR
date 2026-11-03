package com.neoxr.player

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Private read-only bridge: Glide and metadata extraction can seek through network files. */
class SmbPreviewProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "video/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Preview is read-only")
        val token = uri.lastPathSegment ?: throw FileNotFoundException("Preview expired")
        val video = files[token] ?: throw FileNotFoundException("Preview expired")
        try { slots.acquire() } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FileNotFoundException("Preview cancelled")
        }
        var reader: NetworkRangeReader? = null
        var thread: HandlerThread? = null
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) {
                try { reader?.close() } finally { slots.release(); thread?.quitSafely() }
            }
        }
        try {
            val input: NetworkRangeReader = if (WebDav.isWebDav(video.uri)) WebDavRangeReader(video.uri, video.size) else {
                val handle = Smb.openRandomAccess(video.uri)
                try { SmbRangeReader(handle) } catch (e: Exception) { handle.close(); throw e }
            }
            reader = input
            val handlerThread = HandlerThread("SMB preview").apply { start() }
            thread = handlerThread
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize() = input.size
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                    input.readAt(offset, size, data)
                } catch (_: Exception) { throw ErrnoException("SMB preview", OsConstants.EIO) }
                override fun onRelease() { runCatching { release() } }
            }
            return context!!.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(handlerThread.looper))
        } catch (_: Exception) {
            runCatching { release() }
            // Credentials never appear in provider errors or Glide logs.
            throw FileNotFoundException("Network preview unavailable")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val video = uri.lastPathSegment?.let { files[it] }
        return MatrixCursor(columns).apply {
            if (video != null) addRow(columns.map {
                when (it) { OpenableColumns.DISPLAY_NAME -> video.name; OpenableColumns.SIZE -> video.size; else -> null }
            })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private val files = ConcurrentHashMap<String, LibraryVideo>()
        private val slots = Semaphore(2, true)
        fun uriFor(context: Context, video: LibraryVideo): Uri {
            val key = LocalLibraryRepository.cacheId(video.cacheKey)
            files[key] = video
            return Uri.Builder().scheme("content").authority(context.packageName + ".smb-preview").appendPath(key).build()
        }
    }
}
