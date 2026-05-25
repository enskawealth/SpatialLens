package com.spatiallens.app

import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class SpatialVideoCapture(
    private val context: Context,
    private val mainPreviewSurface: Surface,
    private val ultraPreviewSurface: Surface
) {
    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread = HandlerThread("CameraThread").apply { start() }
    private var backgroundHandler = Handler(backgroundThread.looper)
    private var executor: Executor = Executors.newSingleThreadExecutor()

    var mainPhysicalId: String = ""
    var ultraPhysicalId: String = ""
    var logicalCameraId: String = ""

    private var framePipeline: CameraFramePipeline? = null
    private var stereoEncoder: StereoEncoder? = null
    private var calibration: CalibrationData? = null

    fun findCameras(): Boolean {
        try {
            for (camId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(camId)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) continue
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue

                // Get physical camera IDs (API 28+)
                val physicalIds: Set<String> = chars.getPhysicalCameraIds()
                if (physicalIds.size < 2) continue

                // Simply take the first two; on Pixel Pro they are ultra-wide and main wide
                val ids = physicalIds.toList()
                ultraPhysicalId = ids[0]
                mainPhysicalId = ids[1]
                logicalCameraId = camId

                calibration = CalibrationData.estimate(ultraPhysicalId, mainPhysicalId)
                return true
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return false
    }

    fun openCamera() {
        if (logicalCameraId.isEmpty()) throw IllegalStateException("Call findCameras() first")
        try {
            cameraManager.openCamera(logicalCameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createSession()
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null }
            })
        } catch (e: SecurityException) { throw RuntimeException("Camera permission not granted", e) }
          catch (e: CameraAccessException) { throw RuntimeException("Cannot open camera", e) }
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val mainConfig = OutputConfiguration(mainPreviewSurface).apply { setPhysicalCameraId(mainPhysicalId) }
        val ultraConfig = OutputConfiguration(ultraPreviewSurface).apply { setPhysicalCameraId(ultraPhysicalId) }
        val configs = listOf(mainConfig, ultraConfig)

        val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    throw RuntimeException("Failed to configure capture session")
                }
            })

        if (!device.isSessionConfigurationSupported(sessionConfig))
            throw RuntimeException("Dual physical camera streaming not supported")

        device.createCaptureSession(sessionConfig)
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(mainPreviewSurface)
            builder.addTarget(ultraPreviewSurface)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) { e.printStackTrace() }
    }

    fun startRecording(outputPath: String) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val cal = calibration ?: throw IllegalStateException("Calibration data not available")
        session.close()

        val frameSize = Size(1920, 1080)
        framePipeline = CameraFramePipeline(frameSize)

        val mainConfig = OutputConfiguration(framePipeline!!.mainImageReader.surface)
            .apply { setPhysicalCameraId(mainPhysicalId) }
        val ultraConfig = OutputConfiguration(framePipeline!!.ultraImageReader.surface)
            .apply { setPhysicalCameraId(ultraPhysicalId) }
        val mainPrev = OutputConfiguration(mainPreviewSurface).apply { setPhysicalCameraId(mainPhysicalId) }
        val ultraPrev = OutputConfiguration(ultraPreviewSurface).apply { setPhysicalCameraId(ultraPhysicalId) }

        val allConfigs = listOf(mainConfig, ultraConfig, mainPrev, ultraPrev)
        val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, allConfigs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    stereoEncoder = StereoEncoder(outputPath, frameSize, cal)
                    stereoEncoder!!.start()
                    framePipeline!!.onFramesAvailable = { mainFrame, ultraFrame ->
                        stereoEncoder!!.processFrames(mainFrame, ultraFrame)
                    }
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(framePipeline!!.mainImageReader.surface)
                        builder.addTarget(framePipeline!!.ultraImageReader.surface)
                        builder.addTarget(mainPreviewSurface)
                        builder.addTarget(ultraPreviewSurface)
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    } catch (e: CameraAccessException) { e.printStackTrace() }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    throw RuntimeException("Failed to configure recording session")
                }
            })
        device.createCaptureSession(sessionConfig)
    }

    fun stopRecording() {
        framePipeline?.onFramesAvailable = null
        stereoEncoder?.stop()
        stereoEncoder = null
        framePipeline?.close()
        framePipeline = null
        createSession()
    }

    fun release() { stopRecording(); captureSession?.close(); cameraDevice?.close(); backgroundThread.quitSafely() }
}
