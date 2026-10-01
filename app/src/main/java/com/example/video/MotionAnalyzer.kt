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
    private const val PATCH_SIZE = 14
    private const val SEARCH_RADIUS = 16
    private const val GRID_COLS = 6
    private const val GRID_ROWS = 4

    private data class LumaFrame(
        val luma: ByteArray,
        val width: Int,
        val height: Int
    )

    /**
     * Uses MediaExtractor to step through reference video frames across the entire timeline
     * and stores extracted motion data directly into a list of MotionKeyframe objects using
     * timestamps in milliseconds.
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
            throw IllegalArgumentException("Reference video could not be read: ${e.localizedMessage}")
        }

        val extractedKeyframes = mutableListOf<MotionKeyframe>()

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
            // Minimum sample step to maintain responsive speed and prevent redundant frame calculations
            val sampleStepThresholdMs = when {
                safeDurationMs <= 3000L -> 100L
                safeDurationMs <= 10000L -> 150L
                safeDurationMs <= 30000L -> 250L
                else -> (safeDurationMs / 100L).coerceIn(300L, 600L)
            }

            var previousFrame: LumaFrame? = null
            var currentScale = 1.0f
            var currentPanX = 0.5f
            var currentPanY = 0.5f

            // Always add initial anchor at 0ms
            extractedKeyframes.add(
                MotionKeyframe(
                    timestampMs = 0L,
                    scale = 1.0f,
                    positionX = 0.5f,
                    positionY = 0.5f,
                    rotationDeg = 0f,
                    interpolation = InterpolationType.EASE_IN_OUT
                )
            )

            var lastSampledMs = 0L

            // Step through reference video frames using MediaExtractor
            while (true) {
                ensureActive()
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0) break

                // Frame timestamp in milliseconds
                val frameTimeMs = (sampleTimeUs / 1000L).coerceIn(0L, safeDurationMs)

                // Check if frame satisfies sample interval step
                if (frameTimeMs == 0L || abs(frameTimeMs - lastSampledMs) >= sampleStepThresholdMs) {
                    val progress = (frameTimeMs.toFloat() / safeDurationMs.toFloat()).coerceIn(0f, 1f)
                    val formattedCurrent = String.format(java.util.Locale.US, "%.2fs", frameTimeMs / 1000f)
                    val formattedDuration = String.format(java.util.Locale.US, "%.2fs", safeDurationMs / 1000f)
                    val statusText = "Extracting motion at $formattedCurrent / $formattedDuration (${(progress * 100).toInt()}%)"
                    onProgress(progress, statusText)

                    val frameBitmap = extractScaledBitmap(retriever, frameTimeMs)
                    if (frameBitmap != null) {
                        val currentFrame = bitmapToGrayscale(frameBitmap)
                        frameBitmap.recycle()

                        if (previousFrame != null) {
                            val frameDelta = estimateFrameMotion(
                                previousFrame,
                                currentFrame
                            )

                            // Accumulate scale with dampening to avoid noise
                            val stepScaleFactor = frameDelta.scaleFactor.coerceIn(0.92f, 1.08f)
                            currentScale = (currentScale * stepScaleFactor).coerceIn(0.6f, 3.0f)

                            // Accumulate pan coordinates (normalized 0.0 to 1.0)
                            val dNormX = if (currentFrame.width > 0) frameDelta.deltaX / currentFrame.width.toFloat() else 0f
                            val dNormY = if (currentFrame.height > 0) frameDelta.deltaY / currentFrame.height.toFloat() else 0f
                            currentPanX = (currentPanX + dNormX * 0.8f).coerceIn(0.1f, 0.9f)
                            currentPanY = (currentPanY + dNormY * 0.8f).coerceIn(0.1f, 0.9f)

                            extractedKeyframes.add(
                                MotionKeyframe(
                                    timestampMs = frameTimeMs,
                                    scale = roundToDecimals(currentScale, 2),
                                    positionX = roundToDecimals(currentPanX, 3),
                                    positionY = roundToDecimals(currentPanY, 3),
                                    rotationDeg = 0f,
                                    interpolation = InterpolationType.EASE_IN_OUT
                                )
                            )
                        }

                        previousFrame = currentFrame
                        lastSampledMs = frameTimeMs
                    }
                }

                // Advance MediaExtractor to next reference frame
                if (!extractor.advance()) break
            }

            // Ensure the exact end timestamp of reference video is present
            if (extractedKeyframes.isEmpty()) {
                extractedKeyframes.add(
                    MotionKeyframe(
                        timestampMs = 0L,
                        scale = 1.0f,
                        positionX = 0.5f,
                        positionY = 0.5f,
                        rotationDeg = 0f,
                        interpolation = InterpolationType.EASE_IN_OUT
                    )
                )
            }

            val lastExisting = extractedKeyframes.last()
            if (lastExisting.timestampMs < safeDurationMs) {
                extractedKeyframes.add(
                    MotionKeyframe(
                        timestampMs = safeDurationMs,
                        scale = roundToDecimals(lastExisting.scale, 2),
                        positionX = roundToDecimals(lastExisting.positionX, 3),
                        positionY = roundToDecimals(lastExisting.positionY, 3),
                        rotationDeg = 0f,
                        interpolation = InterpolationType.EASE_IN_OUT
                    )
                )
            }

            onProgress(1.0f, "Reference motion extraction complete")
            extractedKeyframes.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }
        } finally {
            extractor.release()
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Backward-compatible method returning RawMotionSample list.
     */
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

    /**
     * Safely extracts a normalized bitmap strictly guaranteed to be ANALYSIS_WIDTH x ANALYSIS_HEIGHT.
     * Note: MediaMetadataRetriever.getScaledFrameAtTime scales while preserving original aspect ratio
     * (e.g. 720x1280 9:16 portrait video yields 81x144, 11664 bytes instead of 256x144).
     * We guarantee exact dimensions by re-scaling if the decoder preserved aspect ratio.
     */
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

    private data class FrameMotionDelta(
        val deltaX: Float,
        val deltaY: Float,
        val scaleFactor: Float
    )

    private fun estimateFrameMotion(
        prevFrame: LumaFrame,
        currFrame: LumaFrame
    ): FrameMotionDelta {
        val width = currFrame.width
        val height = currFrame.height

        // Strict dimension and size match check
        if (width <= 0 || height <= 0 ||
            prevFrame.width != width || prevFrame.height != height ||
            prevFrame.luma.size != width * height || currFrame.luma.size != width * height
        ) {
            return FrameMotionDelta(0f, 0f, 1.0f)
        }

        val prevLuma = prevFrame.luma
        val currLuma = currFrame.luma

        val centerX = width / 2.0f
        val centerY = height / 2.0f

        val stepX = width / (GRID_COLS + 1)
        val stepY = height / (GRID_ROWS + 1)

        val displacementsX = mutableListOf<Float>()
        val displacementsY = mutableListOf<Float>()
        val radialNumerator = mutableListOf<Float>()
        val radialDenominator = mutableListOf<Float>()

        val halfPatch = PATCH_SIZE / 2

        for (row in 1..GRID_ROWS) {
            for (col in 1..GRID_COLS) {
                val px = col * stepX
                val py = row * stepY

                // Ensure entire template patch is safely inside image bounds
                if (px - halfPatch < 0 || px + halfPatch >= width ||
                    py - halfPatch < 0 || py + halfPatch >= height
                ) continue

                var bestDx = 0
                var bestDy = 0
                var minDiff = Long.MAX_VALUE
                var foundMatch = false

                for (dy in -SEARCH_RADIUS..SEARCH_RADIUS step 2) {
                    val cy = py + dy
                    if (cy - halfPatch < 0 || cy + halfPatch >= height) continue

                    for (dx in -SEARCH_RADIUS..SEARCH_RADIUS step 2) {
                        val cx = px + dx
                        if (cx - halfPatch < 0 || cx + halfPatch >= width) continue

                        var diffSum = 0L
                        var validPixels = 0

                        for (pyi in -halfPatch..halfPatch step 2) {
                            val prevY = py + pyi
                            val currY = cy + pyi
                            if (prevY !in 0 until height || currY !in 0 until height) continue

                            val prevRowOff = prevY * width
                            val currRowOff = currY * width

                            for (pxi in -halfPatch..halfPatch step 2) {
                                val prevX = px + pxi
                                val currX = cx + pxi
                                if (prevX !in 0 until width || currX !in 0 until width) continue

                                val pIdx = prevRowOff + prevX
                                val cIdx = currRowOff + currX

                                // Absolute bounds validation
                                if (pIdx in 0 until prevLuma.size && cIdx in 0 until currLuma.size) {
                                    val pVal = prevLuma[pIdx].toInt() and 0xFF
                                    val cVal = currLuma[cIdx].toInt() and 0xFF
                                    diffSum += abs(pVal - cVal)
                                    validPixels++
                                }
                            }
                        }

                        if (validPixels > 0 && diffSum < minDiff) {
                            minDiff = diffSum
                            bestDx = dx
                            bestDy = dy
                            foundMatch = true
                        }
                    }
                }

                if (foundMatch) {
                    val avgDiff = minDiff.toFloat() / ((PATCH_SIZE / 2) * (PATCH_SIZE / 2)).coerceAtLeast(1)
                    if (avgDiff < 45f) {
                        displacementsX.add(bestDx.toFloat())
                        displacementsY.add(bestDy.toFloat())

                        val rx = px - centerX
                        val ry = py - centerY
                        val rDistSq = rx * rx + ry * ry

                        if (rDistSq > 100f) {
                            val radialDisp = (bestDx * rx + bestDy * ry)
                            radialNumerator.add(radialDisp)
                            radialDenominator.add(rDistSq)
                        }
                    }
                }
            }
        }

        if (displacementsX.isEmpty()) {
            return FrameMotionDelta(0f, 0f, 1.0f)
        }

        displacementsX.sort()
        displacementsY.sort()
        val medianDx = displacementsX[(displacementsX.size / 2).coerceIn(0, displacementsX.size - 1)]
        val medianDy = displacementsY[(displacementsY.size / 2).coerceIn(0, displacementsY.size - 1)]

        var scaleDelta = 0.0f
        var sumNum = 0f
        var sumDen = 0f
        for (i in radialNumerator.indices) {
            sumNum += radialNumerator[i]
            sumDen += radialDenominator[i]
        }

        if (sumDen > 0.001f) {
            scaleDelta = sumNum / sumDen
        }

        val frameScale = 1.0f + scaleDelta

        return FrameMotionDelta(
            deltaX = medianDx,
            deltaY = medianDy,
            scaleFactor = frameScale
        )
    }

    private fun roundToDecimals(value: Float, decimals: Int): Float {
        var multiplier = 1.0f
        repeat(decimals) { multiplier *= 10.0f }
        return Math.round(value * multiplier) / multiplier
    }
}
