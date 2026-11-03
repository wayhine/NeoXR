package com.neoxr.player

import android.app.Activity
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.signature.ObjectKey
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Same cards as the feed browser, with bounded background work for local video metadata. */
class LocalVideoAdapter(private val activity: Activity) : BaseAdapter() {
    var videos: List<LibraryVideo> = emptyList()
    private val context = activity.applicationContext
    private val prefs = context.getSharedPreferences("local_video_metadata", Context.MODE_PRIVATE)
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val durations = mutableMapOf<String, Long>()
    private val pending = mutableMapOf<String, MutableList<WeakReference<TextView>>>()
    @Volatile private var closed = false
    @Volatile private var warmGeneration = 0
    private var warmKeys: List<String>? = null
    private var warmCompleted = 0
    private var warmTotal = 0
    val warmingProgress get() = warmCompleted to warmTotal
    var onMetadataChanged: (() -> Unit)? = null
    private var notifyScheduled = false
    private var saveScheduled = false
    private val savedDurations = mutableMapOf<String, Long>()
    private val notifyMetadata = Runnable { notifyScheduled = false; if (!closed) onMetadataChanged?.invoke() }
    private val saveMetadata = Runnable { flushDurations() }
    private val corner = (14 * activity.resources.displayMetrics.density).toInt()

    override fun getCount() = videos.size
    override fun getItem(position: Int) = videos[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
        val view = convert ?: activity.layoutInflater.inflate(R.layout.item_video, parent, false)
        val video = videos[position]
        view.findViewById<TextView>(R.id.videoTitle).text = video.name
        view.findViewById<TextView>(R.id.videoLibraryInfo).apply {
            val parts = mutableListOf<String>()
            if (video.size > 0) parts.add(android.text.format.Formatter.formatShortFileSize(activity, video.size))
            val time = LibraryCatalog.time(video)
            if (time > 0) parts.add(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(time)))
            text = parts.joinToString(" · "); visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
        view.contentDescription = video.name
        val thumb = view.findViewById<ImageView>(R.id.videoThumb)
        Glide.with(activity).asBitmap().load(previewUri(video))
            .signature(ObjectKey(if (NetworkMedia.isNetwork(video.uri)) LocalLibraryRepository.cacheId(video.cacheKey) else video.cacheKey))
            .frame(1_000_000L)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .placeholder(R.drawable.ic_video_placeholder).error(R.drawable.ic_video_placeholder)
            .transform(CenterCrop(), RoundedCorners(corner)).into(thumb)
        bindDuration(video, view.findViewById(R.id.videoDuration))
        return view
    }

    private fun previewUri(video: LibraryVideo) =
        if (NetworkMedia.isNetwork(video.uri)) SmbPreviewProvider.uriFor(context, video) else Uri.parse(video.uri)

    private fun showDuration(view: TextView, milliseconds: Long) {
        view.visibility = if (milliseconds > 0) View.VISIBLE else View.GONE
        view.text = if (milliseconds > 0)
            Deo.formatDuration((milliseconds / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else ""
    }

    private fun bindDuration(video: LibraryVideo, view: TextView) {
        val key = video.cacheKey
        view.tag = key
        cachedDuration(video)?.let { showDuration(view, it); return }
        showDuration(view, 0)
        val waiting = pending[key]
        if (waiting != null) {
            if (waiting.none { it.get() === view }) waiting.add(WeakReference(view))
            return
        }
        pending[key] = mutableListOf(WeakReference(view))
        worker.execute {
            val duration = readDuration(video)
            main.post {
                if (!closed) recordDuration(video, duration)
            }
        }
    }

    fun cachedDuration(video: LibraryVideo): Long? =
        durations.getOrPut(video.cacheKey) { prefs.getLong(video.cacheKey, -1) }.takeIf { it >= 0 }

    private fun readDuration(video: LibraryVideo): Long = runCatching {
        val cached = prefs.getLong(video.cacheKey, -1)
        if (cached >= 0) cached else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, previewUri(video))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            } finally { retriever.release() }
        }
    }.getOrDefault(0)

    private fun recordDuration(video: LibraryVideo, value: Long) {
        val key = video.cacheKey
        durations[key] = value
        if (value > 0) {
            savedDurations[key] = value
            if (!saveScheduled) { saveScheduled = true; main.postDelayed(saveMetadata, 1000) }
        }
        pending.remove(key)?.forEach { ref -> ref.get()?.takeIf { it.tag == key }?.let { showDuration(it, value) } }
        if (!notifyScheduled) { notifyScheduled = true; main.postDelayed(notifyMetadata, 500) }
    }

    private fun flushDurations() {
        saveScheduled = false
        if (savedDurations.isEmpty()) return
        val edit = prefs.edit()
        savedDurations.forEach { (key, value) -> edit.putLong(key, value) }
        savedDurations.clear()
        edit.apply()
    }

    /** Two bounded worker loops, not one queued task per video in a large library. */
    fun prepareDurations(videos: List<LibraryVideo>) {
        val keys = videos.map { it.cacheKey }
        if (keys == warmKeys || closed) return
        warmKeys = keys
        val generation = ++warmGeneration
        val needed = videos.filter { cachedDuration(it) == null }
        warmCompleted = 0; warmTotal = needed.size
        val next = AtomicInteger()
        repeat(minOf(2, needed.size)) {
            worker.execute {
                while (!closed && generation == warmGeneration && !Thread.currentThread().isInterrupted) {
                    val index = next.getAndIncrement()
                    if (index >= needed.size) break
                    val video = needed[index]
                    val value = readDuration(video)
                    main.post {
                        if (!closed) {
                            if (generation == warmGeneration) warmCompleted++
                            recordDuration(video, value)
                        }
                    }
                }
            }
        }
    }

    fun cancelDurationWarmup() { warmGeneration++; warmKeys = null; warmCompleted = 0; warmTotal = 0 }
    fun retryMissingDurations() { durations.entries.removeAll { it.value == 0L }; cancelDurationWarmup() }

    fun close() {
        closed = true
        warmGeneration++
        onMetadataChanged = null
        main.removeCallbacks(notifyMetadata)
        main.removeCallbacks(saveMetadata)
        flushDurations()
        pending.clear()
        worker.shutdownNow()
    }
}
