package com.neoxr.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** GT USB transport and the vendor SDK's fused glasses pose. */
class RayNeoImu(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onOrientation: (Float, Float) -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val TAG = "NeoXR.RayNeo"
        private const val PERMISSION = "com.neoxr.player.RAYNEO_USB_PERMISSION"
        // Native SDK state is process-wide. Serializing cleanup and start prevents
        // a retiring activity from closing the next player's USB connection.
        private val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "rayneo-imu")
        }
        private var sdkLoader: ClassLoader? = null
        private val runtimeFiles = listOf("runtime.jar", "libFFalconXRServer.so", "libFFalconXRClient.so",
            "libFFalconXRConfig.so", "libFFalconXRGraphic.so")

        fun findDevice(context: Context): UsbDevice? =
            (context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.firstOrNull {
                (it.vendorId == 0x3941 && it.productId in 0xAF50..0xAF51) ||
                    (it.vendorId == 0x1BBB && it.productId == 0xAF50)
            }

        private fun loader(context: Context): ClassLoader {
            sdkLoader?.let { return it }
            val build = context.assets.open("rayneo/build-id.txt").bufferedReader().use { it.readText().trim() }
            require(build.matches(Regex("[0-9a-f]{64}"))) { "Invalid RayNeo runtime build ID" }
            val directory = File(context.codeCacheDir, "rayneo-$build")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create RayNeo runtime directory" }
            for (name in runtimeFiles) {
                val target = File(directory, name)
                if (target.exists()) continue
                val temporary = File(directory, "$name.tmp")
                temporary.delete()
                FileOutputStream(temporary).use { output ->
                    // Android 14+ requires dynamically loaded code to be read-only
                    // before writing through the already-open descriptor.
                    check(temporary.setReadOnly()) { "Cannot protect RayNeo runtime" }
                    context.assets.open("rayneo/$name").use { it.copyTo(output) }
                }
                check(temporary.renameTo(target)) { "Cannot finalize RayNeo runtime" }
            }
            return DexClassLoader(File(directory, "runtime.jar").path, context.codeCacheDir.path,
                directory.path, context.classLoader).also { sdkLoader = it }
        }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var running = false
    @Volatile private var lastImuNs = 0L
    private var registered = false
    private var deviceName: String? = null
    private var connection: UsbDeviceConnection? = null
    private var sdk: Any? = null
    private var imuListener: Any? = null
    private var imuType: Class<*>? = null
    private var polling: ScheduledFuture<*>? = null
    private var connectedAt = 0L
    private var emitted = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running || intent == null) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (device.deviceName != deviceName) return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                fail("头控：雷鸟眼镜已断开，请连接后重新开启头控")
            } else if (intent.action == PERMISSION) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    worker.execute { connect(device) }
                } else fail("头控：未获准通过 USB 访问雷鸟眼镜")
            }
        }
    }

    @Synchronized fun start(device: UsbDevice) {
        if (running) return
        running = true
        deviceName = device.deviceName
        val filter = IntentFilter(PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            registered = true
            status("头控：正在连接雷鸟眼镜…")
            if (usb.hasPermission(device)) worker.execute { connect(device) }
            else usb.requestPermission(device, PendingIntent.getBroadcast(app, 0,
                Intent(PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } catch (error: Exception) {
            Log.e(TAG, "USB permission request", error)
            fail("头控：无法申请雷鸟眼镜的 USB 权限")
        }
    }

    private fun connect(device: UsbDevice) {
        if (!running || sdk != null) return
        try {
            val classLoader = loader(app)
            connection = usb.openDevice(device) ?: error("USB openDevice failed")
            val type = classLoader.loadClass("com.ffalcon.xr.server.FFalconXRServer")
            val server = type.getMethod("Get").invoke(null)!!
            sdk = server
            val api = classLoader.loadClass("com.ffalcon.xr.sdk.FxrApi")
            api.getMethod("Get").invoke(null)
            val init = api.getDeclaredMethod("InitJNI", Context::class.java).apply { isAccessible = true }
            // This SDK returns false on GT even when initialization and streaming succeed.
            Log.i(TAG, "InitJNI=${init.invoke(server, app)}")
            imuType = classLoader.loadClass("com.ffalcon.xr.sdk.listener.IMUEventListener")
            imuListener = Proxy.newProxyInstance(classLoader, arrayOf(imuType!!)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> "NeoXR RayNeo IMU listener"
                    else -> { lastImuNs = SystemClock.elapsedRealtimeNanos(); null }
                }
            }
            type.getMethod("RegisterIMUEventListener", imuType!!).invoke(server, imuListener)
            type.getMethod("StartXR").invoke(server)
            val result = type.getMethod("EstablishUsbConnection", Int::class.javaPrimitiveType)
                .invoke(server, connection!!.fileDescriptor)
            check(result == 0) { "USB handshake returned $result" }
            type.getMethod("OpenIMU").invoke(server)
            connectedAt = SystemClock.elapsedRealtimeNanos()
            val pose = type.getMethod("GetHeadTrackerPose")
            val quaternion = classLoader.loadClass("com.ffalcon.xr.sdk.typedef.FXRPoseInfo").getMethod("GetQuaternion")
            polling = worker.scheduleWithFixedDelay({ poll(server, pose, quaternion) }, 0, 16, TimeUnit.MILLISECONDS)
            Log.i(TAG, "USB connected ${device.vendorId.toString(16)}:${device.productId.toString(16)}")
        } catch (error: Exception) {
            Log.e(TAG, "SDK connection failed", error)
            fail("头控：雷鸟传感器连接失败，请重新连接眼镜后重试")
        } catch (error: LinkageError) {
            Log.e(TAG, "SDK loading failed", error)
            fail("头控：雷鸟运行库无法加载")
        }
    }

    private fun poll(server: Any, pose: Method, quaternion: Method) {
        if (!running) return
        try {
            val now = SystemClock.elapsedRealtimeNanos()
            val last = lastImuNs
            if (now - (last.takeIf { it != 0L } ?: connectedAt) > 3_000_000_000L) {
                fail("头控：雷鸟传感器数据中断，请重新开启头控")
                return
            }
            if (last == 0L) return
            val angles = RayNeoPose.angles(quaternion.invoke(pose.invoke(server)) as FloatArray) ?: return
            if (!emitted) {
                emitted = true
                Log.i(TAG, "First glasses pose yaw=${angles.first} pitch=${angles.second}")
                status("雷鸟头控已连接，长按回正可重新居中")
            }
            if (running) onOrientation(angles.first, angles.second)
        } catch (error: Exception) {
            Log.e(TAG, "Pose read failed", error)
            fail("头控：读取雷鸟眼镜姿态失败")
        }
    }

    private fun status(message: String) = main.post { if (running) onStatus(message) }

    @Synchronized private fun fail(message: String) {
        if (!running) return
        stop()
        main.post { onStatus(message); onStopped() }
    }

    @Synchronized fun stop() {
        running = false
        if (registered) {
            app.unregisterReceiver(receiver)
            registered = false
        }
        worker.execute {
            polling?.cancel(false)
            polling = null
            sdk?.let { server ->
                for (name in listOf("CloseIMU", "NotifyUsbDisconnected", "StopXR")) {
                    runCatching { server.javaClass.getMethod(name).invoke(server) }
                        .onFailure { Log.w(TAG, "Cleanup $name", it) }
                }
                imuType?.let { listenerType ->
                    runCatching { server.javaClass.getMethod("UnRegisterIMUEventListener", listenerType).invoke(server, imuListener) }
                        .onFailure { Log.w(TAG, "Unregister IMU listener", it) }
                }
            }
            sdk = null
            imuListener = null
            connection?.close()
            connection = null
            Log.i(TAG, "USB stopped")
        }
    }
}
