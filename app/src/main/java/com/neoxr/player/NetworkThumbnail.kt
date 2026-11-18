package com.neoxr.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.Registry
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.IOException

/** Decode network thumbnails through seekable range reads and cache only the image. */
internal class NetworkThumbnail(val video: LibraryVideo) {
    private val cacheId = LocalLibraryRepository.cacheId("network-frame-v2|${video.cacheKey}")
    override fun equals(other: Any?) = other is NetworkThumbnail && cacheId == other.cacheId
    override fun hashCode() = cacheId.hashCode()
    override fun toString() = cacheId

    companion object {
        private var registered: Registry? = null
        @Synchronized fun register(context: Context) {
            val registry = Glide.get(context).registry
            if (registered === registry) return
            registry.append(NetworkThumbnail::class.java, Bitmap::class.java, Factory)
            registered = registry
        }
    }

    private object Factory : ModelLoaderFactory<NetworkThumbnail, Bitmap> {
        override fun teardown() = Unit
        override fun build(multiFactory: MultiModelLoaderFactory) = object : ModelLoader<NetworkThumbnail, Bitmap> {
            override fun handles(model: NetworkThumbnail) = true
            override fun buildLoadData(model: NetworkThumbnail, width: Int, height: Int, options: Options) =
                ModelLoader.LoadData(ObjectKey(model.cacheId), Fetcher(model.video))
        }
    }

    private class Fetcher(private val video: LibraryVideo) : DataFetcher<Bitmap> {
        @Volatile private var cancelled = false
        override fun getDataClass() = Bitmap::class.java
        override fun getDataSource() = DataSource.REMOTE
        override fun cleanup() = Unit
        override fun cancel() { cancelled = true }
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
            try {
                SmbPreviewProvider.acquireSlot()
                val frame = try {
                    if (cancelled) return
                    decode()
                } finally { SmbPreviewProvider.releaseSlot() }
                if (cancelled) frame.recycle() else callback.onDataReady(frame)
            } catch (_: Exception) {
                if (!cancelled) callback.onLoadFailed(IOException("Network video thumbnail unavailable"))
            }
        }

        private fun decode(): Bitmap {
            PreviewDataSource(SmbPreviewProvider.openReader(video)) { cancelled }.use { source ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(source)
                    val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(1_000_000L, option, 384, 216)
                            ?: retriever.getScaledFrameAtTime(-1, option, 384, 216)
                    } else {
                        retriever.getFrameAtTime(-1, option)
                    }
                    return frame ?: throw IOException("No decodable preview frame")
                } finally { retriever.release() }
            }
        }
    }
}

internal class PreviewDataSource(
    private val reader: NetworkRangeReader,
    private val cancelled: () -> Boolean
) : MediaDataSource() {
    override fun getSize() = reader.size
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (cancelled()) throw IOException("Preview cancelled")
        val target = if (offset == 0) buffer else ByteArray(size)
        val read = reader.readAt(position, size, target)
        if (read <= 0) return -1
        if (offset != 0) target.copyInto(buffer, offset, 0, read)
        return read
    }
    override fun close() = reader.close()
}
