package com.spatiallens.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.SizeF

data class CalibrationData(
    val mainFocalLengthPixels: Float,
    val mainPrincipalX: Float,
    val mainPrincipalY: Float,
    val mainSensorSize: SizeF,
    val ultraFocalLengthPixels: Float,
    val ultraPrincipalX: Float,
    val ultraPrincipalY: Float,
    val ultraSensorSize: SizeF,
    val baselineTranslationX: Float,
    val baselineTranslationY: Float,
    val baselineTranslationZ: Float,
    val mainIntrinsicMatrix: FloatArray,
    val ultraIntrinsicMatrix: FloatArray,
    val rectificationHomography: FloatArray
) {
    companion object {
        fun fromCameraCharacteristics(
            manager: CameraManager,
            logicalId: String,
            mainPhysicalId: String,
            ultraPhysicalId: String
        ): CalibrationData? {
            return try {
                val logicalChars = manager.getCameraCharacteristics(logicalId)
                val mainChars = logicalChars.getPhysicalCameraCharacteristics(mainPhysicalId)!!
                val ultraChars = logicalChars.getPhysicalCameraCharacteristics(ultraPhysicalId)!!

                val mainSensorSize = mainChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
                val ultraSensorSize = ultraChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!

                val mainFocalLengths = mainChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!
                val ultraFocalLengths = ultraChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!

                val mainFLmm = mainFocalLengths[0]
                val ultraFLmm = ultraFocalLengths[0]

                val mainPixelSize = mainChars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
                val ultraPixelSize = ultraChars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!

                val mainFLPixels = (mainFLmm / mainSensorSize.width) * mainPixelSize.width
                val ultraFLPixels = (ultraFLmm / ultraSensorSize.width) * ultraPixelSize.width

                val mainPx = mainPixelSize.width / 2f
                val mainPy = mainPixelSize.height / 2f
                val ultraPx = ultraPixelSize.width / 2f
                val ultraPy = ultraPixelSize.height / 2f

                val mainK = floatArrayOf(
                    mainFLPixels, 0f, mainPx,
                    0f, mainFLPixels, mainPy,
                    0f, 0f, 1f
                )
                val ultraK = floatArrayOf(
                    ultraFLPixels, 0f, ultraPx,
                    0f, ultraFLPixels, ultraPy,
                    0f, 0f, 1f
                )

                val mainPose = mainChars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                val ultraPose = ultraChars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)

                val baselineX = if (mainPose != null && ultraPose != null) {
                    ultraPose[0] - mainPose[0]
                } else {
                    0.015f
                }
                val baselineY = if (mainPose != null && ultraPose != null) {
                    ultraPose[1] - mainPose[1]
                } else 0f
                val baselineZ = if (mainPose != null && ultraPose != null) {
                    ultraPose[2] - mainPose[2]
                } else 0f

                val fovScale = ultraFLPixels / mainFLPixels
                val homography = floatArrayOf(
                    fovScale, 0f, ultraPx - mainPx * fovScale,
                    0f, fovScale, ultraPy - mainPy * fovScale,
                    0f, 0f, 1f
                )

                CalibrationData(
                    mainFocalLengthPixels = mainFLPixels,
                    mainPrincipalX = mainPx,
                    mainPrincipalY = mainPy,
                    mainSensorSize = mainSensorSize,
                    ultraFocalLengthPixels = ultraFLPixels,
                    ultraPrincipalX = ultraPx,
                    ultraPrincipalY = ultraPy,
                    ultraSensorSize = ultraSensorSize,
                    baselineTranslationX = baselineX,
                    baselineTranslationY = baselineY,
                    baselineTranslationZ = baselineZ,
                    mainIntrinsicMatrix = mainK,
                    ultraIntrinsicMatrix = ultraK,
                    rectificationHomography = homography
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
