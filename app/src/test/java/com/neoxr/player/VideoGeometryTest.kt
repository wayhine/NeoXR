package com.neoxr.player

import org.junit.Assert.*
import org.junit.Test

class VideoGeometryTest {
    @Test fun glassesUseTheirCurrentHalfOrFullSbsAspectIncluding1sSixteenByTen() {
        assertEquals(16f / 9f, VideoGeometry.glassesEyeAspect(1920, 1080), 0.0001f)
        assertEquals(16f / 9f, VideoGeometry.glassesEyeAspect(3840, 1080), 0.0001f)
        assertEquals(1.6f, VideoGeometry.glassesEyeAspect(1920, 1200), 0.0001f)
        assertEquals(1.6f, VideoGeometry.glassesEyeAspect(3840, 1200), 0.0001f)
        assertEquals(1.6f, VideoGeometry.outputAspect(1920, 1200, false, 1.6f), 0.0001f)
        assertEquals(3.2f, VideoGeometry.outputAspect(3840, 1200, true, 1.6f), 0.0001f)
    }
    @Test fun portraitOnAWidePhoneUsesFullHeightAndKeepsItsWidthToHeightRatio() {
        val video = VideoGeometry.aspect(1080, 1920)
        val panel = VideoGeometry.outputAspect(2400, 1080, true)
        val (x, y) = VideoGeometry.fit(video, panel)
        assertEquals(1f, y, 0.0001f)
        assertEquals(0.253125f, x, 0.0001f)
        assertEquals(9f / 16f, x * 2400 / (y * 1080), 0.0001f)
    }

    @Test fun aPortraitFrameFitsCompletelyInTheGlassesEyePanel() {
        val panel = VideoGeometry.outputAspect(3840, 1080, false)
        assertEquals(16f / 9f, panel, 0.0001f)
        assertEquals(panel, VideoGeometry.outputAspect(1920, 1080, false), 0.0001f)
        val (x, y) = VideoGeometry.fit(9f / 16f, panel)
        assertEquals(1f, y, 0.0001f)
        assertEquals(9f / 16f, x / y * panel, 0.0001f)
    }

    @Test fun switchingOutputAndChangingSurfaceSizeRecomputesTheFit() {
        val video = 9f / 16f
        val phone = VideoGeometry.fit(video, VideoGeometry.outputAspect(2400, 1080, true))
        val glasses = VideoGeometry.fit(video, VideoGeometry.outputAspect(2400, 1080, false))
        assertTrue(phone.first < glasses.first)
        assertEquals(1f to 1f, VideoGeometry.fit(video, VideoGeometry.outputAspect(1080, 1920, true)))
    }

    @Test fun landscapeAndUltrawideFramesKeepTheirShapeWithoutCropping() {
        for (video in listOf(4f / 3f, 16f / 9f, 21f / 9f)) {
            for (panel in listOf(16f / 9f, 20f / 9f)) {
                val (x, y) = VideoGeometry.fit(video, panel)
                assertTrue(x > 0 && x <= 1f && y > 0 && y <= 1f)
                assertTrue(x == 1f || y == 1f)
                assertEquals(video, x / y * panel, 0.0001f)
            }
        }
    }

    @Test fun displayAspectIncludesPixelShapeAndRejectsInvalidPixelRatios() {
        assertEquals(16f / 9f, VideoGeometry.aspect(720, 576, 64f / 45f), 0.0001f)
        assertEquals(9f / 16f, VideoGeometry.aspect(1080, 1080, 9f / 16f), 0.0001f)
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(9f / 16f, VideoGeometry.aspect(1080, 1920, invalid), 0.0001f)
        }
    }

    @Test fun unknownVideoAndOutputSizesNeverGenerateInvalidMeshCoordinates() {
        assertEquals(0f, VideoGeometry.aspect(0, 1920), 0f)
        assertEquals(0f, VideoGeometry.aspect(1080, 0), 0f)
        assertEquals(1f to 1f, VideoGeometry.fit(0f, 16f / 9f))
        assertEquals(1f to 1f, VideoGeometry.fit(1f, Float.NaN))
        assertEquals(16f / 9f, VideoGeometry.outputAspect(0, 0, true), 0.0001f)
    }
}
