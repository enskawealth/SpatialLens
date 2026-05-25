package com.spatiallens.app

import android.view.Surface
import java.nio.ByteBuffer

class RectificationRenderer(private val calibration: CalibrationData) {

    fun setup(encoderSurface: Surface, width: Int, height: Int) {
        // No OpenGL needed; we'll do a simple copy
    }

    fun renderFrame(mainY: ByteBuffer, ultraY: ByteBuffer) {
        // Placeholder – actual SBS composition is done in StereoEncoder
    }

    fun release() {}
}
