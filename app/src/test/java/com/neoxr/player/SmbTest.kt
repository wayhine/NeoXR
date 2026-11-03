package com.neoxr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Path arithmetic of the share browser — the part that has no network in it. */
class SmbTest {

    @Test fun networkScanKeepsCreationTimeForLibrarySorting() {
        val videos = Smb.scan("smb://nas/share") { listOf(Smb.Entry("movie.mp4", false, 42, 100, created = 200)) }
        assertEquals(200L, videos.single().created)
        assertEquals(100L, videos.single().modified)
    }

    @Test
    fun parentClimbsOneLevel() {
        assertEquals("smb://nas/movies/", Smb.parent("smb://nas/movies/VR/"))
        assertEquals("smb://nas/", Smb.parent("smb://nas/movies"))
    }

    @Test
    fun parentStopsAtTheServer() {
        assertNull(Smb.parent("smb://nas/"))
        assertNull(Smb.parent("smb://nas"))
    }

    @Test
    fun childKeepsOneSeparator() {
        assertEquals("smb://nas/movies/a.mkv", Smb.child("smb://nas/movies/", "a.mkv"))
        assertEquals("smb://nas/movies/a.mkv", Smb.child("smb://nas/movies", "a.mkv"))
    }

    @Test
    fun sharesKeepTheirScheme() {
        assertEquals("smb://user:pw@10.0.0.2/vr", Deo.normalizeSiteUrl(" smb://user:pw@10.0.0.2/vr "))
        assertEquals("https://example.com", Deo.normalizeSiteUrl("example.com"))
    }

    @Test
    fun onlyVideoFilesAreListed() {
        assertTrue(Smb.isVideo("Movie.2019.1080p.SBS.mkv"))
        assertTrue(Smb.isVideo("clip.MP4"))
        assertEquals(false, Smb.isVideo("cover.jpg"))
        assertEquals(false, Smb.isVideo("metadata.json"))
        assertEquals(false, Smb.isVideo("README"))
    }

    /**
     * The reason a 4 GB film would not start: media3's extractors read a container
     * header a few bytes at a time, and one SMB request per read is minutes of
     * round trips. Reads must come out of a buffer.
     */
    @Test
    fun smallReadsCostOneRequestPerBuffer() {
        val server = CountingSource(2_000_000)
        val src = Smb.Source()
        src.attach(server, 2_000_000)
        val one = ByteArray(1)
        repeat(300_000) { src.read(one, 0, 1) }
        assertEquals(2, server.requests)
    }

    /** Bytes must come back in order and unmodified across buffer refills. */
    @Test
    fun bufferedReadsKeepTheStreamIntact() {
        val src = Smb.Source()
        src.attach(CountingSource(700_000), 700_000)
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(9_999)
        while (true) {
            val n = src.read(chunk, 0, chunk.size)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        val bytes = out.toByteArray()
        assertEquals(700_000, bytes.size)
        assertTrue(bytes.indices.all { bytes[it] == (it % 251).toByte() })
    }

    @Test fun deviceKeepsAccountAndFoldersRequireAShare() {
        assertEquals("smb://user:pw@nas/", Smb.device("smb://user:pw@nas/movies/VR"))
        assertEquals(false, Smb.isFolder("smb://nas/"))
        assertTrue(Smb.isFolder("smb://nas/movies"))
    }

    @Test fun childKeepsLiteralFilenameCharactersForJcifs() {
        val url = Smb.child("smb://user:secret@nas/movies", "clip #1%.mp4")
        assertEquals("nas/movies/clip #1%.mp4", Smb.display(url))
        val context = jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(java.util.Properties()))
        try {
            jcifs.smb.SmbFile(url, context).use { file ->
                assertEquals("\\clip #1%.mp4", file.getLocator().getUNCPath())
            }
        } finally { context.close() }
    }

