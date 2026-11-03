package com.neoxr.player

import org.junit.Assert.*
import org.junit.Test

class LocalLibraryTest {
    private val video = LibraryVideo("content://storage/tree/VR/document/VR%2Ffilm.MKV", "电影.MKV", "VR180/精选")
    private val folder = LibraryFolder("content://storage/tree/VR", "影片", listOf(video))

    @Test fun recognizesRequestedFormatsEvenWhenProviderUsesGenericMime() {
        listOf("film.mp4", "film.MKV", "film.Mov", "film.WEBM").forEach {
            assertTrue(it, LocalLibrary.isVideo(it, "application/octet-stream"))
        }
        assertTrue(LocalLibrary.isVideo("no-extension", "video/mp4"))
        assertFalse(LocalLibrary.isVideo("cover.jpg", "image/jpeg"))
        assertFalse(LocalLibrary.isVideo("movie.mp4.txt", null))
        assertFalse(LocalLibrary.isVideo("README", null))
    }

    @Test fun savedLibraryRestoresUrisNamesAndNestedPaths() {
        val other = folder.copy(uri = "content://storage/tree/other")
        assertEquals(listOf(folder, other), LocalLibrary.decode(LocalLibrary.encode(listOf(folder, other))))
        assertEquals(emptyList<LibraryFolder>(), LocalLibrary.decode("[]"))
    }

    @Test fun duplicateEntriesDoNotDuplicateLibrary() {
        val duplicated = folder.copy(videos = listOf(video, video))
        assertEquals(listOf(folder), LocalLibrary.decode(LocalLibrary.encode(listOf(duplicated, duplicated))))
    }

    @Test fun inaccessibleFolderKeepsLastSuccessfulIndex() {
        val failed = LocalLibrary.scanned(folder, Result.failure(SecurityException("Permission revoked")))
        assertEquals(folder.videos, failed.videos)
        assertNotNull(failed.error)
        // Cached videos also survive a restart while the storage is disconnected.
        assertEquals(folder, LocalLibrary.decode(LocalLibrary.encode(listOf(failed))).single())
    }

    @Test fun successfulRescanReplacesDeletedVideosAndClearsFailure() {
        val failed = folder.copy(error = "Disconnected")
        val replacement = video.copy(uri = "content://storage/new", name = "new.mp4")
        val refreshed = LocalLibrary.scanned(failed, Result.success(listOf(replacement, replacement)))
        assertEquals(listOf(replacement), refreshed.videos)
        assertNull(refreshed.error)
        assertTrue(LocalLibrary.scanned(folder, Result.success(emptyList())).videos.isEmpty())
    }

    @Test fun recursivelyScansVideosWithRelativeFoldersAndIgnoresNonVideos() {
        val tree = mapOf(
            "root" to listOf(
                LibraryDocument("a", "VR180", null, "uri:a", true),
                LibraryDocument("1", "one.MP4", null, "uri:1", false),
                LibraryDocument("2", "cover.jpg", "image/jpeg", "uri:2", false)),
            "a" to listOf(
                LibraryDocument("b", "精选", null, "uri:b", true),
                LibraryDocument("3", "three.mkv", "application/octet-stream", "uri:3", false)),
            "b" to listOf(LibraryDocument("4", "four.webm", null, "uri:4", false))
        )
        val videos = LocalLibrary.scan("root") { tree.getValue(it) }
        assertEquals(listOf(
            LibraryVideo("uri:1", "one.MP4", ""),
            LibraryVideo("uri:3", "three.mkv", "VR180"),
            LibraryVideo("uri:4", "four.webm", "VR180/精选")
        ), videos)
    }

    @Test fun directoryCyclesAndDuplicateProviderRowsAreSafe() {
        var queries = 0
        val videos = LocalLibrary.scan("root") {
            queries++
            listOf(LibraryDocument("root", "loop", null, "uri:root", true),
                LibraryDocument("1", "one.mov", null, "uri:1", false),
                LibraryDocument("1", "one.mov", null, "uri:1", false))
        }
        assertEquals(1, queries)
        assertEquals(1, videos.size)
    }

    @Test fun partialScanFailureDoesNotReplaceCachedVideos() {
        val result = runCatching {
            LocalLibrary.scan("root") { id ->
                if (id == "bad") throw SecurityException("Cannot read subfolder")
                listOf(LibraryDocument("bad", "Blocked", null, "uri:bad", true),
                    LibraryDocument("1", "new.mp4", null, "uri:1", false))
            }
        }
        assertTrue(result.isFailure)
        assertEquals(folder.videos, LocalLibrary.scanned(folder, result).videos)
    }

    @Test fun oldSavedIndexStillLoadsWithoutThumbnailMetadata() {
        val json = """[{"uri":"root","name":"Movies","videos":[{"uri":"video","name":"one.mp4","folder":""}]}]"""
        assertEquals(LibraryVideo("video", "one.mp4", ""), LocalLibrary.decode(json).single().videos.single())
    }

    @Test fun fileChangesInvalidateThumbnailAndDurationCache() {
        val original = video.copy(size = 100, modified = 123)
        assertNotEquals(original.cacheKey, original.copy(size = 200).cacheKey)
        assertNotEquals(original.cacheKey, original.copy(modified = 456).cacheKey)
        assertEquals(original, LocalLibrary.decode(LocalLibrary.encode(listOf(folder.copy(videos = listOf(original)))))
            .single().videos.single())
    }

    @Test fun allVideosDeduplicatesOverlappingSourcesAndSearchesTitles() {
        val another = LibraryVideo("uri:2", "Travel.MP4", "")
        val sources = listOf(folder, folder.copy(uri = "root:2", videos = listOf(video, another)))
        assertEquals(2, LocalLibrary.videos(sources, null, "", "").size)
        assertEquals(listOf(another), LocalLibrary.videos(sources, null, "", " travel "))
        assertTrue(LocalLibrary.videos(sources, null, "", "missing").isEmpty())
    }

    @Test fun folderFilterIncludesDescendantsWithoutMatchingSiblingPrefix() {
        val nested = video.copy(uri = "uri:child", folder = "VR180/精选/More")
        val sibling = video.copy(uri = "uri:sibling", folder = "VR180/精选2")
        val sources = listOf(folder.copy(videos = listOf(video, nested, sibling)),
            folder.copy(uri = "other", videos = listOf(video.copy(uri = "uri:other"))))
        assertEquals(setOf(video, nested), LocalLibrary.videos(sources, folder.uri, "VR180/精选", "").toSet())
        assertEquals(3, LocalLibrary.videos(sources, folder.uri, "", "").size)
    }

    @Test fun scannerRetainsFileRevisionForPreviewInvalidation() {
        val found = LocalLibrary.scan("root") {
            listOf(LibraryDocument("1", "one.mp4", "video/mp4", "uri:1", false, 999, 123456))
        }
        assertEquals(LibraryVideo("uri:1", "one.mp4", "", 999, 123456), found.single())
    }
}
