package com.example.model

enum class InterpolationType(val displayName: String) {
    EASE_IN_OUT("Ease In-Out (Smooth)"),
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    SMOOTH("Cubic Spline")
}

data class MotionKeyframe(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestampMs: Long,
    val scale: Float = 1.0f,
    val positionX: Float = 0.5f, // Normalized 0.0 (left) to 1.0 (right), 0.5 = center
    val positionY: Float = 0.5f, // Normalized 0.0 (top) to 1.0 (bottom), 0.5 = center
    val rotationDeg: Float = 0f,
    val interpolation: InterpolationType = InterpolationType.EASE_IN_OUT
) {
    val zoomPercentage: Int
        get() = (scale * 100f).toInt()

    fun copyWith(
        timestampMs: Long = this.timestampMs,
        scale: Float = this.scale,
        positionX: Float = this.positionX,
        positionY: Float = this.positionY,
        rotationDeg: Float = this.rotationDeg,
        interpolation: InterpolationType = this.interpolation
    ): MotionKeyframe {
        return MotionKeyframe(
            id = this.id,
            timestampMs = timestampMs,
            scale = scale,
            positionX = positionX,
            positionY = positionY,
            rotationDeg = rotationDeg,
            interpolation = interpolation
        )
    }
}

enum class TimelineMappingMode(val title: String, val subtitle: String) {
    EXACT_TIME(
        "Exact Time Mapping",
        "Uses 1:1 timestamps (e.g. 02.35s in Reference = 02.35s in Original)"
    ),
    NORMALIZED(
        "Normalized Timeline Mapping",
        "Scales motion proportionally across different video durations (0% → 100%)"
    )
}

data class MotionTransform(
    val timestampMs: Long,
    val scale: Float,
    val positionX: Float,
    val positionY: Float,
    val rotationDeg: Float = 0f
)
