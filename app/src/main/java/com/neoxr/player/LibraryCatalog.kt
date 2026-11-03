package com.neoxr.player

import java.text.Collator
import java.util.Locale

internal enum class LibrarySort(val label: String) {
    NAME("名称"), CREATED("创建时间"), SIZE("文件大小"), DURATION("时长");
    companion object { fun saved(value: String?) = entries.find { it.name == value } ?: NAME }
}

internal data class LibraryCard(
    val name: String, val root: String, val path: String,
    val video: LibraryVideo? = null, val members: List<LibraryVideo> = emptyList()
) {
    val directory get() = video == null
    val videos get() = video?.let { listOf(it) } ?: members
}

/** Pure hierarchy, search and stable sorting for both local and network libraries. */
internal object LibraryCatalog {
    fun scope(folders: List<LibraryFolder>, root: String?, path: String): List<LibraryVideo> =
        folders.asSequence().filter { root == null || it.uri == root }.flatMap { it.videos.asSequence() }
            .filter { path.isEmpty() || it.folder == path || it.folder.startsWith("$path/") }
            .distinctBy { it.uri }.toList()

    fun cards(folders: List<LibraryFolder>, root: String?, path: String, query: String, folderView: Boolean): List<LibraryCard> {
        val q = query.trim()
        if (!folderView) return scope(folders, root, path).filter { it.name.contains(q, true) }
            .map { LibraryCard(it.name, root ?: "", it.folder, it) }
        if (root == null) return folders.filter { folder ->
            q.isEmpty() || folder.name.contains(q, true) || folder.videos.any { it.name.contains(q, true) || it.folder.contains(q, true) }
        }.map { LibraryCard(it.name, it.uri, "", members = it.videos) }
        val folder = folders.find { it.uri == root } ?: return emptyList()
        val prefix = if (path.isEmpty()) "" else "$path/"
        val groups = linkedMapOf<String, MutableList<LibraryVideo>>()
        val files = mutableListOf<LibraryCard>()
        folder.videos.forEach { video ->
            if (video.folder == path) {
                if (q.isEmpty() || video.name.contains(q, true)) files.add(LibraryCard(video.name, root, path, video))
            } else if (video.folder.startsWith(prefix)) {
                val child = video.folder.removePrefix(prefix).substringBefore('/')
                if (child.isNotEmpty()) groups.getOrPut(child) { mutableListOf() }.add(video)
            }
        }
        return groups.filter { (name, videos) -> q.isEmpty() || name.contains(q, true) ||
            videos.any { it.name.contains(q, true) || it.folder.removePrefix(prefix).contains(q, true) }
        }.map { (name, videos) -> LibraryCard(name, root, prefix + name, members = videos) } + files
    }

    fun time(video: LibraryVideo) = video.created.takeIf { it > 0 } ?: video.modified
    fun duration(card: LibraryCard, durationOf: (LibraryVideo) -> Long?): Long? {
        val values = card.videos.map { durationOf(it)?.takeIf { ms -> ms > 0 } }
        return if (values.isEmpty() || values.any { it == null }) null else values.sumOf { it!! }
    }

    fun sorted(cards: List<LibraryCard>, sort: LibrarySort, descending: Boolean,
        durationOf: (LibraryVideo) -> Long?): List<LibraryCard> {
        val names = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
        // Calculate metadata once per item, rather than doing preference reads inside comparisons.
        val values = java.util.IdentityHashMap<LibraryCard, Long?>()
        cards.forEach { card -> values[card] = when (sort) {
            LibrarySort.NAME -> null
            LibrarySort.CREATED -> card.videos.maxOfOrNull { time(it) }?.takeIf { it > 0 }
            LibrarySort.SIZE -> card.videos.sumOf { it.size.coerceAtLeast(0) }.takeIf { it > 0 }
            LibrarySort.DURATION -> duration(card, durationOf)
        } }
        return cards.sortedWith { a, b ->
            if (a.directory != b.directory) return@sortedWith if (a.directory) -1 else 1
            val result = if (sort == LibrarySort.NAME) names.compare(a.name, b.name) else {
                val av = values[a]; val bv = values[b]
                if (av == null && bv != null) return@sortedWith 1
                if (av != null && bv == null) return@sortedWith -1
                if (av == null || bv == null) 0 else av.compareTo(bv)
            }
            (if (descending) -result else result).takeIf { it != 0 }
                ?: names.compare(a.name, b.name).takeIf { it != 0 }
                ?: (a.root + "/" + a.path + "/" + (a.video?.uri ?: "")).compareTo(b.root + "/" + b.path + "/" + (b.video?.uri ?: ""))
        }
    }
}
