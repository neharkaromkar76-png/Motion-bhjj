package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ExportResolution
import com.example.ui.components.KeyframeEditorSheet
import com.example.ui.components.KeyframeGraphView
import com.example.ui.components.PreviewPlayerView
import com.example.ui.components.TimelineMappingSelector
import com.example.ui.components.VideoCard
import com.example.ui.dialogs.AnalysisProgressDialog
import com.example.ui.dialogs.ExportCompleteDialog
import com.example.ui.dialogs.ExportProgressDialog
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurfaceBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioViolet
import com.example.viewmodel.MotionTransferViewModel

@Composable
fun MainScreen(
    viewModel: MotionTransferViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissInfo()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("main_screen_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Bar Header
            item {
                HeaderSection(
                    onLoadSamplePair = { viewModel.loadSamplePair() }
                )
            }

            // Input Video Cards Section
            item {
                VideoCard(
                    title = "REFERENCE VIDEO",
                    stepBadge = "INPUT 1",
                    badgeColor = StudioCyan,
                    video = uiState.referenceVideo,
                    icon = Icons.Default.Videocam,
                    onVideoSelected = { viewModel.selectReferenceVideo(it) },
                    testTagPrefix = "reference_video"
                )
            }

            item {
                VideoCard(
                    title = "ORIGINAL VIDEO",
                    stepBadge = "INPUT 2",
                    badgeColor = StudioViolet,
                    video = uiState.originalVideo,
                    icon = Icons.Default.Movie,
                    onVideoSelected = { viewModel.selectOriginalVideo(it) },
                    testTagPrefix = "original_video"
                )
            }

            // Timeline Mapping Selector
            if (uiState.referenceVideo != null || uiState.originalVideo != null) {
                item {
                    TimelineMappingSelector(
                        referenceVideo = uiState.referenceVideo,
                        originalVideo = uiState.originalVideo,
                        mappingMode = uiState.mappingMode,
                        onMappingModeChanged = { viewModel.setMappingMode(it) }
                    )
                }
            }

            // Analyze Action Button
            if (uiState.referenceVideo != null) {
                item {
                    Button(
                        onClick = { viewModel.analyzeReferenceVideo() },
                        enabled = uiState.canAnalyze,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioCyan,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("analyze_reference_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.motionTimeline == null) "Analyze Reference Video Timeline" else "Re-Analyze Reference Video",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Reconstructed Keyframe Timeline Graph
            if (uiState.motionTimeline != null && uiState.keyframes.isNotEmpty()) {
                item {
                    // Honesty disclosure per requirement #24
                    Surface(
                        color = StudioSurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, StudioSurfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = StudioCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Keyframes are reconstructed from visible camera motion in the reference video across the complete timeline.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    KeyframeGraphView(
                        keyframes = uiState.keyframes,
                        durationMs = uiState.referenceVideo?.durationMs ?: 1000L,
                        currentPositionMs = uiState.currentPositionMs,
                        selectedKeyframe = uiState.selectedKeyframe,
                        onKeyframeSelected = { viewModel.selectKeyframe(it) },
                        onSeek = { viewModel.updatePlaybackPosition(it) },
                        onAddKeyframeAtCurrentTime = {
                            viewModel.addKeyframeAtTime(uiState.currentPositionMs)
                        }
                    )
                }

                // Selected Keyframe Manual Editor
                uiState.selectedKeyframe?.let { kf ->
                    item {
                        KeyframeEditorSheet(
                            keyframe = kf,
                            maxDurationMs = uiState.referenceVideo?.durationMs ?: 10000L,
                            onKeyframeUpdated = { viewModel.updateKeyframe(it) },
                            onDeleteKeyframe = { viewModel.deleteKeyframe(it) }
                        )
                    }
                }
            }

            // Interactive Preview Player (Original video with transferred motion applied)
            if (uiState.originalVideo != null && uiState.motionTimeline != null) {
                item {
                    PreviewPlayerView(
                        video = uiState.originalVideo!!,
                        currentTransform = uiState.currentTransform,
                        previewMode = uiState.previewMode,
                        onPreviewModeChanged = { viewModel.setPreviewMode(it) },
                        onPositionChanged = { viewModel.updatePlaybackPosition(it) }
                    )
                }

                // Export Section
                item {
                    ExportControlCard(
                        selectedResolution = uiState.exportSettings.resolution,
                        onResolutionSelected = { viewModel.setExportResolution(it) },
                        onExportClick = { viewModel.startExport() },
                        canExport = uiState.canExport
                    )
                }
            }

            // Bottom Spacing
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Dialogs
    if (uiState.isAnalyzing) {
        AnalysisProgressDialog(
            progress = uiState.analysisProgress,
            statusMessage = uiState.analysisStatus,
            onCancel = { viewModel.cancelAnalysis() }
        )
    }

    if (uiState.isExporting) {
        ExportProgressDialog(
            progress = uiState.exportProgress,
            statusMessage = uiState.exportStatus,
            onCancel = { viewModel.cancelExport() }
        )
    }

    if (uiState.showExportCompleteDialog) {
        ExportCompleteDialog(
            exportedFile = uiState.exportedFile,
            exportedUri = uiState.exportedUri,
            onDismiss = { viewModel.dismissExportDialog() }
        )
    }
}

@Composable
private fun HeaderSection(
    onLoadSamplePair: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(StudioCyan)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STUDIO MOTION ENGINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StudioCyan,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "KEYFRAME MOTION TRANSFER",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Quick Demo Pair Button
            OutlinedButton(
                onClick = onLoadSamplePair,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCyan),
                modifier = Modifier.testTag("load_sample_pair_button")
            ) {
                Icon(imageVector = Icons.Default.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Demo Pair", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Reconstruct full video zoom and pan camera trajectories from reference footage and transfer them to original videos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExportControlCard(
    selectedResolution: ExportResolution,
    onResolutionSelected: (ExportResolution) -> Unit,
    onExportClick: () -> Unit,
    canExport: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("export_control_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = StudioCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EXPORT MOTION-TRANSFERRED VIDEO",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StudioCyan
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Output Resolution",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Resolution chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportResolution.values().forEach { res ->
                    FilterChip(
                        selected = selectedResolution == res,
                        onClick = { onResolutionSelected(res) },
                        label = { Text(res.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioCyan.copy(alpha = 0.2f),
                            selectedLabelColor = StudioCyan
                        ),
                        modifier = Modifier.testTag("resolution_chip_${res.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onExportClick,
                enabled = canExport,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioCyan,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("export_video_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ElectricBolt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RENDER & EXPORT VIDEO",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
