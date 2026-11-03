package com.neoxr.player

import org.junit.Assert.*
import org.junit.Test

class LibraryCatalogTest {
    private val a = LibraryVideo("a", "a.mp4", "", 30, 100, 300)
    private val b = LibraryVideo("b", "b.mp4", "VR/旅行", 10, 400)
    private val c = LibraryVideo("c", "c.mp4", "VR/演出", 20, 500, 200)
    private val d = LibraryVideo("d", "d.mp4", "VR2", 40, 600, 100)
    private val first = LibraryFolder("root1", "本地影片", listOf(a, b, c, d))
    private val second = LibraryFolder("root2", "网络影片", listOf(LibraryVideo("e", "e.mp4", "", 50, 700)))
    private val folders = listOf(first, second)
    private val durations = mapOf("a" to 10_000L, "b" to 40_000L, "c" to 20_000L, "d" to 30_000L, "e" to 5_000L)
    private fun duration(video: LibraryVideo) = durations[video.uri]
    private fun sorted(sort: LibrarySort, down: Boolean) = LibraryCatalog.sorted(
        LibraryCatalog.cards(folders, null, "", "", false), sort, down, ::duration).map { it.video!!.uri }

    @Test fun fileViewFlattensVideosAcrossSavedRootsAndKeepsSearchAndPathFiltering() {
        assertEquals(5, LibraryCatalog.cards(folders, null, "", "", false).size)
        assertEquals(listOf("b"), LibraryCatalog.cards(folders, "root1", "VR", "B.MP4", false).map { it.video!!.uri })
        assertEquals(setOf("b", "c"), LibraryCatalog.cards(folders, "root1", "VR", "", false).map { it.video!!.uri }.toSet())
    }

    @Test fun folderViewListsRootsThenImmediateChildrenWithoutFlatteningDeepPaths() {
        assertEquals(listOf("本地影片", "网络影片"), LibraryCatalog.cards(folders, null, "", "", true).map { it.name })
        val root = LibraryCatalog.cards(folders, "root1", "", "", true)
        assertEquals(setOf("VR", "VR2", "a.mp4"), root.map { it.name }.toSet())
        val vr = root.first { it.name == "VR" }
        assertEquals(setOf("b", "c"), vr.members.map { it.uri }.toSet())
        assertEquals(setOf("旅行", "演出"), LibraryCatalog.cards(folders, "root1", "VR", "", true).map { it.name }.toSet())
        assertEquals("b", LibraryCatalog.cards(folders, "root1", "VR/旅行", "", true).single().video!!.uri)
    }

    @Test fun folderSearchFindsNamesAndDescendantVideoNamesWithoutMixingSiblingPrefixes() {
        assertEquals(listOf("本地影片"), LibraryCatalog.cards(folders, null, "", "旅行", true).map { it.name })
        assertEquals(listOf("VR"), LibraryCatalog.cards(folders, "root1", "", "b.mp4", true).map { it.name })
        assertEquals(listOf("VR2"), LibraryCatalog.cards(folders, "root1", "", "VR2", true).map { it.name })
        assertTrue(LibraryCatalog.cards(folders, "root1", "VR/旅行", "c.mp4", true).isEmpty())
    }

    @Test fun sizeAndDurationSortInBothDirections() {
        assertEquals(listOf("b", "c", "a", "d", "e"), sorted(LibrarySort.SIZE, false))
        assertEquals(listOf("e", "d", "a", "c", "b"), sorted(LibrarySort.SIZE, true))
        assertEquals(listOf("e", "a", "c", "d", "b"), sorted(LibrarySort.DURATION, false))
        assertEquals(listOf("b", "d", "c", "a", "e"), sorted(LibrarySort.DURATION, true))
    }

    @Test fun creationTimeWinsOverModificationAndOldIndexesUseModificationFallback() {
        assertEquals(listOf("d", "c", "a", "b", "e"), sorted(LibrarySort.CREATED, false))
        assertEquals(listOf("e", "b", "a", "c", "d"), sorted(LibrarySort.CREATED, true))
        val legacy = LocalLibrary.decode("""[{"uri":"old","name":"old","videos":[{"uri":"v","name":"v.mp4","folder":"","modified":123}]}]""").single().videos.single()
        assertEquals(0L, legacy.created)
        assertEquals(123L, LibraryCatalog.time(legacy))
        assertEquals(listOf(first), LocalLibrary.decode(LocalLibrary.encode(listOf(first))))
    }

    @Test fun unknownMetadataAlwaysStaysAtTheEndRegardlessOfDirection() {
        val unknown = LibraryVideo("unknown", "0 unknown.mp4", "")
        val cards = LibraryCatalog.cards(listOf(first.copy(videos = listOf(a, unknown))), null, "", "", false)
        for (sort in listOf(LibrarySort.SIZE, LibrarySort.CREATED, LibrarySort.DURATION)) {
            for (down in listOf(true, false)) {
                assertEquals("unknown", LibraryCatalog.sorted(cards, sort, down, ::duration).last().video!!.uri)
            }
        }
    }

    @Test fun directoriesStayBeforeFilesAndUseAggregatedVideoMetadata() {
        val cards = LibraryCatalog.cards(folders, "root1", "", "", true)
        val result = LibraryCatalog.sorted(cards, LibrarySort.SIZE, true, ::duration)
        assertEquals(listOf("VR2", "VR", "a.mp4"), result.map { it.name })
        assertEquals(60_000L, LibraryCatalog.duration(cards.first { it.name == "VR" }, ::duration))
        assertNull(LibraryCatalog.duration(cards.first { it.name == "VR" }) { if (it.uri == "b") null else 100L })
    }

    @Test fun missingRootsAreSafeAndEmptySavedRootsStillAppearInFolderView() {
        assertTrue(LibraryCatalog.cards(folders, "removed", "", "", true).isEmpty())
        val empty = LibraryFolder("empty", "空目录")
        assertEquals("空目录", LibraryCatalog.cards(listOf(empty), null, "", "", true).single().name)
        assertEquals(LibrarySort.NAME, LibrarySort.saved("invalid"))
    }
}
