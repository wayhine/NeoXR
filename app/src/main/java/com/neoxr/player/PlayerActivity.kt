package com.neoxr.player

import android.app.Activity
import android.app.Presentation
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt
import kotlin.concurrent.thread

/**
 * Controls: drag = look around, single tap = show/hide the control bar (playback
 * toggles only via its ▶/⏸ button), double tap = toggle phone-gyro look,
 * phone video swipe/hold actions are configurable; remote pad long press = recenter.
 *
 * Output: with the glasses plugged in over USB-C (DisplayPort alt mode they are a
 * real external display), the video renders DIRECTLY on them via a [Presentation] —
 * full native frame, none of the mirror-path rescaling — and the phone screen
 * becomes a plain remote: same controls, regular size, no SBS doubling, gyro and
 * swipe still steer the view. Without glasses everything stays on the phone as
 * before. Plug/unplug mid-play recreates the activity, keeping the position.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Activity(), SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private var player: ExoPlayer? = null
    private var videoSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var streamUrl: String? = null
    private var formatHint: String? = null // retain a SAF file's real name for later size-based detection
    private var presentation: Presentation? = null
    private var outputWindow: GlassesPresentation? = null
    private var glassesDisplay: Display? = null
    private lateinit var outputWatcher: GlassesWatcher
    private var restorePlaying = true
    private var lifecyclePaused = false
    private var restartHead = false
    private var resumePos = 0L // carried across output switches (plug/unplug recreate)

    // named sbsOverlay, not "overlay": inside any View.apply{} scope a bare `overlay`
    // silently resolves to View.getOverlay() instead of this field
    private lateinit var sbsOverlay: SbsFrameLayout
    private lateinit var controlsBar: View
    private lateinit var btnPlay: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btn3d: TextView
    private lateinit var timePos: TextView
    private lateinit var timeDur: TextView
    private lateinit var seek: SeekBar
    private lateinit var qualityList: LinearLayout
    private lateinit var trackList: LinearLayout
    private lateinit var trackScroll: View
    // side columns with the format buttons (inside the SBS overlay, see buildSidePanels)
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var btnClose: TextView
    private var btnScreen: TextView? = null // display switcher (multi-display devices)
    // media3's SubtitleView, not a TextView: film rips often carry bitmap subtitles
    // (PGS, VobSub) which have no text at all, and it renders both kinds.
    private var subtitleView: androidx.media3.ui.SubtitleView? = null // phone-side
    private var glassesSubs: androidx.media3.ui.SubtitleView? = null  // on the glasses
    private var btnTracks: TextView? = null
    private var audioDisabled = false // set after an undecodable audio track
    // Subtitle size in sp. The glasses squeeze each half horizontally, so text that
    // looks fine on a phone reads tiny there — hence a large default and a control.
    private var subSizeSp = 26
    private var subColor = 0xFFFFFFFF.toInt()
    // Some releases ship subtitle tracks already rendered as a stereo pair (one for
    // each eye). Duplicating those per eye — right for ordinary tracks — shows four
    // copies, so the duplication has to be switchable.
    private var subStereoSource = false
    private var subBold = false
    /** Distance of the subtitle baseline from the bottom, percent of the view height. */
    private var subBottomPct = 8
    private var subsWrap: SbsFrameLayout? = null
    private lateinit var btnRw: TextView
    private lateinit var btnFf: TextView
    private lateinit var btnCenter: TextView
    private lateinit var btnHoldSpeed: TextView
    private lateinit var playerActions: LinearLayout
    private var phoneSwipeSeeks = false
    private var holdSpeedSetting = 2f
    private var gestureSettingsOpen = false
    private var orientationMode = "landscape"
    private var swipeSeek: SwipeSeekGesture? = null
    private var verticalSwipe: VerticalPlaybackSwipe? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var gestureVolume = 0
    private var swipeSeekWarning = false
    private var screenHoldAllowed = false
    private var speedButtonTouched = false
    private val holdSpeed = TemporaryPlaybackSpeed(
        { player?.playbackParameters?.speed ?: 1f },
        { speed -> player?.setPlaybackSpeed(speed) }
    )
    private val screenBtns = HashMap<Int, TextView>()
    private val layoutBtns = HashMap<String, TextView>()
    private var sources: List<Pair<Int, String>> = emptyList()
    // fallback quality menu for raw intercepted streams (no feed JSON): the variant
    // heights ExoPlayer parsed out of the HLS/DASH manifest
    private var trackHeights: List<Int> = emptyList()
    private var qualityCap = 0 // 0 = adaptive
    private var screenAngle = 180 // projection: 0 = flat screen, else dome width in degrees
    private var stereo = "sbs" // eye layout: "sbs" | "tb" | "off"
    private var formatFromFeed = false // feed metadata set the format — never re-guess
    private var formatAuto = true // no manual override yet — may refine on first frame size
    private var eyeShiftDp = 0
    private var widthPct = 100
    private var heightPct = 100
    private var zoomPct = 100 // camera magnification, in tan space
    private var seekDragging = false
    private val handler = Handler(Looper.getMainLooper())
    private var systemAutoRotate = false
    private var rotationObserverRegistered = false
    private var navigationBottom = 0
    private val rotationFollow = RotationFollowState()
    private val rotationSettingObserver by lazy {
        object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (rotationObserverRegistered) syncSystemRotation()
            }
        }
    }
    private val physicalRotation by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (systemAutoRotate && rotationFollow.onOrientation(orientation, android.os.SystemClock.elapsedRealtime()))
                    requestedOrientation = PlaybackOrientation.requested(orientationMode, true)
            }
        }
    }
    private val hideControls = Runnable {
        controlsBar.visibility = View.GONE
        qualityList.visibility = View.GONE
        trackScroll.visibility = View.GONE
        leftPanel.visibility = View.GONE
        rightPanel.visibility = View.GONE
        btnClose.visibility = View.GONE
        if (::btnCenter.isInitialized) btnCenter.visibility = View.GONE
        if (::playerActions.isInitialized) playerActions.visibility = View.GONE
        btnScreen?.visibility = View.GONE
        findViewById<View>(R.id.eyePanel)?.visibility = View.GONE
    }
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private var gyroEnabled = true
    private var dragYaw = 0f
    private var dragPitch = 0f
    private var sensorYaw = 0f
    private var sensorPitch = 0f
    private var zeroYaw = 0f
    /** Percent of real head/phone rotation carried into the view; 100 = one-to-one. */
    private var headSensitivity = 100
    private var popupIsPicture = false
    private var zeroPitch = 0f
    private var yawZeroed = false // first sensor sample zeroes YAW (and only yaw)
    // glasses-IMU head tracking (Imu.kt): when on, it feeds sensorYaw/sensorPitch
    // instead of the phone sensor — recenter/drag/gyro-toggle logic stays identical
    private var headTracker: HeadTracker? = null
    private var headMode = false
    private var btnHead: TextView? = null
    private var levelView: LevelView? = null // remote-mode gyro instrument
    // live value readouts inside the remote steppers ([−  W 100  +] rows)
    private var wValue: TextView? = null
    private var zValue: TextView? = null
    private var hValue: TextView? = null
    private var wRow: LinearLayout? = null // W stepper row (portrait only)
    private var hRow: LinearLayout? = null // H stepper row (portrait only)
    private val stepperRows = mutableListOf<LinearLayout>() // resized on rotation
    // No full auto-centering on the first sensor sample: recenter() also zeroes PITCH,
    // which would capture whatever pose the phone happens to be in at launch. Laying
    // the phone down flat afterwards then reads as -90° ("looking at the floor") and
    // the video goes black. Only a deliberate long-press recenter, made once the phone
    // is where it will stay, has a meaningful pitch reference. See onSensorChanged for
    // the yaw-only zeroing that IS safe.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullscreenOverCutout()
        resumePos = savedInstanceState?.getLong("pos") ?: 0L
        restorePlaying = savedInstanceState?.getBoolean("playing", true) ?: true
        restartHead = savedInstanceState?.getBoolean("headTracking", false) ?: false
        getSharedPreferences("neoxr", MODE_PRIVATE).let { p ->
            subSizeSp = p.getInt("subSize", 26)
            subColor = p.getInt("subColor", 0xFFFFFFFF.toInt())
            subStereoSource = p.getBoolean("subStereo", false)
            subBold = p.getBoolean("subBold", false)
            subBottomPct = p.getInt("subBottom", 8)
            headSensitivity = p.getInt("headSens", 100)
            phoneSwipeSeeks = p.getBoolean("phoneSwipeSeeks", false)
            holdSpeedSetting = PlaybackGestureOptions.savedHoldSpeed(p.getFloat("holdSpeed", 2f))
        }
        // diagnostic pass: dump every display the system exposes
        for (d in (getSystemService(DISPLAY_SERVICE) as DisplayManager).displays) {
            android.util.Log.i(
                "NeoXR",
                "display id=${d.displayId} name=${d.name} flags=0x${d.flags.toString(16)} " +
                        "mode=${d.mode} state=${d.state} valid=${d.isValid}"
            )
        }
        glassesDisplay = findGlasses()
        android.util.Log.i("NeoXR", "glassesDisplay=${glassesDisplay?.displayId}")
        getSharedPreferences("neoxr", MODE_PRIVATE).let { p ->
            orientationMode = PlaybackOrientation.saved(p.getString("playbackOrientation", null), glassesDisplay != null,
                p.getInt("playbackOrientationLock", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE))
        }
        systemAutoRotate = readSystemAutoRotate()
        requestedOrientation = PlaybackOrientation.requested(orientationMode, systemAutoRotate)
        outputWatcher = GlassesWatcher(this, glassesDisplay) { recreate() }

        // when the glasses display exists, the GL view lives on IT (via Presentation),
        // so it must be built with the presentation's display context
        val ext = glassesDisplay
        if (ext != null) presentation = Presentation(this, ext)
        renderer = VrRenderer { st ->
            android.util.Log.i("NeoXR", "GL surface ready (onSurfaceReady)")
            runOnUiThread {
                if (isDestroyed || isFinishing) { st.release(); return@runOnUiThread }
                val previousTexture = surfaceTexture
                surfaceTexture = st
                val currentPlayer = player
                if (currentPlayer == null) maybeStart() else {
                    // EGL can be recreated by a mode switch / resume. Rebind the
                    // decoder to its new texture instead of leaving the last frame.
                    val previousSurface = videoSurface
                    videoSurface = Surface(st)
                    currentPlayer.setVideoSurface(videoSurface)
                    previousSurface?.release()
                    if (previousTexture !== st) previousTexture?.release()
                }
            }
        }
        renderer.monoOutput = if (ext == null && savedInstanceState?.getBoolean("outputOnGlasses") == true) true
            else savedInstanceState?.getBoolean("monoOutput", true) ?: true
        if (savedInstanceState?.containsKey("projection") == true) {
            screenAngle = savedInstanceState.getInt("projection")
            stereo = savedInstanceState.getString("sourceStereo") ?: "sbs"
            formatAuto = savedInstanceState.getBoolean("formatAuto")
            dragYaw = savedInstanceState.getFloat("dragYaw")
            dragPitch = savedInstanceState.getFloat("dragPitch")
            renderer.configure(screenAngle, stereo)
        }
        ext?.mode?.let { renderer.stereoOutputAspect = VideoGeometry.glassesEyeAspect(it.physicalWidth, it.physicalHeight) }
        // picture calibration describes the glasses, so it is restored per launch —
        // and only here: renderer is lateinit, reading prefs into it any earlier
        // throws before the player ever draws
        getSharedPreferences("neoxr", MODE_PRIVATE).let { p ->
            renderer.blackLevel = p.getInt("picBlack", 0) / 100f
            renderer.brightness = p.getInt("picBright", 0) / 100f
            renderer.contrast = p.getInt("picContrast", 100) / 100f
            renderer.gamma = p.getInt("picGamma", 100) / 100f
            renderer.ambientInset = p.getInt("glowWidth", 86) / 100f
        }
        glView = GLSurfaceView(presentation?.context ?: this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            keepScreenOn = true
        }
        // Use the active physical mode, never request another mode on the glasses.
        // A Half SBS / 2D switch can change the entire DP connection.
        ext?.mode?.let { mode ->
            glView.holder.setFixedSize(mode.physicalWidth, mode.physicalHeight)
        }
        sbsOverlay = SbsFrameLayout(this).apply { sbs = ext == null && !renderer.monoOutput }
        layoutInflater.inflate(R.layout.player_controls, sbsOverlay, true)
        sbsOverlay.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30)
                insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom
                else insets.stableInsetBottom
            if (navigationBottom != bottom) {
                navigationBottom = bottom
                sbsOverlay.post { if (::btn3d.isInitialized && !isDestroyed) applyPlayerDims() }
            }
            insets
        }
        val root = FrameLayout(this)
        root.keepScreenOn = true
        if (ext == null) {
            root.addView(glView)
        } else {
            // remote mode: the phone shows an instrument, not a text dump — a
            // crosshair level whose bubbles ride the live yaw/pitch (the gyro made
            // visible), with a mono readout and one line of gesture hints
            root.setBackgroundColor(getColor(R.color.backdrop))
            levelView = LevelView(this)
            root.addView(levelView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        root.addView(sbsOverlay)
        buildSidePanels()
        setContentView(root)
        presentation?.let {
            try {
                // subtitles have to live on the GLASSES, next to the video — the
                // phone side is the remote and nobody is looking at it. Drawn by us
                // in an overlay above the GL view, doubled per eye by the wrapper.
                val glassesRoot = FrameLayout(it.context)
                glassesRoot.addView(glView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))
                val subs = SbsFrameLayout(it.context).apply { sbs = !renderer.monoOutput && !subStereoSource }
                subsWrap = subs
                subs.addView(androidx.media3.ui.SubtitleView(it.context).apply {
                    glassesSubs = this
                    styleSubtitles(this)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        bottomMargin = (36 * resources.displayMetrics.density).toInt()
                    }
                })
                glassesRoot.addView(subs, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))
                it.setContentView(glassesRoot)
                outputWindow = GlassesPresentation(this, it,
                    onDismissed = { outputWatcher.presentationDismissed() },
                    onFailure = { e ->
                        fallbackToPhone(root, e)
                        applyPlayerDims()
                    })
            } catch (e: Exception) {
                fallbackToPhone(root, e)
            }
        }
        controlsBar = sbsOverlay.findViewById(R.id.controlsBar)
        btnPlay = sbsOverlay.findViewById(R.id.btnPlay)
        btnQuality = sbsOverlay.findViewById(R.id.btnQuality)
        timePos = sbsOverlay.findViewById(R.id.timePos)
        timeDur = sbsOverlay.findViewById(R.id.timeDur)
        seek = sbsOverlay.findViewById(R.id.seek)
        qualityList = sbsOverlay.findViewById(R.id.qualityList)
        trackList = sbsOverlay.findViewById(R.id.trackList)
        trackScroll = sbsOverlay.findViewById(R.id.trackScroll)
        controlsBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && ::btn3d.isInitialized) {
                sbsOverlay.post { if (!isDestroyed) applyPlayerDims() }
            }
        }

        btnPlay.setOnClickListener {
            endHoldSpeed()
            player?.let {
                if (it.playbackState == Player.STATE_ENDED) {
                    it.seekTo(0)
                    it.play()
                } else if (it.playWhenReady) it.pause() else {
                    it.play()
                }
            }
            showControls()
        }
        // relative seek: fine for both local files and streams (CLOSEST_SYNC keeps
        // local seeks instant), and the most-requested missing control
        fun seekBy(deltaMs: Long) {
            player?.let {
                if (!it.isCurrentMediaItemSeekable) {
                    sbsOverlay.flash("此视频不支持调整进度")
                    return@let
                }
                it.seekTo(relativeSeekTarget(it.currentPosition, it.duration, deltaMs))
            }
            showControls()
        }
        btnRw = sbsOverlay.findViewById(R.id.btnRw)
        btnFf = sbsOverlay.findViewById(R.id.btnFf)
        btnRw.setOnClickListener { seekBy(-10_000) }
        btnFf.setOnClickListener { seekBy(+15_000) }
        btnRw.setOnLongClickListener { seekBy(-60_000); true }
        btnFf.setOnLongClickListener { seekBy(+300_000); true }
        setupQuickControls()

        subtitleView = sbsOverlay.findViewById<androidx.media3.ui.SubtitleView>(R.id.subtitleText)
            .apply { styleSubtitles(this); visibility = View.VISIBLE }
        btnTracks = sbsOverlay.findViewById<TextView>(R.id.btnTracks).apply {
            setOnClickListener {
                if (trackScroll.visibility == View.VISIBLE) trackScroll.visibility = View.GONE
                else { qualityList.visibility = View.GONE; buildTrackMenu() }
                showControls()
            }
        }
        sbsOverlay.findViewById<TextView>(R.id.btnPicture).setOnClickListener {
            if (trackScroll.visibility == View.VISIBLE && popupIsPicture) {
                trackScroll.visibility = View.GONE
            } else {
                qualityList.visibility = View.GONE
                buildPictureMenu()
            }
            showControls()
        }
        btnQuality.setOnClickListener {
            trackScroll.visibility = View.GONE
            if (qualityList.visibility == View.VISIBLE) qualityList.visibility = View.GONE
            else showPopup(qualityList)
            showControls()
        }
        // zoom/width/convergence are deliberately NOT persisted: they are per-content
        // corrections, so every video starts from the defaults
        applyEyeShift()
        applyFov()
        renderer.heightScale = 1f
        val eyePanel = sbsOverlay.findViewById<View>(R.id.eyePanel)
        btn3d = sbsOverlay.findViewById(R.id.btn3d)
        applyPlayerDims()
        btn3d.setOnClickListener {
            if (gestureSettingsOpen) closeGestureSettings()
            applyPlayerDims()
            eyePanel.visibility =
                if (eyePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (eyePanel.visibility == View.VISIBLE) {
                trackScroll.visibility = View.GONE
                qualityList.visibility = View.GONE
                eyePanel.bringToFront()
            }
            showControls()
        }
        sbsOverlay.findViewById<TextView>(R.id.btnEyeMinus).setOnClickListener { adjustEyeShift(-2) }
        sbsOverlay.findViewById<TextView>(R.id.btnEyePlus).setOnClickListener { adjustEyeShift(2) }
        sbsOverlay.findViewById<TextView>(R.id.btnHMinus).setOnClickListener { adjustHeight(-5) }
        sbsOverlay.findViewById<TextView>(R.id.btnHPlus).setOnClickListener { adjustHeight(+5) }
        // Output layout is independent of the video's SBS/TB source format.
        // A mono view works on the glasses too, provided their own 3D mode is off.
        sbsOverlay.findViewById<TextView>(R.id.btnMono).apply {
            visibility = View.VISIBLE
            fun paint() {
                val mode = glassesDisplay?.mode
                val wide = mode != null && mode.physicalWidth.toFloat() / mode.physicalHeight >= 2.5f
                text = "输出：" + if (renderer.monoOutput) "单画面" else if (wide) "SBS 全宽" else "SBS 半宽"
                setTextColor(getColor(if (renderer.monoOutput) R.color.primary else R.color.on_surface_variant))
            }
            paint()
            setOnClickListener {
                renderer.monoOutput = !renderer.monoOutput
                sbsOverlay.sbs = glassesDisplay == null && !renderer.monoOutput
                subsWrap?.sbs = !renderer.monoOutput && !subStereoSource
                paint(); applyPlayerDims(); showControls()
                if (glassesDisplay != null) sbsOverlay.flash(if (renderer.monoOutput)
                    "单画面输出到眼镜：请将眼镜的 3D 模式关闭"
                    else "SBS 输出到眼镜：请在眼镜菜单选择与输出匹配的半宽 / 全宽模式，关闭 REAL 3D")
            }
        }
        sbsOverlay.findViewById<TextView>(R.id.btnAmbient).apply {
            var step = getSharedPreferences("neoxr", MODE_PRIVATE).getInt("glow", 0)
                .coerceIn(0, AMBIENT_STEPS.lastIndex)
            fun paint() {
                text = "氛围光：" + AMBIENT_NAMES[step]
                setTextColor(getColor(
                    if (step > 0) R.color.primary else R.color.on_surface_variant
                ))
            }
            renderer.ambientLevel = AMBIENT_STEPS[step]
            paint()
            setOnClickListener {
                step = (step + 1) % AMBIENT_STEPS.size
                renderer.ambientLevel = AMBIENT_STEPS[step]
                getSharedPreferences("neoxr", MODE_PRIVATE)
                    .edit().putInt("glow", step).apply()
                paint()
                showControls()
            }
        }
        sbsOverlay.findViewById<TextView>(R.id.btnWMinus).setOnClickListener { adjustWidth(-5) }
        sbsOverlay.findViewById<TextView>(R.id.btnWPlus).setOnClickListener { adjustWidth(+5) }
        sbsOverlay.findViewById<TextView>(R.id.btnEyeSwap).setOnClickListener { btn ->
            // per-video toggle, deliberately not persisted: only some sources ship R|L
            renderer.swapEyes = !renderer.swapEyes
            (btn as TextView).setTextColor(
                getColor(if (renderer.swapEyes) R.color.primary else R.color.on_surface)
            )
            showControls()
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) timePos.text = Deo.formatDuration(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seekDragging = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seekDragging = false
                player?.let {
                    // surface the reason instead of a silently dead slider
                    if (it.isCurrentMediaItemSeekable) it.seekTo(sb.progress * 1000L)
                    else sbsOverlay.flash("此视频不支持调整进度")
                }
                showControls()
            }
        })

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                swipeSeekWarning = false
                swipeSeek = if (glassesDisplay == null && phoneSwipeSeeks && !gestureSettingsOpen)
                    SwipeSeekGesture(player?.currentPosition ?: 0, player?.duration ?: 0, glView.width) else null
                verticalSwipe = if (swipeSeek != null) startVerticalSwipe(e.x >= glView.width / 2f) else null
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (holdSpeed.active || gestureSettingsOpen) return true
                if (glassesDisplay == null && phoneSwipeSeeks) {
                    val session = swipeSeek ?: return true
                    if (e1 == null) return true
                    val target = session.move(e2.x - e1.x, e2.y - e1.y)
                    if (session.vertical) {
                        if (!swipeSeekWarning) adjustVerticalSwipe(e2.y - e1.y)
                        return true
                    }
                    val p = player
                    if (p == null || !p.isCurrentMediaItemSeekable || p.duration <= 0) {
                        if (!swipeSeekWarning) sbsOverlay.flash("此视频不支持滑动调整进度")
                        swipeSeekWarning = true
                        return true
                    }
                    target?.let { target ->
                        showControls()
                        handler.removeCallbacks(hideControls)
                        val delta = (target - session.start) / 1000
                        sbsOverlay.findViewById<TextView>(R.id.swipeSeekPreview).apply {
                            text = "${Deo.formatDuration((target / 1000).toInt())} / ${Deo.formatDuration((p.duration / 1000).toInt())}\n" +
                                "%+d秒 · 松手跳转".format(delta)
                            visibility = View.VISIBLE
                        }
                        seek.progress = (target / 1000).toInt()
                        timePos.text = Deo.formatDuration((target / 1000).toInt())
                    }
                    return true
                }
                dragYaw += dx * 0.12f
                dragPitch -= dy * 0.12f
                applyView()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // remote mode: the phone screen IS the controls — nothing to
                // unclutter, so a tap must not hide them
                if (glassesDisplay != null) return true
                if (gestureSettingsOpen) { closeGestureSettings(); return true }
                if (holdSpeed.active) return true
                // phone playback: controls visibility only — play/pause via ►/❙❙
                if (controlsBar.visibility == View.VISIBLE) {
                    handler.removeCallbacks(hideControls)
                    hideControls.run()
                } else {
                    showControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (gestureSettingsOpen) { closeGestureSettings(); return true }
                gyroEnabled = !gyroEnabled
                recenter()
                sbsOverlay.flash(if (gyroEnabled) "陀螺仪已开启" else "陀螺仪已关闭")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!screenHoldAllowed || gestureSettingsOpen) return
                if (glassesDisplay == null) beginHoldSpeed() else recenter()
            }
        })
        // look-around gestures: on the GL view itself when it is on the phone, on the
        // whole phone screen (acting as a touchpad) when the video is on the glasses
        (if (glassesDisplay == null) glView else root).setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) screenHoldAllowed = true
            if (ev.pointerCount > 1) {
                screenHoldAllowed = false
                endHoldSpeed()
                MotionEvent.obtain(ev).also { cancel ->
                    cancel.action = MotionEvent.ACTION_CANCEL
                    gestures.onTouchEvent(cancel)
                    cancel.recycle()
                }
            } else if (screenHoldAllowed) gestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                finishSwipeSeek(commit = ev.actionMasked == MotionEvent.ACTION_UP)
                screenHoldAllowed = false
                endHoldSpeed()
            }
            true
        }
        if (glassesDisplay != null) showControls() // a remote keeps its buttons visible

        // local video handed over by the system: a file manager's "Open with"
        // (ACTION_VIEW) or the share sheet (ACTION_SEND). Format is guessed from the
        // real file name (SAF content:// URIs are opaque — resolve DISPLAY_NAME),
        // then refined from the frame aspect like any intercepted stream.
        val extUri: android.net.Uri? = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            else -> null
        }
        if (extUri != null) {
            formatHint = displayName(extUri) ?: extUri.toString()
            val (angle, layout) = Deo.guessFormat(formatHint!!)
            if (savedInstanceState?.containsKey("projection") != true) setFormat(angle, layout)
            else setFormat(screenAngle, stereo)
            streamUrl = extUri.toString()
            buildQualityList()
            maybeStart()
            return
        }

        // direct stream handed over from the in-app browser — no DeoVR JSON involved.
        // Guess the format from URL tokens now; refined once from the real frame size
        // when the decoder reports it (onVideoSizeChanged), unless overridden manually.
        intent.getStringExtra("stream")?.let { url ->
            val (angle, layout) = Deo.guessFormat(url)
            if (savedInstanceState?.containsKey("projection") != true) setFormat(angle, layout)
            else setFormat(screenAngle, stereo)
            streamUrl = url
            buildQualityList()
            maybeStart()
            return
        }

        thread {
            try {
                val videoUrl = intent.getStringExtra("videoUrl")
                // ponytail: some sites intermittently serve detail JSON without sources
                // (rate limiting?) and a repeat request succeeds — retry up to 3 fetches
                var info: VideoInfo? = null
                var lastErr: Exception? = null
                for (attempt in 0..2) {
                    try {
                        val body = if (attempt == 0) {
                            intent.getStringExtra("json") ?: Deo.httpGet(videoUrl!!)
                        } else {
                            Deo.httpGet(videoUrl!!)
                        }
                        info = Deo.parseVideo(body)
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        if (videoUrl == null || e is Deo.PremiumVideoException) break
                        Thread.sleep(600)
                    }
                }
                val vi = info ?: throw lastErr!!
                runOnUiThread {
                    formatFromFeed = true
                    if (savedInstanceState?.containsKey("projection") != true) setFormat(vi.angleDeg, vi.stereo)
                    else setFormat(screenAngle, stereo)
                    streamUrl = vi.url
                    sources = vi.sources
                    buildQualityList()
                    maybeStart()
                }
            } catch (e: Exception) {
                // shown in-layout so it is readable in the glasses; Back exits
                runOnUiThread { sbsOverlay.flash("加载失败：${e.message}") }
            }
        }
    }

    private fun fallbackToPhone(root: FrameLayout, error: Exception) {
        android.util.Log.e("NeoXR", "presentation FAILED: $error", error)
        outputWindow?.close()
        outputWindow = null
        presentation?.setOnDismissListener(null)
        presentation?.dismiss()
        presentation = null
        glassesDisplay = null
        glassesSubs = null
        subsWrap = null
        levelView?.let { root.removeView(it) }
        (glView.parent as? android.view.ViewGroup)?.removeView(glView)
        root.addView(glView, 0)
        renderer.monoOutput = true
        glView.holder.setSizeFromLayout()
        sbsOverlay.sbs = false
        sbsOverlay.flash("无法输出到眼镜，已改为手机播放")
    }

    private fun findGlasses(): Display? = Glasses.find(this)

    /** Real file name behind a SAF content:// URI (they are opaque otherwise). */
    private fun displayName(uri: android.net.Uri): String? = try {
        contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    // Display reconnects are debounced by GlassesWatcher, including a presentation
    // cancelled by Android even if the replacement display reuses the same ID.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("pos", player?.currentPosition ?: resumePos)
        outState.putBoolean("playing", if (lifecyclePaused) restorePlaying else player?.playWhenReady ?: restorePlaying)
        outState.putBoolean("headTracking", headMode || restartHead)
        outState.putBoolean("monoOutput", renderer.monoOutput)
        outState.putBoolean("outputOnGlasses", glassesDisplay != null)
        outState.putInt("projection", screenAngle)
        outState.putString("sourceStereo", stereo)
        outState.putBoolean("formatAuto", formatAuto)
        outState.putFloat("dragYaw", dragYaw)
        outState.putFloat("dragPitch", dragPitch)
    }

    private fun maybeStart() {
        android.util.Log.i(
            "NeoXR",
            "maybeStart: surface=${surfaceTexture != null} url=${streamUrl != null} player=${player != null}"
        )
        val st = surfaceTexture ?: return
        val url = streamUrl ?: return
        if (player != null) return
        videoSurface = Surface(st)
        player = ExoPlayer.Builder(this)
            // network shares are read by our own SMB source; everything else goes
            // through media3's default set (http, file, content, asset)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    if (WebDav.isWebDav(url)) WebDavSource.Factory()
                    else if (Smb.isSmb(url)) Smb.Source.Factory()
                    else androidx.media3.datasource.DefaultDataSource.Factory(this)
                )
            )
            // prefer the bundled FFmpeg decoders: most phones have no hardware
            // decoder for AC-3, E-AC-3, DTS or TrueHD, which are what film rips use
            .setRenderersFactory(
                androidx.media3.exoplayer.DefaultRenderersFactory(this)
                    .setExtensionRendererMode(
                        androidx.media3.exoplayer.DefaultRenderersFactory
                            .EXTENSION_RENDERER_MODE_PREFER
                    )
            )
            // proper media-app citizenship: request audio focus (pause when another
            // app plays, duck for notifications) and stop on headphone unplug
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
            // local hi-res files often have sparse keyframes (5-10 s GOPs): an exact
            // seek decodes the whole GOP and looks frozen for seconds — snap to the
            // nearest sync frame instead (streams are unaffected, segments align)
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            setVideoSurface(videoSurface)
            // resumePos is set on a recreate (glasses plugged in mid-video); a fresh
            // open falls back to where this video was last left
            val start = if (resumePos > 0L) resumePos else rememberedPosition(url)
            if (start > 0L && resumePos == 0L) {
                sbsOverlay.flash("续播至 " + Deo.formatDuration((start / 1000).toInt()))
            }
            setMediaItem(MediaItem.fromUri(url), start)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    cancelHoldPlayback()
                    android.util.Log.e("NeoXR", "player error: $error", error)
                    // A codec the device cannot decode kills the whole playback,
                    // even when only the audio track is at fault (AC-3 and DTS are
                    // the usual ones). Drop audio, keep the video running, and say so.
                    val audioCodec = error.errorCode ==
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                            error.message?.contains("AudioRenderer", true) == true
                    if (audioCodec && !audioDisabled) {
                        audioDisabled = true
                        player?.let { p ->
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .build()
                            p.prepare()
                            p.play()
                        }
                        sbsOverlay.flash("此音轨不受支持，已静音播放")
                        return
                    }
                    // include the error code: the message alone is often a generic
                    // "Source error" and the code is what identifies the real cause
                    sbsOverlay.flash(
                        "播放失败：${error.errorCodeName}\n${error.message}"
                    )
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) endHoldSpeed()
                    updateProgress()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) endHoldSpeed()
                    updateProgress()
                }

                // subtitles come as cues because the video itself lives in a GL
                // texture — ExoPlayer's own subtitle view has nothing to draw on
                override fun onCues(cues: androidx.media3.common.text.CueGroup) {
                    // whichever surface currently shows the video gets the subtitles
                    val target = glassesSubs ?: subtitleView
                    target?.let {
                        it.setCues(cues.cues)
                        it.visibility = View.VISIBLE
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    // the CC button only earns its place when there is a choice:
                    // more than one audio track, or any subtitles at all
                    val audio = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }
                    val text = tracks.groups.count { it.type == C.TRACK_TYPE_TEXT }
                    btnTracks?.visibility =
                        if (audio > 1 || text > 0) View.VISIBLE else View.GONE
                    // feed sources win: they are separate URLs, not manifest variants
                    if (sources.isNotEmpty()) return
                    trackHeights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                        .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }
                        .filter { it > 0 }.distinct().sortedDescending()
                    buildQualityList()
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    // the flat screen needs the frame shape to keep its proportions
                    renderer.setVideoSize(size.width, size.height, size.pixelWidthHeightRatio)
                    // metadata-less streams only: refine the URL guess once with the
                    // real frame aspect, then leave the format alone (manual wins)
                    if (formatFromFeed || !formatAuto || size.width == 0 || size.height == 0) return
                    formatAuto = false
                    val (angle, layout) =
                        Deo.guessFormat(formatHint ?: streamUrl ?: return, size.width, size.height, size.pixelWidthHeightRatio)
                    if (angle != screenAngle || layout != stereo) setFormat(angle, layout)
                }
            })
            prepare()
            playWhenReady = restorePlaying
        }
    }

    private fun setupQuickControls() {
        val d = resources.displayMetrics.density
        val quick = sbsOverlay.findViewById<LinearLayout>(R.id.quickControls)
        btnCenter = sbsOverlay.findViewById(R.id.btnCenter)
        btnHoldSpeed = sbsOverlay.findViewById(R.id.btnHoldSpeed)
        playerActions = sbsOverlay.findViewById(R.id.playerActions)
        sbsOverlay.findViewById<View>(R.id.btnOrientation).setOnClickListener {
            toggleOrientation()
        }
        sbsOverlay.findViewById<View>(R.id.btnGestureSettings).setOnClickListener {
            if (gestureSettingsOpen) closeGestureSettings() else showGestureSettings()
        }
        quick.removeView(btnCenter)
        (btnClose.parent as android.view.ViewGroup).removeView(btnClose)
        playerActions.addView(btnCenter, LinearLayout.LayoutParams((72 * d).toInt(), (36 * d).toInt())
            .apply { marginStart = (6 * d).toInt() })
        playerActions.addView(btnClose, LinearLayout.LayoutParams((48 * d).toInt(), (36 * d).toInt())
            .apply { marginStart = (6 * d).toInt() })
        btnCenter.setOnClickListener { recenter(); sbsOverlay.flash("视角已回正"); showControls() }
        btnHoldSpeed.setOnClickListener {
            sbsOverlay.flash("按住以 ${PlaybackGestureOptions.speedLabel(holdSpeedSetting)} 播放，松手恢复")
        }
        btnHoldSpeed.setOnLongClickListener { view ->
            if (speedButtonTouched) beginHoldSpeed() else view.performClick()
            true
        }
        btnHoldSpeed.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> speedButtonTouched = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { speedButtonTouched = false; endHoldSpeed() }
                MotionEvent.ACTION_POINTER_DOWN -> { speedButtonTouched = false; view.cancelLongPress(); endHoldSpeed() }
                MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height) {
                    speedButtonTouched = false; view.cancelLongPress(); endHoldSpeed()
                }
            }
            false // TextView still handles clicks, long clicks and accessibility.
        }
        if (glassesDisplay == null) {
            // The compact header owns Controls / Center / Close; no stacked phone buttons.
            btnCenter.visibility = View.GONE
        } else {
            quick.visibility = View.VISIBLE
            listOf(btnRw, btnPlay, btnFf).forEachIndexed { index, button ->
                (button.parent as android.view.ViewGroup).removeView(button)
                quick.addView(button, index)
            }
            btnRw.text = "−10秒"; btnRw.contentDescription = "后退10秒，长按后退1分钟"
            btnPlay.text = "播放"
            btnFf.text = "+15秒"; btnFf.contentDescription = "前进15秒，长按前进5分钟"
            for (index in 0 until quick.childCount) {
                (quick.getChildAt(index) as TextView).apply {
                    textSize = 11f; gravity = Gravity.CENTER; maxLines = 2
                    setPadding((2 * d).toInt(), 0, (2 * d).toInt(), 0)
                    setBackgroundResource(R.drawable.bg_input)
                    layoutParams = LinearLayout.LayoutParams(0, (48 * d).toInt(), 1f)
                        .apply { if (index < quick.childCount - 1) marginEnd = (4 * d).toInt() }
                }
            }
        }
        updateHoldSpeedLabels()
        updateOrientationButton()
    }

    private fun beginHoldSpeed() {
        if (holdSpeedSetting == 0f || swipeSeek?.target != null || swipeSeek?.vertical == true) return
        val p = player ?: return
        if (!p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            sbsOverlay.flash("此视频不支持调整倍速")
            return
        }
        if (!p.playWhenReady || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            sbsOverlay.flash("请先播放视频，再使用长按倍速")
            return
        }
        if (!holdSpeed.begin(p.playWhenReady, holdSpeedSetting)) return
        handler.removeCallbacks(hideControls)
        sbsOverlay.findViewById<View>(R.id.speedIndicator).visibility = View.VISIBLE
        btnHoldSpeed.setBackgroundResource(R.drawable.bg_row_active)
    }

    private fun endHoldSpeed() {
        if (!holdSpeed.active) return
        holdSpeed.end()
        sbsOverlay.findViewById<View>(R.id.speedIndicator).visibility = View.GONE
        btnHoldSpeed.setBackgroundResource(R.drawable.bg_input)
        if (glassesDisplay == null && controlsBar.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideControls)
            handler.postDelayed(hideControls, 3000)
        }
    }

    private fun cancelHoldPlayback() {
        finishSwipeSeek(commit = false)
        screenHoldAllowed = false
        speedButtonTouched = false
        if (::btnHoldSpeed.isInitialized) { btnHoldSpeed.cancelLongPress(); btnHoldSpeed.isPressed = false }
        endHoldSpeed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) cancelHoldPlayback()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Catch cancellation even when Android splits different fingers between buttons.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_CANCEL)
            cancelHoldPlayback()
        return super.dispatchTouchEvent(event)
    }

    private fun startVerticalSwipe(volume: Boolean): VerticalPlaybackSwipe {
        if (volume) {
            gestureVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val min = if (android.os.Build.VERSION.SDK_INT >= 28)
                audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            return VerticalPlaybackSwipe(true, gestureVolume.toFloat(), min.toFloat(),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat(), glView.height)
        }
        val brightness = window.attributes.screenBrightness.takeIf { it >= 0f }
            ?: (android.provider.Settings.System.getInt(contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
        return VerticalPlaybackSwipe(false, brightness.coerceIn(0.02f, 1f), 0.02f, 1f, glView.height)
    }

    private fun adjustVerticalSwipe(deltaY: Float) {
        val session = verticalSwipe ?: return
        val value = session.move(deltaY) ?: return
        val label = if (session.volume) {
            if (audio.isVolumeFixed) {
                swipeSeekWarning = true
                sbsOverlay.flash("当前设备的音量由外部设备控制")
                return
            }
            try {
                val index = value.roundToInt()
                if (index != gestureVolume) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
                    gestureVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
            } catch (_: SecurityException) {
                swipeSeekWarning = true
                sbsOverlay.flash("系统暂不允许调整音量，请使用音量键")
                return
            }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            "音量 ${(gestureVolume * 100f / max).roundToInt()}%"
        } else {
            window.attributes = window.attributes.apply { screenBrightness = value }
            "亮度 ${(value * 100).roundToInt()}%"
        }
        sbsOverlay.findViewById<TextView>(R.id.swipeSeekPreview).apply {
            text = label
            visibility = View.VISIBLE
        }
        handler.removeCallbacks(hideControls)
    }

    private fun finishSwipeSeek(commit: Boolean) {
        verticalSwipe = null
        val session = swipeSeek ?: return
        swipeSeek = null
        val target = session.finish(cancelled = !commit)
        if (target != null) player?.let { p ->
            if (p.isCurrentMediaItemSeekable && p.duration > 0) p.seekTo(target.coerceIn(0, p.duration))
        }
        sbsOverlay.findViewById<View>(R.id.swipeSeekPreview).visibility = View.GONE
        updateProgress()
        if (controlsBar.visibility == View.VISIBLE && glassesDisplay == null && !gestureSettingsOpen && !holdSpeed.active) {
            handler.removeCallbacks(hideControls)
            handler.postDelayed(hideControls, 3000)
        }
    }

    private fun updateHoldSpeedLabels() {
        val label = PlaybackGestureOptions.speedLabel(holdSpeedSetting)
        btnHoldSpeed.text = "按住$label"
        btnHoldSpeed.contentDescription = "长按播放倍速：$label"
        btnHoldSpeed.isEnabled = holdSpeedSetting > 0
        btnHoldSpeed.alpha = if (holdSpeedSetting > 0) 1f else 0.45f
        sbsOverlay.findViewById<TextView>(R.id.speedIndicator).text = "$label · 松手恢复"
    }

    private fun toggleOrientation() {
        cancelHoldPlayback()
        if (gestureSettingsOpen) closeGestureSettings()
        orientationMode = PlaybackOrientation.next(currentOrientationMode())
        if (systemAutoRotate) rotationFollow.holdManual()
        getSharedPreferences("neoxr", MODE_PRIVATE).edit()
            .putString("playbackOrientation", orientationMode).remove("playbackOrientationLock").apply()
        requestedOrientation = PlaybackOrientation.requested(orientationMode)
        updateOrientationButton()
        showControls()
    }

    private fun updateOrientationButton() {
        sbsOverlay.findViewById<TextView>(R.id.btnOrientation).apply {
            text = if (PlaybackOrientation.next(currentOrientationMode()) == "portrait") "竖屏" else "横屏"
            contentDescription = "点击切换到${text}播放"
        }
    }

    private fun currentOrientationMode() = if (systemAutoRotate && !rotationFollow.manualOverride) {
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
    } else orientationMode

    private fun readSystemAutoRotate() = android.provider.Settings.System.getInt(contentResolver,
        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) != 0

    private fun syncSystemRotation() {
        val enabled = readSystemAutoRotate()
        if (systemAutoRotate && !enabled) {
            orientationMode = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                "portrait" else "landscape"
            getSharedPreferences("neoxr", MODE_PRIVATE).edit().putString("playbackOrientation", orientationMode).apply()
        }
        systemAutoRotate = enabled
        rotationFollow.reset()
        if (enabled) physicalRotation.enable() else physicalRotation.disable()
        requestedOrientation = PlaybackOrientation.requested(orientationMode, enabled)
        if (::playerActions.isInitialized) updateOrientationButton()
    }

    private fun showGestureSettings() {
        cancelHoldPlayback()
        gestureSettingsOpen = true
        qualityList.visibility = View.GONE
        trackScroll.visibility = View.GONE
        sbsOverlay.findViewById<View>(R.id.eyePanel).visibility = View.GONE
        val rows = sbsOverlay.findViewById<LinearLayout>(R.id.gestureSettingsRows)
        val d = resources.displayMetrics.density
        rows.removeAllViews()
        fun header(label: String) = rows.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(getColor(R.color.on_surface))
            setPadding((6 * d).toInt(), (8 * d).toInt(), 0, (6 * d).toInt())
        })
        fun choices(items: List<Pair<String, Boolean>>, choose: (Int) -> Unit) {
            val line = LinearLayout(this)
            items.forEachIndexed { index, (label, selected) ->
                line.addView(TextView(this).apply {
                    text = label; textSize = 11f; gravity = Gravity.CENTER
                    mark(this, selected)
                    setOnClickListener { choose(index); showGestureSettings() }
                }, LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f).apply { marginEnd = (4 * d).toInt() })
            }
            rows.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * d).toInt() })
        }
        header("手机画面滑动")
        choices(listOf("调整视角" to !phoneSwipeSeeks, "调整进度" to phoneSwipeSeeks)) { index ->
            phoneSwipeSeeks = index == 1
            getSharedPreferences("neoxr", MODE_PRIVATE).edit().putBoolean("phoneSwipeSeeks", phoneSwipeSeeks).apply()
        }
        if (phoneSwipeSeeks) rows.addView(TextView(this).apply {
            text = "左右滑动调进度；左侧上下调屏幕亮度，右侧上下调音量。上滑增大，下滑减小。"
            textSize = 10f; setTextColor(getColor(R.color.on_surface_variant))
            setPadding((6 * d).toInt(), 0, (6 * d).toInt(), (8 * d).toInt())
        })
        header("长按倍速 · 松手恢复")
        PlaybackGestureOptions.holdSpeeds.chunked(4).forEach { speeds ->
            choices(speeds.map { PlaybackGestureOptions.speedLabel(it) to (it == holdSpeedSetting) }) { index ->
                holdSpeedSetting = speeds[index]
                getSharedPreferences("neoxr", MODE_PRIVATE).edit().putFloat("holdSpeed", holdSpeedSetting).apply()
                updateHoldSpeedLabels()
            }
        }
        rows.addView(TextView(this).apply {
            text = "完成"; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.primary)); setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setOnClickListener { closeGestureSettings() }
        })
        val panel = sbsOverlay.findViewById<View>(R.id.gestureSettings)
        (panel.layoutParams as FrameLayout.LayoutParams).apply {
            height = minOf((320 * d).toInt(), (sbsOverlay.height * 0.72f).toInt())
        }.also { panel.layoutParams = it }
        panel.visibility = View.VISIBLE
        panel.bringToFront()
        showControls()
    }

    private fun closeGestureSettings() {
        gestureSettingsOpen = false
        sbsOverlay.findViewById<View>(R.id.gestureSettings).visibility = View.GONE
        showControls()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (gestureSettingsOpen) closeGestureSettings() else super.onBackPressed()
    }

    /** Applies projection + eye layout to the renderer and highlights the side buttons. */
    private fun setFormat(angle: Int, layout: String) {
        screenAngle = angle
        stereo = layout
        renderer.configure(angle, layout)
        player?.videoSize?.let { renderer.setVideoSize(it.width, it.height, it.pixelWidthHeightRatio) }
        for ((a, b) in screenBtns) mark(b, a == angle)
        for ((l, b) in layoutBtns) mark(b, l == layout)
    }

    /** Active format button wears the brand beam on its edge, same as the list rows. */
    private fun mark(b: TextView, on: Boolean) {
        b.setBackgroundResource(if (on) R.drawable.bg_row_active else R.drawable.bg_input)
        b.setTextColor(getColor(if (on) R.color.on_surface else R.color.on_surface_variant))
    }

    /**
     * Format buttons: two columns at the layout edges INSIDE the SBS overlay, so the
     * glasses show them to both eyes (drawn twice, like the control bar); anything
     * placed outside the overlay is invisible in stereo. One direct button per mode,
     * no cycling. Raised above the bottom bar so playback controls and the quality
     * list stay reachable.
     */
    private fun buildSidePanels() {
        val d = resources.displayMetrics.density
        // remote mode has the whole phone screen to itself — roomier cells, column
        // captions, medium type. The SBS-overlay path keeps the tight 36dp budget
        // (7 rows must clear the bar on a ~360dp-tall landscape screen).
        val remote = glassesDisplay != null
        fun column(gravity: Int, caption: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // bottom-anchored, both columns aligned just above the bar. NOT
            // CENTER_VERTICAL: FrameLayout shifts a centered child up by the FULL
            // bottomMargin (not half of it), which threw the columns off the top.
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity or Gravity.BOTTOM
            ).apply {
                marginStart = ((if (remote) 14 else 8) * d).toInt()
                marginEnd = ((if (remote) 14 else 8) * d).toInt()
                bottomMargin = (68 * d).toInt() // bar is ~60dp deep incl. its margin
            }
            if (remote) {
                addView(TextView(this@PlayerActivity).apply {
                    text = caption
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    letterSpacing = 0f
                    // setGravity: the column() parameter `gravity` shadows the property
                    setGravity(Gravity.CENTER)
                    setTextColor(getColor(R.color.on_surface_variant))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * d).toInt() })
            }
            visibility = View.GONE
            sbsOverlay.addView(this)
        }
        fun button(parent: LinearLayout, label: String, onClick: () -> Unit) =
            TextView(this).apply {
                text = label
                textSize = if (remote) 14f else 12f
                if (remote) typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.on_surface))
                setBackgroundResource(R.drawable.bg_input)
                layoutParams = LinearLayout.LayoutParams(
                    ((if (remote) 100 else 84) * d).toInt(),
                    ((if (remote) 44 else 36) * d).toInt()
                ).apply { topMargin = ((if (remote) 4 else 3) * d).toInt() }
                setOnClickListener { onClick() }
                parent.addView(this)
            }

        leftPanel = column(Gravity.START, "画面投影")
        for ((angle, label) in listOf(0 to "平面", 90 to "宽屏", 180 to "180°", 360 to "360°")) {
            screenBtns[angle] = button(leftPanel, label) {
                formatAuto = false
                setFormat(angle, stereo)
                showControls()
            }
        }
        // Head tracking needs the glasses anyway, so this row only exists in remote
        // mode — the phone-SBS overlay keeps its 4-row budget. It sits under its own
        // caption: testers kept reading it as a fifth projection mode and never
        // found the feature.
        if (glassesDisplay != null) {
            // compact caption: the landscape column budget is tight (5 rows must
            // clear the bar on a ~353dp screen), so this adds ~15dp, not ~26dp
            leftPanel.addView(TextView(this).apply {
                text = "视角控制"
                textSize = 8f
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0f
                setGravity(Gravity.CENTER)
                setTextColor(getColor(R.color.on_surface_variant))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (5 * d).toInt() })
            btnHead = button(leftPanel, "头控") { toggleHead() }
        }
        rightPanel = column(Gravity.END, "立体布局")
        for ((layout, label) in listOf("off" to "二维", "sbs" to "左右", "tb" to "上下")) {
            layoutBtns[layout] = button(rightPanel, label) {
                formatAuto = false
                setFormat(screenAngle, layout)
                showControls()
            }
        }
        if (remote) {
            // steppers instead of four stacked buttons: [−  W 100  +] with a live
            // mono value. Cuts the right column to 5 rows — the same count as the
            // left one, so the SCREEN/LAYOUT captions align and the column fits the
            // ~353dp landscape height (7 rows of 48dp overflow it).
            fun stepper(prefix: String, step: (Int) -> Unit): TextView {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundResource(R.drawable.bg_input)
                }
                fun side(label: String, delta: Int) = TextView(this).apply {
                    text = label
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.on_surface_variant))
                    setOnClickListener { step(delta) }
                    row.addView(this, LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                    ))
                }
                side("−", -1)
                val value = TextView(this).apply {
                    text = "$prefix 100"
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.on_surface))
                    row.addView(this, LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f
                    ))
                }
                side("+", +1)
                rightPanel.addView(row, LinearLayout.LayoutParams(
                    (124 * d).toInt(), (44 * d).toInt()
                ).apply { topMargin = (4 * d).toInt() })
                stepperRows.add(row)
                return value
            }
            rightPanel.gravity = Gravity.CENTER_HORIZONTAL
            wValue = stepper("宽") { adjustWidth(it * 5) }
            hValue = stepper("高") { adjustHeight(it * 5) }
            zValue = stepper("缩") { adjustZoom(it * 10) }
            wRow = stepperRows[stepperRows.size - 3]
            hRow = stepperRows[stepperRows.size - 2]
        } else {
            button(rightPanel, "宽−") { adjustWidth(-5) }
            button(rightPanel, "宽+") { adjustWidth(+5) }
            button(rightPanel, "缩−") { adjustZoom(-10) }
            button(rightPanel, "缩+") { adjustZoom(+10) }
        }

        // close: top center, clear of both side columns; inside the overlay so both
        // eyes get a copy; shows and hides with the rest of the controls
        btnClose = TextView(this).apply {
            text = "✕"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.on_surface_variant))
            setBackgroundResource(R.drawable.bg_input)
            layoutParams = FrameLayout.LayoutParams(
                (72 * d).toInt(), (36 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = (8 * d).toInt() }
            visibility = View.GONE
            setOnClickListener { finish() }
            sbsOverlay.addView(this)
        }

        // "屏幕" switches which display the video renders on — the auto-pick can
        // land on a second built-in panel on dual-screen devices. Only shown when
        // there is somewhere else to go.
        if (glassesDisplay != null && Glasses.candidates(this).size > 1) {
            btnScreen = TextView(this).apply {
                text = "屏幕"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.on_surface_variant))
                setBackgroundResource(R.drawable.bg_input)
                layoutParams = FrameLayout.LayoutParams(
                    (72 * d).toInt(), (36 * d).toInt(), Gravity.TOP or Gravity.START
                ).apply { topMargin = (8 * d).toInt(); marginStart = (12 * d).toInt() }
                visibility = View.GONE
                setOnClickListener { Glasses.cycle(this@PlayerActivity)?.let { recreate() } }
                sbsOverlay.addView(this)
            }
        }
    }

    /**
     * Playback controls for both orientations, applied at build and on every
     * rotation (configChanges — no recreate). Portrait narrows the side columns
     * (76dp buttons / 112dp steppers vs 100/124 in landscape) so the central
     * look-around pad stays big; the 3D panel and quality list move ABOVE the
     * columns in portrait — side-by-side there is no width left for them.
     */
    private fun applyPlayerDims() {
        if (!::btn3d.isInitialized) return
        val d = resources.displayMetrics.density
        val remote = glassesDisplay != null
        val portrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val btnW = ((if (portrait) 76 else if (remote) 100 else 84) * d).toInt()
        val stepW = ((if (portrait) 112 else 124) * d).toInt()
        val txt = if (portrait) 13f else 14f
        for (b in screenBtns.values + layoutBtns.values + listOfNotNull(btnHead)) {
            b.textSize = txt
            b.layoutParams = (b.layoutParams as LinearLayout.LayoutParams)
                .apply {
                    width = btnW
                    height = ((if (portrait) 36 else 32) * d).toInt()
                    topMargin = ((if (portrait) 4 else 2) * d).toInt()
                }
        }
        for (r in stepperRows) {
            r.layoutParams = (r.layoutParams as LinearLayout.LayoutParams)
                .apply {
                    width = stepW
                    height = ((if (portrait) 44 else 32) * d).toInt()
                    topMargin = ((if (portrait) 4 else 2) * d).toInt()
                }
        }

        PlayerControlRows.arrange(sbsOverlay, portrait, remote,
            if (remote || renderer.monoOutput) navigationBottom else 0)

        // ✕: top-right in portrait (the front camera sits top-center there);
        // top-center in landscape, where the side columns own the corners
        (playerActions.layoutParams as FrameLayout.LayoutParams).let {
            it.gravity = Gravity.TOP or
                    (if (portrait) Gravity.END else Gravity.CENTER_HORIZONTAL)
            it.marginEnd = ((if (portrait) 12 else 0) * d).toInt()
            it.topMargin = ((if (portrait) 32 else 8) * d).toInt()
        }
        playerActions.layoutParams = playerActions.layoutParams
        btnScreen?.let { button ->
            (button.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = ((if (portrait) 80 else 8) * d).toInt()
            }.also { button.layoutParams = it }
        }

        // W and H live in the right column when there is height for them (portrait)
        // and inside the 3D panel otherwise — the landscape column has no room left
        wRow?.visibility = if (portrait) View.VISIBLE else View.GONE
        hRow?.visibility = if (portrait) View.VISIBLE else View.GONE
        sbsOverlay.findViewById<View>(R.id.panelWidthRow)?.visibility =
            if (remote && portrait) View.GONE else View.VISIBLE
        sbsOverlay.findViewById<View>(R.id.panelHeightRow)?.visibility =
            if (remote && portrait) View.GONE else View.VISIBLE

        // the deeper portrait bar pushes the columns (and the panels above them) up
        val barBottom = (controlsBar.layoutParams as FrameLayout.LayoutParams).bottomMargin
        val colBottom = maxOf(((if (portrait) 170 else if (remote) 116 else 68) * d).toInt(),
            controlsBar.height + barBottom + (12 * d).toInt())
        for (col in listOf(leftPanel, rightPanel)) {
            (col.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                colBottom
            col.layoutParams = col.layoutParams
        }
        val eyePanel = sbsOverlay.findViewById<View>(R.id.eyePanel)
        (eyePanel.layoutParams as FrameLayout.LayoutParams).let {
            it.marginStart = ((if (portrait) 16 else if (remote) 146 else 112) * d).toInt()
            it.marginEnd = it.marginStart
            it.bottomMargin = maxOf(controlsBar.height + barBottom + (6 * d).toInt(),
                ((if (portrait) 150 else if (remote) 124 else 76) * d).toInt())
            it.topMargin = (8 * d).toInt()
        }
        eyePanel.layoutParams = eyePanel.layoutParams
        if (!remote) subtitleView?.let { subtitles ->
            (subtitles.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = maxOf(((if (portrait) 170 else 70) * d).toInt(),
                    controlsBar.height + barBottom + (12 * d).toInt())
            }.also { subtitles.layoutParams = it }
        }
    }

    /**
     * Shows a popup menu directly above the control bar and raises it above the side
     * columns. Menus used to be placed with fixed margins per orientation, which put
     * them under the buttons whenever the bar changed depth.
     */
    private fun showPopup(popup: View) {
        if (gestureSettingsOpen) closeGestureSettings()
        val d = resources.displayMetrics.density
        (popup.layoutParams as FrameLayout.LayoutParams).let {
            if (popup === trackScroll) it.width = minOf((280 * d).toInt(), sbsOverlay.width - (16 * d).toInt()).coerceAtLeast(1)
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.marginStart = (8 * d).toInt()
            it.marginEnd = (8 * d).toInt()
            it.bottomMargin = controlsBar.height + (controlsBar.layoutParams as FrameLayout.LayoutParams).bottomMargin + (6 * d).toInt()
        }
        popup.layoutParams = popup.layoutParams
        popup.visibility = View.VISIBLE
        popup.bringToFront()
        popup.parent?.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::controlsBar.isInitialized) { fullscreenOverCutout(); return }
        if (systemAutoRotate && !rotationFollow.manualOverride) {
            orientationMode = currentOrientationMode()
            getSharedPreferences("neoxr", MODE_PRIVATE).edit().putString("playbackOrientation", orientationMode).apply()
        }
        updateOrientationButton()
        cancelHoldPlayback()
        if (gestureSettingsOpen) closeGestureSettings()
        qualityList.visibility = View.GONE
        trackScroll.visibility = View.GONE
        sbsOverlay.findViewById<View>(R.id.eyePanel).visibility = View.GONE
        applyPlayerDims()
        sbsOverlay.post { if (!isDestroyed) applyPlayerDims() }
        fullscreenOverCutout() // insets shift on rotation
    }

    /**
     * Camera zoom in tan space — a true optical magnification, not an image scale:
     * fov = 2·atan(tan(36°)/zoom). VR content is mastered for ~110°-FOV headsets, so
     * on the glasses' ~57° panel it reads "too far" at 100% and needs roughly 130%.
     * Projection stays rectilinear at any zoom (only rectilinear keeps straight lines
     * straight, which matters for room-scale content); zooming in also shrinks the
     * frustum out of the range where rectilinear edge stretch is visible.
     */
    private fun applyFov() {
        val fov = 2 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(36.0)) / (zoomPct / 100.0)))
        renderer.fovDeg = fov.toFloat().coerceIn(5f, 150f)
    }

    /** Camera zoom; resets per video like the other tweaks. */
    private fun adjustZoom(delta: Int) {
        zoomPct = (zoomPct + delta).coerceIn(50, 300)
        applyFov()
        // remote steppers show the value inline; the SBS overlay has no readout — flash
        zValue?.also { it.text = "缩 $zoomPct" } ?: sbsOverlay.flash("缩放 $zoomPct%")
        showControls()
    }

    /**
     * White text with a heavy outline at the user's size. The glasses squeeze each
     * half horizontally, so subtitles need to be much larger than on a phone;
     * setFixedTextSize also stops the view scaling them by system caption settings.
     */
    private fun styleSubtitles(v: androidx.media3.ui.SubtitleView) {
        v.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                subColor, 0x00000000, 0x00000000,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                0xFF000000.toInt(),
                if (subBold) android.graphics.Typeface.DEFAULT_BOLD else null
            )
        )
        v.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subSizeSp.toFloat())
        // how high above the bottom the line sits — in a dome the picture's lower
        // edge is nowhere near the panel's, so the default 8% is often too low
        // 0 = just above the bottom edge, 100 = top of the view; negative pushes the
        // line down into the black bar, which keeps the picture area itself clear
        v.setBottomPaddingFraction((subBottomPct.coerceIn(0, 100)) / 100f)
        val below = if (subBottomPct < 0) -subBottomPct / 100f else 0f
        v.post { v.translationY = v.height * below }
        v.setApplyEmbeddedStyles(false)
    }

    /** Manual vertical squeeze — for material encoded with the wrong vertical scale. */
    private fun adjustHeight(delta: Int) {
        heightPct = (heightPct + delta).coerceIn(50, 200)
        renderer.heightScale = heightPct / 100f
        sbsOverlay.findViewById<TextView>(R.id.txtH)?.text = "高 $heightPct"
        hValue?.text = "高 $heightPct"
        showControls()
    }

    /** Manual horizontal squeeze for content the glasses show too wide. */
    private fun adjustWidth(delta: Int) {
        widthPct = (widthPct + delta).coerceIn(50, 200)
        renderer.widthScale = widthPct / 100f
        wValue?.text = "宽 $widthPct"
        sbsOverlay.findViewById<TextView>(R.id.txtW)?.text = "宽 $widthPct"
        if (wValue == null && glassesDisplay == null) sbsOverlay.flash("宽度 $widthPct%")
        showControls()
    }

    /** Manual stereo convergence: shifts each eye's image toward (+) or apart (−). */
    private fun adjustEyeShift(d: Int) {
        eyeShiftDp = (eyeShiftDp + d).coerceIn(-30, 30)
        applyEyeShift()
        showControls()
    }

    private fun applyEyeShift() {
        renderer.eyeShiftPx = (eyeShiftDp * resources.displayMetrics.density).toInt()
        findViewById<TextView>(R.id.txtEye)?.text =
            if (eyeShiftDp == 0) "景深" else "景深%+d".format(eyeShiftDp)
    }

    private fun showControls() {
        controlsBar.visibility = View.VISIBLE
        leftPanel.visibility = View.VISIBLE
        rightPanel.visibility = View.VISIBLE
        btnClose.visibility = View.VISIBLE
        if (::playerActions.isInitialized) playerActions.visibility = View.VISIBLE
        if (::btnCenter.isInitialized) btnCenter.visibility = View.VISIBLE
        btnScreen?.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        // auto-hide only when the controls sit over the video itself; the remote
        // screen is all theirs — hiding them there would just add taps
        if (glassesDisplay == null && !holdSpeed.active && !gestureSettingsOpen && swipeSeek?.target == null)
            handler.postDelayed(hideControls, 3000)
        updateProgress()
    }

    private fun updateProgress() {
        val p = player ?: return
        if (controlsBar.visibility != View.VISIBLE) return
        val durSec = (p.duration.coerceAtLeast(0) / 1000).toInt()
        val posSec = (p.currentPosition.coerceAtLeast(0) / 1000).toInt()
        seek.max = durSec
        if (!seekDragging && swipeSeek?.target == null) {
            seek.progress = posSec
            timePos.text = Deo.formatDuration(posSec)
        }
        timeDur.text = Deo.formatDuration(durSec)
        // U+2759 bars / U+25BA pointer: glyphs with NO emoji variant at all. U+23F8
        // has one, and some vendor fonts draw the colored emoji form (ignoring
        // textColor) even with the FE0E text-presentation selector appended.
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        btnPlay.text = if (glassesDisplay != null) {
            if (playing) "暂停" else "播放"
        } else if (playing) "❙❙" else "►"
        btnPlay.contentDescription = if (playing) "暂停播放" else "播放视频"
    }

    /**
     * Quality menu from whichever source exists: the feed's separate source URLs, or
     * — for raw intercepted streams — the manifest variants ExoPlayer reported. The
     * button hides when there is genuinely nothing to pick (single-file mp4): a dead
     * "…" that opens an empty list is worse than no button.
     */
    private fun buildQualityList() {
        qualityList.removeAllViews()
        val padH = (16 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()
        fun entry(label: String, active: Boolean, onClick: () -> Unit) {
            qualityList.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(padH, padV, padH, padV)
                setTextColor(getColor(if (active) R.color.primary else R.color.on_surface))
                setOnClickListener { onClick() }
            })
        }
        if (sources.isNotEmpty()) {
            for ((res, url) in sources) entry("${res}p", url == streamUrl) { switchQuality(url) }
            btnQuality.text =
                sources.firstOrNull { it.second == streamUrl }?.let { "${it.first}p" } ?: "…"
        } else {
            entry("自动", qualityCap == 0) { capQuality(0) }
            for (h in trackHeights) entry("${h}p", qualityCap == h) { capQuality(h) }
            btnQuality.text = if (qualityCap == 0) "自动" else "${qualityCap}p"
        }
        // local files have a single encode — nothing to choose, so no button
        val localFile = intent.data != null && streamUrl?.startsWith("http") != true
        btnQuality.visibility =
            if (!localFile && (sources.size > 1 || trackHeights.size > 1)) View.VISIBLE
            else View.GONE
    }

    /**
     * Audio track and subtitle picker, reusing the quality list as the popup. Both
     * live in the media itself (a file with several dubs or subtitle tracks), so the
     * button only appears when there is something to choose.
     */
    /**
     * Picture calibration and the two comfort knobs that belong with it. Separate
     * from the geometry buttons on purpose: these describe the GLASSES, not the
     * video, so unlike zoom and width they persist across files.
     */
    private fun buildPictureMenu() {
        popupIsPicture = true
        trackList.removeAllViews()
        val padH = (16 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()

        fun header(text: String) {
            trackList.addView(TextView(this).apply {
                this.text = text
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0f
                setPadding(padH, padV, padH, 2)
                setTextColor(getColor(R.color.on_surface_variant))
            })
        }

        // −/value/+ row: writes the pref, applies it live, redraws the menu
        fun row(key: String, value: Int, step: Int, range: IntRange, apply: (Int) -> Unit) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun btn(label: String, delta: Int) = TextView(this).apply {
                text = label
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(0, padV, 0, padV)
                maxLines = 1
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setTextColor(getColor(R.color.on_surface))
                setOnClickListener {
                    val next = (value + delta).coerceIn(range.first, range.last)
                    apply(next)
                    getSharedPreferences("neoxr", MODE_PRIVATE).edit().putInt(key, next).apply()
                    buildPictureMenu()
                    showControls()
                }
                line.addView(this, LinearLayout.LayoutParams(
                    (56 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            btn("−", -step)
            line.addView(TextView(this).apply {
                text = "$value"
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.on_surface_variant))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
            btn("+", +step)
            trackList.addView(line, LinearLayout.LayoutParams(-1, -2))
        }

        header("黑位")
        row("picBlack", (renderer.blackLevel * 100).toInt(), 2, -10..30) {
            renderer.blackLevel = it / 100f
        }
        header("亮度")
        row("picBright", (renderer.brightness * 100).toInt(), 2, -30..30) {
            renderer.brightness = it / 100f
        }
        header("对比度")
        row("picContrast", (renderer.contrast * 100).toInt(), 5, 70..160) {
            renderer.contrast = it / 100f
        }
        header("伽马 · 低于100提亮暗部")
        row("picGamma", (renderer.gamma * 100).toInt(), 5, 60..160) {
            renderer.gamma = it / 100f
        }
        header("氛围光宽度")
        row("glowWidth", (renderer.ambientInset * 100).toInt(), 2, 70..96) {
            renderer.ambientInset = it / 100f
        }
        header("头控 / 陀螺仪灵敏度")
        row("headSens", headSensitivity, 5, 25..150) { headSensitivity = it }

        trackList.addView(TextView(this).apply {
            text = "重置画质"
            textSize = 14f
            setPadding(padH, padV, padH, padV)
            setTextColor(getColor(R.color.primary))
            setOnClickListener {
                renderer.blackLevel = 0f
                renderer.brightness = 0f
                renderer.contrast = 1f
                renderer.gamma = 1f
                getSharedPreferences("neoxr", MODE_PRIVATE).edit()
                    .putInt("picBlack", 0).putInt("picBright", 0)
                    .putInt("picContrast", 100).putInt("picGamma", 100).apply()
                buildPictureMenu()
                showControls()
            }
        })
        showPopup(trackScroll)
    }

    private fun buildTrackMenu() {
        popupIsPicture = false
        val p = player ?: return
        trackList.removeAllViews()
        val padH = (16 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()

        fun header(text: String) {
            trackList.addView(TextView(this).apply {
                this.text = text
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0f
                setPadding(padH, padV, padH, 2)
                setTextColor(getColor(R.color.on_surface_variant))
            })
        }
        fun entry(label: String, active: Boolean, onClick: () -> Unit) {
            trackList.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(padH, padV, padH, padV)
                setTextColor(getColor(if (active) R.color.primary else R.color.on_surface))
                setOnClickListener { onClick(); showControls() }
            })
        }
        fun select(group: Tracks.Group, index: Int) {
            if (group.type == C.TRACK_TYPE_AUDIO) audioDisabled = false
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)
                )
                .setTrackTypeDisabled(group.type, false)
                .build()
            buildTrackMenu()
        }
        fun describe(f: androidx.media3.common.Format, i: Int): String {
            val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
            val label = f.label
            return listOfNotNull(label, lang?.uppercase()).joinToString(" · ")
                .ifEmpty { "轨道 ${i + 1}" }
        }

        for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            val groups = p.currentTracks.groups.filter { it.type == type && it.length > 0 }
            if (groups.isEmpty()) continue
            header(if (type == C.TRACK_TYPE_AUDIO) "音轨" else "字幕")
            if (type == C.TRACK_TYPE_TEXT) {
                val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                entry("关闭", off) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    listOfNotNull(subtitleView, glassesSubs).forEach {
                        it.setCues(emptyList())
                    }
                    buildTrackMenu()
                }
            }
            for (g in groups) {
                for (i in 0 until g.length) {
                    val ok = g.isTrackSupported(i)
                    val label = describe(g.getTrackFormat(i), i) +
                            if (ok) "" else "   （不支持）"
                    entry(label, g.isTrackSelected(i)) {
                        if (ok) select(g, i)
                        else sbsOverlay.flash("设备无法解码此轨道")
                    }
                }
            }
        }
        // subtitle size lives with the subtitle list — the only place it matters
        if (p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
            // −/value/+ stepper: writes the pref, restyles both views, redraws the menu
            fun stepper(key: String, value: Int, step: Int, range: IntRange, set: (Int) -> Unit) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                fun btn(label: String, delta: Int) = TextView(this).apply {
                    text = label
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setPadding(0, padV, 0, padV)
                    maxLines = 1
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    setTextColor(getColor(R.color.on_surface))
                    setOnClickListener {
                        set((value + delta).coerceIn(range.first, range.last))
                        getSharedPreferences("neoxr", MODE_PRIVATE).edit()
                            .putInt(key, (value + delta).coerceIn(range.first, range.last)).apply()
                        listOfNotNull(subtitleView, glassesSubs).forEach { styleSubtitles(it) }
                        buildTrackMenu()
                        showControls()
                    }
                    row.addView(this, LinearLayout.LayoutParams(
                        (56 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                btn("−", -step)
                row.addView(TextView(this).apply {
                    text = "$value"
                    textSize = 14f
                    typeface = android.graphics.Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.on_surface_variant))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
                btn("+", +step)
                trackList.addView(row, LinearLayout.LayoutParams(-1, -2))
            }

            header("字幕大小")
            stepper("subSize", subSizeSp, 2, 12..60) { subSizeSp = it }

            header("字幕高度")
            stepper("subBottom", subBottomPct, 2, -20..100) { subBottomPct = it }

            header("字幕字重")
            entry(if (subBold) "粗体" else "常规", subBold) {
                subBold = !subBold
                getSharedPreferences("neoxr", MODE_PRIVATE)
                    .edit().putBoolean("subBold", subBold).apply()
                listOfNotNull(subtitleView, glassesSubs).forEach { styleSubtitles(it) }
                buildTrackMenu()
            }

            header("字幕颜色")
            val colours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((name, argb) in listOf(
                "白色" to 0xFFFFFFFF.toInt(),
                "黄色" to 0xFFFFE44D.toInt(),
                "青色" to 0xFF6FE7FF.toInt(),
                "绿色" to 0xFF8CFF8C.toInt()
            )) {
                colours.addView(TextView(this).apply {
                    text = if (subColor == argb) "●" else "○"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setPadding(padH, padV, padH, padV)
                    setTextColor(argb)
                    contentDescription = name
                    setOnClickListener {
                        subColor = argb
                        getSharedPreferences("neoxr", MODE_PRIVATE)
                            .edit().putInt("subColor", argb).apply()
                        listOfNotNull(subtitleView, glassesSubs).forEach { styleSubtitles(it) }
                        buildTrackMenu()
                        showControls()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            trackList.addView(colours)

            // Tracks authored for 3D releases already carry a left/right pair, so
            // duplicating them per eye yields four copies — this turns that off.
            header("字幕源布局")
            entry(
                if (subStereoSource) "已为立体字幕（不再复制）" else "普通字幕（双眼各显示一次）",
                false
            ) {
                subStereoSource = !subStereoSource
                getSharedPreferences("neoxr", MODE_PRIVATE)
                    .edit().putBoolean("subStereo", subStereoSource).apply()
                subsWrap?.sbs = !renderer.monoOutput && !subStereoSource
                buildTrackMenu()
            }
        }
        showPopup(trackScroll)
    }

    /** Pins an HLS/DASH stream to a variant height (0 = let ABR choose). */
    private fun capQuality(h: Int) {
        qualityCap = h
        qualityList.visibility = View.GONE
        player?.let {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, if (h == 0) Int.MAX_VALUE else h)
                .build()
        }
        buildQualityList()
        showControls()
    }

    private fun switchQuality(url: String) {
        qualityList.visibility = View.GONE
        if (url != streamUrl) {
            streamUrl = url
            player?.run {
                setMediaItem(MediaItem.fromUri(url), currentPosition)
                prepare()
                play()
            }
            buildQualityList()
        }
        showControls()
    }

    /**
     * Look source toggle: glasses IMU instead of the phone sensor. Requires, in the
     * glasses' own menu, Stabilizer OFF and Follow mode — their native anchoring
     * composites its own warp on top of ours. Long-press recenter works as usual.
     */
    private fun toggleHead() {
        if (headMode) {
            headMode = false
            headTracker?.stop()
            headTracker = null
            btnHead?.let { mark(it, false) }
            sbsOverlay.flash("头控已关闭")
        } else {
            headMode = true
            btnHead?.let { mark(it, true) }
            headTracker = HeadTracker(this) { s -> sbsOverlay.flash(s) }.apply {
                onOrientation = { yaw, pitch ->
                    // reader thread; renderer fields are volatile floats — safe
                    sensorYaw = yaw
                    sensorPitch = pitch
                    applyView()
                }
                // tracker died (no network / stream lost): drop back to the phone
                // gyro automatically, otherwise Head mode leaves the view frozen
                onStopped = {
                    if (headMode) {
                        headMode = false
                        headTracker?.stop()
                        headTracker = null
                        btnHead?.let { mark(it, false) }
                    }
                }
                start()
            }
        }
        showControls()
    }

    private fun applyView() {
        // Sensitivity scales the angle the head (or the phone) has actually turned.
        // 100% is one-to-one with the real world, which is correct but feels fast to
        // some people: the glasses show ~50 degrees of view, so a real 50-degree turn
        // sweeps the whole panel. Below 100 the scene turns less than you do.
        val g = headSensitivity / 100f
        renderer.yawDeg = dragYaw + if (gyroEnabled) wrap(sensorYaw - zeroYaw) * g else 0f
        renderer.pitchDeg =
            (dragPitch + if (gyroEnabled) (sensorPitch - zeroPitch) * g else 0f)
                .coerceIn(-89f, 89f)
        levelView?.postInvalidateOnAnimation()
    }

    /**
     * Remote-mode centerpiece: a crosshair level. Two hairline rails cross at the
     * screen center; an azure bubble rides each — yaw on the horizontal, pitch on
     * the vertical — so the "magic window" orientation is visible on the remote
     * itself. Mono readout below, gesture hints above: the instrument look shared
     * with the menu controller's trackpad.
     */
    private inner class LevelView(ctx: android.content.Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val hair = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(R.color.surface_stroke)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2 * d
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val notch = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(R.color.outline)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2 * d
        }
        private val bubble = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(R.color.primary)
        }
        private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val mono = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(R.color.on_surface_variant)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11 * d
            textAlign = android.graphics.Paint.Align.CENTER
        }
        private val primary = getColor(R.color.primary)

        override fun onDraw(canvas: android.graphics.Canvas) {
            val cx = width / 2f
            // landscape: raised above center so the readout clears the bottom bar.
            // Portrait: the columns fill the lower half — center the cross in the
            // free zone between the top hints and the column tops.
            val portrait = height > width
            val cy = height * (if (portrait) 0.27f else 0.38f)
            val half = Math.min(width, height) * (if (portrait) 0.24f else 0.18f)

            // rails with end + center notches
            canvas.drawLine(cx - half, cy, cx + half, cy, hair)
            canvas.drawLine(cx, cy - half, cx, cy + half, hair)
            val n = 5 * d
            canvas.drawLine(cx - half, cy - n, cx - half, cy + n, notch)
            canvas.drawLine(cx + half, cy - n, cx + half, cy + n, notch)
            canvas.drawLine(cx - n, cy - half, cx + n, cy - half, notch)
            canvas.drawLine(cx - n, cy + half, cx + n, cy + half, notch)
            canvas.drawCircle(cx, cy, 3 * d, notch)

            // bubbles: yaw wraps ±90 into the rail, pitch clamps ±89
            val yaw = wrap(renderer.yawDeg)
            val pitch = renderer.pitchDeg
            val bx = cx + (yaw / 90f).coerceIn(-1f, 1f) * half
            val by = cy - (pitch / 90f).coerceIn(-1f, 1f) * half
            for ((x, y) in listOf(bx to cy, cx to by)) {
                glow.shader = android.graphics.RadialGradient(
                    x, y, 16 * d,
                    (primary and 0x00FFFFFF) or (0x50 shl 24), primary and 0x00FFFFFF,
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawCircle(x, y, 16 * d, glow)
                canvas.drawCircle(x, y, 5 * d, bubble)
            }

            mono.textSize = 11 * d
            canvas.drawText(
                "偏航 %+04.0f°   俯仰 %+04.0f°".format(yaw, pitch),
                cx, cy + half + 26 * d, mono
            )
            // clears the ✕ button above it (top-center, 36dp tall plus its margin)
            mono.textSize = 10 * d
            canvas.drawText(
                "滑动调整视角 · 双击切换陀螺仪 · 长按回正",
                cx, (if (portrait) 90 else 60) * d, mono
            )
        }
    }

    private fun recenter() {
        zeroYaw = sensorYaw
        zeroPitch = sensorPitch
        dragYaw = 0f
        dragPitch = 0f
        applyView()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (headMode) return // the glasses' IMU owns sensorYaw/sensorPitch
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // "magic window": look where the back of the phone points (-Z device axis in
        // world coords). No Euler angles / remap — no gimbal lock in normal holds.
        // R rows = world east/north/up, columns = device x/y/z; back = -(column z).
        val east = -r[2]
        val north = -r[5]
        val up = -r[8]
        // negated so that turning the phone right pans the view right
        sensorYaw = -Math.toDegrees(Math.atan2(east.toDouble(), north.toDouble())).toFloat()
        sensorPitch = Math.toDegrees(Math.asin(up.coerceIn(-1f, 1f).toDouble())).toFloat()
        // Auto-center YAW ONLY on the first sample: the sensor's yaw reference is
        // arbitrary, so without this every video starts looking sideways. Pitch is
        // NOT zeroed — that would capture whatever pose the phone is in at the first
        // sample, and it needs no zero anyway: it is gravity-referenced and already
        // absolute.
        if (!yawZeroed) {
            yawZeroed = true
            zeroYaw = sensorYaw
        }
        applyView()
    }

    /** Wraps an angle difference to [-180, 180] so recentered yaw never jumps across ±180. */
    private fun wrap(a: Float) = ((a % 360f) + 540f) % 360f - 180f

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        lifecyclePaused = false
        if (!rotationObserverRegistered) {
            contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(
                android.provider.Settings.System.ACCELEROMETER_ROTATION), false, rotationSettingObserver)
            rotationObserverRegistered = true
        }
        syncSystemRotation()
        glView.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        handler.post(progressTick)
        fullscreenOverCutout()
        if (restartHead && glassesDisplay != null && !headMode) {
            restartHead = false
            toggleHead()
        }
    }

    override fun onPause() {
        restorePlaying = player?.playWhenReady ?: restorePlaying
        lifecyclePaused = true
        restartHead = headMode
        cancelHoldPlayback()
        physicalRotation.disable()
        if (rotationObserverRegistered) {
            contentResolver.unregisterContentObserver(rotationSettingObserver)
            rotationObserverRegistered = false
        }
        super.onPause()
        rememberPosition()
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(hideControls)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        player?.pause()
        glView.onPause()
    }

    /**
     * Where each video was left off, so reopening it continues instead of restarting.
     * A short prefs list rather than a database: only the last few dozen matter, and
     * a finished or barely-started video is not worth an entry.
     */
    private fun rememberPosition() {
        val url = streamUrl ?: return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        val keep = dur > 0 && pos > 15_000 && dur - pos > 15_000
        val old = org.json.JSONArray(
            getSharedPreferences("neoxr", MODE_PRIVATE).getString("resume", "[]")
        )
        val out = org.json.JSONArray()
        if (keep) out.put(org.json.JSONObject().put("u", url).put("p", pos))
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optString("u") != url && out.length() < 40) out.put(o)
        }
        getSharedPreferences("neoxr", MODE_PRIVATE)
            .edit().putString("resume", out.toString()).apply()
    }

    private fun rememberedPosition(url: String): Long {
        val list = org.json.JSONArray(
            getSharedPreferences("neoxr", MODE_PRIVATE).getString("resume", "[]")
        )
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("u") == url) return o.optLong("p")
        }
        return 0L
    }

    override fun onStop() {
        super.onStop()
        // release the glasses' IMU socket while off screen: it accepts one client,
        // and holding it here would deny head tracking to the next player opened
        if (headMode) {
            headMode = false
            headTracker?.stop()
            headTracker = null
            btnHead?.let { mark(it, false) }
        }
    }

    companion object {
        /** Ambient glow dim factors; index 0 is off. Testers asked for brighter than one fixed level. */
        private val AMBIENT_STEPS = floatArrayOf(0f, 0.45f, 0.8f, 1.2f)
        private val AMBIENT_NAMES = arrayOf("关闭", "柔和", "适中", "明亮")
    }

    override fun onDestroy() {
        cancelHoldPlayback()
        super.onDestroy()
        if (::outputWatcher.isInitialized) outputWatcher.close()
        outputWindow?.close()
        headTracker?.stop()
        presentation?.setOnDismissListener(null)
        presentation?.dismiss()
        player?.release()
        videoSurface?.release()
        surfaceTexture?.release()
    }
}
