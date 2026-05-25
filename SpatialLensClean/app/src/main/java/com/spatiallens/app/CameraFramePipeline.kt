package com.spatiallens.app

import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class CameraFramePipeline(private val frameSize: Size) {

    private var frameThread = HandlerThread("FrameThread").apply { start() }
    private var frameHandler = Handler(frameThread.looper)

    private var latestMainFrame: AtomicReference<ByteBuffer?> = AtomicReference(null)
    private var latestUltraFrame: AtomicReference<ByteBuffer?> = AtomicReference(null)
    private var mainTimestamp: AtomicReference<Long> = AtomicReference(0L)
    private var ultraTimestamp: AtomicReference<Long> = AtomicReference(0L)

    var onFramesAvailable: ((ByteBuffer, ByteBuffer) -> Unit)? = null

    val mainImageReader: ImageReader = ImageReader.newInstance(
        frameSize.width, frameSize.height,
        android.graphics.ImageFormat.YUV_420_888, 4
    )
    val ultraImageReader: ImageReader = ImageReader.newInstance(
        frameSize.width, frameSize.height,
        android.graphics.ImageFormat.YUV_420_888, 4
    )

    init {
        mainImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processImage(image, isMain = true)
            image.close()
        }, frameHandler)

        ultraImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processImage(image, isMain = false)
            image.close()
        }, frameHandler)

        frameHandler.post(object : Runnable {
            override fun run() {
                checkSync()
                frameHandler.postDelayed(this, 16)
            }
        })
    }

    private fun processImage(image: Image, isMain: Boolean) {
        val yPlane = image.planes[0]
        val buffer = ByteBuffer.allocateDirect(yPlane.buffer.remaining())
        buffer.put(yPlane.buffer)
        buffer.rewind()

        if (isMain) {
            latestMainFrame.set(buffer)
            mainTimestamp.set(System.nanoTime())
        } else {
            latestUltraFrame.set(buffer)
            ultraTimestamp.set(System.nanoTime())
        }
    }

    private fun checkSync() {
        val mainFrame = latestMainFrame.get() ?: return
        val ultraFrame = latestUltraFrame.get() ?: return
        val mainTs = mainTimestamp.get()
        val ultraTs = ultraTimestamp.get()

        val delta = kotlin.math.abs(mainTs - ultraTs) / 1_000_000f
        if (delta < 5f) {
            onFramesAvailable?.invoke(mainFrame, ultraFrame)
            latestMainFrame.set(null)
            latestUltraFrame.set(null)
        }
    }

    fun close() {
        mainImageReader.close()
        ultraImageReader.close()
        frameThread.quitSafely()
    }
}
