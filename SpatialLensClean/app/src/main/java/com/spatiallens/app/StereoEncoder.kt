package com.spatiallens.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class StereoEncoder(
    private val outputPath: String,
    private val perEyeResolution: Size,
    private val calibration: CalibrationData
) {
    private val sbsWidth = perEyeResolution.width * 2
    private val sbsHeight = perEyeResolution.height

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var encoderSurface: Surface? = null

    private var renderer: RectificationRenderer? = null

    private val frameQueue = LinkedBlockingQueue<Pair<ByteBuffer, ByteBuffer>>(30)
    private var encoderThread: Thread? = null
    private var running = false

    fun start() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIME_TYPE_AVC, sbsWidth, sbsHeight
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 30_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIME_TYPE_AVC)
        mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = mediaCodec!!.createInputSurface()
        mediaCodec!!.start()

        mediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerStarted = false

        renderer = RectificationRenderer(calibration)
        renderer!!.setup(encoderSurface!!, sbsWidth, sbsHeight)

        running = true

        encoderThread = Thread {
            drainEncoder()
        }.apply { start() }

        Thread {
            while (running) {
                val frames = frameQueue.poll()
                if (frames != null) {
                    renderer!!.renderFrame(frames.first, frames.second)
                } else {
                    Thread.sleep(1)
                }
            }
        }.apply { start() }
    }

    fun processFrames(mainFrame: ByteBuffer, ultraFrame: ByteBuffer) {
        if (!running) return
        val mainClone = ByteBuffer.allocateDirect(mainFrame.remaining())
        mainClone.put(mainFrame)
        mainClone.rewind()

        val ultraClone = ByteBuffer.allocateDirect(ultraFrame.remaining())
        ultraClone.put(ultraFrame)
        ultraClone.rewind()

        frameQueue.offer(mainClone to ultraClone)
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (running || frameQueue.isNotEmpty()) {
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputBufferId >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)!!
                    if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        if (!muxerStarted) {
                            videoTrackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
                            mediaMuxer!!.start()
                            muxerStarted = true
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer!!.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)
                }
                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
                        mediaMuxer!!.start()
                        muxerStarted = true
                    }
                }
                outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!running && frameQueue.isEmpty()) break
                }
            }
        }
    }

    fun stop() {
        running = false
        encoderThread?.join(3000)
        mediaCodec?.signalEndOfInputStream()
        Thread.sleep(500)
        drainEncoder()
        renderer?.release()
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaMuxer?.stop()
        mediaMuxer?.release()
    }
}
