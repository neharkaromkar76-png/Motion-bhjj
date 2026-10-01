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

    @Test
    fun testMotionTimeline_exactTimeMapping_longDuration48s() {
        val refDuration = 48577L // 00:48.577
        val origDuration = 48879L // 00:48.879

        val keyframes = listOf(
            MotionKeyframe(timestampMs = 0L, scale = 1.0f, positionX = 0.5f, positionY = 0.5f),
            MotionKeyframe(timestampMs = 12045L, scale = 1.5f, positionX = 0.6f, positionY = 0.5f),
            MotionKeyframe(timestampMs = 24000L, scale = 1.8f, positionX = 0.5f, positionY = 0.5f),
            MotionKeyframe(timestampMs = 48577L, scale = 1.0f, positionX = 0.5f, positionY = 0.5f)
        )

        val timeline = MotionTimeline(
            referenceDurationMs = refDuration,
            keyframes = keyframes
        )

        // Query at index/time 12045ms (from user error)
        val transformAt12s = timeline.getTransformForOriginalTime(
            originalTimeMs = 12045L,
            originalDurationMs = origDuration,
            mappingMode = TimelineMappingMode.EXACT_TIME
        )
        assertEquals(1.5f, transformAt12s.scale, 0.01f)
        assertEquals(12045L, transformAt12s.timestampMs)

        // Query beyond reference duration
        val transformAtEnd = timeline.getTransformForOriginalTime(
            originalTimeMs = 48879L,
            originalDurationMs = origDuration,
            mappingMode = TimelineMappingMode.EXACT_TIME
        )
        assertEquals(1.0f, transformAtEnd.scale, 0.01f)
        assertEquals(48879L, transformAtEnd.timestampMs)
    }

    @Test
    fun testMotionInterpolator_boundsSafety() {
        // Empty list
        val emptyTransform = MotionInterpolator.interpolate(emptyList(), 12045L)
        assertEquals(1.0f, emptyTransform.scale, 0.01f)

        // Single keyframe
        val single = listOf(MotionKeyframe(timestampMs = 5000L, scale = 2.0f, positionX = 0.7f, positionY = 0.3f))
        val singleBefore = MotionInterpolator.interpolate(single, 0L)
        assertEquals(2.0f, singleBefore.scale, 0.01f)
        val singleAfter = MotionInterpolator.interpolate(single, 10000L)
        assertEquals(2.0f, singleAfter.scale, 0.01f)

        // Query before start and after end
        val keyframes = listOf(
            MotionKeyframe(timestampMs = 1000L, scale = 1.2f),
            MotionKeyframe(timestampMs = 2000L, scale = 1.4f)
        )
        val before = MotionInterpolator.interpolate(keyframes, -500L)
        assertEquals(1.2f, before.scale, 0.01f)
        val after = MotionInterpolator.interpolate(keyframes, 99999L)
        assertEquals(1.4f, after.scale, 0.01f)
    }

    @Test
    fun testSyntheticMotionCurve_zoomInAndZoomOutSequence() {
        // Synthetic motion curve requested by user:
        // 0s = 1.00
        // 1s = 1.25
        // 2s = 1.50 (Peak 1)
        // 3s = 1.25 (Zoom out!)
        // 4s = 1.00 (Valley 1)
        // 5s = 1.40
        // 6s = 1.70 (Peak 2)
        // 7s = 1.30 (Zoom out!)
        // 8s = 1.00 (Valley 2)
        val syntheticSamples = listOf(
            RawMotionSample(0L, 1.00f, 0.5f, 0.5f),
            RawMotionSample(1000L, 1.25f, 0.5f, 0.5f),
            RawMotionSample(2000L, 1.50f, 0.5f, 0.5f),
            RawMotionSample(3000L, 1.25f, 0.5f, 0.5f),
            RawMotionSample(4000L, 1.00f, 0.5f, 0.5f),
            RawMotionSample(5000L, 1.40f, 0.5f, 0.5f),
            RawMotionSample(6000L, 1.70f, 0.5f, 0.5f),
            RawMotionSample(7000L, 1.30f, 0.5f, 0.5f),
            RawMotionSample(8000L, 1.00f, 0.5f, 0.5f)
        )

        val keyframes = KeyframeDetector.reconstructKeyframes(syntheticSamples, 8000L)
        val timeline = MotionTimeline(referenceDurationMs = 8000L, keyframes = keyframes)

        // 1. Must detect both Zoom In and Zoom Out
        assertTrue("Timeline must detect Zoom In", timeline.hasZoomIn)
        assertTrue("Timeline must detect Zoom Out", timeline.hasZoomOut)
        assertTrue("Timeline must have at least 1 Zoom In event", timeline.zoomInCount >= 1)
        assertTrue("Timeline must have at least 1 Zoom Out event", timeline.zoomOutCount >= 1)

        // 2. Sample points along timeline:
        val scale0s = timeline.getTransformForOriginalTime(0L, 8000L, TimelineMappingMode.EXACT_TIME).scale
        val scale1s = timeline.getTransformForOriginalTime(1000L, 8000L, TimelineMappingMode.EXACT_TIME).scale
        val scale2s = timeline.getTransformForOriginalTime(2000L, 8000L, TimelineMappingMode.EXACT_TIME).scale
        val scale3s = timeline.getTransformForOriginalTime(3000L, 8000L, TimelineMappingMode.EXACT_TIME).scale
        val scale4s = timeline.getTransformForOriginalTime(4000L, 8000L, TimelineMappingMode.EXACT_TIME).scale
        val scale5s = timeline.getTransformForOriginalTime(5000L, 8000L, TimelineMappingMode.EXACT_TIME).scale
        val scale6s = timeline.getTransformForOriginalTime(6000L, 8000L, TimelineMappingMode.EXACT_TIME).scale
        val scale7s = timeline.getTransformForOriginalTime(7000L, 8000L, TimelineMappingMode.EXACT_TIME).scale
        val scale8s = timeline.getTransformForOriginalTime(8000L, 8000L, TimelineMappingMode.EXACT_TIME).scale

        // Expected result:
        // 0-2s ZOOM IN: scale rises
        assertTrue("0-2s must zoom in: scale2s ($scale2s) > scale0s ($scale0s)", scale2s > scale0s + 0.3f)

        // 2-4s ZOOM OUT: scale decreases
        assertTrue("2-4s must zoom out: scale2s ($scale2s) > scale3s ($scale3s)", scale2s > scale3s)
        assertTrue("2-4s must zoom out: scale3s ($scale3s) > scale4s ($scale4s)", scale3s > scale4s)
        assertTrue("2-4s must reach near 1.0 at 4s ($scale4s)", scale4s < 1.15f)

        // 4-6s ZOOM IN: scale rises again
        assertTrue("4-6s must zoom in: scale6s ($scale6s) > scale4s ($scale4s)", scale6s > scale4s + 0.4f)

        // 6-8s ZOOM OUT: scale decreases again
        assertTrue("6-8s must zoom out: scale6s ($scale6s) > scale7s ($scale7s)", scale6s > scale7s)
        assertTrue("6-8s must zoom out: scale7s ($scale7s) > scale8s ($scale8s)", scale7s > scale8s)
        assertTrue("6-8s must reach near 1.0 at 8s ($scale8s)", scale8s < 1.15f)

        // Verify NON-MONOTONIC curve (not a single positive zoom)
        assertTrue("Curve is non-monotonic", scale2s > scale4s && scale6s > scale4s && scale6s > scale8s)
    }

    @Test
    fun testCase1_referenceSingleZoomIn() {
        // TEST 1: Reference has one zoom-in
        val samples = listOf(
            RawMotionSample(0L, 1.0f, 0.5f, 0.5f),
            RawMotionSample(1000L, 1.2f, 0.5f, 0.5f),
            RawMotionSample(2000L, 1.5f, 0.5f, 0.5f)
        )
        val kfs = KeyframeDetector.reconstructKeyframes(samples, 2000L)
        val timeline = MotionTimeline(2000L, kfs, samples)
        assertTrue(timeline.hasZoomIn)
        val startScale = timeline.getTransformForOriginalTime(0L, 2000L, TimelineMappingMode.EXACT_TIME).scale
        val endScale = timeline.getTransformForOriginalTime(2000L, 2000L, TimelineMappingMode.EXACT_TIME).scale
        assertTrue("End scale must be greater than start scale", endScale > startScale + 0.4f)
    }

    @Test
    fun testCase2_referenceSingleZoomOut() {
        // TEST 2: Reference has one zoom-out
        val samples = listOf(
            RawMotionSample(0L, 1.6f, 0.5f, 0.5f),
            RawMotionSample(1000L, 1.3f, 0.5f, 0.5f),
            RawMotionSample(2000L, 1.0f, 0.5f, 0.5f)
        )
        val kfs = KeyframeDetector.reconstructKeyframes(samples, 2000L)
        val timeline = MotionTimeline(2000L, kfs, samples)
        assertTrue(timeline.hasZoomOut)
        val startScale = timeline.getTransformForOriginalTime(0L, 2000L, TimelineMappingMode.EXACT_TIME).scale
        val endScale = timeline.getTransformForOriginalTime(2000L, 2000L, TimelineMappingMode.EXACT_TIME).scale
        assertTrue("Start scale must be greater than end scale", startScale > endScale + 0.4f)
    }

    @Test
    fun testCase3_turningPointZoomInToZoomOut() {
        // TEST 3: zoom-in -> turning point -> zoom-out
        val samples = listOf(
            RawMotionSample(0L, 1.0f, 0.5f, 0.5f),
            RawMotionSample(1000L, 1.3f, 0.5f, 0.5f),
            RawMotionSample(2000L, 1.6f, 0.5f, 0.5f), // Peak
            RawMotionSample(3000L, 1.3f, 0.5f, 0.5f),
            RawMotionSample(4000L, 1.0f, 0.5f, 0.5f)
        )
        val kfs = KeyframeDetector.reconstructKeyframes(samples, 4000L)
        val timeline = MotionTimeline(4000L, kfs, samples)
        assertTrue("Must detect peak zoom turning point", kfs.any { it.timestampMs in 1800L..2200L && it.scale >= 1.55f })
        assertTrue(timeline.hasZoomIn)
        assertTrue(timeline.hasZoomOut)
    }

    @Test
    fun testCase5_lowMotionReference() {
        // TEST 5: Reference has no meaningful camera motion
        val staticSamples = listOf(
            RawMotionSample(0L, 1.000f, 0.50f, 0.50f),
            RawMotionSample(1000L, 1.002f, 0.50f, 0.50f),
            RawMotionSample(2000L, 1.001f, 0.50f, 0.50f),
            RawMotionSample(3000L, 1.000f, 0.50f, 0.50f)
        )
        val kfs = KeyframeDetector.reconstructKeyframes(staticSamples, 3000L)
        val timeline = MotionTimeline(3000L, kfs, staticSamples)
        val isStatic = (timeline.maxScale - timeline.minScale < 0.02f)
        assertTrue("Static reference must be detected as low/no motion", isStatic)
    }

    @Test
    fun testCase6_differentFpsTimeAlignment() {
        // TEST 6: Continuous interpolation at arbitrary frame timestamps (e.g. 24 FPS vs 60 FPS)
        val samples = listOf(
            RawMotionSample(0L, 1.0f, 0.5f, 0.5f),
            RawMotionSample(1000L, 1.4f, 0.6f, 0.4f),
            RawMotionSample(2000L, 1.0f, 0.5f, 0.5f)
        )
        val timeline = MotionTimeline(2000L, emptyList(), samples)

        // Query at 24 FPS frame timestamp (41.66ms) and 60 FPS timestamp (16.66ms)
        val t16 = timeline.getTransformForOriginalTime(16L, 2000L, TimelineMappingMode.EXACT_TIME)
        val t41 = timeline.getTransformForOriginalTime(41L, 2000L, TimelineMappingMode.EXACT_TIME)
        val t500 = timeline.getTransformForOriginalTime(500L, 2000L, TimelineMappingMode.EXACT_TIME)

        assertTrue(t16.scale in 1.0f..1.05f)
        assertTrue(t41.scale in 1.0f..1.08f)
        assertTrue(t500.scale in 1.15f..1.25f)
    }
}
