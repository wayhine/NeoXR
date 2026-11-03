package com.neoxr.player

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/*
 * XREAL One / One Pro / 1S head tracking over the glasses' network IMU service.
 *
 * The One series exposes no HID IMU — its HID interfaces carry the MCU and buttons
 * and never stream motion data. The X1 chip instead brings up a USB CDC-NCM virtual
 * ethernet link (USB interface class 2.13), and the IMU is a plain TCP service on
 * the glasses: connect to 169.254.2.1 or 169.254.1.1 on port 52998 and read. No init
 * sequence or enable command; frames start arriving immediately at roughly 1000 Hz.
 * This tracker uses the raw gyro / accelerometer reports and skips interleaved
 * magnetometer reports. Orientation fusion is done on this side (see [Madgwick]).
 * Protocol details match the XRLinuxDriver, xreal_one_driver and X-Stuff/opentrack
 * implementations.
 *
 * Glasses-side prerequisites: recent firmware, Stabilizer OFF and Follow mode (not
 * Anchor). With Anchor or Stabilizer on, the X1's own 3DoF image warp is applied on
 * top of this tracking and motion is applied twice.
 */

/**
 * One 134-byte IMU frame. Frames are self-delimited by a 6-byte magic at offset 0,
 * so a reader resyncs by scanning for it. Pure JVM, so the parser is unit-testable.
 */
object ImuFrame {
    const val SIZE = 134
    val MAGIC = byteArrayOf(0x28, 0x36, 0x00, 0x00, 0x00, 0x80.toByte())

    fun magicAt(b: ByteArray, off: Int): Boolean {
        if (off < 0 || off > b.size - SIZE) return false
        if (b[off] != 0x28.toByte() && b[off] != 0x27.toByte()) return false
        for (i in 1 until MAGIC.size) if (b[off + i] != MAGIC[i]) return false
        return true
    }

    /**
     * Parses gyro (rad/s) + accel (m/s²) out of a frame known to start at [off]:
     * six little-endian floats at offset 34, ordered gx gy gz ax ay az. Returns
     * null for other frame types and for values outside sensor range, which is how
     * a false magic match is rejected.
     */
    fun parse(b: ByteArray, off: Int): FloatArray? {
        if (!magicAt(b, off)) return null
        // 1S's report type is at body offset 0x18. Bytes at 78 are padding /
        // firmware-specific data, not a stable packet type marker.
        if (ByteBuffer.wrap(b, off + 30, 4).order(ByteOrder.LITTLE_ENDIAN).int != 0x0b) return null
        val bb = ByteBuffer.wrap(b, off + 34, 24).order(ByteOrder.LITTLE_ENDIAN)
        val v = FloatArray(6) { bb.float } // gx gy gz ax ay az
        for (f in v) if (f.isNaN() || f.isInfinite()) return null
        if (abs(v[0]) > 35f || abs(v[1]) > 35f || abs(v[2]) > 35f) return null // ~2000°/s
        if (abs(v[3]) > 100f || abs(v[4]) > 100f || abs(v[5]) > 100f) return null
        return v
    }

    fun timestamp(b: ByteArray, off: Int): Long =
        ByteBuffer.wrap(b, off + 14, 8).order(ByteOrder.LITTLE_ENDIAN).long
}

/**
 * Madgwick AHRS, 6-axis (no magnetometer), as needed by a raw gyro+accel stream.
 * Gyro in rad/s; accel in any unit (normalized internally, and its correction is
 * skipped when the magnitude falls outside the plausible-gravity band, i.e. during
 * linear acceleration). Pure JVM, unit-testable.
 */
