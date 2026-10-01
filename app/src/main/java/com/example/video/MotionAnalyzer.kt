package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class RawMotionSample(
    val timestampMs: Long,
    val scale: Float,
    val panX: Float,
    val panY: Float
)

object MotionAnalyzer {

    private const val ANALYSIS_WIDTH = 256
    private const val ANALYSIS_HEIGHT = 144

    private data class LumaFrame(
        val luma: ByteArray,
        val width: Int,
        val height: Int
    )

    private data class FrameMotionDelta(
        val deltaX: Float,
        val deltaY: Float,
        val scaleFactor: Float
    )

    /**
     * Steps through the reference video across the entire timeline (0.000s to end),
     * extracting dense motion data and producing accurate keyframes that capture
     * both zoom-in and zoom-out camera transforms.
     */
    suspend fun extractMotionKeyframesWithMediaExtractor(
        context: Context,
        referenceUri: Uri,
        durationMs: Long,
        onProgress: (progress: Float, message: String) -> Unit
    ): List<MotionKeyframe> = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()

        try {
            extractor.setDataSource(context, referenceUri, null)
            retriever.setDataSource(context, referenceUri)
        } catch (e: Exception) {
            extractor.release()
            try { retriever.release() } catch (_: Exception) {}
            throw IllegalArgumentException("Reference video could not be read: ${e.localizedMessage}")
        }

        val rawSamples = mutableListOf<RawMotionSample>()

        try {
            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }

            if (videoTrackIndex == -1) {
                throw IllegalStateException("No video track found in reference video")
            }

            extractor.selectTrack(videoTrackIndex)

            val safeDurationMs = durationMs.coerceAtLeast(500L)
            // Dense sampling interval for high-fidelity zoom curve reconstruction (100ms - 150ms)
            val sampleStepThresholdMs = when {
                safeDurationMs <= 5000L -> 80L
                safeDurationMs <= 15000L -> 100L
                safeDurationMs <= 35000L -> 125L
                else -> 150L
            }

            var previousFrame: LumaFrame? = null
            var currentScale = 1.0f
            var currentPanX = 0.5f
            var currentPanY = 0.5f

            // Add initial sample at 0ms
            rawSamples.add(
                RawMotionSample(
                    timestampMs = 0L,
                    scale = 1.0f,
                    panX = 0.5f,
                    panY = 0.5f
                )
            )

            var lastSampledMs = 0L

            // Step through reference video frames using MediaExtractor timestamps
            while (true) {
                ensureActive()
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0) break

                val frameTimeMs = (sampleTimeUs / 1000L).coerceIn(0L, safeDurationMs)

                if (frameTimeMs == 0L || abs(frameTimeMs - lastSampledMs) >= sampleStepThresholdMs) {
                    val progress = (frameTimeMs.toFloat() / safeDurationMs.toFloat()).coerceIn(0f, 1f)
                    val formattedCurrent = String.format(java.util.Locale.US, "%.2fs", frameTimeMs / 1000f)
                    val formattedDuration = String.format(java.util.Locale.US, "%.2fs", safeDurationMs / 1000f)
                    val statusText = "Analyzing reference motion at $formattedCurrent / $formattedDuration (${(progress * 100).toInt()}%)"
                    onProgress(progress, statusText)

                    val frameBitmap = extractScaledBitmap(retriever, frameTimeMs)
                    if (frameBitmap != null) {
                        val currentFrame = bitmapToGrayscale(frameBitmap)
                        frameBitmap.recycle()

                        if (previousFrame != null) {
                            val frameDelta = estimateFrameMotion(previousFrame, currentFrame)

                            // Apply frame scale change symmetrically for both zoom-in and zoom-out
                            val stepFactor = frameDelta.scaleFactor.coerceIn(0.85f, 1.15f)
                            currentScale = (currentScale * stepFactor).coerceIn(0.5f, 4.0f)

                            val dNormX = if (currentFrame.width > 0) frameDelta.deltaX / currentFrame.width.toFloat() else 0f
                            val dNormY = if (currentFrame.height > 0) frameDelta.deltaY / currentFrame.height.toFloat() else 0f
                            currentPanX = (currentPanX + dNormX * 0.7f).coerceIn(0.1f, 0.9f)
                            currentPanY = (currentPanY + dNormY * 0.7f).coerceIn(0.1f, 0.9f)

                            rawSamples.add(
                                RawMotionSample(
                                    timestampMs = frameTimeMs,
                                    scale = currentScale,
                                    panX = currentPanX,
                                    panY = currentPanY
                                )
                            )
                        }

                        previousFrame = currentFrame
                        lastSampledMs = frameTimeMs
                    }
                }

