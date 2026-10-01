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
import com.example.video.MotionInterpolator

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
                        text = "RECONSTRUCTED MOTION TIMELINE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = KeyframeAmber
                    )
                }
                Text(
                    text = "${keyframes.size} Keyframes Detected across ${(durationMs / 1000f).let { String.format(java.util.Locale.US, "%.2fs", it) }}",
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

        Spacer(modifier = Modifier.height(12.dp))

        // Visual Graph Canvas
        val safeDuration = durationMs.coerceAtLeast(1000L)
        val maxScale = remember(keyframes) {
            keyframes.maxOfOrNull { it.scale }?.coerceAtLeast(1.5f) ?: 2.0f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
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

                // Grid lines (horizontal for scale levels 1.0, 1.5, 2.0)
                val baseScaleY = h * 0.85f
                val topScaleY = h * 0.15f

                // Draw baseline (1.0x scale)
                drawLine(
                    color = Color.White.copy(alpha = 0.15f),
                    start = Offset(0f, baseScaleY),
                    end = Offset(w, baseScaleY),
                    strokeWidth = 1.dp.toPx()
                )

                // Draw motion continuous curve
                if (keyframes.isNotEmpty()) {
                    val curvePath = Path()
                    val steps = 100
                    for (step in 0..steps) {
                        val tFrac = step / steps.toFloat()
                        val sampleTime = (tFrac * safeDuration).toLong()
                        val motion = MotionInterpolator.interpolate(keyframes, sampleTime)

                        val x = tFrac * w
                        // Map scale 1.0 -> baseScaleY, maxScale -> topScaleY
                        val normScale = ((motion.scale - 1.0f) / (maxScale - 1.0f).coerceAtLeast(0.1f)).coerceIn(0f, 1.2f)
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

                // Draw Keyframe Diamond nodes
                for (kf in keyframes) {
                    val kfX = (kf.timestampMs.toFloat() / safeDuration.toFloat()) * w
                    val normScale = ((kf.scale - 1.0f) / (maxScale - 1.0f).coerceAtLeast(0.1f)).coerceIn(0f, 1.2f)
                    val kfY = baseScaleY - normScale * (baseScaleY - topScaleY)

                    val isSelected = selectedKeyframe?.id == kf.id
                    val nodeColor = if (isSelected) KeyframeAmberBright else KeyframeAmber
                    val nodeRadius = if (isSelected) 8.dp.toPx() else 5.dp.toPx()

                    // Diamond shape path
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
