package com.neoxr.player

import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Real loopback HTTP requests exercise authentication, PROPFIND, redirects and ranges. */
class WebDavTest {
    private lateinit var server: MockWebServer
    private lateinit var root: String
    private val password = "p@:/#密%码"
    private val fileName = "片 段#100%.mp4"
    private val bytes = ByteArray(700_000) { (it % 251).toByte() }
    private val requests = CopyOnWriteArrayList<String>()
    private val authorization = Credentials.basic("alice", password, Charsets.UTF_8)

    @Before fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(exchange: RecordedRequest): MockResponse {
                val method = exchange.method
                val path = exchange.requestUrl!!.toUri().path
                requests.add("$method $path")
                return if (exchange.getHeader("Authorization") != authorization) {
                    send(401, ByteArray(0))
                } else if (method == "PROPFIND") {
                    if (exchange.getHeader("Depth") != "1" || !exchange.body.readUtf8().contains("propfind"))
                        return send(400, ByteArray(0))
                    val xml = when (path) {
                        "/dav/" -> listing(row("/dav/", true) + row("/dav/sub/", true) +
                            row(WebDav.httpUrl(WebDav.child(root, fileName)).encodedPath, false) + row("/dav/notes.txt", false))
                        "/dav/sub/" -> listing(row("/dav/sub/", true) + row("/dav/sub/nested.mkv", false))
                        else -> null
                    }
                    if (xml == null) send(404, ByteArray(0)) else send(207, xml.toByteArray())
                } else if (path == "/redirect") {
                    send(302, ByteArray(0)).setHeader("Location", WebDav.httpUrl(WebDav.child(root, fileName)).encodedPath)
                } else if (path == "/ignore-range") {
                    send(200, bytes)
                } else {
                    val range = exchange.getHeader("Range")!!
                    val start = range.substringAfter("bytes=").substringBefore('-').toInt()
                    if (start >= bytes.size) {
                        send(416, ByteArray(0)).setHeader("Content-Range", "bytes */${bytes.size}")
                    } else {
                        val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                        send(206, bytes.copyOfRange(start, end + 1))
                            .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    }
                }
            }
        }
        server.start()
        root = WebDav.connection(server.url("/dav/").toString(), "alice", password)
    }
    @After fun stopServer() { server.shutdown() }
    private fun send(code: Int, body: ByteArray) = MockResponse().setResponseCode(code).setBody(Buffer().write(body))
    private fun listing(rows: String) = "<d:multistatus xmlns:d=\"DAV:\">$rows</d:multistatus>"
    private fun row(href: String, dir: Boolean) = """<d:response><d:href>$href</d:href><d:propstat><d:prop>
        <d:resourcetype>${if (dir) "<d:collection/>" else ""}</d:resourcetype>
        <d:getcontentlength>${bytes.size}</d:getcontentlength>
        <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    @Test fun connectionRoundTripsCredentialsAndUsesHttpsForWebdavs() {
        val connection = WebDav.connection("https://nas:5006/dav", "a@b", password)
        assertTrue(connection.startsWith("webdavs://"))
        assertEquals(password, WebDav.httpUrl(connection).password)
        assertEquals("a@b", WebDav.httpUrl(connection).username)
        assertEquals("/dav/", WebDav.httpUrl(connection).encodedPath)
        assertEquals("https://nas:5006/dav/", WebDav.display(connection))
        assertEquals(connection, Deo.normalizeSiteUrl(connection))
    }
    @Test fun pathOperationsEncodeNamesOnceAndStopAtConfiguredRoot() {
        val child = WebDav.child(root, fileName)
        assertEquals(fileName, WebDav.httpUrl(child).pathSegments.last())
        assertEquals(root, WebDav.parent(child, root))
        assertNull(WebDav.parent(root, root))
        assertTrue(WebDav.contains(root, child))
        assertFalse(WebDav.contains(root, WebDav.connection(server.url("/dav-other/").toString(), "alice", password)))
        assertFalse(WebDav.contains(root, WebDav.connection(server.url("/dav/").toString(), "bob", password)))
    }
    @Test fun authenticatedPropfindListsCollectionsAndVideosWithMetadata() {
        val entries = WebDav.list(root)
        assertEquals(listOf("sub", fileName), entries.map { it.name })
        assertTrue(entries.first().isDir)
        assertFalse(entries.last().isDir)
        assertEquals(bytes.size.toLong(), entries.last().size)
        assertEquals(1445412480000L, entries.last().modified)
    }

    @Test fun creationDateIsParsedAndRetainedSeparatelyFromModificationTime() {
        val xml = listing(row("/dav/", true) + row("/dav/clip.mp4", false).replace(
            "<d:getlastmodified>", "<d:creationdate>2024-01-01T00:00:00Z</d:creationdate><d:getlastmodified>"))
        val entry = WebDav.parseListing(root, xml).single()
        assertEquals(1704067200000L, entry.created)
        assertEquals(1445412480000L, entry.modified)
    }
    @Test fun recursiveScanAndSavedIndexPreserveNestedPaths() {
        val videos = WebDav.scan(root)
        assertEquals(2, videos.size)
        assertEquals("sub", videos.first { it.name == "nested.mkv" }.folder)
        assertEquals(fileName, WebDav.httpUrl(videos.first { it.folder.isEmpty() }.uri).pathSegments.last())
        val saved = listOf(LibraryFolder(root, "dav", videos))
        assertEquals(saved, LocalLibrary.decode(LocalLibrary.encode(saved)))
        assertTrue(NetworkMedia.belongsTo(videos.first().uri, root))
    }
    @Test fun permissionFailureDoesNotBecomeAnEmptyLibrary() {
        val xml = listing("<d:response><d:href>/dav/</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response>")
        val folder = LibraryFolder(root, "dav", listOf(LibraryVideo(WebDav.child(root, "old.mp4"), "old.mp4", "")))
        val scanned = LocalLibrary.scanned(folder, runCatching { WebDav.parseListing(root, xml); emptyList() })
        assertEquals(folder.videos, scanned.videos)
        assertNotNull(scanned.error)
    }
    @Test fun parsingIgnoresAncestorsOtherServersAndNonDirectDescendants() {
        val xml = listing(row("/dav/", true) + row("/", true) + row("/dav/sub/deep.mp4", false) +
            row("https://other.example/dav/leak.mp4", false) + row("/dav/good.mp4", false))
        assertEquals(listOf("good.mp4"), WebDav.parseListing(root, xml).map { it.name })
    }
    @Test fun doctypesAndNonDavHtmlAreRejected() {
        for (xml in listOf("<!DOCTYPE d [<!ENTITY e SYSTEM 'file:///missing'>]><d/>", "<html/>")) {
            assertTrue(runCatching { WebDav.parseListing(root, xml) }.isFailure)
        }
    }
    @Test fun streamedRangesStartAtRequestedOffsetAndRespectRequestedLength() {
        val url = WebDav.child(root, fileName)
        WebDav.open(url, 100_000, 1234).use {
            assertEquals(206, it.code)
            assertArrayEquals(bytes.copyOfRange(100_000, 101_234), it.body!!.bytes())
            assertEquals("", it.request.url.username)
            assertEquals("", it.request.url.password)
        }
        assertEquals(bytes.size.toLong(), WebDav.length(url))
    }
    @Test fun previewReusesReadAheadAndFetchesTailWithoutDownloadingWholeVideo() {
        WebDavRangeReader(WebDav.child(root, fileName), bytes.size.toLong()).use { reader ->
            val result = ByteArray(32)
            assertEquals(32, reader.readAt(1000, 32, result))
            assertArrayEquals(bytes.copyOfRange(1000, 1032), result)
            reader.readAt(2000, 32, result)
            assertEquals(1, requests.count { it.startsWith("GET ") })
            assertEquals(32, reader.readAt(600_000, 32, result))
            assertArrayEquals(bytes.copyOfRange(600_000, 600_032), result)
            assertEquals(2, requests.count { it.startsWith("GET ") })
            assertEquals(0, reader.readAt(bytes.size.toLong(), 32, result))
        }
    }
    @Test fun previewBudgetAndClosePreventFurtherRequests() {
        val reader = WebDavRangeReader(WebDav.child(root, fileName), bytes.size.toLong(), 64)
        val data = ByteArray(16)
        reader.readAt(0, 16, data)
        assertTrue(runCatching { reader.readAt(128, 16, data) }.exceptionOrNull() is IOException)
        reader.close()
        assertTrue(runCatching { reader.readAt(0, 16, data) }.isFailure)
        assertEquals(1, requests.count { it.startsWith("GET ") })
    }
    @Test fun ignoredRangesCannotSilentlyPlayFromTheWrongOffset() {
        val url = root.substringBefore("/dav/") + "/ignore-range"
        assertTrue(runCatching { WebDav.open(url, 300) }.exceptionOrNull() is IOException)
    }
    @Test fun failedLoginHasUsefulMessageWithoutCredentials() {
        val bad = WebDav.connection(WebDav.display(root), "alice", "bad-secret")
        val error = runCatching { WebDav.list(bad) }.exceptionOrNull()!!
        assertTrue(error is WebDav.HttpFailure)
        assertTrue(WebDav.explain(error).contains("登录"))
        assertFalse(WebDav.explain(error).contains("bad-secret"))
    }
    @Test fun directoryRedirectCannotSilentlyReplaceTheSavedLibraryWithAnEmptyIndex() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(e: RecordedRequest) =
                send(301, ByteArray(0)).setHeader("Location", "/another-dav-root/")
        }
        val error = runCatching { WebDav.list(root) }.exceptionOrNull()!!
        assertTrue(error is WebDav.DirectoryRedirect)
        assertTrue(WebDav.explain(error).contains("跳转"))
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectedDownloadsNeverForwardLoginToAnotherOrigin() {
        val other = MockWebServer()
        var leaked: String? = "not-called"
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(e: RecordedRequest): MockResponse {
                leaked = e.getHeader("Authorization")
                return send(206, byteArrayOf(1, 2, 3, 4)).setHeader("Content-Range", "bytes 0-3/4")
            }
        }
        other.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(e: RecordedRequest) =
                send(302, ByteArray(0)).setHeader("Location", other.url("/file.mp4").toString())
        }
        try {
            WebDav.open(root.substringBefore("/dav/") + "/external", 0, 4).use {
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), it.body!!.bytes())
            }
            assertNull(leaked)
        } finally { other.shutdown() }
    }
}
