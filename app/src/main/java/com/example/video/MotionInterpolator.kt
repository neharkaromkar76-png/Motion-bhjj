package com.example.video

import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import com.example.model.MotionTransform

object MotionInterpolator {

    /**
     * Interpolates continuous camera transform from dense motion samples.
     * Uses binary search and cubic Hermite smoothstep for frame-accurate, fluid motion.
     */
    fun interpolateFromSamples(samples: List<RawMotionSample>, queryTimestampMs: Long): MotionTransform {
        if (samples.isEmpty()) {
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = 1.0f,
                positionX = 0.5f,
                positionY = 0.5f,
                rotationDeg = 0f
            )
        }

        if (samples.size == 1) {
            val single = samples[0]
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = single.scale,
                positionX = single.panX,
                positionY = single.panY,
                rotationDeg = 0f
            )
        }

        val first = samples.first()
        if (queryTimestampMs <= first.timestampMs) {
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = first.scale,
                positionX = first.panX,
                positionY = first.panY,
                rotationDeg = 0f
            )
        }

        val last = samples.last()
        if (queryTimestampMs >= last.timestampMs) {
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = last.scale,
                positionX = last.panX,
                positionY = last.panY,
                rotationDeg = 0f
            )
        }

        // Fast binary search to find interval [low, low + 1]
        var low = 0
        var high = samples.size - 1
        var matchIdx = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (samples[mid].timestampMs <= queryTimestampMs) {
                matchIdx = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        val idx0 = matchIdx.coerceIn(0, samples.size - 2)
        val idx1 = idx0 + 1

        val s0 = samples[idx0]
        val s1 = samples[idx1]

        val span = (s1.timestampMs - s0.timestampMs).coerceAtLeast(1L).toFloat()
        val rawAlpha = ((queryTimestampMs - s0.timestampMs).toFloat() / span).coerceIn(0f, 1f)

        // Smooth cubic Hermite S-curve
        val alpha = rawAlpha * rawAlpha * (3f - 2f * rawAlpha)

        val scale = lerp(s0.scale, s1.scale, alpha)
        val posX = lerp(s0.panX, s1.panX, alpha)
        val posY = lerp(s0.panY, s1.panY, alpha)

        return MotionTransform(
            timestampMs = queryTimestampMs,
            scale = scale,
            positionX = posX,
            positionY = posY,
            rotationDeg = 0f
        )
    }

    /**
     * Interpolates between keyframes according to their assigned interpolation type.
     */
    fun interpolate(keyframes: List<MotionKeyframe>, queryTimestampMs: Long): MotionTransform {
        if (keyframes.isEmpty()) {
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = 1.0f,
                positionX = 0.5f,
                positionY = 0.5f,
                rotationDeg = 0f
            )
        }

        val sorted = keyframes.sortedBy { it.timestampMs }
        if (sorted.isEmpty()) {
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = 1.0f,
                positionX = 0.5f,
                positionY = 0.5f,
                rotationDeg = 0f
            )
        }

        if (sorted.size == 1) {
            val single = sorted[0]
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = single.scale,
                positionX = single.positionX,
                positionY = single.positionY,
                rotationDeg = single.rotationDeg
            )
        }

        val first = sorted.first()
        if (queryTimestampMs <= first.timestampMs) {
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = first.scale,
                positionX = first.positionX,
                positionY = first.positionY,
                rotationDeg = first.rotationDeg
            )
        }

        val last = sorted.last()
        if (queryTimestampMs >= last.timestampMs) {
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = last.scale,
                positionX = last.positionX,
                positionY = last.positionY,
                rotationDeg = last.rotationDeg
            )
        }

        // Scan for containing segment
        var k0 = sorted[0]
        var k1 = sorted.getOrElse(1) { sorted[0] }
        for (i in 0 until sorted.size - 1) {
            val cur = sorted[i]
            val next = sorted[i + 1]
            if (queryTimestampMs >= cur.timestampMs && queryTimestampMs <= next.timestampMs) {
                k0 = cur
                k1 = next
                break
            }
        }

        val duration = (k1.timestampMs - k0.timestampMs).coerceAtLeast(1L).toFloat()
        val alpha = ((queryTimestampMs - k0.timestampMs).toFloat() / duration).coerceIn(0f, 1f)

        val factor = evaluateCurve(k0.interpolation, alpha)

        val scale = lerp(k0.scale, k1.scale, factor)
        val posX = lerp(k0.positionX, k1.positionX, factor)
        val posY = lerp(k0.positionY, k1.positionY, factor)
        val rot = lerp(k0.rotationDeg, k1.rotationDeg, factor)

        return MotionTransform(
            timestampMs = queryTimestampMs,
            scale = scale,
            positionX = posX,
            positionY = posY,
            rotationDeg = rot
        )
    }

    private fun evaluateCurve(type: InterpolationType, t: Float): Float {
        return when (type) {
            InterpolationType.LINEAR -> t
            InterpolationType.EASE_IN -> t * t
            InterpolationType.EASE_OUT -> 1f - (1f - t) * (1f - t)
            InterpolationType.EASE_IN_OUT -> t * t * (3f - 2f * t)
            InterpolationType.SMOOTH -> {
                // Quintic smoothstep for ultra-smooth camera glide
                t * t * t * (t * (t * 6f - 15f) + 10f)
            }
        }
    }

    private fun lerp(start: Float, stop: Float, fraction: Float): Float {
        return start + fraction * (stop - start)
    }
}
