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
    val direction: MotionDirection,
    val peakScale: Float = maxOf(startScale, endScale),
    val confidence: Float = 1.0f
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

data class MotionTimeline(
    val referenceDurationMs: Long,
    val keyframes: List<MotionKeyframe>,
    val samples: List<RawMotionSample> = emptyList(),
    val events: List<MotionSegment> = emptyList()
) {
    fun getTransformForOriginalTime(
        originalTimeMs: Long,
        originalDurationMs: Long,
        mappingMode: TimelineMappingMode
    ): MotionTransform {
        if (samples.isEmpty() && keyframes.isEmpty()) {
            return MotionTransform(
                timestampMs = originalTimeMs,
                scale = 1.0f,
                positionX = 0.5f,
                positionY = 0.5f,
                rotationDeg = 0f
            )
        }

        val mappedReferenceTimeMs: Long = when {
            // When durations are equal, use exact 1:1 mapping
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

        // Evaluate continuous motion samples if available, falling back to keyframe interpolation
        val refTransform = if (samples.isNotEmpty()) {
            MotionInterpolator.interpolateFromSamples(samples, mappedReferenceTimeMs)
        } else {
            MotionInterpolator.interpolate(keyframes, mappedReferenceTimeMs)
        }

        return refTransform.copy(timestampMs = originalTimeMs)
    }

    val segments: List<MotionSegment>
        get() {
            if (events.isNotEmpty()) return events
            if (keyframes.size < 2) return emptyList()
            val sorted = keyframes.sortedBy { it.timestampMs }
            val list = mutableListOf<MotionSegment>()
            for (i in 0 until sorted.size - 1) {
                val k0 = sorted[i]
                val k1 = sorted[i + 1]
                val deltaScale = k1.scale - k0.scale
                val dir = when {
                    deltaScale > 0.03f -> MotionDirection.ZOOM_IN
                    deltaScale < -0.03f -> MotionDirection.ZOOM_OUT
                    else -> MotionDirection.HOLD
                }
                list.add(
                    MotionSegment(
                        startMs = k0.timestampMs,
                        endMs = k1.timestampMs,
                        startScale = k0.scale,
                        endScale = k1.scale,
                        direction = dir,
                        peakScale = maxOf(k0.scale, k1.scale)
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
        get() = if (samples.isNotEmpty()) {
            samples.maxOfOrNull { it.scale } ?: (keyframes.maxOfOrNull { it.scale } ?: 1.0f)
        } else {
            keyframes.maxOfOrNull { it.scale } ?: 1.0f
        }

    val minScale: Float
        get() = if (samples.isNotEmpty()) {
            samples.minOfOrNull { it.scale } ?: (keyframes.minOfOrNull { it.scale } ?: 1.0f)
        } else {
            keyframes.minOfOrNull { it.scale } ?: 1.0f
        }

    val hasZoomIn: Boolean
        get() = segments.any { it.direction == MotionDirection.ZOOM_IN } || maxScale > 1.05f

    val hasZoomOut: Boolean
        get() = segments.any { it.direction == MotionDirection.ZOOM_OUT }

    val zoomInCount: Int
        get() = segments.count { it.direction == MotionDirection.ZOOM_IN }

    val zoomOutCount: Int
        get() = segments.count { it.direction == MotionDirection.ZOOM_OUT }
}
