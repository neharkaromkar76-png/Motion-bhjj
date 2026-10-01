package com.example

import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import com.example.model.TimelineMappingMode
import com.example.video.KeyframeDetector
import com.example.video.MotionInterpolator
import com.example.video.MotionTimeline
import com.example.video.RawMotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTransferUnitTest {

    @Test
    fun testMotionInterpolator_boundaryConditions() {
        val keyframes = listOf(
            MotionKeyframe(timestampMs = 0L, scale = 1.0f, positionX = 0.5f, positionY = 0.5f),
            MotionKeyframe(timestampMs = 2000L, scale = 1.5f, positionX = 0.6f, positionY = 0.4f)
        )

        // At t = 0
        val t0 = MotionInterpolator.interpolate(keyframes, 0L)
        assertEquals(1.0f, t0.scale, 0.001f)
        assertEquals(0.5f, t0.positionX, 0.001f)

        // At t = 2000
        val t2 = MotionInterpolator.interpolate(keyframes, 2000L)
        assertEquals(1.5f, t2.scale, 0.001f)
        assertEquals(0.6f, t2.positionX, 0.001f)

        // Beyond end
        val tBeyond = MotionInterpolator.interpolate(keyframes, 5000L)
        assertEquals(1.5f, tBeyond.scale, 0.001f)
    }

    @Test
    fun testMotionInterpolator_linearMidpoint() {
        val keyframes = listOf(
            MotionKeyframe(timestampMs = 1000L, scale = 1.0f, interpolation = InterpolationType.LINEAR),
            MotionKeyframe(timestampMs = 3000L, scale = 2.0f, interpolation = InterpolationType.LINEAR)
        )

        // Exactly halfway at t = 2000ms
        val mid = MotionInterpolator.interpolate(keyframes, 2000L)
        assertEquals(1.5f, mid.scale, 0.01f)
    }

    @Test
    fun testTimelineMapping_exactTimeMapping() {
        val keyframes = listOf(
            MotionKeyframe(timestampMs = 0L, scale = 1.0f),
            MotionKeyframe(timestampMs = 2350L, scale = 1.45f),
            MotionKeyframe(timestampMs = 6000L, scale = 1.0f)
        )
        val timeline = MotionTimeline(
            referenceDurationMs = 6000L,
            keyframes = keyframes
        )

        // Exact time mapping at 2350ms should produce scale 1.45f
        val transform = timeline.getTransformForOriginalTime(
            originalTimeMs = 2350L,
            originalDurationMs = 10000L,
            mappingMode = TimelineMappingMode.EXACT_TIME
        )
        assertEquals(1.45f, transform.scale, 0.01f)
    }

    @Test
    fun testTimelineMapping_normalizedMapping() {
        val keyframes = listOf(
            MotionKeyframe(timestampMs = 0L, scale = 1.0f),
            MotionKeyframe(timestampMs = 5000L, scale = 1.8f),
            MotionKeyframe(timestampMs = 10000L, scale = 1.0f)
        )
        val timeline = MotionTimeline(
            referenceDurationMs = 10000L,
            keyframes = keyframes
        )

        // Reference peak is at 50% (5000ms / 10000ms)
        // Original video duration is 20000ms.
        // At 50% of Original (10000ms), normalized mapping should yield the peak scale 1.8f!
        val transform = timeline.getTransformForOriginalTime(
            originalTimeMs = 10000L,
            originalDurationMs = 20000L,
            mappingMode = TimelineMappingMode.NORMALIZED
        )
        assertEquals(1.8f, transform.scale, 0.01f)
    }

    @Test
    fun testKeyframeDetector_reconstructsExtrema() {
        val samples = listOf(
            RawMotionSample(0L, 1.0f, 0.5f, 0.5f),
            RawMotionSample(500L, 1.05f, 0.5f, 0.5f),
            RawMotionSample(1000L, 1.20f, 0.5f, 0.5f),
            RawMotionSample(1500L, 1.45f, 0.5f, 0.5f), // peak
            RawMotionSample(2000L, 1.30f, 0.5f, 0.5f),
            RawMotionSample(2500L, 1.10f, 0.5f, 0.5f),
            RawMotionSample(3000L, 1.00f, 0.5f, 0.5f)
        )

        val keyframes = KeyframeDetector.reconstructKeyframes(samples, 3000L)

        // Must start at 0ms and end at 3000ms
        assertEquals(0L, keyframes.first().timestampMs)
        assertEquals(3000L, keyframes.last().timestampMs)

        // Must detect peak around 1500ms
        val hasPeak = keyframes.any { it.scale >= 1.35f }
        assertTrue("Detected keyframes must capture peak zoom", hasPeak)
    }

    @Test
    fun testKeyframeDetector_reconstructsFromExtractedKeyframes() {
        val extractedKeyframes = listOf(
            MotionKeyframe(timestampMs = 0L, scale = 1.0f, positionX = 0.5f, positionY = 0.5f),
            MotionKeyframe(timestampMs = 600L, scale = 1.15f, positionX = 0.52f, positionY = 0.5f),
            MotionKeyframe(timestampMs = 1250L, scale = 1.45f, positionX = 0.56f, positionY = 0.51f), // peak
            MotionKeyframe(timestampMs = 2100L, scale = 1.25f, positionX = 0.53f, positionY = 0.5f),
            MotionKeyframe(timestampMs = 3500L, scale = 1.0f, positionX = 0.5f, positionY = 0.5f)
        )

        val result = KeyframeDetector.reconstructFromExtractedKeyframes(extractedKeyframes, 3500L)
        assertEquals(0L, result.first().timestampMs)
        assertEquals(3500L, result.last().timestampMs)
        assertTrue("Timeline should contain keyframe with millisecond timestamp", result.any { it.timestampMs in 1000L..1500L })
    }
}
