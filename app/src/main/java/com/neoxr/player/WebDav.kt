package com.neoxr.player

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Read-only WebDAV collections over HTTP(S); credentials never enter request URLs. */
object WebDav {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val properties = """<?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/>
        <d:getlastmodified/><d:creationdate/></d:prop></d:propfind>""".trimIndent()

    internal class HttpFailure(val status: Int) : IOException("WebDAV HTTP $status")
    internal class DirectoryRedirect : IOException("WebDAV directory redirected")
    fun isWebDav(url: String) = url.startsWith("webdav://", true) || url.startsWith("webdavs://", true)

    internal fun httpUrl(url: String): HttpUrl {
        require(isWebDav(url)) { "WebDAV 地址无效" }
        return ((if (url.startsWith("webdavs:", true)) "https" else "http") + url.substring(url.indexOf(':'))).toHttpUrl()
    }
    internal fun stored(url: HttpUrl): String =
        (if (url.isHttps) "webdavs" else "webdav") + url.toString().substring(url.scheme.length)

    fun connection(address: String, user: String = "", password: String = ""): String {
        val value = address.trim()
        val url = if (isWebDav(value)) httpUrl(value) else value.toHttpUrl()
        require(url.query == null && url.fragment == null) { "请填写 WebDAV 目录地址，不要包含查询参数或片段" }
        val builder = url.newBuilder()
        if (user.isNotEmpty() || password.isNotEmpty()) builder.username(user).password(password)
        if (!url.encodedPath.endsWith('/')) builder.addPathSegment("")
        return stored(builder.build())
    }
    fun display(url: String): String = httpUrl(url).newBuilder().username("").password("").build().toString()
    fun child(url: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name) { "目录名称无效" }
        return stored(httpUrl(connection(url)).newBuilder().addPathSegment(name).build())
    }
    fun contains(root: String, url: String): Boolean {
        val a = httpUrl(root); val b = httpUrl(url)
        val prefix = a.pathSegments.dropLastWhile { it.isEmpty() }
        return sameOrigin(a, b) && a.username == b.username && a.password == b.password &&
            b.pathSegments.dropLastWhile { it.isEmpty() }.take(prefix.size) == prefix
    }
    fun parent(url: String, root: String): String? {
        if (!contains(root, url)) return null
        val current = httpUrl(connection(url))
        if (current.pathSegments == httpUrl(connection(root)).pathSegments) return null
        return stored(current.newBuilder().removePathSegment(current.pathSegments.lastIndex)
            .removePathSegment(current.pathSegments.lastIndex - 1).addPathSegment("").build())
    }
    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun request(url: String): Request.Builder {
        val parsed = httpUrl(url)
        return Request.Builder().url(parsed.newBuilder().username("").password("").build())
            .header("Accept-Encoding", "identity").apply {
                if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty())
                    header("Authorization", Credentials.basic(parsed.username, parsed.password, Charsets.UTF_8))
            }
    }

    internal fun execute(original: Request): Response {
        var next = original
        repeat(6) {
            val response = http.newCall(next).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val target = response.header("Location")?.let { next.url.resolve(it) }
            response.close()
            if (target == null || (next.url.isHttps && !target.isHttps))
                throw IOException("WebDAV 重定向地址无效")
            if (next.method != "GET" && (!sameOrigin(next.url, target) ||
                    next.url.encodedPath.trimEnd('/') != target.encodedPath.trimEnd('/') || next.url.encodedQuery != target.encodedQuery))
                throw DirectoryRedirect()
            val builder = next.newBuilder().url(target.newBuilder().username("").password("").build())
            if (!sameOrigin(next.url, target)) {
                builder.removeHeader("Authorization")
            }
            next = builder.build()
        }
        throw IOException("WebDAV 重定向次数过多")
    }

    fun list(url: String): List<Smb.Entry> {
        val directory = connection(url)
        val req = request(directory).header("Depth", "1")
            .method("PROPFIND", properties.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        execute(req).use { response ->
            if (response.code != 207) throw HttpFailure(response.code)
            val body = response.body ?: throw IOException("WebDAV 返回了空目录数据")
            val source = body.source()
            if (source.request(8L * 1024 * 1024 + 1)) throw IOException("WebDAV 单个目录过大，请选择更小的目录")
            return parseListing(directory, body.string())
        }
    }

    internal fun parseListing(directory: String, xml: String): List<Smb.Entry> {
        require(!xml.contains("<!DOCTYPE", true)) { "WebDAV 返回了不支持的 XML 文档" }
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IOException("不允许读取外部 XML 实体") }
        val doc = builder.parse(InputSource(StringReader(xml)))
        require(doc.documentElement.localName == "multistatus" && doc.documentElement.namespaceURI == "DAV:") {
            "服务器没有返回 WebDAV 目录，请检查地址"
        }
        val base = httpUrl(connection(directory))
        val prefix = base.pathSegments.dropLastWhile { it.isEmpty() }
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        require(responses.length > 0) { "WebDAV 目录数据不完整" }
        val entries = mutableListOf<Smb.Entry>()
        for (i in 0 until responses.length) {
            val item = responses.item(i) as? Element ?: continue
            val target = base.resolve(item.value("href")) ?: continue
            if (!sameOrigin(base, target) || target.query != null || target.fragment != null) continue
            val parts = target.pathSegments.dropLastWhile { it.isEmpty() }
            val self = parts == prefix
            if (!self && (parts.size != prefix.size + 1 || parts.take(prefix.size) != prefix)) continue
            val blocks = item.getElementsByTagNameNS("DAV:", "propstat")
            val good = mutableListOf<Element>()
            for (j in 0 until blocks.length) {
                val block = blocks.item(j) as Element
                if (block.value("status").split(Regex("\\s+")).getOrNull(1) == "200") good.add(block)
            }
            if (good.isEmpty()) throw IOException("WebDAV 无法读取部分文件信息，请检查目录权限")
            val folder = good.any { it.getElementsByTagNameNS("DAV:", "collection").length > 0 }
            if (self) {
                if (!folder) throw IOException("此 WebDAV 地址不是目录")
                continue
            }
            val name = parts.last()
            if (name.isEmpty() || name == "." || name == ".." || '/' in name) continue
            fun property(tag: String) = good.firstNotNullOfOrNull { it.value(tag).takeIf { value -> value.isNotEmpty() } } ?: ""
            if (name.startsWith('.') || (!folder && !Smb.isVideo(name))) continue
            val modified = runCatching { ZonedDateTime.parse(property("getlastmodified"),
                DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0L)
            val created = runCatching { java.time.Instant.parse(property("creationdate")).toEpochMilli() }.getOrDefault(0L)
            entries.add(Smb.Entry(name, folder, property("getcontentlength").toLongOrNull()?.coerceAtLeast(0) ?: 0, modified, created = created))
        }
        return entries.distinctBy { it.name }.sortedWith(compareByDescending<Smb.Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }
    private fun Element.value(tag: String) = getElementsByTagNameNS("DAV:", tag).item(0)?.textContent?.trim() ?: ""

    fun scan(root: String): List<LibraryVideo> = LocalLibrary.scan(root) { directory ->
        list(directory).map { entry ->
            val url = child(directory, entry.name)
            LibraryDocument(url, entry.name, if (Smb.isVideo(entry.name)) "video/*" else null,
                url, entry.isDir, entry.size, entry.modified, entry.created)
        }
    }
    fun explain(error: Throwable): String = when (error) {
        is DirectoryRedirect -> "WebDAV 地址发生跳转，请填写重定向后的完整目录地址。"
        is HttpFailure -> when (error.status) {
            401, 403 -> "WebDAV 登录或目录访问被拒绝，请检查用户名、密码和权限。"
            404 -> "找不到 WebDAV 目录，请检查完整路径。"
            405, 501 -> "此地址不支持 WebDAV，请填写服务的 WebDAV 入口地址。"
            else -> "WebDAV 请求失败（HTTP ${error.status}），请检查服务状态。"
        }
        is javax.net.ssl.SSLException -> "WebDAV 安全连接失败，请检查服务器证书。"
        else -> "WebDAV 读取失败，请检查地址、网络、账号及服务器的范围读取支持。"
    }

    internal fun open(url: String, position: Long, length: Long = -1): Response {
        require(position >= 0 && length != 0L)
        val end = if (length > 0) (position + length - 1).toString() else ""
        val response = execute(request(url).header("Range", "bytes=$position-$end").get().build())
        try {
            if (response.code !in setOf(200, 206)) throw HttpFailure(response.code)
            if (response.code == 206) {
                val start = Regex("bytes (\\d+)-\\d+/[\\d*]+").matchEntire(response.header("Content-Range") ?: "")
                    ?.groupValues?.get(1)?.toLongOrNull()
                if (start != position) throw IOException("WebDAV 返回了错误的读取范围")
            } else if (position > 0) throw IOException("WebDAV 服务不支持范围读取，无法跳转进度或提取缩略图")
            return response
        } catch (e: Exception) { response.close(); throw e }
    }
    internal fun length(url: String): Long = open(url, 0, 1).use { response ->
        response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: response.body?.contentLength()?.takeIf { response.code == 200 && it >= 0 }
            ?: throw IOException("WebDAV 未返回文件大小")
    }
}