class Madgwick(private val beta: Float = 0.08f) {
    var q0 = 1f; var q1 = 0f; var q2 = 0f; var q3 = 0f

    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float) {
        var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

        val norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm in 5f..15f) { // plausible gravity → apply the accel correction
            val a1 = ax / norm; val a2 = ay / norm; val a3 = az / norm
            val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
            val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
            val _8q1 = 8f * q1; val _8q2 = 8f * q2
            val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3
            var s0 = _4q0 * q2q2 + _2q2 * a1 + _4q0 * q1q1 - _2q1 * a2
            var s1 = _4q1 * q3q3 - _2q3 * a1 + 4f * q0q0 * q1 - _2q0 * a2 -
                    _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * a3
            var s2 = 4f * q0q0 * q2 + _2q0 * a1 + _4q2 * q3q3 - _2q3 * a2 -
                    _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * a3
            var s3 = 4f * q1q1 * q3 - _2q1 * a1 + 4f * q2q2 * q3 - _2q2 * a2
            val sn = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sn > 0f) {
                s0 /= sn; s1 /= sn; s2 /= sn; s3 /= sn
                qDot1 -= beta * s0; qDot2 -= beta * s1; qDot3 -= beta * s2; qDot4 -= beta * s3
            }
        }

        q0 += qDot1 * dt; q1 += qDot2 * dt; q2 += qDot3 * dt; q3 += qDot4 * dt
        val qn = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 /= qn; q1 /= qn; q2 /= qn; q3 /= qn
    }

    val yawDeg: Float
        get() = Math.toDegrees(
            atan2(2.0 * (q1 * q2 + q0 * q3), (q0 * q0 + q1 * q1 - q2 * q2 - q3 * q3).toDouble())
        ).toFloat()
    val pitchDeg: Float
        get() = Math.toDegrees(
            asin((2.0 * (q0 * q2 - q1 * q3)).coerceIn(-1.0, 1.0))
        ).toFloat()
    val rollDeg: Float
        get() = Math.toDegrees(
            atan2(2.0 * (q0 * q1 + q2 * q3), (q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3).toDouble())
        ).toFloat()
}

/**
 * Connects to the glasses' IMU service and turns the raw stream into look angles.
 * [onStatus] gets connection-state messages (UI thread); [onOrientation] gets
 * yaw/pitch degrees, throttled to ~60 Hz, on the reader thread — the renderer's
 * volatile floats are safe to write from there.
 */
class HeadTracker(private val context: Context, private val onStatus: (String) -> Unit) {

    companion object {
        const val HOST = "169.254.2.1"
        const val PORT = 52998
        // One Pro (PID 0x0435/0x0436) mounts its IMU 35° off the optical axis, so a
        // level head reads as pitched; One / One S have no such offset. Flip the
        // constant's sign if the horizon tilts the wrong way on other hardware.
        private val PITCH_OFF_PIDS = setOf(0x0435, 0x0436)
        private const val PITCH_MOUNT_DEG = 35f
        // Signs that map fusion output into renderer axes — the empirical knob of
        // this file, set by live test rather than derivation. Note that after the
        // axis remap below a head nod shows up in the fusion's ROLL axis, which is
        // why pitch is taken from rollDeg.
        private const val YAW_SIGN = 1f
        private const val PITCH_SIGN = -1f

        // The glasses accept a single connection on the IMU port, so only one
        // tracker may be live per process. An activity left in the back stack used
        // to keep the socket open, which made head tracking unavailable in every
        // player opened afterwards — starting a tracker now retires the previous one.
        @Volatile private var active: HeadTracker? = null
    }

    var onOrientation: ((yawDeg: Float, pitchDeg: Float) -> Unit)? = null

    /** Fires (main thread) when the tracker dies on its own — connect failure or a
     *  dropped stream. NOT fired on a deliberate [stop], so the caller can use it to
     *  auto-disable head mode and fall back to the phone gyro. */
    var onStopped: (() -> Unit)? = null

    @Volatile var yawDeg = 0f; private set
    @Volatile var pitchDeg = 0f; private set

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val fusion = Madgwick()
    private val bias = FloatArray(3)

    private val isAir = AirImu.findDevice(context) != null

    private val pitchMount: Float =
        if ((context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values
                .any { it.vendorId == 0x3318 && it.productId in PITCH_OFF_PIDS }
        ) PITCH_MOUNT_DEG else 0f

    private fun status(s: String) {
        android.os.Handler(context.mainLooper).post { if (active === this) onStatus(s) }
    }

    fun start() {
        if (running) return
        active?.takeIf { it !== this }?.stop()
        active = this
        running = true

        // Air-series glasses carry the sensor on USB HID instead of the One series'
        // TCP service — different transport, same fusion (see AirImu.kt).
        AirImu.findDevice(context)?.let { dev ->
            status("头控：正在连接眼镜…")
            AirImu.requestAccess(context, dev,
                onReady = { granted -> if (running) streamAir(granted) },
                onFail = { msg ->
                    status(msg)
                    running = false
                    onStopped?.let { cb -> android.os.Handler(context.mainLooper).post(cb) }
                })
            return
        }

        status("头控：正在连接眼镜…")

        // the NCM link registers as an ethernet network; grab it so the socket pins
        // to that interface even if the phone has wifi/mobile up
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val gotNet = CountDownLatch(1)
        val networks = java.util.concurrent.CopyOnWriteArrayList<Network>()
        fun addNetwork(network: Network) {
            if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                networks.addIfAbsent(network); gotNet.countDown()
            }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = addNetwork(network)
            override fun onLost(network: Network) { networks.remove(network) }
        }
        netCallback = cb
        try { cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb
        )
            cm.allNetworks.forEach(::addNetwork)
        } catch (e: Exception) {
            android.util.Log.w("NeoXR", "IMU network discovery: $e")
        }