                if (!extractor.advance()) break
            }

            // Ensure terminal timestamp is present
            if (rawSamples.isNotEmpty() && rawSamples.last().timestampMs < safeDurationMs) {
                val last = rawSamples.last()
                rawSamples.add(
                    RawMotionSample(
                        timestampMs = safeDurationMs,
                        scale = last.scale,
                        panX = last.panX,
                        panY = last.panY
                    )
                )
            }

            // Sort and deduplicate raw samples
            val sortedSamples = rawSamples.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }

            // Normalize curve to prevent black borders while strictly preserving relative rises and falls
            val minScale = sortedSamples.minOfOrNull { it.scale } ?: 1.0f
            val normalizedSamples = if (minScale < 1.0f && minScale > 0.1f) {
                val multiplier = 1.0f / minScale
                sortedSamples.map { sample ->
                    sample.copy(
                        scale = roundToDecimals(sample.scale * multiplier, 3),
                        panX = roundToDecimals(sample.panX, 3),
                        panY = roundToDecimals(sample.panY, 3)
                    )
                }
            } else {
                sortedSamples.map { sample ->
                    sample.copy(
                        scale = roundToDecimals(sample.scale, 3),
                        panX = roundToDecimals(sample.panX, 3),
                        panY = roundToDecimals(sample.panY, 3)
                    )
                }
            }

            onProgress(0.95f, "Reconstructing motion turning points...")

            // Reconstruct keyframes capturing all zoom-in and zoom-out inflection points
            val reconstructedKeyframes = KeyframeDetector.reconstructKeyframes(
                rawSamples = normalizedSamples,
                referenceDurationMs = safeDurationMs
            )

            onProgress(1.0f, "Reference motion analysis complete (${reconstructedKeyframes.size} keyframes)")
            reconstructedKeyframes
        } finally {
            extractor.release()
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    suspend fun analyzeReferenceVideo(
        context: Context,
        referenceUri: Uri,
        durationMs: Long,
        onProgress: (progress: Float, message: String) -> Unit
    ): List<RawMotionSample> {
        val keyframes = extractMotionKeyframesWithMediaExtractor(context, referenceUri, durationMs, onProgress)
        return keyframes.map {
            RawMotionSample(
                timestampMs = it.timestampMs,
                scale = it.scale,
                panX = it.positionX,
                panY = it.positionY
            )
        }
    }

    private fun extractScaledBitmap(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        val timeUs = (timeMs * 1000L).coerceAtLeast(0L)
        return try {
            val raw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    ANALYSIS_WIDTH,
                    ANALYSIS_HEIGHT
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }

            if (raw == null) return null

            if (raw.width == ANALYSIS_WIDTH && raw.height == ANALYSIS_HEIGHT) {
                raw
            } else {
                val scaled = Bitmap.createScaledBitmap(raw, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, true)
                raw.recycle()
                scaled
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToGrayscale(bitmap: Bitmap): LumaFrame {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val luma = ByteArray(width * height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            luma[i] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
        }
        return LumaFrame(luma, width, height)
    }

    /**
     * Symmetrically estimates the scale factor and pan translation between two frames.
     * Evaluates candidate relative scales (zoom in > 1.0, hold == 1.0, zoom out < 1.0)
     * using multi-scale region correlation with sub-pixel parabolic refinement.
     */
    private fun estimateFrameMotion(
        prevFrame: LumaFrame,
        currFrame: LumaFrame
    ): FrameMotionDelta {
        val width = currFrame.width
        val height = currFrame.height

        if (width <= 0 || height <= 0 ||
            prevFrame.width != width || prevFrame.height != height ||
            prevFrame.luma.size != width * height || currFrame.luma.size != width * height
        ) {
            return FrameMotionDelta(0f, 0f, 1.0f)
        }

        val prevLuma = prevFrame.luma
        val currLuma = currFrame.luma

        val cx = width / 2.0f
        val cy = height / 2.0f

        // Candidate relative scale factors covering zoom-out (<1.0) and zoom-in (>1.0) symmetrically
        val scaleCandidates = floatArrayOf(
            0.92f, 0.94f, 0.96f, 0.97f, 0.98f, 0.99f,
            1.00f,
            1.01f, 1.02f, 1.03f, 1.04f, 1.06f, 1.08f
        )
        val errors = FloatArray(scaleCandidates.size)

        // Evaluate difference across central active region
        val startX = (width * 0.20f).toInt()
        val endX = (width * 0.80f).toInt()
        val startY = (height * 0.20f).toInt()
        val endY = (height * 0.80f).toInt()
        val step = 4

        for (sIdx in scaleCandidates.indices) {
            val s = scaleCandidates[sIdx]
            var sumDiff = 0L
            var count = 0

            var y = startY
            while (y < endY) {
                val ry = y - cy
                val mappedY = (cy + s * ry).toInt()
                if (mappedY in 0 until height) {
                    val prevRow = y * width
                    val currRow = mappedY * width

                    var x = startX
                    while (x < endX) {
                        val rx = x - cx
                        val mappedX = (cx + s * rx).toInt()
                        if (mappedX in 0 until width) {
                            val pIdx = prevRow + x
                            val cIdx = currRow + mappedX
                            if (pIdx in prevLuma.indices && cIdx in currLuma.indices) {
                                val pVal = prevLuma[pIdx].toInt() and 0xFF
                                val cVal = currLuma[cIdx].toInt() and 0xFF
                                sumDiff += abs(pVal - cVal)
                                count++
                            }
                        }
                        x += step
                    }
                }
                y += step
            }

            errors[sIdx] = if (count > 0) sumDiff.toFloat() / count.toFloat() else Float.MAX_VALUE
        }

        // Find scale candidate with minimum error
        var bestIdx = 0
        var minErr = errors[0]

        for (i in errors.indices) {
            if (errors[i] < minErr) {
                minErr = errors[i]
                bestIdx = i
            }
        }

        // Parabolic sub-step refinement around the minimum
        var bestScale = scaleCandidates[bestIdx]
        if (bestIdx > 0 && bestIdx < scaleCandidates.size - 1) {
            val y0 = errors[bestIdx - 1]
            val y1 = errors[bestIdx]
            val y2 = errors[bestIdx + 1]
            val denom = 2.0f * (y0 - 2.0f * y1 + y2)
            if (abs(denom) > 1e-4f) {
                val delta = (y0 - y2) / denom
                val stepSize = (scaleCandidates[bestIdx + 1] - scaleCandidates[bestIdx - 1]) / 2.0f
                bestScale = (bestScale + delta * stepSize).coerceIn(0.90f, 1.10f)
            }
        }

        // Estimate pan translation with the refined scale
        val panCandidatesX = intArrayOf(-6, -3, 0, 3, 6)
        val panCandidatesY = intArrayOf(-4, -2, 0, 2, 4)
        var bestDx = 0
        var bestDy = 0
        var minPanErr = Long.MAX_VALUE

        for (dy in panCandidatesY) {
            for (dx in panCandidatesX) {
                var panDiff = 0L
                var panCount = 0

                var y = startY
                while (y < endY) {
                    val ry = y - cy
                    val mappedY = (cy + bestScale * ry + dy).toInt()
                    if (mappedY in 0 until height) {
                        val prevRow = y * width
                        val currRow = mappedY * width

                        var x = startX
                        while (x < endX) {
                            val rx = x - cx
                            val mappedX = (cx + bestScale * rx + dx).toInt()
                            if (mappedX in 0 until width) {
                                val pIdx = prevRow + x
                                val cIdx = currRow + mappedX
                                if (pIdx in prevLuma.indices && cIdx in currLuma.indices) {
                                    val pVal = prevLuma[pIdx].toInt() and 0xFF
                                    val cVal = currLuma[cIdx].toInt() and 0xFF
                                    panDiff += abs(pVal - cVal)
                                    panCount++
                                }
                            }
                            x += step * 2
                        }
                    }
                    y += step * 2
                }

                if (panCount > 0 && panDiff < minPanErr) {
                    minPanErr = panDiff
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        return FrameMotionDelta(
            deltaX = bestDx.toFloat(),
            deltaY = bestDy.toFloat(),
            scaleFactor = bestScale
        )
    }

    private fun roundToDecimals(value: Float, decimals: Int): Float {
        var multiplier = 1.0f
        repeat(decimals) { multiplier *= 10.0f }
        return Math.round(value * multiplier) / multiplier
    }
}
