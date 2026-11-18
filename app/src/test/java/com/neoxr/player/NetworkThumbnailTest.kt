package com.neoxr.player

import com.bumptech.glide.Glide
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkThumbnailTest {
    @Test fun previewSupportsSeeksPartialReadsAndCancellation() {
        val payload = byteArrayOf(6, 7, 8, 9)
        var closed = false
        var cancelled = false
        val reader = object : NetworkRangeReader {
            override val size = payload.size.toLong()
            override fun readAt(position: Long, length: Int, data: ByteArray): Int {
                val count = minOf(length, payload.size - position.toInt()).coerceAtLeast(0)
                payload.copyInto(data, 0, position.toInt(), position.toInt() + count)
                return count
            }
            override fun close() { closed = true }
        }
        PreviewDataSource(reader) { cancelled }.use { source ->
            val buffer = ByteArray(6)
            assertEquals(2, source.readAt(2, buffer, 2, 4))
            assertArrayEquals(byteArrayOf(0, 0, 8, 9, 0, 0), buffer)
            assertEquals(-1, source.readAt(4, buffer, 0, 1))
            assertEquals(4L, source.size)
            cancelled = true
            assertThrows(IOException::class.java) { source.readAt(1, buffer, 0, 2) }
        }
        assertTrue(closed)
    }

    @Test fun glideUsesTheFrameLoaderAndItsCacheIdentityDoesNotContainCredentials() {
        val context = RuntimeEnvironment.getApplication()
        NetworkThumbnail.register(context)
        NetworkThumbnail.register(context)
        val video = LibraryVideo("smb://user:password@host/share/clip.mp4", "clip.mp4", "", 1234, 100)
        val model = NetworkThumbnail(video)
        assertFalse(model.toString().contains("password"))
        assertNotEquals(model, NetworkThumbnail(video.copy(modified = 101)))
        assertEquals(1, Glide.get(context).registry.getModelLoaders(model).size)
    }
}
