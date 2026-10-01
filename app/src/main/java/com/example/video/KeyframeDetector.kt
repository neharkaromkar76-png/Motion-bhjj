package com.example.video

import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import kotlin.math.abs

object KeyframeDetector {

    /**
     * Reconstructs an optimized motion timeline from the raw keyframes extracted
     * via MediaExtractor, identifying significant inflection points and curve transitions.
     */
    fun reconstructFromExtractedKeyframes(
        extractedKeyframes: List<MotionKeyframe>,
        referenceDurationMs: Long
    ): List<MotionKeyframe> {
        if (extractedKeyframes.isEmpty()) {
            return listOf(
                MotionKeyframe(timestampMs = 0L, scale = 1.0f, positionX = 0.5f, positionY = 0.5f),
                MotionKeyframe(timestampMs = referenceDurationMs, scale = 1.0f, positionX = 0.5f, positionY = 0.5f)
            )
        }

        val rawSamples = extractedKeyframes.map {
            RawMotionSample(
                timestampMs = it.timestampMs,
                scale = it.scale,
                panX = it.positionX,
                panY = it.positionY
            )
        }
        return reconstructKeyframes(rawSamples, referenceDurationMs)
    }

    fun reconstructKeyframes(
        rawSamples: List<RawMotionSample>,
        referenceDurationMs: Long
    ): List<MotionKeyframe> {
        if (rawSamples.isEmpty()) {
            return listOf(
                MotionKeyframe(timestampMs = 0L, scale = 1.0f, positionX = 0.5f, positionY = 0.5f),
                MotionKeyframe(timestampMs = referenceDurationMs, scale = 1.0f, positionX = 0.5f, positionY = 0.5f)
            )
        }

        // 1. Smooth raw samples with a 3-tap Gaussian moving window to suppress high-frequency noise
        val smoothed = smoothSamples(rawSamples)

        // 2. Identify key inflection points:
        // We detect points where scale or position trajectory changes direction or acceleration
        val keyframeCandidates = mutableListOf<MotionKeyframe>()

        // Always include initial frame at 0ms
        val firstSample = smoothed.first()
        keyframeCandidates.add(
            MotionKeyframe(
                timestampMs = 0L,
                scale = roundToDecimals(firstSample.scale, 2),
                positionX = roundToDecimals(firstSample.panX, 3),
                positionY = roundToDecimals(firstSample.panY, 3),
                interpolation = InterpolationType.EASE_IN_OUT
            )
        )

        // Find extrema and slope changes
        if (smoothed.size > 2) {
            for (i in 1 until smoothed.size - 1) {
                val prev = smoothed[i - 1]
                val curr = smoothed[i]
                val next = smoothed[i + 1]

                val scaleSlope1 = curr.scale - prev.scale
                val scaleSlope2 = next.scale - curr.scale

                val panXSlope1 = curr.panX - prev.panX
                val panXSlope2 = next.panX - curr.panX

                val panYSlope1 = curr.panY - prev.panY
                val panYSlope2 = next.panY - curr.panY

                // Extrema check: scale slope changes sign (peak zoom or valley zoom)
                val isScaleExtremum = (scaleSlope1 * scaleSlope2 < 0f) && (abs(scaleSlope1) > 0.015f || abs(scaleSlope2) > 0.015f)

                // Large scale delta from last added keyframe
                val lastKeyframe = keyframeCandidates.last()
                val deltaScaleFromLast = abs(curr.scale - lastKeyframe.scale)
                val deltaPanFromLast = abs(curr.panX - lastKeyframe.positionX) + abs(curr.panY - lastKeyframe.positionY)
                val deltaTimeFromLast = curr.timestampMs - lastKeyframe.timestampMs

                // Pan slope reversal
                val isPanExtremum = (panXSlope1 * panXSlope2 < 0f || panYSlope1 * panYSlope2 < 0f) &&
                        (abs(panXSlope1) > 0.02f || abs(panYSlope1) > 0.02f)

                val shouldAdd = (isScaleExtremum || isPanExtremum) && (deltaTimeFromLast >= 500L) ||
                        (deltaScaleFromLast >= 0.12f && deltaTimeFromLast >= 600L) ||
                        (deltaPanFromLast >= 0.10f && deltaTimeFromLast >= 800L)

                if (shouldAdd) {
                    keyframeCandidates.add(
                        MotionKeyframe(
                            timestampMs = curr.timestampMs,
                            scale = roundToDecimals(curr.scale, 2),
                            positionX = roundToDecimals(curr.panX, 3),
                            positionY = roundToDecimals(curr.panY, 3),
                            interpolation = InterpolationType.EASE_IN_OUT
                        )
                    )
                }
            }
        }

        // Always include terminal frame at referenceDurationMs
        val lastSample = smoothed.last()
        val finalTimestamp = referenceDurationMs.coerceAtLeast(lastSample.timestampMs)
        if (keyframeCandidates.last().timestampMs < finalTimestamp - 300L) {
            keyframeCandidates.add(
                MotionKeyframe(
                    timestampMs = finalTimestamp,
                    scale = roundToDecimals(lastSample.scale, 2),
                    positionX = roundToDecimals(lastSample.panX, 3),
                    positionY = roundToDecimals(lastSample.panY, 3),
                    interpolation = InterpolationType.EASE_IN_OUT
                )
            )
        } else {
            val prevLast = keyframeCandidates.removeAt(keyframeCandidates.size - 1)
            keyframeCandidates.add(
                prevLast.copy(timestampMs = finalTimestamp)
            )
        }

        // If very few keyframes were detected, add midpoint anchor
        if (keyframeCandidates.size <= 2 && smoothed.size >= 4) {
            val midIdx = smoothed.size / 2
            val midSample = smoothed[midIdx]
            keyframeCandidates.add(
                1,
                MotionKeyframe(
                    timestampMs = midSample.timestampMs,
                    scale = roundToDecimals(midSample.scale, 2),
                    positionX = roundToDecimals(midSample.panX, 3),
                    positionY = roundToDecimals(midSample.panY, 3),
                    interpolation = InterpolationType.SMOOTH
                )
            )
        }

        return keyframeCandidates.sortedBy { it.timestampMs }
    }

    private fun smoothSamples(samples: List<RawMotionSample>): List<RawMotionSample> {
        if (samples.size < 3) return samples

        val result = mutableListOf<RawMotionSample>()
        result.add(samples.first())

        for (i in 1 until samples.size - 1) {
            val p = samples[i - 1]
            val c = samples[i]
            val n = samples[i + 1]

            val smoothedScale = p.scale * 0.25f + c.scale * 0.50f + n.scale * 0.25f
            val smoothedPanX = p.panX * 0.25f + c.panX * 0.50f + n.panX * 0.25f
            val smoothedPanY = p.panY * 0.25f + c.panY * 0.50f + n.panY * 0.25f

            result.add(
                RawMotionSample(
                    timestampMs = c.timestampMs,
                    scale = smoothedScale,
                    panX = smoothedPanX,
                    panY = smoothedPanY
                )
            )
        }

        result.add(samples.last())
        return result
    }

    private fun roundToDecimals(value: Float, decimals: Int): Float {
        var multiplier = 1.0f
        repeat(decimals) { multiplier *= 10.0f }
        return Math.round(value * multiplier) / multiplier
    }
}
