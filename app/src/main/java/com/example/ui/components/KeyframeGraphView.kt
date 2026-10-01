package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MotionKeyframe
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.KeyframeAmberBright
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurfaceBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioViolet
import com.example.utils.TimeFormatter
import com.example.video.MotionDirection
import com.example.video.MotionInterpolator
import com.example.video.MotionTimeline

@Composable
fun KeyframeGraphView(
    keyframes: List<MotionKeyframe>,
    durationMs: Long,
    currentPositionMs: Long,
    selectedKeyframe: MotionKeyframe?,
    onKeyframeSelected: (MotionKeyframe) -> Unit,
    onSeek: (Long) -> Unit,
    onAddKeyframeAtCurrentTime: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDuration = durationMs.coerceAtLeast(1000L)
    val timeline = remember(keyframes, safeDuration) {
        MotionTimeline(safeDuration, keyframes)
    }

    val maxScale = remember(keyframes) {
        keyframes.maxOfOrNull { it.scale }?.coerceAtLeast(1.4f) ?: 2.0f
    }
    val minScale = remember(keyframes) {
        keyframes.minOfOrNull { it.scale }?.coerceAtMost(1.0f) ?: 1.0f
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, StudioSurfaceBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("keyframe_graph_view")
    ) {
        // Timeline Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Diamond,
                        contentDescription = null,
                        tint = KeyframeAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MOTION BLUEPRINT TIMELINE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = KeyframeAmber
                    )
                }
                Text(
                    text = "${keyframes.size} Keyframes | Zoom In: ${timeline.zoomInCount} | Zoom Out: ${timeline.zoomOutCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = onAddKeyframeAtCurrentTime,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = KeyframeAmber),
                border = androidx.compose.foundation.BorderStroke(1.dp, KeyframeAmber.copy(alpha = 0.5f)),
                modifier = Modifier.testTag("add_keyframe_button")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add KF", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Live direction status banner
        val currentDir = timeline.getCurrentDirection(currentPositionMs)
        val dirColor = when (currentDir) {
            MotionDirection.ZOOM_IN -> StudioCyan
            MotionDirection.ZOOM_OUT -> KeyframeAmber
            MotionDirection.HOLD -> StudioViolet
        }
        val dirIcon = when (currentDir) {
            MotionDirection.ZOOM_IN -> "↗ ZOOM IN"
            MotionDirection.ZOOM_OUT -> "↘ ZOOM OUT"
            MotionDirection.HOLD -> "→ HOLD"
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(dirColor.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dirColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "MOTION STATE: $dirIcon",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = dirColor
                )
            }
            Text(
                text = "Scale: ${String.format(java.util.Locale.US, "%.2fx", (keyframes.find { it.timestampMs <= currentPositionMs }?.scale ?: 1.0f))} (Range: ${String.format(java.util.Locale.US, "%.2f - %.2fx", minScale, maxScale)})",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Visual Graph Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(StudioSurfaceElevated)
                .pointerInput(keyframes, safeDuration) {
                    detectTapGestures { offset ->
                        val tapRatio = (offset.x / size.width).coerceIn(0f, 1f)
                        val tappedTimeMs = (tapRatio * safeDuration).toLong()

                        // Check if tap was close to any keyframe diamond (within ~24px)
                        val matchedKf = keyframes.minByOrNull { kf ->
                            val kfX = (kf.timestampMs.toFloat() / safeDuration.toFloat()) * size.width
                            kotlin.math.abs(kfX - offset.x)
                        }

                        if (matchedKf != null) {
                            val kfX = (matchedKf.timestampMs.toFloat() / safeDuration.toFloat()) * size.width
                            if (kotlin.math.abs(kfX - offset.x) < 40f) {
                                onKeyframeSelected(matchedKf)
                                onSeek(matchedKf.timestampMs)
                                return@detectTapGestures
                            }
                        }

                        onSeek(tappedTimeMs)
                    }
                }
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height

                val baseScaleY = h * 0.85f
                val topScaleY = h * 0.15f
                val scaleSpan = (maxScale - minScale).coerceAtLeast(0.1f)

                // Baseline 1.0x line
                drawLine(
                    color = Color.White.copy(alpha = 0.15f),
                    start = Offset(0f, baseScaleY),
                    end = Offset(w, baseScaleY),
                    strokeWidth = 1.dp.toPx()
                )

                // Draw continuous transform curve showing scale rises (zoom in) and falls (zoom out)
                if (keyframes.isNotEmpty()) {
                    val curvePath = Path()
                    val steps = 150
                    for (step in 0..steps) {
                        val tFrac = step / steps.toFloat()
                        val sampleTime = (tFrac * safeDuration).toLong()
                        val motion = MotionInterpolator.interpolate(keyframes, sampleTime)

                        val x = tFrac * w
                        val normScale = ((motion.scale - minScale) / scaleSpan).coerceIn(0f, 1.2f)
                        val y = baseScaleY - normScale * (baseScaleY - topScaleY)

                        if (step == 0) {
                            curvePath.moveTo(x, y)
                        } else {
                            curvePath.lineTo(x, y)
                        }
                    }

                    // Stroke curve
                    drawPath(
                        path = curvePath,
                        color = StudioCyan,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }

                // Draw Keyframe Diamond nodes at peaks, valleys, and turning points
                for (kf in keyframes) {
                    val kfX = (kf.timestampMs.toFloat() / safeDuration.toFloat()) * w
                    val normScale = ((kf.scale - minScale) / scaleSpan).coerceIn(0f, 1.2f)
                    val kfY = baseScaleY - normScale * (baseScaleY - topScaleY)

                    val isSelected = selectedKeyframe?.id == kf.id
                    val nodeColor = if (isSelected) KeyframeAmberBright else KeyframeAmber
                    val nodeRadius = if (isSelected) 8.dp.toPx() else 5.dp.toPx()

                    val diamond = Path().apply {
                        moveTo(kfX, kfY - nodeRadius)
                        lineTo(kfX + nodeRadius, kfY)
                        lineTo(kfX, kfY + nodeRadius)
                        lineTo(kfX - nodeRadius, kfY)
                        close()
                    }

                    drawPath(diamond, color = nodeColor)
                    drawPath(diamond, color = Color.White, style = Stroke(width = 1.5.dp.toPx()))
                }

                // Playhead indicator
                val playheadX = (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) * w
                drawLine(
                    color = Color.White,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, h),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // MOTION DIRECTION TIMELINE TRACK (IN, OUT, HOLD intervals)
        if (timeline.segments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                for (seg in timeline.segments) {
                    val weight = (seg.durationMs.toFloat() / safeDuration.toFloat()).coerceAtLeast(0.01f)
                    val segColor = when (seg.direction) {
                        MotionDirection.ZOOM_IN -> StudioCyan.copy(alpha = 0.7f)
                        MotionDirection.ZOOM_OUT -> KeyframeAmber.copy(alpha = 0.7f)
                        MotionDirection.HOLD -> Color.Gray.copy(alpha = 0.3f)
                    }
                    val segText = when (seg.direction) {
                        MotionDirection.ZOOM_IN -> "IN"
                        MotionDirection.ZOOM_OUT -> "OUT"
                        MotionDirection.HOLD -> "—"
                    }

                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .height(20.dp)
                            .background(segColor)
                            .border(0.5.dp, Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = segText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal Keyframe list chips
        Text(
            text = "KEYFRAME NODES (TAP TO EDIT)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(keyframes, key = { it.id }) { kf ->
                val isSelected = selectedKeyframe?.id == kf.id
                Surface(
                    onClick = {
                        onKeyframeSelected(kf)
                        onSeek(kf.timestampMs)
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) KeyframeAmber.copy(alpha = 0.2f) else StudioSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) KeyframeAmber else StudioSurfaceBorder
                    ),
                    modifier = Modifier.testTag("keyframe_chip_${kf.timestampMs}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) KeyframeAmber else StudioCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = TimeFormatter.formatMsToTimecode(kf.timestampMs),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) KeyframeAmber else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Zoom: ${kf.zoomPercentage}% | Pan: ${String.format(java.util.Locale.US, "%.2f, %.2f", kf.positionX, kf.positionY)}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