    @Test fun recursivelyIndexesSelectedNetworkFolderAndSkipsLinks() {
        val root = "smb://nas/movies"
        val tree = mapOf(root to listOf(
            Smb.Entry("one.mp4", false, 100, 123), Smb.Entry("VR", true, 0),
            Smb.Entry("loop", true, 0, isLink = true), Smb.Entry("cover.jpg", false, 100)),
            "$root/VR" to listOf(Smb.Entry("two.MKV", false, 200, 456)))
        val videos = Smb.scan(root) { tree.getValue(it) }
        assertEquals(listOf(LibraryVideo("$root/one.mp4", "one.mp4", "", 100, 123),
            LibraryVideo("$root/VR/two.MKV", "two.MKV", "VR", 200, 456)), videos)
        assertEquals(videos, LocalLibrary.decode(LocalLibrary.encode(listOf(LibraryFolder(root, "movies", videos)))).single().videos)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesIndexingEntireServer() { Smb.scan("smb://nas") { error("Must not enumerate server") } }

    @Test fun interruptedNetworkScanKeepsCachedListing() {
        val folder = LibraryFolder("smb://nas/movies", "movies", listOf(LibraryVideo("old", "old.mp4", "")))
        val scan = runCatching { Smb.scan(folder.uri) { throw java.io.IOException("Offline") } }
        assertEquals(folder.videos, LocalLibrary.scanned(folder, scan).videos)
    }

    @Test fun previewReadsSeekAndFillAcrossBufferBoundaries() {
        val server = CountingSource(700_000)
        val reader = SmbRangeReader(server)
        val out = ByteArray(600_000)
        assertEquals(out.size, reader.readAt(37, out.size, out))
        assertTrue(out.indices.all { out[it] == ((it + 37) % 251).toByte() })
        val tail = ByteArray(12)
        assertEquals(5, reader.readAt(699_995, tail.size, tail))
        assertTrue((0 until 5).all { tail[it] == ((699_995 + it) % 251).toByte() })
        assertEquals(0, reader.readAt(700_000, 12, tail))
        reader.close(); reader.close()
        assertTrue(server.closed)
    }

    @Test fun previewSmallReadsUseReadAhead() {
        val server = CountingSource(700_000)
        SmbRangeReader(server).use { reader ->
            val one = ByteArray(1)
            repeat(300_000) { position ->
                assertEquals(1, reader.readAt(position.toLong(), 1, one))
                assertEquals((position % 251).toByte(), one[0])
            }
        }
        assertEquals(2, server.requests)
    }

    @Test(expected = java.io.IOException::class)
    fun previewsHaveABoundedNetworkReadBudget() {
        SmbRangeReader(CountingSource(1000), budget = 32).use {
            it.readAt(0, 16, ByteArray(16))
            it.readAt(128, 16, ByteArray(16))
        }
    }

    @Test fun networkErrorMessagesHideCredentials() {
        val message = Smb.explain(java.io.IOException("Cannot read smb://user:secret@nas/movies/film.mp4"))
        assertEquals(false, message.contains("secret"))
        assertTrue(message.contains("nas/movies"))
    }

    /** Stand-in for a share: hands out a known pattern and counts network calls. */
    private class CountingSource(private val size: Int) : jcifs.SmbRandomAccess {
        var requests = 0
        var closed = false
        private var pos = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            requests++
            val n = minOf(len, size - pos)
            if (n <= 0) return -1
            for (i in 0 until n) b[off + i] = ((pos + i) % 251).toByte()
            pos += n
            return n
        }

        override fun seek(p: Long) { pos = p.toInt() }
        override fun close() { closed = true }
        override fun read() = throw UnsupportedOperationException()
        override fun read(b: ByteArray) = read(b, 0, b.size)
        override fun length() = size.toLong()
        override fun getFilePointer() = pos.toLong()
        override fun setLength(newLength: Long) = throw UnsupportedOperationException()
        override fun skipBytes(n: Int) = throw UnsupportedOperationException()
        override fun readFully(b: ByteArray) = throw UnsupportedOperationException()
        override fun readFully(b: ByteArray, off: Int, len: Int) = throw UnsupportedOperationException()
        override fun readBoolean() = throw UnsupportedOperationException()
        override fun readByte() = throw UnsupportedOperationException()
        override fun readUnsignedByte() = throw UnsupportedOperationException()
        override fun readShort() = throw UnsupportedOperationException()
        override fun readUnsignedShort() = throw UnsupportedOperationException()
        override fun readChar() = throw UnsupportedOperationException()
        override fun readInt() = throw UnsupportedOperationException()
        override fun readLong() = throw UnsupportedOperationException()
        override fun readFloat() = throw UnsupportedOperationException()
        override fun readDouble() = throw UnsupportedOperationException()
        override fun readLine() = throw UnsupportedOperationException()
        override fun readUTF() = throw UnsupportedOperationException()
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) = throw UnsupportedOperationException()
        override fun writeBoolean(v: Boolean) = throw UnsupportedOperationException()
        override fun writeByte(v: Int) = throw UnsupportedOperationException()
        override fun writeShort(v: Int) = throw UnsupportedOperationException()
        override fun writeChar(v: Int) = throw UnsupportedOperationException()
        override fun writeInt(v: Int) = throw UnsupportedOperationException()
        override fun writeLong(v: Long) = throw UnsupportedOperationException()
        override fun writeFloat(v: Float) = throw UnsupportedOperationException()
        override fun writeDouble(v: Double) = throw UnsupportedOperationException()
        override fun writeBytes(sv: String) = throw UnsupportedOperationException()
        override fun writeChars(sv: String) = throw UnsupportedOperationException()
        override fun writeUTF(sv: String) = throw UnsupportedOperationException()
    }
}
