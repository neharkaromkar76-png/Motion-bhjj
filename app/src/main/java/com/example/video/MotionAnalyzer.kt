package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

data class RawMotionSample(
    val timestampMs: Long,
    val scale: Float,
    val panX: Float,
    val panY: Float
)

data class MotionAnalysisResult(
    val durationMs: Long,
    val samples: List<RawMotionSample>,
    val keyframes: List<MotionKeyframe>,
    val timeline: MotionTimeline,
    val minScale: Float,
    val maxScale: Float,
    val minPanX: Float,
    val maxPanX: Float,
    val minPanY: Float,
    val maxPanY: Float,
    val zoomInEventsCount: Int,
    val zoomOutEventsCount: Int,
    val isStatic: Boolean
)

object MotionAnalyzer {

    private const val TAG = "MotionAnalyzer"
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
        val zoomAlpha: Float,
        val validFeatureCount: Int
    )

    /**
     * Complete video motion analysis:
     * - Analyzes the entire Reference Video timeline (0.000s to duration)
     * - Estimates continuous time-series camera transform (zoom in, zoom out, pan X, pan Y)
     * - Detects turning points (peaks and valleys)
     * - Builds the unified MotionTimeline shared by Preview and Export
     * - Emits internal verification logs per requirement #19
     */
    suspend fun analyzeVideoMotion(
        context: Context,
        referenceUri: Uri,
        durationMs: Long,
        onProgress: (progress: Float, message: String) -> Unit
    ): MotionAnalysisResult = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()

        try {
            extractor.setDataSource(context, referenceUri, null)
            retriever.setDataSource(context, referenceUri)
        } catch (e: Exception) {
            extractor.release()
            try { retriever.release() } catch (_: Exception) {}
            throw IllegalArgumentException("Reference video could not be opened: ${e.localizedMessage}")
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
            // Dense sampling step: 66ms to 100ms (~10 to 15 samples/sec) for complete curve capture
            val sampleStepThresholdMs = when {
                safeDurationMs <= 5000L -> 66L
                safeDurationMs <= 15000L -> 80L
                safeDurationMs <= 35000L -> 100L
                else -> 100L
            }

            var previousFrame: LumaFrame? = null
            var currentScale = 1.0f
            var currentPanX = 0.5f
            var currentPanY = 0.5f

            // 0ms anchor
            rawSamples.add(
                RawMotionSample(
                    timestampMs = 0L,
                    scale = 1.0f,
                    panX = 0.5f,
                    panY = 0.5f
                )
            )

            var lastSampledMs = 0L

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
                            val motionDelta = estimateOpticalFlowCameraMotion(previousFrame, currentFrame)

                            // Apply camera zoom rate symmetrically:
                            // zoomAlpha > 0 -> zoom-in (features expand from center)
                            // zoomAlpha < 0 -> zoom-out (features contract toward center)
                            val stepFactor = (1.0f + motionDelta.zoomAlpha).coerceIn(0.85f, 1.15f)
                            currentScale = (currentScale * stepFactor).coerceIn(0.6f, 3.5f)

                            val normDx = motionDelta.deltaX / currentFrame.width.toFloat()
                            val normDy = motionDelta.deltaY / currentFrame.height.toFloat()
                            currentPanX = (currentPanX - normDx * 0.75f).coerceIn(0.15f, 0.85f)
                            currentPanY = (currentPanY - normDy * 0.75f).coerceIn(0.15f, 0.85f)

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

            // Ensure exact video duration is reached
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

            val sortedSamples = rawSamples.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }

            // Framing normalization: preserve relative rises and falls while ensuring scale >= 1.0
            val rawMinScale = sortedSamples.minOfOrNull { it.scale } ?: 1.0f
            val rawMaxScale = sortedSamples.maxOfOrNull { it.scale } ?: 1.0f

            val normalizedSamples = if (rawMinScale < 1.0f && rawMinScale > 0.1f) {
                val multiplier = 1.0f / rawMinScale
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

            onProgress(0.92f, "Reconstructing motion turning points...")

            // Reconstruct keyframes at turning points (peaks and valleys)
            val reconstructedKeyframes = KeyframeDetector.reconstructKeyframes(
                rawSamples = normalizedSamples,
                referenceDurationMs = safeDurationMs
            )

            // Build MotionTimeline
            val timeline = MotionTimeline(
                referenceDurationMs = safeDurationMs,
                keyframes = reconstructedKeyframes,
                samples = normalizedSamples
            )

            val finalMinScale = normalizedSamples.minOfOrNull { it.scale } ?: 1.0f
            val finalMaxScale = normalizedSamples.maxOfOrNull { it.scale } ?: 1.0f
            val minX = normalizedSamples.minOfOrNull { it.panX } ?: 0.5f
            val maxX = normalizedSamples.maxOfOrNull { it.panX } ?: 0.5f
            val minY = normalizedSamples.minOfOrNull { it.panY } ?: 0.5f
            val maxY = normalizedSamples.maxOfOrNull { it.panY } ?: 0.5f

            val zoomInCount = timeline.zoomInCount
            val zoomOutCount = timeline.zoomOutCount
            val isStatic = (finalMaxScale - finalMinScale < 0.02f) && (maxX - minX < 0.02f) && (maxY - minY < 0.02f)

            // Requirement #19: Internal verification logging
            Log.i(TAG, "=== REFERENCE VIDEO MOTION ANALYSIS ===")
            Log.i(TAG, "Reference duration: $safeDurationMs ms")
            Log.i(TAG, "Analyzed samples: ${normalizedSamples.size}")
            Log.i(TAG, "Reconstructed keyframes: ${reconstructedKeyframes.size}")
            Log.i(TAG, "Zoom events: In=$zoomInCount, Out=$zoomOutCount")
            Log.i(TAG, "Scale range: ${String.format(java.util.Locale.US, "%.3f - %.3f", finalMinScale, finalMaxScale)}")
            Log.i(TAG, "Pan X range: ${String.format(java.util.Locale.US, "%.3f - %.3f", minX, maxX)}")
            Log.i(TAG, "Pan Y range: ${String.format(java.util.Locale.US, "%.3f - %.3f", minY, maxY)}")
            Log.i(TAG, "Camera motion isStatic: $isStatic")

            onProgress(1.0f, "Reference motion analysis complete (${reconstructedKeyframes.size} keyframes, ${normalizedSamples.size} samples)")

            MotionAnalysisResult(
                durationMs = safeDurationMs,
                samples = normalizedSamples,
                keyframes = reconstructedKeyframes,
                timeline = timeline,
                minScale = finalMinScale,
                maxScale = finalMaxScale,
                minPanX = minX,
                maxPanX = maxX,
                minPanY = minY,
                maxPanY = maxY,
                zoomInEventsCount = zoomInCount,
                zoomOutEventsCount = zoomOutCount,
                isStatic = isStatic
            )
        } finally {
            extractor.release()
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    suspend fun extractMotionKeyframesWithMediaExtractor(
        context: Context,
        referenceUri: Uri,
        durationMs: Long,
        onProgress: (progress: Float, message: String) -> Unit
    ): List<MotionKeyframe> {
        val result = analyzeVideoMotion(context, referenceUri, durationMs, onProgress)
        return result.keyframes
    }

    suspend fun analyzeReferenceVideo(
        context: Context,
        referenceUri: Uri,
        durationMs: Long,
        onProgress: (progress: Float, message: String) -> Unit
    ): List<RawMotionSample> {
        val result = analyzeVideoMotion(context, referenceUri, durationMs, onProgress)
        return result.samples
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
     * Optical-flow based 2D affine camera estimation:
     * - Distributes feature tracking points across salient image textures
     * - Finds sub-pixel displacements (dx, dy)
     * - Decouples zoom rate (radial expansion/contraction) from camera pan
     * - Uses trimmed mean to reject moving object outliers
     */
    private fun estimateOpticalFlowCameraMotion(
        prevFrame: LumaFrame,
        currFrame: LumaFrame
    ): FrameMotionDelta {
        val width = currFrame.width
        val height = currFrame.height

        if (width <= 0 || height <= 0 ||
            prevFrame.width != width || prevFrame.height != height ||
            prevFrame.luma.size != width * height || currFrame.luma.size != width * height
        ) {
            return FrameMotionDelta(0f, 0f, 0f, 0)
        }

        val prevLuma = prevFrame.luma
        val currLuma = currFrame.luma

        val cx = width / 2.0f
        val cy = height / 2.0f

        // Grid configuration for feature detection
        val cols = 8
        val rows = 6
        val blockW = width / cols
        val blockH = height / rows
        val patchRadius = 4 // 9x9 template patch
        val searchRadiusX = 14
        val searchRadiusY = 10

        val radialAlphas = mutableListOf<Float>()
        val panDisplacementsX = mutableListOf<Float>()
        val panDisplacementsY = mutableListOf<Float>()

        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                // Find highest gradient pixel in this block to track salient feature
                var bestPx = c * blockW + blockW / 2
                var bestPy = r * blockH + blockH / 2
                var maxGrad = 0

                val startY = r * blockH + patchRadius + 1
                val endY = (r + 1) * blockH - patchRadius - 1
                val startX = c * blockW + patchRadius + 1
                val endX = (c + 1) * blockW - patchRadius - 1

                var y = startY
                while (y < endY) {
                    var x = startX
                    while (x < endX) {
                        val rowOff = y * width
                        val gx = abs((prevLuma[rowOff + x + 1].toInt() and 0xFF) - (prevLuma[rowOff + x - 1].toInt() and 0xFF))
                        val gy = abs((prevLuma[(y + 1) * width + x].toInt() and 0xFF) - (prevLuma[(y - 1) * width + x].toInt() and 0xFF))
                        val grad = gx + gy
                        if (grad > maxGrad) {
                            maxGrad = grad
                            bestPx = x
                            bestPy = y
                        }
                        x += 3
                    }
                    y += 3
                }

                // Skip flat/featureless blocks
                if (maxGrad < 20) continue

                val px = bestPx
                val py = bestPy

                // Block matching optical flow with step 1
                var bestDx = 0
                var bestDy = 0
                var minSAD = Long.MAX_VALUE

                var sDy = -searchRadiusY
                while (sDy <= searchRadiusY) {
                    val cyPos = py + sDy
                    if (cyPos - patchRadius >= 0 && cyPos + patchRadius < height) {
                        var sDx = -searchRadiusX
                        while (sDx <= searchRadiusX) {
                            val cxPos = px + sDx
                            if (cxPos - patchRadius >= 0 && cxPos + patchRadius < width) {
                                var sumDiff = 0L
                                for (pyi in -patchRadius..patchRadius) {
                                    val pRow = (py + pyi) * width
                                    val cRow = (cyPos + pyi) * width
                                    for (pxi in -patchRadius..patchRadius) {
                                        val pVal = prevLuma[pRow + (px + pxi)].toInt() and 0xFF
                                        val cVal = currLuma[cRow + (cxPos + pxi)].toInt() and 0xFF
                                        sumDiff += abs(pVal - cVal)
                                    }
                                }

                                if (sumDiff < minSAD) {
                                    minSAD = sumDiff
                                    bestDx = sDx
                                    bestDy = sDy
                                }
                            }
                            sDx++
                        }
                    }
                    sDy++
                }

                val patchPixels = (patchRadius * 2 + 1) * (patchRadius * 2 + 1)
                val avgDiff = minSAD.toFloat() / patchPixels.toFloat()

                // Reject poor matches
                if (avgDiff < 32f) {
                    val rx = px - cx
                    val ry = py - cy
                    val rDistSq = rx * rx + ry * ry

                    // Calculate radial zoom expansion/contraction
                    if (rDistSq > 225f) { // radius >= 15px
                        val radialComponent = (bestDx * rx + bestDy * ry) / rDistSq
                        radialAlphas.add(radialComponent)
                    }

                    panDisplacementsX.add(bestDx.toFloat())
                    panDisplacementsY.add(bestDy.toFloat())
                }
            }
        }

        if (radialAlphas.isEmpty()) {
            return FrameMotionDelta(0f, 0f, 0f, 0)
        }

        // Trimmed mean (middle 60%) to reject moving objects and outlier noise
        radialAlphas.sort()
        val trimStart = (radialAlphas.size * 0.20f).toInt()
        val trimEnd = (radialAlphas.size * 0.80f).toInt().coerceAtLeast(trimStart + 1)
        var sumAlpha = 0f
        var countAlpha = 0
        for (i in trimStart until trimEnd) {
            sumAlpha += radialAlphas[i]
            countAlpha++
        }
        val medianZoomAlpha = if (countAlpha > 0) sumAlpha / countAlpha else 0f

        panDisplacementsX.sort()
        panDisplacementsY.sort()
        val medianDx = panDisplacementsX[panDisplacementsX.size / 2]
        val medianDy = panDisplacementsY[panDisplacementsY.size / 2]

        return FrameMotionDelta(
            deltaX = medianDx,
            deltaY = medianDy,
            zoomAlpha = medianZoomAlpha,
            validFeatureCount = radialAlphas.size
        )
    }

    private fun roundToDecimals(value: Float, decimals: Int): Float {
        var multiplier = 1.0f
        repeat(decimals) { multiplier *= 10.0f }
        return Math.round(value * multiplier) / multiplier
    }
}
