package com.neoxr.player

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ListView
import android.widget.TextView

/** Shared view/sort controls and folder navigation for local, SMB and WebDAV indexes. */
internal class LibraryPresentation(
    private val activity: Activity,
    private val wrap: View,
    preferenceKey: String,
    private val metadata: LocalVideoAdapter,
    private val onInteract: () -> Unit,
    private val onRender: () -> Unit,
    private val onNavigate: (String?, String) -> Unit,
    private val onPlay: (LibraryVideo) -> Unit
) {
    private val prefs = activity.getSharedPreferences("library_view_$preferenceKey", Context.MODE_PRIVATE)
    private var folderView = prefs.getBoolean("folders", false)
    private var sort = LibrarySort.saved(prefs.getString("sort", null))
    private var descending = prefs.getBoolean("descending", false)
    private var sortOpen = false
    private var preparing = false
    private var active = true
    private var scope = emptyList<LibraryVideo>()
    private var selected: String? = null
    private var path = ""
    private val grid = wrap.findViewById<GridView>(R.id.libraryGrid)
    private val cards = CardAdapter()

    init {
        grid.isNestedScrollingEnabled = true
        grid.adapter = cards
        grid.setOnItemClickListener { _, _, position, _ ->
            val card = cards.items[position]
            if (card.directory) { sortOpen = false; onNavigate(card.root, card.path) } else onPlay(card.video!!)
        }
        wrap.findViewById<View>(R.id.libraryFilesView).setOnClickListener { changeView(false) }
        wrap.findViewById<View>(R.id.libraryFolderView).setOnClickListener { changeView(true) }
        wrap.findViewById<View>(R.id.librarySort).setOnClickListener {
            onInteract(); sortOpen = !sortOpen; onRender()
        }
        wrap.findViewById<View>(R.id.libraryUp).setOnClickListener {
            sortOpen = false
            if (selected != null) onNavigate(if (path.isEmpty()) null else selected, path.substringBeforeLast('/', ""))
        }
        val choices = LibrarySort.entries.flatMap { key -> listOf(key to false, key to true) }
        val menu = wrap.findViewById<ListView>(R.id.librarySortMenu)
        menu.adapter = object : BaseAdapter() {
            override fun getCount() = choices.size
            override fun getItem(position: Int) = choices[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
                val (key, down) = choices[position]
                return ((convert as? TextView) ?: TextView(activity).apply {
                    textSize = 12f
                    setTextColor(activity.getColor(R.color.on_surface))
                    val pad = (14 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }).apply {
                    text = "${if (key == sort && down == descending) "● " else "○ "}${key.label} · ${if (down) "降序 ↓" else "升序 ↑"}"
                }
            }
        }
        menu.setOnItemClickListener { _, _, position, _ ->
            val option = choices[position]
            sort = option.first; descending = option.second; sortOpen = false
            save(); onRender(); grid.setSelection(0)
        }
        metadata.onMetadataChanged = {
            if (active && sort == LibrarySort.DURATION) {
                val (done, total) = metadata.warmingProgress
                if (scope.size > 2000 && done < total) updateInfo() else onRender()
            }
        }
    }

    private fun changeView(folders: Boolean) {
        onInteract(); folderView = folders; sortOpen = false
        save(); onRender(); grid.setSelection(0)
    }
    private fun save() = prefs.edit().putBoolean("folders", folderView)
        .putString("sort", sort.name).putBoolean("descending", descending).apply()
    fun closeMenus(): Boolean { val open = sortOpen; sortOpen = false; return open }

    fun render(folders: List<LibraryFolder>, root: String?, directory: String, query: String): Int {
        selected = root; path = directory
        scope = LibraryCatalog.scope(folders, root, directory)
        if (sort == LibrarySort.DURATION && active) {
            preparing = true; metadata.prepareDurations(scope)
        } else if (preparing) { preparing = false; metadata.cancelDurationWarmup() }
        val entries = LibraryCatalog.sorted(LibraryCatalog.cards(folders, root, directory, query, folderView),
            sort, descending, metadata::cachedDuration)
        cards.setItems(entries)
        listOf(R.id.libraryFilesView to !folderView, R.id.libraryFolderView to folderView).forEach { (id, active) ->
            wrap.findViewById<View>(id).setBackgroundResource(if (active) R.drawable.bg_row_active else R.drawable.bg_input)
        }
        wrap.findViewById<TextView>(R.id.librarySort).text = "${sort.label} ${if (descending) "↓" else "↑"} ▾"
        wrap.findViewById<View>(R.id.librarySortMenu).visibility = if (sortOpen) View.VISIBLE else View.GONE
        wrap.findViewById<View>(R.id.libraryLocationRow).visibility = if (root != null) View.VISIBLE else View.GONE
        wrap.findViewById<TextView>(R.id.libraryLocation).apply {
            text = (folders.find { it.uri == root }?.name ?: "媒体库") + if (directory.isEmpty()) "" else "/$directory"
            contentDescription = text
        }
        updateInfo()
        return entries.size
    }

    private fun updateInfo() {
        val notes = mutableListOf<String>()
        if (folderView) notes.add("目录按内部视频的最新时间、合计大小或时长排序。")
        if (sort == LibrarySort.CREATED && scope.any { it.created <= 0 }) notes.add("部分创建时间不可用，已按修改时间排序。")
        if (sort == LibrarySort.DURATION) {
            val (done, total) = metadata.warmingProgress
            if (done < total) notes.add("正在读取时长 $done/$total，完成后更新排序…")
            else if (scope.any { metadata.cachedDuration(it) == 0L }) notes.add("部分时长无法读取，已置于末尾。")
        }
        wrap.findViewById<TextView>(R.id.librarySortInfo).apply {
            text = notes.joinToString("\n"); visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    fun close() { metadata.onMetadataChanged = null; metadata.cancelDurationWarmup() }
    fun resume() { active = true }
    fun suspend() { active = false; metadata.cancelDurationWarmup() }

    private inner class CardAdapter : BaseAdapter() {
        var items = emptyList<LibraryCard>()
            private set
        private var videoPositions = emptyMap<String, Int>()
        fun setItems(value: List<LibraryCard>) {
            items = value
            metadata.videos = value.mapNotNull { it.video }
            videoPositions = metadata.videos.mapIndexed { index, video -> video.uri to index }.toMap()
            notifyDataSetChanged()
        }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position].directory) 1 else 0
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val card = items[position]
            val video = card.video
            if (video != null) return metadata.getView(videoPositions.getValue(video.uri), convert, parent)
            val view = convert ?: activity.layoutInflater.inflate(R.layout.item_library_folder, parent, false)
            view.contentDescription = "文件夹 ${card.name}，${card.members.size} 个视频"
            view.findViewById<TextView>(R.id.folderCardName).text = card.name
            view.findViewById<TextView>(R.id.folderCardInfo).text = buildString {
                append("${card.members.size} 个视频 · ")
                append(Formatter.formatShortFileSize(activity, card.members.sumOf { it.size.coerceAtLeast(0) }))
                if (sort == LibrarySort.DURATION) LibraryCatalog.duration(card, metadata::cachedDuration)?.let {
                    append(" · ${Deo.formatDuration((it / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}")
                }
            }
            return view
        }
    }
}