        thread(name = "xreal-imu") {
            try {
                gotNet.await(2, TimeUnit.SECONDS)
                for (attempt in 0 until 3) {
                    if (!running) break
                    val addresses = ncmAddresses()
                    val hosts = OneImuEndpoints.hosts(addresses.mapNotNull { it.hostAddress })
                    var connected = false
                    for (host in hosts) {
                        val tries = mutableListOf<Pair<String, () -> Socket>>()
                        for (address in addresses.filter { OneImuEndpoints.matches(it.hostAddress.orEmpty(), host) }) {
                            tries += "bind ${address.hostAddress}" to {
                                Socket().also { socket ->
                                    try { socket.bind(InetSocketAddress(address, 0)) }
                                    catch (e: Exception) { socket.close(); throw e }
                                }
                            }
                        }
                        for (network in networks) tries += "network $network" to { network.socketFactory.createSocket() }
                        tries += "direct" to { Socket() }
                        for ((route, make) in tries) {
                            if (!running) return@thread
                            var candidate: Socket? = null
                            try {
                                candidate = make()
                                socket = candidate // stop() also interrupts connect and first read
                                if (!running) break
                                candidate.connect(InetSocketAddress(host, PORT), 1200)
                                candidate.soTimeout = 1000
                                candidate.tcpNoDelay = true
                                val reader = OneImuStream(candidate.getInputStream())
                                val first = reader.next()
                                if (!running) break
                                connected = true
                                status("头控已连接：$host\n请保持头部平稳片刻")
                                android.util.Log.i("NeoXR", "IMU valid sample $host:$PORT via $route")
                                readLoop(reader, first)
                            } catch (e: Exception) {
                                if (running) android.util.Log.w("NeoXR", "IMU $host via $route: ${e.javaClass.simpleName}: ${e.message}")
                            } finally {
                                try { candidate?.close() } catch (_: Exception) {}
                                if (socket === candidate) socket = null
                            }
                            if (connected) break
                        }
                        if (connected) break
                    }
                    if (running && attempt < 2) {
                        status("头控连接中断或未就绪，正在重试（${attempt + 1}/2）…")
                        Thread.sleep(500)
                    }
                }
                if (running) status(diagnose(cm))
            } catch (e: Exception) {
                // Report what actually went wrong. Blaming a VPN unconditionally
                // sent users without one chasing a setting they do not have.
                if (running) status(diagnose(cm))
            } finally {
                stopNet()
            }
            // reader gone while nobody called stop() → died on its own
            if (running) {
                running = false
                val cb = onStopped
                if (cb != null) android.os.Handler(context.mainLooper).post { if (active === this) cb() }
            }
        }
    }

    /**
     * Why the glasses could not be reached, in the user's terms. Distinguishes the
     * three real cases: the phone never brought up the glasses' network at all
     * (firmware or the glasses' ethernet toggle), something captures that subnet
     * (a VPN), or the network is there but the sensor service refused us.
     */
    private fun diagnose(cm: ConnectivityManager): String {
        if (isAir) return "头控：眼镜未通过 USB 响应。\n请重新插拔眼镜后重试。"
        val addresses = ncmAddresses()
        val vpnUp = cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        return when {
            addresses.isEmpty() && vpnUp ->
                "头控：未连接到眼镜。\n" +
                        "当前 VPN 可能影响连接，请关闭 VPN，" +
                        "或允许本应用绕过 VPN 后重试。"
            addresses.isEmpty() ->
                "头控：手机未检测到眼镜的网络接口。\n" +
                        "请更新眼镜固件，并在眼镜开发者菜单中" +
                        "开启以太网。此连接方式需要 " +
                        "XREAL One / One Pro / 1S，且手机系统须支持 USB 网络。视频输出正常并不代表 USB 网络已连接。"
            vpnUp ->
                "头控：已检测到眼镜，但连接被拒绝。\n" +
                        "请关闭 VPN，或允许本应用绕过 VPN。"
            else ->
                "头控：已检测到眼镜，但传感器没有响应。\n" +
                        "请关闭 Control Glasses、Nebula 等可能占用传感器的应用，重新插拔眼镜，" +
                        "并检查眼镜固件。"
        }
    }

    // IPv4 candidates for an explicitly bound socket: link-local addresses
    // (169.254/16 — the glasses hand these out) and addresses on eth-/usb-/ncm-named
    // interfaces, the glasses' own subnet (169.254.2.x) sorted first.
    // Empty = the phone never brought the interface up.
    private fun ncmAddresses(): List<java.net.Inet4Address> = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { i ->
                i.inetAddresses.asSequence()
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { i.name to it }
            }
            .filter { (n, a) ->
                a.isLinkLocalAddress ||
                        n.startsWith("eth") || n.startsWith("usb") || n.startsWith("ncm")
            }
            .sortedByDescending { (_, a) ->
                if (a.hostAddress?.startsWith("169.254.2.") == true) 1 else 0
            }
            .map { it.second }.distinct().toList()
    } catch (_: Exception) { emptyList() }


    /** Reads the Air-series sensor over USB and feeds the same fusion as the TCP path. */
    private fun streamAir(device: android.hardware.usb.UsbDevice) = thread(name = "xreal-air-imu") {
        val startNs = System.nanoTime()
        var lastNs = 0L
        var lastEmitNs = 0L
        val err = AirImu.stream(context, device, stopped = { !running }) { s ->
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.001f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.02f)
            lastNs = now

            if (abs(s.gx) < 0.035f && abs(s.gy) < 0.035f && abs(s.gz) < 0.035f) {
                bias[0] += (s.gx - bias[0]) * 0.02f
                bias[1] += (s.gy - bias[1]) * 0.02f
                bias[2] += (s.gz - bias[2]) * 0.02f
            }
            // Axis remap is the empirical knob here, exactly as on the One series;
            // this ordering matches the reference driver's device frame.
            fusion.update(
                -(s.gx - bias[0]), -(s.gz - bias[2]), -(s.gy - bias[1]),
                -s.ax, -s.az, -s.ay, dt
            )
            yawDeg = YAW_SIGN * fusion.yawDeg
            pitchDeg = PITCH_SIGN * fusion.rollDeg
            if (now - lastEmitNs > 16_000_000 && now - startNs > 500_000_000) {
                lastEmitNs = now
                onOrientation?.invoke(yawDeg, pitchDeg)
            }
        }
        if (running) {
            status(err ?: "头控：眼镜连接已断开")
            running = false
            onStopped?.let { cb -> android.os.Handler(context.mainLooper).post(cb) }
        }
    }

    private fun readLoop(reader: OneImuStream, first: FloatArray) {
        val startNs = System.nanoTime()
        var previousTimestamp = 0L
        var lastEmitNs = 0L
        var sample = first
        while (running) {
            val v = sample
            val now = System.nanoTime()
            val timestamp = reader.timestampNs
            val step = timestamp - previousTimestamp
            val dt = if (previousTimestamp > 0 && step in 1..20_000_000) step / 1e9f else 0.001f
            previousTimestamp = timestamp

            // runtime gyro-bias: adapt only while nearly still (< ~2°/s)
            if (abs(v[0]) < 0.035f && abs(v[1]) < 0.035f && abs(v[2]) < 0.035f) {
                for (i in 0..2) bias[i] += (v[i] - bias[i]) * 0.02f
            }
            // axis remap per XRLinuxDriver: (x,y,z) → (-x,-z,-y), same for accel
            fusion.update(
                -(v[0] - bias[0]), -(v[2] - bias[2]), -(v[1] - bias[1]),
                -v[3], -v[5], -v[4], dt
            )
            yawDeg = YAW_SIGN * fusion.yawDeg
            pitchDeg = PITCH_SIGN * fusion.rollDeg - pitchMount
            // first ~0.5 s the accel correction is still leveling the horizon —
            // don't steer the view with a converging pose
            if (now - lastEmitNs > 16_000_000 && now - startNs > 500_000_000) {
                lastEmitNs = now
                onOrientation?.invoke(yawDeg, pitchDeg)
            }
            sample = reader.next()
        }
    }

    fun stop() {
        running = false
        if (active === this) active = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        stopNet()
    }

    private fun stopNet() {
        netCallback?.let {
            try {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        netCallback = null
    }
}
