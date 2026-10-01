package com.example.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.example.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin

object SampleVideoGenerator {

    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val FPS = 30
    private const val BITRATE = 2_000_000

    suspend fun createReferenceSampleVideo(
        context: Context,
        durationSeconds: Int = 6
    ): VideoSource = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "sample_reference_motion.mp4")
        if (file.exists() && file.length() > 10_000L) {
            return@withContext VideoSource(
                uri = Uri.fromFile(file),
                title = "Sample Reference Video (Dynamic Zoom & Pan)",
                durationMs = durationSeconds * 1000L,
                width = WIDTH,
                height = HEIGHT,
                fps = FPS.toFloat(),
                fileSizeBytes = file.length(),
                isSample = true
            )
        }

        generateVideoFile(file, durationSeconds, isReference = true)

        VideoSource(
            uri = Uri.fromFile(file),
            title = "Sample Reference Video (Dynamic Zoom & Pan)",
            durationMs = durationSeconds * 1000L,
            width = WIDTH,
            height = HEIGHT,
            fps = FPS.toFloat(),
            fileSizeBytes = file.length(),
            isSample = true
        )
    }

    suspend fun createOriginalSampleVideo(
        context: Context,
        durationSeconds: Int = 8
    ): VideoSource = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "sample_original_footage.mp4")
        if (file.exists() && file.length() > 10_000L) {
            return@withContext VideoSource(
                uri = Uri.fromFile(file),
                title = "Sample Original Footage (Static Wide Shot)",
                durationMs = durationSeconds * 1000L,
                width = WIDTH,
                height = HEIGHT,
                fps = FPS.toFloat(),
                fileSizeBytes = file.length(),
                isSample = true
            )
        }

        generateVideoFile(file, durationSeconds, isReference = false)

        VideoSource(
            uri = Uri.fromFile(file),
            title = "Sample Original Footage (Static Wide Shot)",
            durationMs = durationSeconds * 1000L,
            width = WIDTH,
            height = HEIGHT,
            fps = FPS.toFloat(),
            fileSizeBytes = file.length(),
            isSample = true
        )
    }

    private fun generateVideoFile(outputFile: File, durationSeconds: Int, isReference: Boolean) {
        val totalFrames = durationSeconds * FPS
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        try {
            for (frame in 0 until totalFrames) {
                val timeMs = (frame * 1000L) / FPS
                val timeSec = frame.toFloat() / FPS.toFloat()

                val canvas: Canvas = inputSurface.lockHardwareCanvas()
                try {
                    if (isReference) {
                        renderReferenceFrame(canvas, timeSec, durationSeconds, paint, textPaint)
                    } else {
                        renderOriginalFrame(canvas, timeSec, durationSeconds, paint, textPaint)
                    }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                // Drain encoder
                while (true) {
                    val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (encoderStatus >= 0) {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)
                        if (encodedData != null && muxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            bufferInfo.presentationTimeUs = (frame * 1_000_000L) / FPS
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)
                    }
                }
            }

            // Signal EOS
            encoder.signalEndOfInputStream()
            var eosReached = false
            while (!eosReached) {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 20_000)
                if (encoderStatus >= 0) {
                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                    if (encodedData != null && muxerStarted && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eosReached = true
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false)
                } else if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
            }
        } finally {
            try {
                encoder.stop()
                encoder.release()
            } catch (_: Exception) {}
            try {
                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
            } catch (_: Exception) {}
            inputSurface.release()
        }
    }

    private fun renderReferenceFrame(
        canvas: Canvas,
        timeSec: Float,
        durationSeconds: Int,
        paint: Paint,
        textPaint: Paint
    ) {
        // Dynamic camera motion simulated:
        // 0.0s - 1.0s: hold at 1.0
        // 1.0s - 2.5s: zoom in from 1.0 to 1.45x
        // 2.5s - 3.8s: hold at 1.45x and pan right
        // 3.8s - 5.2s: zoom out from 1.45x back to 1.0x
        // 5.2s - end: hold
        val currentScale: Float
        val panXRatio: Float

        when {
            timeSec < 1.0f -> {
                currentScale = 1.0f
                panXRatio = 0.5f
            }
            timeSec < 2.5f -> {
                val progress = (timeSec - 1.0f) / 1.5f
                val smooth = progress * progress * (3f - 2f * progress)
                currentScale = 1.0f + smooth * 0.45f
                panXRatio = 0.5f
            }
            timeSec < 3.8f -> {
                val progress = (timeSec - 2.5f) / 1.3f
                val smooth = progress * progress * (3f - 2f * progress)
                currentScale = 1.45f
                panXRatio = 0.5f + smooth * 0.12f
            }
            timeSec < 5.2f -> {
                val progress = (timeSec - 3.8f) / 1.4f
                val smooth = progress * progress * (3f - 2f * progress)
                currentScale = 1.45f - smooth * 0.45f
                panXRatio = 0.62f - smooth * 0.12f
            }
            else -> {
                currentScale = 1.0f
                panXRatio = 0.5f
            }
        }

        canvas.save()
        // Apply simulated camera zoom & pan transform to the drawing canvas
        val focalX = WIDTH * panXRatio
        val focalY = HEIGHT * 0.5f
        canvas.translate(WIDTH * 0.5f - (focalX - WIDTH * 0.5f) * currentScale, HEIGHT * 0.5f)
        canvas.scale(currentScale, currentScale)
        canvas.translate(-WIDTH * 0.5f, -HEIGHT * 0.5f)

        // Draw rich reference backdrop with distinct geometric landmarks
        paint.color = Color.rgb(18, 24, 38)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        // Grid lines
        paint.color = Color.rgb(35, 45, 68)
        paint.strokeWidth = 2f
        for (x in 0..WIDTH step 40) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), HEIGHT.toFloat(), paint)
        }
        for (y in 0..HEIGHT step 40) {
            canvas.drawLine(0f, y.toFloat(), WIDTH.toFloat(), y.toFloat(), paint)
        }

        // Concentric target circles in the center
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(0, 229, 255)
        paint.strokeWidth = 4f
        canvas.drawCircle(WIDTH / 2f, HEIGHT / 2f, 90f, paint)
        paint.color = Color.rgb(124, 77, 255)
        canvas.drawCircle(WIDTH / 2f, HEIGHT / 2f, 50f, paint)
        paint.color = Color.rgb(255, 183, 77)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(WIDTH / 2f, HEIGHT / 2f, 15f, paint)

        // Landmark cards
        paint.color = Color.rgb(41, 121, 255)
        canvas.drawRoundRect(80f, 60f, 200f, 130f, 12f, 12f, paint)
        paint.color = Color.rgb(0, 200, 83)
        canvas.drawRoundRect(440f, 220f, 560f, 290f, 12f, 12f, paint)

        canvas.restore()

        // Un-zoomed HUD overlay showing current ground-truth reference camera status
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 10, 14, 20)
        canvas.drawRoundRect(16f, 16f, 320f, 76f, 8f, 8f, paint)

        textPaint.textSize = 16f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.rgb(0, 229, 255)
        canvas.drawText("REF CAMERA TRACKER", 28f, 38f, textPaint)
        textPaint.color = Color.WHITE
        val zoomPct = (currentScale * 100).toInt()
        canvas.drawText("Time: ${String.format(java.util.Locale.US, "%.2fs", timeSec)} | Zoom: ${zoomPct}%", 28f, 60f, textPaint)
    }

    private fun renderOriginalFrame(
        canvas: Canvas,
        timeSec: Float,
        durationSeconds: Int,
        paint: Paint,
        textPaint: Paint
    ) {
        // Original video has static wide angle view of an urban neon scene with a bouncing element
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(15, 20, 30)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        // Gradient neon horizon
        paint.color = Color.rgb(26, 35, 60)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT * 0.6f, paint)

        // City skyline silhouettes
        paint.color = Color.rgb(10, 14, 22)
        val buildings = listOf(
            Triple(20f, 160f, 70f),
            Triple(100f, 120f, 90f),
            Triple(200f, 80f, 80f),
            Triple(290f, 140f, 60f),
            Triple(360f, 100f, 85f),
            Triple(460f, 130f, 70f),
            Triple(540f, 170f, 80f)
        )
        for ((bx, bh, bw) in buildings) {
            canvas.drawRect(bx, HEIGHT * 0.6f - bh, bx + bw, HEIGHT * 0.6f, paint)
        }

        // Street grid
        paint.color = Color.rgb(255, 64, 129)
        paint.strokeWidth = 2f
        canvas.drawLine(0f, HEIGHT * 0.6f, WIDTH.toFloat(), HEIGHT * 0.6f, paint)

        // Perspective grid lines
        for (i in -4..4) {
            val startX = WIDTH / 2f + i * 20f
            val endX = WIDTH / 2f + i * 140f
            canvas.drawLine(startX, HEIGHT * 0.6f, endX, HEIGHT.toFloat(), paint)
        }

        // Animated neon focal sphere
        val bounceY = HEIGHT * 0.5f + (sin(timeSec * 3f) * 35f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 230, 118)
        canvas.drawCircle(WIDTH / 2f, bounceY, 26f, paint)

        paint.color = Color.WHITE
        canvas.drawCircle(WIDTH / 2f, bounceY, 10f, paint)

        // HUD overlay
        paint.color = Color.argb(180, 10, 14, 20)
        canvas.drawRoundRect(WIDTH - 280f, 16f, WIDTH - 16f, 76f, 8f, 8f, paint)

        textPaint.textSize = 16f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.rgb(255, 183, 77)
        canvas.drawText("ORIGINAL FOOTAGE (WIDE)", WIDTH - 268f, 38f, textPaint)
        textPaint.color = Color.WHITE
        canvas.drawText("Static Camera | Duration: ${durationSeconds}s", WIDTH - 268f, 60f, textPaint)
    }
}
