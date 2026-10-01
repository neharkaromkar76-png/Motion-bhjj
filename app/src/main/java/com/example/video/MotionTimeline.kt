package com.example.video

import com.example.model.MotionKeyframe
import com.example.model.MotionTransform
import com.example.model.TimelineMappingMode

enum class MotionDirection(val label: String) {
    ZOOM_IN("ZOOM IN"),
    ZOOM_OUT("ZOOM OUT"),
    HOLD("HOLD")
}

data class MotionSegment(
    val startMs: Long,
    val endMs: Long,
    val startScale: Float,
    val endScale: Float,
    val direction: MotionDirection
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

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
            // If durations are equal, always exact 1:1 mapping per requirement
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

    val segments: List<MotionSegment>
        get() {
            if (keyframes.size < 2) return emptyList()
            val sorted = keyframes.sortedBy { it.timestampMs }
            val list = mutableListOf<MotionSegment>()
            for (i in 0 until sorted.size - 1) {
                val k0 = sorted[i]
                val k1 = sorted[i + 1]
                val deltaScale = k1.scale - k0.scale
                val dir = when {
                    deltaScale > 0.04f -> MotionDirection.ZOOM_IN
                    deltaScale < -0.04f -> MotionDirection.ZOOM_OUT
                    else -> MotionDirection.HOLD
                }
                list.add(
                    MotionSegment(
                        startMs = k0.timestampMs,
                        endMs = k1.timestampMs,
                        startScale = k0.scale,
                        endScale = k1.scale,
                        direction = dir
                    )
                )
            }
            return list
        }

    fun getCurrentDirection(timeMs: Long): MotionDirection {
        val seg = segments.firstOrNull { timeMs in it.startMs..it.endMs }
        return seg?.direction ?: MotionDirection.HOLD
    }

    val maxScale: Float
        get() = keyframes.maxOfOrNull { it.scale } ?: 1.0f

    val minScale: Float
        get() = keyframes.minOfOrNull { it.scale } ?: 1.0f

    val hasZoomIn: Boolean
        get() = segments.any { it.direction == MotionDirection.ZOOM_IN } || maxScale > 1.05f

    val hasZoomOut: Boolean
        get() = segments.any { it.direction == MotionDirection.ZOOM_OUT }

    val zoomInCount: Int
        get() = segments.count { it.direction == MotionDirection.ZOOM_IN }

    val zoomOutCount: Int
        get() = segments.count { it.direction == MotionDirection.ZOOM_OUT }
}
