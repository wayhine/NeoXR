package com.neoxr.player

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        // No bundled site list: the app ships empty and users add their own feeds.
        // Keeps the build free of third-party URLs.

        // Chinese help stays in-layout so it reads the same on the phone, in SBS
        // split and on the glasses.
        // NO manual wrapping inside sentences — the TextView wraps to its own width,
        // and hand-inserted breaks produce ragged lines at the glasses' width.
        val HELP = """
            NEOXR 社区二开版使用指南

            版权与开源说明
            本版本由 wayhine 维护，基于 NeoXR-app/NeoXR 二次开发，非上游官方发行版。本次修改日期：2026-11-04。
            上游源码与原有素材的版权归原作者和相应贡献者所有；二开新增与修改部分的权利归相应贡献者所有。本项目继续依照 GNU GPL 第3版发布，允许按许可证条款使用、修改与再分发，不提供担保。
            上游：https://github.com/NeoXR-app/NeoXR
            二开源码与版本：https://github.com/wayhine/NeoXR
            GPL-3.0 许可证全文随 APK 提供，见本帮助页末尾。第三方组件保留各自许可证与署名；品牌名称与标识的相关权利归其权利人所有，本二开版本不表示获得上游或硬件厂商的官方认可。

            本地媒体库
            “视频视图”集中显示当前范围的视频；“文件夹视图”按目录逐层浏览，支持搜索文件夹名及内部视频。“上一级”返回父目录。排序按钮可按名称、创建时间、文件大小或时长升降序排列，选择会保存；本地与各个网络设备分别保存。文件夹按内部视频的最新时间、合计大小或合计时长排序。
            时长在后台读取并缓存；部分创建时间无法获取时使用修改时间，并在界面提示。无法获取的时长排在最后。
            主页直接显示已保存的视频缩略图。点击“管理 → 添加目录”，在手机上选择文件夹并允许访问；会自动扫描视频和子目录。使用“全部文件夹”筛选目录，输入标题搜索视频。“重新扫描”更新目录；“移除”只移除媒体库记录，保留原文件。下次启动会恢复媒体库并在后台更新。目录失效时，连接存储设备或重新添加目录授权。

            网站与网络媒体库
            “网站 / 网络”切换到网站和网络设备。在输入框中填写网站、视频源或 SMB 地址，再点击“添加”。支持 DeoVR 接口的网站会显示视频缩略图；其他网站进入内置浏览器。点击来源旁的“×”可移除。
            WebDAV：点击“添加 WebDAV 媒体库”，填写服务的 HTTP / HTTPS 目录地址、用户名和密码。打开目录后点击“添加此目录”，自动递归扫描视频并生成缩略图媒体库。支持公开目录和用户名密码登录（Basic 认证）；账号信息保存在应用私有存储中。HTTPS 服务需使用受系统信任的证书。服务需支持范围读取才能跳转进度并提取缩略图。
            网络设备示例：smb://用户名:密码@192.168.1.10；访客共享可填写 smb://192.168.1.10。进入设备后，点击“管理 → 添加目录”，打开共享目录并选择“添加此目录”。网络视频会建立独立的缩略图媒体库，可搜索、筛选和续播。账号密码保存在应用私有存储中。
            “打开文件”临时浏览本机视频；也可以从文件管理器的“打开方式”或“分享”进入 NeoXR。

            内置浏览器
            网页播放视频后，点击“VR播放”交给播放器。箭头控制网页前进、后退。“视图”可切换手机、Quest 3 或电脑版页面；百分比按钮调整页面缩放。网页自身是否支持登录、播放由网站决定。

            眼镜与手机控制器
            连接 USB-C 眼镜后，画面显示在眼镜上，手机成为控制器。菜单中滑动移动指针、轻点选择、双指滚动。输入框会在手机上弹出编辑窗口。
            未连接眼镜时，长按菜单可切换菜单双屏显示；这与播放器中的长按倍速是不同的操作。多屏设备可用“屏幕”按钮切换输出。

            播放器手势与倍速
            轻点视频显示或隐藏控制栏；“播放 / 暂停”按钮控制播放状态。顶部的横竖屏切换按钮、手势、回正与关闭并排显示。
            播放器直接跟随手机系统的自动旋转设置：系统开启自动旋转时随手机方向转动，关闭时保持当前方向。没有额外开关。顶部按钮仍可一键切换横竖屏；系统自动旋转开启时，手动切换暂时生效，手机再次转到另一稳定方向后恢复自动跟随。眼镜模式下只改变手机控制器的方向。竖屏底部从上到下依次为进度条、播放控制、画质与字幕等按钮。
            手机和眼镜每次打开媒体默认单画面输出。“立体”中的“输出”可切换为 SBS 双屏。单画面须在眼镜上关闭 3D；SBS 须在眼镜上选择与输出对应的半宽或全宽模式并关闭 REAL 3D。视频源的二维、左右、上下布局与输出模式独立。
            进入“手势”可选择手机画面滑动方式：“调整视角”转动画面；“调整进度”左右滑动预览时间，松手后跳转。向右快进，向左后退；左半屏上下滑动调整屏幕亮度，右半屏上下滑动调整媒体音量，上滑增大，下滑减小。起始半屏和首次滑动方向会锁定，避免误调。亮度仅对当前播放窗口生效。进度条始终可以拖动。
            长按倍速可设为关闭、1.5×、2×、3×、4×、5×、6×。按住视频临时加速，松手恢复原速度；暂停状态不会被长按自动播放。取消触摸、切后台或切换屏幕也会恢复。手势和倍速设置会保存。
            眼镜模式下，手机底部常驻后退10秒、播放/暂停、前进15秒和按住倍速按钮。长按后退可回退1分钟，长按前进可前进5分钟。空白控制区滑动调整视角、双击切换陀螺仪、长按回正；顶部“回正”也可直接使用。
            视频会从上次停止的位置续播。双击可切换手机陀螺仪；画面方向不合适时点击“回正”。

            画面与立体设置
            左侧选择平面、宽屏、180°或360°投影；右侧选择二维、左右或上下布局。格式会自动识别，也可以手动选择。
            宽、高调整画面比例；缩放调整视野。“立体”面板可调景深、交换左右眼以及调整宽高。超过100的宽高会填满画面并裁切边缘。
            氛围光为平面和宽屏画面增加边缘光照，支持关闭、柔和、适中和明亮。内嵌黑边会自动避开。
            “画质”中可调整黑位、亮度、对比度、伽马、氛围光宽度及头控灵敏度。这些设置会保存。伽马低于100可提亮暗部。手机播放时，可切换单画面或双屏输出。

            字幕与音轨
            有多条音轨或字幕时，“字幕”按钮提供轨道选择。不受设备支持的轨道会标出；音轨解码失败时可静音继续播放。
            可调整字幕大小、高度、字重、颜色和源布局。已含左右眼的立体字幕请选择“已为立体字幕”，避免重复显示。字幕高度可设为负值，让字幕显示在画面下方的黑色区域。

            头部追踪
            支持 XREAL One / One Pro / 1S 和 Air 系列；其他设备可使用手机陀螺仪。在眼镜菜单中关闭防抖，选择跟随模式而非锚定模式，并更新固件。
            播放时点击左侧“头控”。Air 系列需允许 USB 访问。面向正前方后，点击“回正”或长按手机空白控制区校准。
            转动过快时，在“画质”中降低头控 / 陀螺仪灵敏度；100表示真实角度。再次点击“头控”返回手机陀螺仪。
            One / 1S 通过 USB 网络读取头控；手机须支持 USB 以太网，眼镜须启用 TCP/IP / 以太网。能显示视频不代表传感器链路已连接。连接失败时会自动重试，仍失败请关闭 Control Glasses / Nebula 等占用应用，并检查 VPN、线材和固件。只对头控套接字选路，不改变媒体库的 Wi-Fi 连接。
        """.trimIndent()
    }

    private val prefs by lazy { getSharedPreferences("neoxr", MODE_PRIVATE) }
    private var sites = mutableListOf<String>()
    private val adapter = SiteAdapter()

    private val REQ_OPEN_FILE = 7
    private val REQ_MEDIA_PERM = 8
    private val REQ_LOCAL_FOLDER = 9
    private var wrapView: SbsFrameLayout? = null
    private val library by lazy { LocalLibraryRepository.get(this) }
    private var libraryMode = true
    private var libraryFolder: String? = null
    private var libraryPath = ""
    private var libraryQuery = ""
    private var libraryFoldersOpen = false
    private var libraryManageOpen = false
    private val libraryAdapter by lazy { LocalVideoAdapter(this) }
    private var libraryView: LibraryPresentation? = null
    private val libraryObserver: () -> Unit = {
        updateHomeTabs()
        if (libraryMode) renderLibrary()
    }

    private fun pickLibraryFolder() {
        wrapView?.flash("请在手机上选择文件夹并允许访问。")
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                libraryFolder?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(it)) }
            }, REQ_LOCAL_FOLDER)
        } catch (_: android.content.ActivityNotFoundException) {
            wrapView?.flash("系统未提供文件夹选择器。")
        }
    }

    private fun showLibrary() {
        val wrap = wrapView ?: return
        libraryMode = true
        wrap.findViewById<View>(R.id.sites).visibility = View.GONE
        wrap.findViewById<View>(R.id.sitesEmpty).visibility = View.GONE
        wrap.findViewById<View>(R.id.libraryPanel).visibility = View.VISIBLE
        updateHomeTabs()
        renderLibrary()
    }

    private fun showSites() {
        val wrap = wrapView ?: return
        wrap.findViewById<LibraryScrollFrame>(R.id.libraryScroller)?.expand()
        libraryMode = false
        libraryFoldersOpen = false
        libraryManageOpen = false
        libraryView?.closeMenus()
        libraryAdapter.cancelDurationWarmup()
        wrap.findViewById<View>(R.id.libraryPanel).visibility = View.GONE
        wrap.findViewById<View>(R.id.sites).visibility = View.VISIBLE
        updateSitesSummary()
    }

    private fun updateSitesSummary() {
        updateHomeTabs()
        wrapView?.findViewById<View>(R.id.sitesEmpty)?.visibility =
            if (!libraryMode && sites.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateHomeTabs() {
        val wrap = wrapView ?: return
        val local = wrap.findViewById<TextView>(R.id.btnLibrary)
        val network = wrap.findViewById<TextView>(R.id.btnSources)
        local.text = "本地媒体库 (${library.state.folders.sumOf { it.videos.size }})"
        network.text = "网站 / 网络 (${sites.size})"
        listOf(local to libraryMode, network to !libraryMode).forEach { (tab, selected) ->
            tab.isSelected = selected
            tab.setBackgroundResource(if (selected) R.drawable.bg_row_active else R.drawable.bg_input)
            tab.setTextColor(getColor(if (selected) R.color.on_surface else R.color.on_surface_variant))
        }
        wrap.findViewById<View>(R.id.siteControls).visibility = if (libraryMode) View.GONE else View.VISIBLE
        wrap.findViewById<View>(R.id.addWebDav).visibility = if (libraryMode) View.GONE else View.VISIBLE
        wrap.findViewById<TextView>(R.id.homeHint).text = if (libraryMode)
            "点击播放 · 菜单长按切换双屏" else "点击浏览来源 · 菜单长按切换双屏"
    }

    private fun libraryRows(): List<Pair<String, () -> Unit>> {
        val state = library.state
        fun choose(root: String?, path: String) {
            libraryFolder = root
            libraryPath = path
            libraryFoldersOpen = false
            renderLibrary()
            wrapView?.findViewById<GridView>(R.id.libraryGrid)?.setSelection(0)
        }
        return listOf<Pair<String, () -> Unit>>(
            "全部视频 (${LocalLibrary.videos(state.folders, null, "", "").size})" to { choose(null, "") }
        ) + state.folders.flatMap { folder ->
            listOf<Pair<String, () -> Unit>>(
                "${folder.name} (${folder.videos.size})${if (folder.error != null) " · 不可用" else ""}" to {
                    choose(folder.uri, "")
                }
            )
        }
    }

    private fun renderLibrary() {
        val wrap = wrapView ?: return
        val state = library.state
        val selected = state.folders.find { it.uri == libraryFolder }
        if (selected == null && !state.busy) {
            libraryFolder = null
            libraryPath = ""
        }
        val entryCount = libraryView?.render(state.folders, libraryFolder, libraryPath, libraryQuery) ?: 0
        wrap.findViewById<TextView>(R.id.libraryBrowse).text =
            "${selected?.let { if (libraryPath.isEmpty()) it.name else libraryPath.substringAfterLast('/') } ?: "全部文件夹"} ▾"
        wrap.findViewById<View>(R.id.libraryRefresh).isEnabled = !state.busy
        wrap.findViewById<View>(R.id.libraryAdd).isEnabled = !state.busy
        wrap.findViewById<View>(R.id.libraryManage).apply {
            isSelected = libraryManageOpen
            setBackgroundResource(if (libraryManageOpen) R.drawable.bg_row_active else R.drawable.bg_input)
        }
        val status = wrap.findViewById<TextView>(R.id.libraryStatus)
        val unavailable = if (selected != null) selected.error else
            state.folders.count { it.error != null }.takeIf { it > 0 }?.let { "$it 个目录无法访问，已保留缓存。" }
        status.text = listOfNotNull(state.message, unavailable).joinToString("\n")
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        wrap.findViewById<TextView>(R.id.libraryEmpty).apply {
            text = when {
                state.busy && state.folders.isEmpty() -> "正在加载媒体库…"
                state.folders.isEmpty() -> "媒体库为空，请在“管理”中添加文件夹。"
                libraryQuery.isNotBlank() -> "未找到匹配的视频或文件夹。"
                else -> "未找到视频。添加视频后，请在“管理”中重新扫描。"
            }
            visibility = if (entryCount == 0 && !libraryFoldersOpen && !libraryManageOpen) View.VISIBLE else View.GONE
        }
        val folders = wrap.findViewById<ListView>(R.id.libraryFolders)
        folders.visibility = if (libraryFoldersOpen) View.VISIBLE else View.GONE
        wrap.findViewById<View>(R.id.libraryManager).visibility = if (libraryManageOpen) View.VISIBLE else View.GONE
        if (libraryManageOpen) renderLibraryManager(state)
        if (libraryFoldersOpen) {
            val rows = libraryRows()
            val pad = (12 * resources.displayMetrics.density).toInt()
            folders.adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(position: Int) = rows[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convert: View?, parent: ViewGroup): View =
                    ((convert as? TextView) ?: TextView(this@MainActivity).apply {
                        textSize = 11f
                        setTextColor(getColor(R.color.on_surface))
                        setPadding(pad, pad, pad, pad)
                    }).apply { text = rows[position].first }
            }
            folders.setOnItemClickListener { _, _, position, _ -> rows[position].second() }
        }
    }

    private fun renderLibraryManager(state: LocalLibraryRepository.State) {
        val container = wrapView?.findViewById<LinearLayout>(R.id.libraryManageRows) ?: return
        val pad = (10 * resources.displayMetrics.density).toInt()
        container.removeAllViews()
        state.folders.forEach { folder ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
            }
            row.addView(TextView(this).apply {
                text = "${folder.name} · ${folder.videos.size} 个视频${if (folder.error != null) "\n目录不可用，请重新添加以恢复访问。" else ""}"
                textSize = 11f
                setTextColor(getColor(R.color.on_surface))
                setPadding(0, 0, pad, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "移除"
                contentDescription = "从媒体库移除 ${folder.name}"
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.on_surface_variant))
                setBackgroundResource(R.drawable.bg_input)
                setPadding(pad, pad, pad, pad)
                isEnabled = !state.busy
                alpha = if (state.busy) 0.45f else 1f
                setOnClickListener { library.remove(folder.uri) }
            })
            container.addView(row)
        }
    }

    private fun mediaPermission() =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun openLocalVideos() {
        if (checkSelfPermission(mediaPermission()) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) showLocalVideos() else requestPermissions(arrayOf(mediaPermission()), REQ_MEDIA_PERM)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MEDIA_PERM) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showLocalVideos()
        } else {
            // no media access — the system document picker still works without it
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }, REQ_OPEN_FILE)
        }
    }

    // One video on the device: its URI, file name, duration and the folder it lives in.
    private data class LocalVideo(
        val uri: android.net.Uri, val name: String, val duration: String, val folder: String
    )

    private var localVideos = listOf<LocalVideo>()
    private var openFolder: String? = null // null = folder list, otherwise its videos

    private fun showLocalVideos() {
        val wrap = wrapView ?: return
        val found = mutableListOf<LocalVideo>()
        try {
            contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    android.provider.MediaStore.Video.Media._ID,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.DURATION,
                    android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                ),
                null, null,
                android.provider.MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(0)
                    )
                    found.add(
                        LocalVideo(
                            uri,
                            c.getString(1) ?: "?",
                            Deo.formatDuration((c.getLong(2) / 1000).toInt()),
                            c.getString(3) ?: "其他"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取视频失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        localVideos = found
        openFolder = null
        renderFileList()
        wrap.findViewById<View>(R.id.fileOverlay).visibility = View.VISIBLE
    }

    /**
     * Draws either the folder list or the videos inside [openFolder]. A flat list of
     * every video on the device is unusable on a real library, so the first level
     * groups by folder (MediaStore's bucket) with a count per row.
     */
    private fun renderFileList() {
        val wrap = wrapView ?: return
        val title = wrap.findViewById<TextView>(R.id.fileTitle)
        val list = wrap.findViewById<ListView>(R.id.fileList)
        val pad = (12 * resources.displayMetrics.density).toInt()

        val folder = openFolder
        // rows: label to action
        val rows: List<Pair<String, () -> Unit>> = if (folder == null) {
            title.text = "本机视频"
            wrap.findViewById<TextView>(R.id.fileEmpty).text = "本机未找到视频。"
            localVideos.groupBy { it.folder }.entries
                .sortedBy { it.key.lowercase() }
                .map { (name, items) ->
                    "▸  $name   (${items.size})" to { openFolder = name; renderFileList() }
                }
        } else {
            title.text = "◂  ${folder.uppercase()}"
            listOf<Pair<String, () -> Unit>>(
                "◂  返回目录" to { openFolder = null; renderFileList() }
            ) + localVideos.filter { it.folder == folder }.map { v ->
                "${v.name}   ·   ${v.duration}" to {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .setData(v.uri)
                    )
                }
            }
        }

        wrap.findViewById<View>(R.id.fileEmpty).visibility =
            if (rows.isEmpty()) View.VISIBLE
            else View.GONE
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(pos: Int) = rows[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
                val v = (convert as? TextView) ?: TextView(this@MainActivity).apply {
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setTextColor(getColor(R.color.on_surface))
                    setPadding(pad, pad, pad, pad)
                }
                v.text = rows[pos].first
                return v
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> rows[pos].second() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // findViewById via wrap: with glasses attached the content lives on THEM,
        // not in the activity's content view (which is then the touchpad controller)
        val wrap = SbsFrameLayout.attach(this, R.layout.activity_main)
        // version in the header so a stale APK is visible at a glance
        wrap.findViewById<TextView>(R.id.subtitle).append(
            " · v" + packageManager.getPackageInfo(packageName, 0).versionName
        )
        load()

        val list = wrap.findViewById<ListView>(R.id.sites)
        val input = wrap.findViewById<EditText>(R.id.siteInput)
        list.adapter = adapter

        val help = wrap.findViewById<View>(R.id.helpOverlay)
        wrap.findViewById<TextView>(R.id.helpText).text = HELP
        wrap.findViewById<View>(R.id.btnHelp).setOnClickListener {
            wrap.findViewById<TextView>(R.id.helpText).text = HELP +
                "\n\nGNU GENERAL PUBLIC LICENSE — Version 3\n\n" +
                assets.open("LICENSE").bufferedReader(Charsets.UTF_8).use { it.readText() }
            help.visibility = View.VISIBLE
        }
        wrap.findViewById<View>(R.id.helpClose).setOnClickListener { help.visibility = View.GONE }

        // local videos: an IN-LAYOUT MediaStore list — the system picker is a
        // phone-only window and cannot be shown on the glasses (same limitation as
        // dialogs/IME), while this overlay lives in the wrap: on the glasses,
        // SBS-aware, cursor-clickable. Fallback to the system picker only when the
        // media permission is denied.
        wrap.findViewById<View>(R.id.btnFile).setOnClickListener { openLocalVideos() }
        wrap.findViewById<View>(R.id.btnLibrary).setOnClickListener { showLibrary() }
        wrap.findViewById<View>(R.id.btnSources).setOnClickListener { showSites() }
        wrap.findViewById<View>(R.id.libraryAdd).setOnClickListener { pickLibraryFolder() }
        wrap.findViewById<View>(R.id.libraryRefresh).setOnClickListener { libraryAdapter.retryMissingDurations(); library.refresh() }
        wrap.findViewById<View>(R.id.libraryManage).setOnClickListener {
            libraryView?.closeMenus()
            libraryManageOpen = !libraryManageOpen
            libraryFoldersOpen = false
            renderLibrary()
        }
        wrap.findViewById<View>(R.id.libraryBrowse).setOnClickListener {
            libraryView?.closeMenus()
            libraryFoldersOpen = !libraryFoldersOpen
            libraryManageOpen = false
            renderLibrary()
        }
        wrap.findViewById<EditText>(R.id.librarySearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                libraryQuery = s?.toString() ?: ""
                if (libraryMode) {
                    renderLibrary()
                    wrap.findViewById<GridView>(R.id.libraryGrid).setSelection(0)
                }
            }
        })
        wrap.findViewById<View>(R.id.fileClose).setOnClickListener {
            wrapView?.findViewById<View>(R.id.fileOverlay)?.visibility = View.GONE
        }
        wrapView = wrap
        libraryView = LibraryPresentation(this, wrap, "local", libraryAdapter,
            onInteract = { libraryManageOpen = false; libraryFoldersOpen = false },
            onRender = { if (libraryMode) renderLibrary() },
            onNavigate = { root, directory ->
                libraryFolder = root; libraryPath = directory
                libraryManageOpen = false; libraryFoldersOpen = false
                renderLibrary(); wrap.findViewById<GridView>(R.id.libraryGrid).setSelection(0)
            },
            onPlay = { video -> startActivity(Intent(this, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse(video.uri))) })
        libraryFolder = savedInstanceState?.getString("libraryFolder")
        libraryPath = savedInstanceState?.getString("libraryPath") ?: ""
        updateSitesSummary()
        if (savedInstanceState?.getBoolean("libraryMode") != false) showLibrary() else showSites()

        fun addTypedSite() {
            try {
                val url = Deo.normalizeSiteUrl(input.text.toString())
                if (url !in sites) {
                    sites.add(url)
                    save()
                    adapter.notifyDataSetChanged()
                }
                input.text.clear()
                showSites()
            } catch (e: Exception) {
                Toast.makeText(this, "地址格式不正确", Toast.LENGTH_SHORT).show()
            }
        }
        wrap.findViewById<Button>(R.id.addSite).setOnClickListener { addTypedSite() }
        wrap.findViewById<View>(R.id.addWebDav).setOnClickListener { addWebDav() }
        // Done on the keyboard adds the site too — typing a URL and then hunting for
        // the Add button is a tap too many
        input.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                            event.action == android.view.KeyEvent.ACTION_DOWN)
            if (done && input.text.isNotBlank()) {
                addTypedSite()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                input.clearFocus()
            }
            done
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val url = sites[pos]
            if (NetworkMedia.isNetwork(url)) startActivity(Intent(this, NetworkLibraryActivity::class.java).putExtra("url", url))
            else startActivity(Intent(this, VideoListActivity::class.java).putExtra("url", url))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOCAL_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            libraryFolder = null
            libraryPath = ""
            libraryFoldersOpen = false
            libraryManageOpen = false
            wrapView?.findViewById<EditText>(R.id.librarySearch)?.text?.clear()
            library.add(uri, data.flags)
            showLibrary()
            return
        }
        if (requestCode != REQ_OPEN_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // survive player recreate on glasses plug/unplug (grants die with the task)
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
        )
    }

    override fun onStart() {
        super.onStart()
        libraryView?.resume()
        library.observe(libraryObserver)
    }

    override fun onStop() {
        libraryView?.suspend()
        library.removeObserver(libraryObserver)
        super.onStop()
    }

    override fun onDestroy() {
        libraryView?.close()
        if (wrapView?.findViewById<GridView>(R.id.libraryGrid)?.adapter != null) libraryAdapter.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("libraryMode", libraryMode)
        outState.putString("libraryFolder", libraryFolder)
        outState.putString("libraryPath", libraryPath)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wrap = wrapView
        if (wrap?.findViewById<View>(R.id.fileOverlay)?.visibility == View.VISIBLE) {
            wrap.findViewById<View>(R.id.fileClose).performClick()
        } else if (wrap?.findViewById<View>(R.id.helpOverlay)?.visibility == View.VISIBLE) {
            wrap.findViewById<View>(R.id.helpClose).performClick()
        } else if (!libraryMode) {
            showLibrary()
        } else {
            if (libraryView?.closeMenus() == true) { renderLibrary(); return }
            if (libraryManageOpen) libraryManageOpen = false
            else if (libraryFoldersOpen) libraryFoldersOpen = false
            else if (libraryQuery.isNotEmpty()) wrapView?.findViewById<EditText>(R.id.librarySearch)?.text?.clear()
            else if (libraryPath.isNotEmpty()) libraryPath = libraryPath.substringBeforeLast('/', "")
            else if (libraryFolder != null) libraryFolder = null
            else {
                super.onBackPressed()
                return
            }
            renderLibrary()
        }
    }

    private fun addWebDav() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hintText: String, type: Int) = EditText(this).apply {
            hint = hintText
            inputType = type
            setSingleLine(true)
            fields.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val address = field("WebDAV 地址，如 https://nas:5006/dav/", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val user = field("用户名（公开目录可留空）", android.text.InputType.TYPE_CLASS_TEXT)
        val password = field("密码", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dialog = AlertDialog.Builder(this).setTitle("添加 WebDAV 媒体库")
            .setView(fields).setPositiveButton("添加", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val url = WebDav.connection(address.text.toString(), user.text.toString(), password.text.toString())
                    if (url !in sites) { sites.add(url); save(); adapter.notifyDataSetChanged() }
                    showSites()
                    dialog.dismiss()
                    startActivity(Intent(this, NetworkLibraryActivity::class.java).putExtra("url", url).putExtra("browse", true))
                } catch (_: IllegalArgumentException) {
                    address.error = "请填写完整的 HTTP 或 HTTPS WebDAV 目录地址"
                }
            }
        }
        dialog.show()
    }

    private fun confirmRemove(url: String) {
        AlertDialog.Builder(this)
            .setMessage("移除 ${host(url)}？")
            .setPositiveButton("移除") { _, _ ->
                sites.remove(url)
                save()
                adapter.notifyDataSetChanged()
                updateSitesSummary()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // The credentials of a share stay in storage but never on screen: a site row is
    // also drawn on the glasses, and a password does not belong in a list anyone can
    // look at over your shoulder.
    private fun host(url: String) =
        if (WebDav.isWebDav(url)) "WebDAV · ${WebDav.httpUrl(url).host}"
        else if (Smb.isSmb(url)) Smb.display(url).substringBefore('/')
        else try {
            URL(url).host.removePrefix("www.")
        } catch (e: Exception) {
            url
        }

    private fun shown(url: String) =
        if (WebDav.isWebDav(url)) WebDav.display(url)
        else if (Smb.isSmb(url)) Smb.SCHEME + Smb.display(url) else url

    private fun load() {
        val arr = JSONArray(prefs.getString("sites", "[]"))
        sites = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
    }

    private fun save() = prefs.edit()
        .putString("sites", JSONArray(sites).toString())
        .apply()

    private inner class SiteAdapter : BaseAdapter() {
        override fun getCount() = sites.size
        override fun getItem(pos: Int) = sites[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val view = convert ?: layoutInflater.inflate(R.layout.item_site, parent, false)
            val url = sites[pos]
            val h = host(url)
            view.findViewById<TextView>(R.id.siteAvatar).text =
                h.firstOrNull()?.uppercase() ?: "?"
            view.findViewById<TextView>(R.id.siteHost).text = h
            view.findViewById<TextView>(R.id.siteUrl).text = shown(url)
            view.findViewById<TextView>(R.id.siteRemove).setOnClickListener {
                confirmRemove(url)
            }
            return view
        }
    }
}
