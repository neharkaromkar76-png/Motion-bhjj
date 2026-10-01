package com.example.model

import android.net.Uri

data class VideoSource(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val rotation: Int = 0,
    val fileSizeBytes: Long = 0L,
    val isSample: Boolean = false
) {
    val resolutionLabel: String
        get() = "${width}x${height}"

    val fpsLabel: String
        get() = if (fps > 0) String.format(java.util.Locale.US, "%.1f FPS", fps) else "30 FPS"

    val durationFormatted: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val millis = durationMs % 1000
            return String.format(java.util.Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
        }
}

enum class ExportResolution(val label: String, val maxDimension: Int) {
    ORIGINAL("Original Resolution", 0),
    FHD_1080P("1080p (Full HD)", 1080),
    HD_720P("720p (HD)", 720)
}

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    val mappingMode: TimelineMappingMode = TimelineMappingMode.NORMALIZED,
    val targetBitrateMbps: Int = 8
)
