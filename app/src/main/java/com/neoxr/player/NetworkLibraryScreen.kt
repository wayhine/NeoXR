package com.neoxr.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

/** Per-device saved libraries, plus an in-layout network folder picker visible in the glasses. */
internal class NetworkLibraryScreen(
    private val activity: Activity,
    private val wrap: SbsFrameLayout,
    url: String,
    savedInstanceState: Bundle?,
    browseInitially: Boolean = false,
    private val onClose: () -> Unit
) : AutoCloseable {
    private lateinit var device: String
    private lateinit var initialLocation: String
    private lateinit var repository: LocalLibraryRepository
    private lateinit var adapter: LocalVideoAdapter
    private lateinit var libraryView: LibraryPresentation
    private val listingWorker = Executors.newSingleThreadExecutor()
    private var selected: String? = null
    private var path = ""
    private var query = ""
    private var folderMenu = false
    private var manager = false
    private var browsing = false
    private var browserUrl = ""
    private var listingGeneration = 0
    private var canSelect = false
    private var closed = false
    private var resumed = false
    private val observer: () -> Unit = { if (!closed) render() }

    init {
        initialLocation = url
        device = NetworkMedia.device(url)
        repository = LocalLibraryRepository.forNetwork(activity, device)
        adapter = LocalVideoAdapter(activity)
        selected = savedInstanceState?.getString("selected")
        path = savedInstanceState?.getString("path") ?: ""
        wrap.findViewById<View>(R.id.networkClose).setOnClickListener { onClose() }
        libraryView = LibraryPresentation(activity, wrap, "network_" + LocalLibraryRepository.cacheId(device), adapter,
            onInteract = { manager = false; folderMenu = false }, onRender = { render() },
            onNavigate = { root, directory ->
                selected = root; path = directory; manager = false; folderMenu = false
                render(); wrap.findViewById<GridView>(R.id.libraryGrid).setSelection(0)
            }, onPlay = { video -> activity.startActivity(Intent(activity, PlayerActivity::class.java).putExtra("stream", video.uri)) })
        wrap.findViewById<View>(R.id.libraryBrowse).setOnClickListener {
            libraryView.closeMenus()
            folderMenu = !folderMenu; manager = false; render()
        }
        wrap.findViewById<View>(R.id.libraryManage).setOnClickListener {
            libraryView.closeMenus()
            manager = !manager; folderMenu = false; render()
        }
        wrap.findViewById<View>(R.id.libraryAdd).setOnClickListener { browse(initialLocation) }
        wrap.findViewById<View>(R.id.libraryRefresh).setOnClickListener { adapter.retryMissingDurations(); repository.refresh() }
        wrap.findViewById<EditText>(R.id.librarySearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; render() }
        })
        wrap.findViewById<EditText>(R.id.librarySearch).setText(savedInstanceState?.getString("query").orEmpty())
        wrap.findViewById<View>(R.id.networkCancel).setOnClickListener { closeBrowser() }
        wrap.findViewById<View>(R.id.networkUp).setOnClickListener { NetworkMedia.parent(browserUrl, device)?.let { browse(it) } }
        wrap.findViewById<View>(R.id.networkSelect).setOnClickListener {
            if (canSelect && !repository.state.busy) {
                repository.addNetwork(browserUrl)
                selected = null; path = ""; manager = false; folderMenu = false
                wrap.findViewById<EditText>(R.id.librarySearch).text.clear()
                closeBrowser()
            }
        }
        render()
        if (savedInstanceState == null && browseInitially) browse(initialLocation)
    }

    private fun render() {
        val state = repository.state
        val folder = state.folders.find { it.uri == selected }
        if (folder == null && !state.busy) { selected = null; path = "" }
        val entryCount = libraryView.render(state.folders, selected, path, query)
        wrap.findViewById<TextView>(R.id.networkTitle).text =
            "${NetworkMedia.display(device).trimEnd('/')} · $entryCount 项"
        wrap.findViewById<TextView>(R.id.libraryBrowse).text =
            "${folder?.let { if (path.isEmpty()) it.name else path.substringAfterLast('/') } ?: "全部文件夹"} ▾"
        wrap.findViewById<View>(R.id.libraryAdd).isEnabled = !state.busy
        wrap.findViewById<View>(R.id.libraryRefresh).isEnabled = !state.busy
        wrap.findViewById<View>(R.id.libraryManage).setBackgroundResource(
            if (manager) R.drawable.bg_row_active else R.drawable.bg_input)
        val error = folder?.error ?: state.folders.count { it.error != null }
            .takeIf { it > 0 }?.let { "$it 个网络目录无法访问，已保留缓存。" }
        wrap.findViewById<TextView>(R.id.libraryStatus).apply {
            text = listOfNotNull(state.message, error).joinToString("\n")
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        wrap.findViewById<TextView>(R.id.libraryEmpty).apply {
            text = when {
                state.busy -> "正在加载网络媒体库…"
                state.folders.isEmpty() -> "请在“管理 → 添加目录”中选择此设备的媒体目录。"
                query.isNotBlank() -> "未找到匹配的视频或文件夹。"
                else -> "未找到视频，请在“管理”中重新扫描。"
            }
            visibility = if (entryCount == 0 && !folderMenu && !manager) View.VISIBLE else View.GONE
        }
        wrap.findViewById<View>(R.id.libraryManager).visibility = if (manager) View.VISIBLE else View.GONE
        wrap.findViewById<View>(R.id.libraryFolders).visibility = if (folderMenu) View.VISIBLE else View.GONE
        if (folderMenu) renderFolders(state.folders)
        if (manager) renderManager(state)
        if (browsing) wrap.findViewById<View>(R.id.networkSelect).isEnabled = canSelect && !state.busy
    }

    private fun renderFolders(folders: List<LibraryFolder>) {
        fun choose(uri: String?, subfolder: String) {
            selected = uri; path = subfolder; folderMenu = false; render()
            wrap.findViewById<GridView>(R.id.libraryGrid).setSelection(0)
        }
        val rows = listOf<Pair<String, () -> Unit>>("全部文件夹" to { choose(null, "") }) +
            folders.flatMap { folder ->
                listOf<Pair<String, () -> Unit>>("${folder.name} (${folder.videos.size})" to { choose(folder.uri, "") })
            }
        setRows(R.id.libraryFolders, rows)
    }

    private fun renderManager(state: LocalLibraryRepository.State) {
        val container = wrap.findViewById<LinearLayout>(R.id.libraryManageRows)
        val pad = (10 * activity.resources.displayMetrics.density).toInt()
        container.removeAllViews()
        state.folders.forEach { folder ->
            val row = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, pad, 0, pad) }
            row.addView(TextView(activity).apply {
                text = "${folder.name} · ${folder.videos.size} 个视频${if (folder.error != null) "\n不可用" else ""}"
                textSize = 11f; setTextColor(activity.getColor(R.color.on_surface)); setPadding(0, 0, pad, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = "移除"; textSize = 10f; setTextColor(activity.getColor(R.color.on_surface_variant))
                setBackgroundResource(R.drawable.bg_input); setPadding(pad, pad, pad, pad)
                isEnabled = !state.busy
                setOnClickListener { repository.remove(folder.uri) }
            })
            container.addView(row)
        }
    }

    private fun browse(url: String) {
        browsing = true; browserUrl = url; canSelect = false
        val generation = ++listingGeneration
        wrap.findViewById<View>(R.id.networkBrowser).visibility = View.VISIBLE
        wrap.findViewById<TextView>(R.id.networkPath).text = NetworkMedia.display(url)
        wrap.findViewById<TextView>(R.id.networkBrowserStatus).text = "正在读取共享目录…"
        wrap.findViewById<View>(R.id.networkSelect).isEnabled = false
        wrap.findViewById<View>(R.id.networkUp).isEnabled = NetworkMedia.parent(url, device) != null
        setRows(R.id.networkDirectoryList, emptyList())
        listingWorker.execute {
            val result = runCatching { NetworkMedia.list(url) }
            activity.runOnUiThread {
                if (closed || activity.isDestroyed || !browsing || generation != listingGeneration) return@runOnUiThread
                canSelect = result.isSuccess && NetworkMedia.isFolder(url)
                wrap.findViewById<View>(R.id.networkSelect).isEnabled = canSelect && !repository.state.busy
                wrap.findViewById<TextView>(R.id.networkBrowserStatus).text = result.exceptionOrNull()?.let { NetworkMedia.explain(url, it) }
                    ?: if (canSelect) "添加此目录，扫描其中的视频和子目录。"
                    else "请先打开共享，再选择要加入媒体库的目录。"
                setRows(R.id.networkDirectoryList, result.getOrDefault(emptyList()).filter { it.isDir }.map { entry ->
                    "▸ ${entry.name}" to { browse(NetworkMedia.child(url, entry.name)) }
                })
            }
        }
    }

    private fun closeBrowser() {
        browsing = false; listingGeneration++
        wrap.findViewById<View>(R.id.networkBrowser).visibility = View.GONE
        render()
    }

    private fun setRows(id: Int, rows: List<Pair<String, () -> Unit>>) {
        val list = wrap.findViewById<ListView>(id)
        val pad = (12 * activity.resources.displayMetrics.density).toInt()
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convert: View?, parent: ViewGroup): View =
                ((convert as? TextView) ?: TextView(activity).apply {
                    textSize = 11f; setTextColor(activity.getColor(R.color.on_surface)); setPadding(pad, pad, pad, pad)
                }).apply { text = rows[position].first }
        }
        list.setOnItemClickListener { _, _, position, _ -> rows[position].second() }
    }

    fun resume() {
        if (closed || resumed) return
        resumed = true
        libraryView.resume()
        repository.observe(observer)
    }
    fun pause() {
        if (!resumed) return
        resumed = false
        libraryView.suspend()
        repository.removeObserver(observer)
    }
    override fun close() {
        if (closed) return
        closed = true
        pause()
        listingGeneration++
        listingWorker.shutdownNow()
        libraryView.close()
        adapter.close()
    }
    fun saveState(outState: Bundle) {
        outState.putString("selected", selected); outState.putString("path", path)
        outState.putString("query", query)
    }
    fun onBack(): Boolean {
        when {
            browsing -> NetworkMedia.parent(browserUrl, device)?.let { browse(it) } ?: closeBrowser()
            libraryView.closeMenus() -> render()
            manager -> { manager = false; render() }
            folderMenu -> { folderMenu = false; render() }
            query.isNotEmpty() -> wrap.findViewById<EditText>(R.id.librarySearch).text.clear()
            path.isNotEmpty() -> { path = path.substringBeforeLast('/', ""); render() }
            selected != null -> { selected = null; path = ""; render() }
            else -> return false
        }
        return true
    }
}
