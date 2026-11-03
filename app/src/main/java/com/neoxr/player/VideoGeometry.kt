package com.neoxr.player

/** Display geometry shared by automatic format detection and the flat video quad. */
internal object VideoGeometry {
    fun aspect(width: Int, height: Int, pixelRatio: Float = 1f): Float {
        if (width <= 0 || height <= 0) return 0f
        val pixels = pixelRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (width.toFloat() / height * pixels).takeIf { it.isFinite() && it > 0f } ?: 0f
    }

    // A glasses SBS half is stretched by the display into a full 16:9 eye panel.
    // A phone's single-view output must instead use its actual drawable dimensions.
    fun outputAspect(width: Int, height: Int, mono: Boolean, eyeAspect: Float = 16f / 9f): Float =
        if (mono) aspect(width, height).takeIf { it > 0f } ?: (16f / 9f) else eyeAspect

    // Half SBS stretches each half to the entire panel; Full SBS sends two full
    // panels. 1S can expose 16:10 as well as 16:9. A wide timing alone does not
    // prove the glasses are in 3D: the user must match the OSD mode to our output.
    fun glassesEyeAspect(width: Int, height: Int): Float {
        val full = aspect(width, height).takeIf { it > 0 } ?: return 16f / 9f
        return if (full >= 2.5f) full / 2 else full
    }

    /** Contain the full frame, without cropping UVs or distorting its aspect. */
    fun fit(videoAspect: Float, outputAspect: Float): Pair<Float, Float> {
        if (videoAspect <= 0f || !videoAspect.isFinite() || outputAspect <= 0f || !outputAspect.isFinite()) return 1f to 1f
        return if (videoAspect > outputAspect) 1f to (outputAspect / videoAspect)
            else (videoAspect / outputAspect) to 1f
    }
}
