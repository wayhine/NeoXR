package com.neoxr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RayNeoPoseTest {
    @Test fun `identity and sign equivalent quaternion stay centered`() {
        for (w in listOf(1f, -1f)) {
            val pose = RayNeoPose.angles(floatArrayOf(0f, 0f, 0f, w))!!
            assertEquals(0f, pose.first, 0.001f)
            assertEquals(0f, pose.second, 0.001f)
        }
    }

    @Test fun `yaw uses Y and nod uses X in xyzw order`() {
        val half = Math.toRadians(30.0)
        val yaw = RayNeoPose.angles(floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat()))!!
        assertEquals(60f, yaw.first, 0.001f)
        assertEquals(0f, yaw.second, 0.001f)
        val pitch = RayNeoPose.angles(floatArrayOf(-sin(half).toFloat(), 0f, 0f, cos(half).toFloat()))!!
        assertEquals(0f, pitch.first, 0.001f)
        assertEquals(-60f, pitch.second, 0.001f)
    }

    @Test fun `roll alone does not pan the video`() {
        val half = Math.toRadians(40.0)
        val pose = RayNeoPose.angles(floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat()))!!
        assertEquals(0f, pose.first, 0.001f)
        assertEquals(0f, pose.second, 0.001f)
    }

    @Test fun `invalid pose is skipped`() {
        assertNull(RayNeoPose.angles(floatArrayOf(0f, 0f, 0f, 0f)))
        assertNull(RayNeoPose.angles(floatArrayOf(0f, 0f, 0f, Float.NaN)))
        assertNull(RayNeoPose.angles(floatArrayOf(0f, 0f, 0f)))
        assertNull(RayNeoPose.angles(floatArrayOf(0f, 0f, 0f, 20f)))
    }
}
