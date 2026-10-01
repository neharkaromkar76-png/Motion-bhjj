package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ExportResolution
import com.example.model.ExportSettings
import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import com.example.model.TimelineMappingMode
import com.example.model.VideoSource
import com.example.utils.MediaStoreUtils
import com.example.video.KeyframeDetector
import com.example.video.MotionAnalyzer
import com.example.video.MotionTimeline
import com.example.video.SampleVideoGenerator
import com.example.video.VideoAnalyzer
import com.example.video.VideoRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

class MotionTransferViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MotionTransferUiState())
    val uiState: StateFlow<MotionTransferUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null
    private var exportJob: Job? = null

    init {
        // Automatically check if sample videos should be prepped in background for 1-click test
        viewModelScope.launch {
            try {
                // Pre-generate sample videos in background so user has zero wait time
                SampleVideoGenerator.createReferenceSampleVideo(getApplication())
                SampleVideoGenerator.createOriginalSampleVideo(getApplication())
            } catch (_: Exception) {}
        }
    }

    fun loadSamplePair() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(analysisStatus = "Preparing sample test videos...") }
                val ref = SampleVideoGenerator.createReferenceSampleVideo(getApplication())
                val orig = SampleVideoGenerator.createOriginalSampleVideo(getApplication())

                _uiState.update { current ->
                    current.copy(
                        referenceVideo = ref,
                        originalVideo = orig,
                        mappingMode = if (abs(ref.durationMs - orig.durationMs) < 200L) {
                            TimelineMappingMode.EXACT_TIME
                        } else {
                            TimelineMappingMode.NORMALIZED
                        },
                        motionTimeline = null,
                        keyframes = emptyList(),
                        infoMessage = "Loaded sample videos! Tap 'Analyze Reference' to reconstruct motion timeline."
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Could not load sample videos: ${e.localizedMessage}") }
            }
        }
    }

    fun selectReferenceVideo(uri: Uri) {
        viewModelScope.launch {
            try {
                val metadata = VideoAnalyzer.extractMetadata(getApplication(), uri, "Reference Video")
                _uiState.update { current ->
                    val shouldDefaultExact = current.originalVideo != null &&
                            abs(metadata.durationMs - current.originalVideo.durationMs) < 200L
                    current.copy(
                        referenceVideo = metadata,
                        mappingMode = if (shouldDefaultExact) TimelineMappingMode.EXACT_TIME else current.mappingMode,
                        motionTimeline = null,
                        keyframes = emptyList(),
                        selectedKeyframe = null,
                        infoMessage = "Reference video loaded. Ready to analyze motion."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to load reference video. Please choose another file.")
                }
            }
        }
    }

    fun selectOriginalVideo(uri: Uri) {
        viewModelScope.launch {
            try {
                val metadata = VideoAnalyzer.extractMetadata(getApplication(), uri, "Original Video")
                _uiState.update { current ->
                    val shouldDefaultExact = current.referenceVideo != null &&
                            abs(metadata.durationMs - current.referenceVideo.durationMs) < 200L
                    current.copy(
                        originalVideo = metadata,
                        mappingMode = if (shouldDefaultExact) TimelineMappingMode.EXACT_TIME else current.mappingMode,
                        infoMessage = "Original video loaded. Motion will be applied here."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to load original video. Please choose another file.")
                }
            }
        }
    }

    fun setMappingMode(mode: TimelineMappingMode) {
        _uiState.update { current ->
            val updated = current.copy(mappingMode = mode)
            current.motionTimeline?.let { timeline ->
                val currentOrigMs = current.currentPositionMs
                val origDuration = current.originalVideo?.durationMs ?: timeline.referenceDurationMs
                val transform = timeline.getTransformForOriginalTime(currentOrigMs, origDuration, mode)
                updated.copy(currentTransform = transform)
            } ?: updated
        }
    }

    fun analyzeReferenceVideo() {
        val ref = _uiState.value.referenceVideo ?: return

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAnalyzing = true,
                    analysisProgress = 0f,
                    analysisStatus = "Initializing full video motion analyzer..."
                )
            }

            try {
                val extractedKeyframes = MotionAnalyzer.extractMotionKeyframesWithMediaExtractor(
                    context = getApplication(),
                    referenceUri = ref.uri,
                    durationMs = ref.durationMs
                ) { progress, message ->
                    _uiState.update {
                        it.copy(analysisProgress = progress, analysisStatus = message)
                    }
                }

                val detectedKeyframes = KeyframeDetector.reconstructFromExtractedKeyframes(
                    extractedKeyframes = extractedKeyframes,
                    referenceDurationMs = ref.durationMs
                )

                val timeline = MotionTimeline(
                    referenceDurationMs = ref.durationMs,
                    keyframes = detectedKeyframes
                )

                val origDuration = _uiState.value.originalVideo?.durationMs ?: ref.durationMs
                val initialTransform = timeline.getTransformForOriginalTime(
                    originalTimeMs = 0L,
                    originalDurationMs = origDuration,
                    mappingMode = _uiState.value.mappingMode
                )

                val rawSamplesList = extractedKeyframes.map {
                    com.example.video.RawMotionSample(it.timestampMs, it.scale, it.positionX, it.positionY)
                }

                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        analysisProgress = 1.0f,
                        analysisStatus = "Motion analysis complete",
                        rawSamples = rawSamplesList,
                        keyframes = detectedKeyframes,
                        motionTimeline = timeline,
                        currentTransform = initialTransform,
                        selectedKeyframe = detectedKeyframes.firstOrNull(),
                        infoMessage = "Detected ${detectedKeyframes.size} motion keyframes across full timeline!"
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(isAnalyzing = false, analysisStatus = "Analysis cancelled")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        errorMessage = "Reference video could not be analyzed: ${e.localizedMessage ?: "Unknown error"}. Please choose another video."
                    )
                }
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        _uiState.update { it.copy(isAnalyzing = false, analysisStatus = "Analysis cancelled by user") }
    }

    fun selectKeyframe(keyframe: MotionKeyframe) {
        _uiState.update { it.copy(selectedKeyframe = keyframe) }
    }

    fun updateKeyframe(updated: MotionKeyframe) {
        _uiState.update { current ->
            val updatedList = current.keyframes.map { if (it.id == updated.id) updated else it }
                .sortedBy { it.timestampMs }
            val refDuration = current.referenceVideo?.durationMs ?: 1000L
            val timeline = MotionTimeline(refDuration, updatedList)

            val origDuration = current.originalVideo?.durationMs ?: refDuration
            val currentTransform = timeline.getTransformForOriginalTime(
                current.currentPositionMs,
                origDuration,
                current.mappingMode
            )

            current.copy(
                keyframes = updatedList,
                motionTimeline = timeline,
                selectedKeyframe = updated,
                currentTransform = currentTransform
            )
        }
    }

    fun addKeyframeAtTime(timeMs: Long) {
        val currentTimeline = _uiState.value.motionTimeline ?: return
        val currentRefDuration = _uiState.value.referenceVideo?.durationMs ?: 1000L
        val currentOrigDuration = _uiState.value.originalVideo?.durationMs ?: currentRefDuration

        val currentTransform = currentTimeline.getTransformForOriginalTime(
            originalTimeMs = timeMs,
            originalDurationMs = currentOrigDuration,
            mappingMode = _uiState.value.mappingMode
        )

        val newKf = MotionKeyframe(
            timestampMs = timeMs.coerceIn(0L, currentRefDuration),
            scale = currentTransform.scale,
            positionX = currentTransform.positionX,
            positionY = currentTransform.positionY,
            rotationDeg = currentTransform.rotationDeg,
            interpolation = InterpolationType.EASE_IN_OUT
        )

        val updatedList = (_uiState.value.keyframes + newKf).sortedBy { it.timestampMs }
        val timeline = MotionTimeline(currentRefDuration, updatedList)

        _uiState.update {
            it.copy(
                keyframes = updatedList,
                motionTimeline = timeline,
                selectedKeyframe = newKf
            )
        }
    }

    fun deleteKeyframe(id: String) {
        _uiState.update { current ->
            // Prevent deleting if 2 or fewer keyframes remain
            if (current.keyframes.size <= 2) {
                return@update current.copy(errorMessage = "Minimum of 2 keyframes required for timeline interpolation.")
            }
            val updatedList = current.keyframes.filterNot { it.id == id }
            val refDuration = current.referenceVideo?.durationMs ?: 1000L
            val timeline = MotionTimeline(refDuration, updatedList)

            current.copy(
                keyframes = updatedList,
                motionTimeline = timeline,
                selectedKeyframe = updatedList.firstOrNull()
            )
        }
    }

    fun updatePlaybackPosition(positionMs: Long) {
        _uiState.update { current ->
            val timeline = current.motionTimeline
            val origDuration = current.originalVideo?.durationMs ?: (timeline?.referenceDurationMs ?: 1000L)
            val transform = timeline?.getTransformForOriginalTime(
                originalTimeMs = positionMs,
                originalDurationMs = origDuration,
                mappingMode = current.mappingMode
            ) ?: current.currentTransform

            current.copy(
                currentPositionMs = positionMs,
                currentTransform = transform
            )
        }
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { it.copy(isPlaying = playing) }
    }

    fun setPreviewMode(mode: PreviewMode) {
        _uiState.update { it.copy(previewMode = mode) }
    }

    fun setExportResolution(resolution: ExportResolution) {
        _uiState.update { current ->
            current.copy(
                exportSettings = current.exportSettings.copy(resolution = resolution)
            )
        }
    }

    fun startExport() {
        val orig = _uiState.value.originalVideo ?: run {
            _uiState.update { it.copy(errorMessage = "Please select an Original Video to apply motion to.") }
            return
        }
        val timeline = _uiState.value.motionTimeline ?: run {
            _uiState.update { it.copy(errorMessage = "Please analyze the Reference Video first to reconstruct keyframes.") }
            return
        }

        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val cacheFile = File(app.cacheDir, "MotionTransfer_Export_${System.currentTimeMillis()}.mp4")

            _uiState.update {
                it.copy(
                    isExporting = true,
                    exportProgress = 0f,
                    exportStatus = "Preparing export pipeline...",
                    exportedFile = null,
                    exportedUri = null
                )
            }

            try {
                val finalFile = VideoRenderer.renderMotionTransferredVideo(
                    context = app,
                    originalVideo = orig,
                    motionTimeline = timeline,
                    exportSettings = _uiState.value.exportSettings.copy(mappingMode = _uiState.value.mappingMode),
                    outputFile = cacheFile
                ) { progress, message ->
                    _uiState.update {
                        it.copy(exportProgress = progress, exportStatus = message)
                    }
                }

                // Save to MediaStore
                val mediaStoreUri = MediaStoreUtils.saveVideoToMediaStore(app, finalFile)

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 1.0f,
                        exportStatus = "Export Complete",
                        exportedFile = finalFile,
                        exportedUri = mediaStoreUri,
                        showExportCompleteDialog = true
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isExporting = false, exportStatus = "Export cancelled") }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "Video export failed: ${e.localizedMessage ?: "Unknown error"}. Check storage and video format."
                    )
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _uiState.update { it.copy(isExporting = false, exportStatus = "Export cancelled by user") }
    }

    fun dismissExportDialog() {
        _uiState.update { it.copy(showExportCompleteDialog = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissInfo() {
        _uiState.update { it.copy(infoMessage = null) }
    }
}
