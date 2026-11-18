package com.neoxr.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [PlayerAutoHeadTest.Usb::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerAutoHeadTest {
    // Exercise the real Activity -> HeadTracker -> GT path up to Android's USB
    // permission boundary; no native sensor or decoder is required for this check.
    @Implements(UsbManager::class)
    class Usb : ShadowUsbManager() {
        var requests = 0
        lateinit var reply: PendingIntent
        @Implementation fun requestPermission(device: UsbDevice, result: PendingIntent) { requests++; reply = result }
    }

    @Test fun onlyGlassesPlaybackRequestsTheImuAndOptOutLastsForOneVideo() {
        val app = RuntimeEnvironment.getApplication()
        val usb = Shadow.extract<Usb>(app.getSystemService(Context.USB_SERVICE))
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "/dev/bus/usb/001/002")
        ReflectionHelpers.setField(device, "mVendorId", 0x1bbb)
        ReflectionHelpers.setField(device, "mProductId", 0xaf50)
        usb.addOrUpdateUsbDevice(device, false)
        val video = Intent().putExtra("stream", "file:///test/demo.mp4")
        fun open(state: Bundle? = null) = Robolectric.buildActivity(PlayerActivity::class.java, video)
            .create(state).start().resume().also { shadowOf(Looper.getMainLooper()).idle() }
        open().pause().stop().destroy()
        assertEquals(0, usb.requests)
        val display = ShadowDisplayManager.addDisplay("w1920dp-h1080dp-land-mdpi")
        shadowOf(app.getSystemService(DisplayManager::class.java).getDisplay(display)).setFlags(Display.FLAG_PRESENTATION)
        val player = open()
        assertEquals(1, usb.requests)
        fun headButton(view: View): TextView? {
            if (view is TextView && view.text == "头控") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) headButton(view.getChildAt(i))?.let { return it }
            return null
        }
        checkNotNull(headButton(player.get().window.decorView)).performClick()
        val saved = Bundle()
        player.pause().saveInstanceState(saved).stop().destroy()
        open(saved).pause().stop().destroy()
        assertEquals(1, usb.requests)
        val next = open()
        assertEquals(2, usb.requests)
        usb.reply.send(app, 0, Intent().putExtra(UsbManager.EXTRA_DEVICE, device)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        shadowOf(Looper.getMainLooper()).idle()
        val denied = Bundle()
        next.pause().saveInstanceState(denied).stop().destroy()
        assertFalse(denied.getBoolean("headTracking", true))
        val retry = open(denied)
        assertEquals(2, usb.requests)
        checkNotNull(headButton(retry.get().window.decorView)).performClick()
        assertEquals(3, usb.requests)
        retry.pause().stop().destroy()
    }
}
