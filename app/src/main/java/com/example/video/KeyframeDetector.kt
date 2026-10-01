package com.example.video

import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import kotlin.math.abs

object KeyframeDetector {

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

    /**
     * Reconstructs motion keyframes from time-series motion samples,
     * reliably identifying all turning points:
     * - Local maximums (zoom-in turning into zoom-out)
     * - Local minimums (zoom-out turning into zoom-in)
     * - Level-offs (entering or leaving hold states)
     */
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

        // Apply a gentle 3-tap moving window to reduce high-frequency sensor noise
        // while strictly preserving all turning points and slopes
        val smoothed = smoothSamplesPreservingExtrema(rawSamples)
        if (smoothed.isEmpty()) {
            return listOf(
                MotionKeyframe(timestampMs = 0L, scale = 1.0f, positionX = 0.5f, positionY = 0.5f),
                MotionKeyframe(timestampMs = referenceDurationMs, scale = 1.0f, positionX = 0.5f, positionY = 0.5f)
            )
        }

        val keyframeCandidates = mutableListOf<MotionKeyframe>()

        // 1. Initial keyframe at 0ms
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

        // 2. Identify all extrema and slope reversals
        if (smoothed.size > 2) {
            var lastAddedTimeMs = 0L
            var lastAddedScale = firstSample.scale
            var lastAddedPanX = firstSample.panX
            var lastAddedPanY = firstSample.panY

            for (i in 1 until smoothed.size - 1) {
                val prev = smoothed[i - 1]
                val curr = smoothed[i]
                val next = smoothed[i + 1]

                val slopeBefore = curr.scale - prev.scale
                val slopeAfter = next.scale - curr.scale

                val panXBefore = curr.panX - prev.panX
                val panXAfter = next.panX - curr.panX

                val panYBefore = curr.panY - prev.panY
                val panYAfter = next.panY - curr.panY

                // Peak turning point: scale was increasing, now decreasing (Zoom-in to Zoom-out)
                val isPeakZoom = (slopeBefore > 0.005f && slopeAfter < -0.005f) ||
                        (curr.scale >= prev.scale && curr.scale > next.scale && curr.scale - prev.scale > 0.01f)

                // Valley turning point: scale was decreasing, now increasing (Zoom-out to Zoom-in)
                val isValleyZoom = (slopeBefore < -0.005f && slopeAfter > 0.005f) ||
                        (curr.scale <= prev.scale && curr.scale < next.scale && prev.scale - curr.scale > 0.01f)

                // Pan extrema
                val isPanXExtremum = (panXBefore * panXAfter < 0f) && (abs(panXBefore) > 0.015f || abs(panXAfter) > 0.015f)
                val isPanYExtremum = (panYBefore * panYAfter < 0f) && (abs(panYBefore) > 0.015f || abs(panYAfter) > 0.015f)

                val deltaScaleFromLast = abs(curr.scale - lastAddedScale)
                val deltaPanFromLast = abs(curr.panX - lastAddedPanX) + abs(curr.panY - lastAddedPanY)
                val deltaTimeFromLast = curr.timestampMs - lastAddedTimeMs

                // Add keyframe if turning point occurs or substantial movement has elapsed
                val isTurningPoint = (isPeakZoom || isValleyZoom || isPanXExtremum || isPanYExtremum) && (deltaTimeFromLast >= 150L)
                val isSignificantDelta = (deltaScaleFromLast >= 0.15f && deltaTimeFromLast >= 400L) ||
                        (deltaPanFromLast >= 0.12f && deltaTimeFromLast >= 500L)
                val isTimeIntervalSpacing = (deltaTimeFromLast >= 1800L && (deltaScaleFromLast > 0.05f || deltaPanFromLast > 0.05f))

                if (isTurningPoint || isSignificantDelta || isTimeIntervalSpacing) {
                    keyframeCandidates.add(
                        MotionKeyframe(
                            timestampMs = curr.timestampMs,
                            scale = roundToDecimals(curr.scale, 2),
                            positionX = roundToDecimals(curr.panX, 3),
                            positionY = roundToDecimals(curr.panY, 3),
                            interpolation = InterpolationType.EASE_IN_OUT
                        )
                    )
                    lastAddedTimeMs = curr.timestampMs
                    lastAddedScale = curr.scale
                    lastAddedPanX = curr.panX
                    lastAddedPanY = curr.panY
                }
            }
        }

        // 3. Final keyframe at referenceDurationMs
        val lastSample = smoothed.last()
        val finalTimestamp = referenceDurationMs.coerceAtLeast(lastSample.timestampMs)
        val lastCandidate = keyframeCandidates.lastOrNull()

        if (lastCandidate == null || lastCandidate.timestampMs < finalTimestamp - 200L) {
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
            // Update terminal timestamp to exact video end
            val prevLast = keyframeCandidates.removeAt(keyframeCandidates.size - 1)
            keyframeCandidates.add(
                prevLast.copy(
                    timestampMs = finalTimestamp,
                    scale = roundToDecimals(lastSample.scale, 2)
                )
            )
        }

        return keyframeCandidates.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }
    }

    /**
     * Noise smoothing that preserves turning points (peaks and valleys) without flattening extrema.
     */
    private fun smoothSamplesPreservingExtrema(samples: List<RawMotionSample>): List<RawMotionSample> {
        if (samples.size < 3) return samples

        val result = mutableListOf<RawMotionSample>()
        samples.firstOrNull()?.let { result.add(it) }

        for (i in 1 until samples.size - 1) {
            val p = samples[i - 1]
            val c = samples[i]
            val n = samples[i + 1]

            // If c is a peak or valley, do not average it away
            val isPeak = c.scale > p.scale && c.scale > n.scale
            val isValley = c.scale < p.scale && c.scale < n.scale

            val smoothedScale = if (isPeak || isValley) {
                c.scale // preserve exact peak/valley value
            } else {
                p.scale * 0.20f + c.scale * 0.60f + n.scale * 0.20f
            }

            val smoothedPanX = p.panX * 0.20f + c.panX * 0.60f + n.panX * 0.20f
            val smoothedPanY = p.panY * 0.20f + c.panY * 0.60f + n.panY * 0.20f

            result.add(
                RawMotionSample(
                    timestampMs = c.timestampMs,
                    scale = smoothedScale,
                    panX = smoothedPanX,
                    panY = smoothedPanY
                )
            )
        }

        samples.lastOrNull()?.let { result.add(it) }
        return result
    }

    private fun roundToDecimals(value: Float, decimals: Int): Float {
        var multiplier = 1.0f
        repeat(decimals) { multiplier *= 10.0f }
        return Math.round(value * multiplier) / multiplier
    }
}
