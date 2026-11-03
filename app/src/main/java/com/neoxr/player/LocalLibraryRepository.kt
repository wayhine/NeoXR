package com.neoxr.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.util.concurrent.Executors

/** One worker owns the index, so scans cannot overwrite a later add/remove or activity recreation. */
class LocalLibraryRepository private constructor(context: Context, private val networkDevice: String? = null) {
    data class State(
        val folders: List<LibraryFolder> = emptyList(),
        val busy: Boolean = true,
        val message: String? = null
    )

    private val resolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences(
        networkDevice?.let { "smb_library_" + cacheId(it) } ?: "local_library", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<() -> Unit>()
    @Volatile var state = State()
        private set

    init {
        worker.execute {
            try {
                val folders = LocalLibrary.decode(prefs.getString("index", "[]") ?: "[]")
                publish(State(folders, busy = folders.isNotEmpty()))
                if (folders.isNotEmpty()) scan()
            } catch (_: Exception) {
                // Keep the on-disk value for recovery; never silently overwrite a failed load.
                publish(State(busy = false, message = "无法读取已保存的媒体库，请重新添加目录。"))
            }
        }
    }

    // Activity observers are registered/removed on the main thread.
    fun observe(observer: () -> Unit) { observers.add(observer); observer() }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }

    private fun publish(next: State) {
        state = next
        main.post { observers.toList().forEach { it() } }
    }

    private fun save(folders: List<LibraryFolder>): String? =
        if (prefs.edit().putString("index", LocalLibrary.encode(folders)).commit()) null
        else "媒体库保存失败，请检查剩余空间并重新扫描。"

    fun add(uri: Uri, flags: Int) {
        worker.execute {
            publish(state.copy(busy = true, message = "正在添加目录…"))
            try {
                require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                val name = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: "本地目录"
                val folders = state.folders.toMutableList()
                val existing = folders.indexOfFirst { it.uri == uri.toString() }
                if (existing < 0) folders.add(LibraryFolder(uri.toString(), name))
                else folders[existing] = folders[existing].copy(name = name, error = null)
                val message = save(folders)
                publish(State(folders, busy = true, message = message))
                scan()
            } catch (_: Exception) {
                publish(state.copy(busy = false, message = "无法保存目录访问权限，请重新选择并允许访问。"))
            }
        }
    }

    fun refresh() {
        if (state.busy) return
        worker.execute { scan() }
    }

    fun addNetwork(url: String) {
        require(networkDevice != null && NetworkMedia.belongsTo(url, networkDevice) && NetworkMedia.isFolder(url))
        worker.execute {
            val root = url.trimEnd('/')
            val folders = state.folders.toMutableList()
            val index = folders.indexOfFirst { it.uri == root }
            val name = if (WebDav.isWebDav(root)) WebDav.httpUrl(root).pathSegments.lastOrNull { it.isNotEmpty() }
                ?: WebDav.httpUrl(root).host else Smb.display(root).substringAfter('/')
            if (index < 0) folders.add(LibraryFolder(root, name))
            else folders[index] = folders[index].copy(error = null)
            publish(State(folders, busy = true, message = save(folders)))
            scan()
        }
    }

    fun remove(uri: String) {
        worker.execute {
            val folders = state.folders.filterNot { it.uri == uri }
            val message = save(folders)
            if (message == null) {
                // Do not remove a grant if the updated index could not be saved.
                if (networkDevice == null)
                    runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                publish(State(folders, busy = false))
            } else publish(state.copy(busy = false, message = message))
        }
    }

    private fun scan() {
        var folders = state.folders
        publish(State(folders, busy = true, message = "正在扫描目录…"))
        folders.indices.forEach { index ->
            val folder = folders[index]
            val result = runCatching { if (networkDevice == null) scanTree(Uri.parse(folder.uri)) else NetworkMedia.scan(folder.uri) }
            var scanned = LocalLibrary.scanned(folder, result)
            if (networkDevice != null && result.isFailure)
                scanned = scanned.copy(error = "网络目录无法访问，请检查服务器、网络和登录信息。已保留缓存。")
            folders = folders.toMutableList().apply { this[index] = scanned }
            publish(State(folders, busy = true, message = "正在扫描 ${index + 1}/${folders.size}…"))
        }
        publish(State(folders, busy = false, message = save(folders)))
    }

    private fun scanTree(tree: Uri): List<LibraryVideo> {
        return LocalLibrary.scan(DocumentsContract.getTreeDocumentId(tree)) { id ->
            val entries = mutableListOf<LibraryDocument>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val cursor = resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ), null, null, null) ?: error("未能读取目录内容")
            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.getString(0)
                    val name = it.getString(1) ?: childId
                    val mime = it.getString(2)
                    entries.add(LibraryDocument(childId, name, mime,
                        DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString(),
                        mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (it.isNull(3)) 0 else it.getLong(3),
                        if (it.isNull(4)) 0 else it.getLong(4)))
                }
                // Some remote providers return a partial listing while fetching more data.
                check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
            }
            entries
        }
    }

    companion object {
        @Volatile private var instance: LocalLibraryRepository? = null
        private val networks = mutableMapOf<String, LocalLibraryRepository>()
        internal fun cacheId(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun forNetwork(context: Context, url: String): LocalLibraryRepository = synchronized(networks) {
            val device = NetworkMedia.device(url)
            networks.getOrPut(device) { LocalLibraryRepository(context.applicationContext, device) }
        }
        fun get(context: Context): LocalLibraryRepository = instance ?: synchronized(this) {
            instance ?: LocalLibraryRepository(context.applicationContext).also { instance = it }
        }
    }
}
