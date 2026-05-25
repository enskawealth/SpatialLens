package com.spatiallens.app

data class CalibrationData(
    val baselineMeters: Float,
    val fovScale: Float,
    val cropX: Float,
    val cropY: Float
) {
    companion object {
        fun estimate(ultraId: String, mainId: String): CalibrationData {
            // Hard‑coded estimates for Pixel Pro (main ~24mm, ultra ~16mm)
            return CalibrationData(
                baselineMeters = 0.015f, // ~15mm
                fovScale = 1.5f, // ultra is 1.5x wider
                cropX = 0.0f,
                cropY = 0.0f
            )
        }
    }
}
