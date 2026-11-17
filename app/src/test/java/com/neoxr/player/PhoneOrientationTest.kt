package com.neoxr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PhoneOrientationTest {
    private fun rotation(yaw: Double, tilt: Double): FloatArray {
        val z = Math.toRadians(yaw)
        val x = Math.toRadians(tilt)
        return floatArrayOf(
            cos(z).toFloat(), (-sin(z) * cos(x)).toFloat(), (sin(z) * sin(x)).toFloat(),
            sin(z).toFloat(), (cos(z) * cos(x)).toFloat(), (-cos(z) * sin(x)).toFloat(),
            0f, sin(x).toFloat(), cos(x).toFloat()
        )
    }

    @Test fun flatPhoneDoesNotFlipHeadingWhenItsTiltCrossesZero() {
        val sensor = PhoneOrientation()
        for (tilt in listOf(0.1, -0.1, 0.001, -0.001, 0.0)) {
            assertEquals(30f, sensor.update(rotation(30.0, tilt))!!.first, 0.01f)
        }
    }

    @Test fun horizontalRotationHasTheSameGainForFlatAndUprightPhones() {
        for (tilt in listOf(0.0, 80.0)) {
            val sensor = PhoneOrientation()
            val start = sensor.update(rotation(10.0, tilt))!!.first
            val turned = sensor.update(rotation(20.0, tilt))!!.first
            assertEquals(10f, turned - start, 0.01f)
        }
    }

    @Test fun changingTheHeadingAxisPreservesTheView() {
        val sensor = PhoneOrientation()
        for (tilt in listOf(80.0, 40.0, 10.0, -10.0, -40.0, -80.0)) {
            assertEquals(30f, sensor.update(rotation(30.0, tilt))!!.first, 0.01f)
        }
    }

    @Test fun pitchRetainsTheGravityReference() {
        val sensor = PhoneOrientation()
        assertEquals(-90f, sensor.update(rotation(0.0, 0.0))!!.second, 0.01f)
        assertEquals(-10f, sensor.update(rotation(0.0, 80.0))!!.second, 0.01f)
    }

    @Test fun invalidSensorDataIsSkipped() {
        assertNull(PhoneOrientation().update(FloatArray(4)))
        assertNull(PhoneOrientation().update(rotation(0.0, 0.0).apply { this[2] = Float.NaN }))
    }
}
