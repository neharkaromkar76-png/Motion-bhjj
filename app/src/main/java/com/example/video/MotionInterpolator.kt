package com.example.video

import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import com.example.model.MotionTransform

object MotionInterpolator {

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

        if (queryTimestampMs <= sorted.first().timestampMs) {
            val first = sorted.first()
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = first.scale,
                positionX = first.positionX,
                positionY = first.positionY,
                rotationDeg = first.rotationDeg
            )
        }

        if (queryTimestampMs >= sorted.last().timestampMs) {
            val last = sorted.last()
            return MotionTransform(
                timestampMs = queryTimestampMs,
                scale = last.scale,
                positionX = last.positionX,
                positionY = last.positionY,
                rotationDeg = last.rotationDeg
            )
        }

        // Binary search or linear scan for segment
        var k0 = sorted[0]
        var k1 = sorted[1]
        for (i in 0 until sorted.size - 1) {
            if (queryTimestampMs >= sorted[i].timestampMs && queryTimestampMs <= sorted[i + 1].timestampMs) {
                k0 = sorted[i]
                k1 = sorted[i + 1]
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
