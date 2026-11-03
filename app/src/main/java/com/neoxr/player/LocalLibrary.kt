package com.neoxr.player

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale

/** Stored content URIs stay inside their original tree grant, including for nested videos. */
data class LibraryVideo(
    val uri: String, val name: String, val folder: String,
    val size: Long = 0, val modified: Long = 0, val created: Long = 0
) {
    val cacheKey: String get() = "$uri|$size|$modified"
}

internal data class LibraryDocument(
    val id: String, val name: String, val mime: String?, val uri: String, val isDirectory: Boolean,
    val size: Long = 0, val modified: Long = 0, val created: Long = 0
)

data class LibraryFolder(
    val uri: String,
    val name: String,
    val videos: List<LibraryVideo> = emptyList(),
    val error: String? = null
)

object LocalLibrary {
    fun isVideo(name: String, mime: String?): Boolean =
        mime?.startsWith("video/", ignoreCase = true) == true ||
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("mp4", "mkv", "mov", "webm", "m4v", "avi", "mpg", "mpeg", "ts", "m2ts", "3gp")

    internal fun scan(rootId: String, children: (String) -> List<LibraryDocument>): List<LibraryVideo> {
        val found = mutableListOf<LibraryVideo>()
        val pending = ArrayDeque<Pair<String, String>>()
        val visited = mutableSetOf<String>()
        pending.add(rootId to "")
        while (pending.isNotEmpty()) {
            val (id, path) = pending.removeFirst()
            if (!visited.add(id)) continue
            children(id).forEach { child ->
                if (child.isDirectory) pending.add(child.id to if (path.isEmpty()) child.name else "$path/${child.name}")
                else if (isVideo(child.name, child.mime)) found.add(LibraryVideo(child.uri, child.name, path, child.size, child.modified, child.created))
            }
        }
        return found.distinctBy { it.uri }
    }

    fun encode(folders: List<LibraryFolder>): String = JSONArray().apply {
        folders.forEach { folder ->
            put(JSONObject().put("uri", folder.uri).put("name", folder.name)
                .put("videos", JSONArray().apply {
                    folder.videos.forEach { video ->
                        put(JSONObject().put("uri", video.uri).put("name", video.name)
                            .put("folder", video.folder).put("size", video.size).put("modified", video.modified).put("created", video.created))
                    }
                }))
        }
    }.toString()

    fun decode(json: String): List<LibraryFolder> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val folder = array.getJSONObject(index)
            val videos = folder.getJSONArray("videos")
            LibraryFolder(folder.getString("uri"), folder.getString("name"),
                (0 until videos.length()).map { videoIndex ->
                    val video = videos.getJSONObject(videoIndex)
                    LibraryVideo(video.getString("uri"), video.getString("name"), video.getString("folder"),
                        video.optLong("size"), video.optLong("modified"), video.optLong("created"))
                }.distinctBy { it.uri })
        }.distinctBy { it.uri }
    }

    fun videos(folders: List<LibraryFolder>, root: String?, path: String, query: String): List<LibraryVideo> =
        folders.asSequence().filter { root == null || it.uri == root }.flatMap { it.videos.asSequence() }
            .filter { path.isEmpty() || it.folder == path || it.folder.startsWith("$path/") }
            .filter { it.name.contains(query.trim(), ignoreCase = true) }
            .distinctBy { it.uri }.sortedBy { it.name.lowercase(Locale.ROOT) }.toList()

    /** Only replace a cached listing when the entire tree was read successfully. */
    fun scanned(folder: LibraryFolder, result: Result<List<LibraryVideo>>): LibraryFolder =
        result.fold(
            onSuccess = { folder.copy(videos = it.distinctBy { video -> video.uri }
                .sortedBy { video -> "${video.folder}/${video.name}".lowercase(Locale.ROOT) }, error = null) },
            onFailure = { folder.copy(error = "目录无法访问。请连接存储设备，或重新添加目录以授权访问。已保留缓存。") }
        )
}
