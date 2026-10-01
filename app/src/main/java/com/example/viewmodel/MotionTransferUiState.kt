package com.example.viewmodel

import android.net.Uri
import com.example.model.ExportResolution
import com.example.model.ExportSettings
import com.example.model.MotionKeyframe
import com.example.model.MotionTransform
import com.example.model.TimelineMappingMode
import com.example.model.VideoSource
import com.example.video.MotionTimeline
import com.example.video.RawMotionSample
import java.io.File

enum class PreviewMode(val label: String) {
    AFTER_MOTION("Motion Applied (After)"),
    BEFORE_ORIGINAL("Original Footage (Before)"),
    SPLIT_COMPARE("Split Compare")
}

data class MotionTransferUiState(
    val referenceVideo: VideoSource? = null,
    val originalVideo: VideoSource? = null,

    // Analysis
    val isAnalyzing: Boolean = false,
    val analysisProgress: Float = 0f,
    val analysisStatus: String = "",

    // Motion Timeline
    val motionTimeline: MotionTimeline? = null,
    val keyframes: List<MotionKeyframe> = emptyList(),
    val rawSamples: List<RawMotionSample> = emptyList(),
    val selectedKeyframe: MotionKeyframe? = null,

    // Timeline Configuration
    val mappingMode: TimelineMappingMode = TimelineMappingMode.NORMALIZED,

    // Preview
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val previewMode: PreviewMode = PreviewMode.AFTER_MOTION,
    val currentTransform: MotionTransform = MotionTransform(0L, 1.0f, 0.5f, 0.5f),

    // Export
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportStatus: String = "",
    val exportSettings: ExportSettings = ExportSettings(
        resolution = ExportResolution.ORIGINAL,
        mappingMode = TimelineMappingMode.NORMALIZED
    ),
    val exportedFile: File? = null,
    val exportedUri: Uri? = null,
    val showExportCompleteDialog: Boolean = false,

    // UI Feedback
    val errorMessage: String? = null,
    val infoMessage: String? = null
) {
    val canAnalyze: Boolean
        get() = referenceVideo != null && !isAnalyzing

    val canExport: Boolean
        get() = originalVideo != null && motionTimeline != null && !isExporting

    val isDurationEqual: Boolean
        get() = referenceVideo != null && originalVideo != null &&
                kotlin.math.abs(referenceVideo.durationMs - originalVideo.durationMs) < 200L
}
