package com.example.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.model.MotionTransform
import com.example.model.VideoSource
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurfaceBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioViolet
import com.example.utils.TimeFormatter
import com.example.viewmodel.PreviewMode
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PreviewPlayerView(
    video: VideoSource,
    currentTransform: MotionTransform,
    previewMode: PreviewMode,
    onPreviewModeChanged: (PreviewMode) -> Unit,
    onPositionChanged: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    val durationMs = video.durationMs.coerceAtLeast(1000L)

    val exoPlayer = remember(video.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(video.uri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Polling player time for smooth motion synchronization
    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            if (exoPlayer.isPlaying) {
                val pos = exoPlayer.currentPosition
                currentPositionMs = pos
                onPositionChanged(pos)
            }
            delay(16) // ~60fps poll
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, StudioSurfaceBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("preview_player_view")
    ) {
        // Mode selector row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = StudioCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "MOTION TRANSFER PREVIEW",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StudioCyan
                )
            }

            // Mode chips
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = previewMode == PreviewMode.AFTER_MOTION,
                    onClick = { onPreviewModeChanged(PreviewMode.AFTER_MOTION) },
                    label = { Text("After", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyan.copy(alpha = 0.2f),
                        selectedLabelColor = StudioCyan
                    ),
                    modifier = Modifier.testTag("mode_after_chip")
                )
                FilterChip(
                    selected = previewMode == PreviewMode.BEFORE_ORIGINAL,
                    onClick = { onPreviewModeChanged(PreviewMode.BEFORE_ORIGINAL) },
                    label = { Text("Before", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioViolet.copy(alpha = 0.2f),
                        selectedLabelColor = StudioViolet
                    ),
                    modifier = Modifier.testTag("mode_before_chip")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Video Viewport Box with dynamic GraphicsLayer motion application
        val aspect = remember(video.width, video.height) {
            val w = video.width.toFloat()
            val h = video.height.toFloat()
            if (w > 0 && h > 0) (w / h).coerceIn(0.5f, 2.2f) else (16f / 9f)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .testTag("video_viewport_box"),
            contentAlignment = Alignment.Center
        ) {
            // Video Player container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (previewMode == PreviewMode.AFTER_MOTION) {
                            scaleX = currentTransform.scale
                            scaleY = currentTransform.scale

                            // Pan calculation: posX = 0.5 is centered.
                            // Moving camera framing right (posX > 0.5) translates the image left (- translation)
                            val panOffsetX = (0.5f - currentTransform.positionX) * size.width * currentTransform.scale
                            val panOffsetY = (0.5f - currentTransform.positionY) * size.height * currentTransform.scale

                            translationX = panOffsetX
                            translationY = panOffsetY
                            rotationZ = currentTransform.rotationDeg
                        } else {
                            scaleX = 1.0f
                            scaleY = 1.0f
                            translationX = 0f
                            translationY = 0f
                            rotationZ = 0f
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Real-time HUD overlay badge
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        text = if (previewMode == PreviewMode.AFTER_MOTION) "MOTION ACTIVE" else "ORIGINAL (STATIC)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (previewMode == PreviewMode.AFTER_MOTION) StudioCyan else StudioViolet
                    )
                    Text(
                        text = "Zoom: ${(currentTransform.scale * 100).toInt()}%  Pan: ${String.format(java.util.Locale.US, "%.2f, %.2f", currentTransform.positionX, currentTransform.positionY)}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                }
            }

            // Current Timecode Badge
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
            ) {
                Text(
                    text = TimeFormatter.formatMsToTimecode(currentPositionMs),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scrubbing timeline slider
        Slider(
            value = currentPositionMs.toFloat(),
            onValueChange = { newPos ->
                currentPositionMs = newPos.toLong()
                exoPlayer.seekTo(newPos.toLong())
                onPositionChanged(newPos.toLong())
            },
            valueRange = 0f..durationMs.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = StudioCyan,
                activeTrackColor = StudioCyan
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("preview_scrubber_slider")
        )

        // Transport Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(StudioCyan.copy(alpha = 0.15f))
                        .testTag("play_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = StudioCyan
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        exoPlayer.seekTo(0L)
                        exoPlayer.play()
                    },
                    modifier = Modifier.testTag("restart_playback_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = "Restart",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "${TimeFormatter.formatMsToTimecode(currentPositionMs)} / ${TimeFormatter.formatMsToTimecode(durationMs)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
