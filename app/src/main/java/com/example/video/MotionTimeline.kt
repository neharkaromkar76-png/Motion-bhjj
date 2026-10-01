package com.example.video

import com.example.model.MotionKeyframe
import com.example.model.MotionTransform
import com.example.model.TimelineMappingMode

data class MotionTimeline(
    val referenceDurationMs: Long,
    val keyframes: List<MotionKeyframe>
) {
    fun getTransformForOriginalTime(
        originalTimeMs: Long,
        originalDurationMs: Long,
        mappingMode: TimelineMappingMode
    ): MotionTransform {
        if (keyframes.isEmpty()) {
            return MotionTransform(
                timestampMs = originalTimeMs,
                scale = 1.0f,
                positionX = 0.5f,
                positionY = 0.5f,
                rotationDeg = 0f
            )
        }

        val mappedReferenceTimeMs: Long = when {
            // If durations are equal, always exact 1:1 mapping per requirement #3
            originalDurationMs == referenceDurationMs -> {
                originalTimeMs.coerceIn(0L, referenceDurationMs)
            }
            mappingMode == TimelineMappingMode.EXACT_TIME -> {
                originalTimeMs.coerceIn(0L, referenceDurationMs)
            }
            else -> {
                // NORMALIZED TIMELINE MAPPING:
                // reference_t = (original_t / original_duration) * reference_duration
                if (originalDurationMs <= 0L) {
                    0L
                } else {
                    val progress = (originalTimeMs.toDouble() / originalDurationMs.toDouble()).coerceIn(0.0, 1.0)
                    (progress * referenceDurationMs).toLong()
                }
            }
        }

        val refTransform = MotionInterpolator.interpolate(keyframes, mappedReferenceTimeMs)
        return refTransform.copy(timestampMs = originalTimeMs)
    }

    val maxScale: Float
        get() = keyframes.maxOfOrNull { it.scale } ?: 1.0f

    val minScale: Float
        get() = keyframes.minOfOrNull { it.scale } ?: 1.0f

    val hasZoomIn: Boolean
        get() = maxScale > 1.05f

    val hasZoomOut: Boolean
        get() = minScale < 0.95f
}
