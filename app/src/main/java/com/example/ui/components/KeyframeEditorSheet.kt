package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.InterpolationType
import com.example.model.MotionKeyframe
import com.example.ui.theme.AccentError
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurfaceBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.utils.TimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyframeEditorSheet(
    keyframe: MotionKeyframe,
    maxDurationMs: Long,
    onKeyframeUpdated: (MotionKeyframe) -> Unit,
    onDeleteKeyframe: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedDropdown by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("keyframe_editor_sheet"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, KeyframeAmber.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = KeyframeAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "EDIT KEYFRAME",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = KeyframeAmber
                    )
                }

                Text(
                    text = TimeFormatter.formatMsToTimecode(keyframe.timestampMs),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. Timestamp slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Timeline Position",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${keyframe.timestampMs} ms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Slider(
                value = keyframe.timestampMs.toFloat(),
                onValueChange = { newTime ->
                    onKeyframeUpdated(keyframe.copy(timestampMs = newTime.toLong()))
                },
                valueRange = 0f..maxDurationMs.toFloat().coerceAtLeast(1000f),
                colors = SliderDefaults.colors(
                    thumbColor = KeyframeAmber,
                    activeTrackColor = KeyframeAmber
                ),
                modifier = Modifier.fillMaxWidth().testTag("editor_time_slider")
            )

            // 2. Scale / Zoom Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Camera Zoom / Scale",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(keyframe.scale * 100).toInt()}% (${String.format(java.util.Locale.US, "%.2fx", keyframe.scale)})",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StudioCyan
                )
            }
            Slider(
                value = keyframe.scale,
                onValueChange = { newScale ->
                    val rounded = Math.round(newScale * 100f) / 100f
                    onKeyframeUpdated(keyframe.copy(scale = rounded))
                },
                valueRange = 0.8f..2.5f,
                colors = SliderDefaults.colors(
                    thumbColor = StudioCyan,
                    activeTrackColor = StudioCyan
                ),
                modifier = Modifier.fillMaxWidth().testTag("editor_scale_slider")
            )

            // 3. Pan Position X & Y Sliders
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Pan X
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pan X: ${String.format(java.util.Locale.US, "%.2f", keyframe.positionX)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = keyframe.positionX,
                        onValueChange = { newX ->
                            onKeyframeUpdated(keyframe.copy(positionX = Math.round(newX * 100f) / 100f))
                        },
                        valueRange = 0.1f..0.9f,
                        modifier = Modifier.fillMaxWidth().testTag("editor_pan_x_slider")
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Pan Y
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pan Y: ${String.format(java.util.Locale.US, "%.2f", keyframe.positionY)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = keyframe.positionY,
                        onValueChange = { newY ->
                            onKeyframeUpdated(keyframe.copy(positionY = Math.round(newY * 100f) / 100f))
                        },
                        valueRange = 0.1f..0.9f,
                        modifier = Modifier.fillMaxWidth().testTag("editor_pan_y_slider")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Interpolation Selector
            Text(
                text = "Transition Interpolation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = expandedDropdown,
                onExpandedChange = { expandedDropdown = !expandedDropdown },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = keyframe.interpolation.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                        .testTag("interpolation_dropdown"),
                    shape = RoundedCornerShape(8.dp)
                )

                ExposedDropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false }
                ) {
                    InterpolationType.values().forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            onClick = {
                                onKeyframeUpdated(keyframe.copy(interpolation = type))
                                expandedDropdown = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Delete Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { onDeleteKeyframe(keyframe.id) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentError),
                    border = BorderStroke(1.dp, AccentError.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("delete_keyframe_button")
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Keyframe", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete Node", fontSize = 12.sp)
                }
            }
        }
    }
}
