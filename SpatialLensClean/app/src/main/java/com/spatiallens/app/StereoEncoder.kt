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
    @Volatile private var running = false

    fun start() {
        val format = MediaFormat.createVideoFormat("video/avc", sbsWidth, sbsHeight)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 30_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        mediaCodec = MediaCodec.createEncoderByType("video/avc")
        mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = mediaCodec!!.createInputSurface()
        mediaCodec!!.start()

        mediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        renderer = RectificationRenderer(calibration)
        renderer!!.setup(encoderSurface!!, sbsWidth, sbsHeight)

        running = true
        encoderThread = Thread { drainEncoder() }.apply { start() }

        Thread {
            while (running) {
                val pair = frameQueue.poll()
                if (pair != null) {
                    renderer!!.renderFrame(pair.first, pair.second)
                } else {
                    Thread.sleep(1)
                }
            }
        }.apply { start() }
    }

    fun processFrames(mainFrame: ByteBuffer, ultraFrame: ByteBuffer) {
        if (!running) return
        val mainClone = ByteBuffer.allocateDirect(mainFrame.remaining()).put(mainFrame).apply { rewind() }
        val ultraClone = ByteBuffer.allocateDirect(ultraFrame.remaining()).put(ultraFrame).apply { rewind() }
        frameQueue.offer(mainClone to ultraClone)
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (running || frameQueue.isNotEmpty()) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!muxerStarted) {
                            videoTrackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
                            mediaMuxer!!.start()
                            muxerStarted = true
                        }
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer!!.writeSampleData(videoTrackIndex, buf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
                        mediaMuxer!!.start()
                        muxerStarted = true
                    }
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
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
