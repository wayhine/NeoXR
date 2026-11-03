package com.neoxr.player

/** Shared browsing contract; existing SMB addresses and cache identities stay unchanged. */
object NetworkMedia {
    fun isNetwork(url: String) = Smb.isSmb(url) || WebDav.isWebDav(url)
    fun device(url: String) = if (WebDav.isWebDav(url)) WebDav.connection(url) else Smb.device(url)
    fun display(url: String) = if (WebDav.isWebDav(url)) WebDav.display(url) else Smb.display(url)
    fun list(url: String) = if (WebDav.isWebDav(url)) WebDav.list(url) else Smb.list(url)
    fun child(url: String, name: String) = if (WebDav.isWebDav(url)) WebDav.child(url, name) else Smb.child(url, name)
    fun parent(url: String, root: String) = if (WebDav.isWebDav(url)) WebDav.parent(url, root) else Smb.parent(url)
    fun isFolder(url: String) = WebDav.isWebDav(url) || Smb.isFolder(url)
    fun belongsTo(url: String, root: String) = if (WebDav.isWebDav(root))
        WebDav.isWebDav(url) && WebDav.contains(root, url) else Smb.isSmb(url) && Smb.device(url) == root
    fun scan(url: String) = if (WebDav.isWebDav(url)) WebDav.scan(url) else Smb.scan(url)
    fun explain(url: String, error: Throwable) = if (WebDav.isWebDav(url)) WebDav.explain(error) else Smb.explain(error)
}
