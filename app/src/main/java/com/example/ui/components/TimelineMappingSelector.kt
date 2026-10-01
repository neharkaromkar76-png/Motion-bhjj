package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.TimelineMappingMode
import com.example.model.VideoSource
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurfaceBorder
import com.example.ui.theme.StudioSurfaceElevated

@Composable
fun TimelineMappingSelector(
    referenceVideo: VideoSource?,
    originalVideo: VideoSource?,
    mappingMode: TimelineMappingMode,
    onMappingModeChanged: (TimelineMappingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDurationsEqual = referenceVideo != null && originalVideo != null &&
            kotlin.math.abs(referenceVideo.durationMs - originalVideo.durationMs) < 200L

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("timeline_mapping_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, StudioSurfaceBorder)
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
                    imageVector = Icons.Default.SyncAlt,
                    contentDescription = null,
                    tint = StudioCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "TIMELINE MAPPING LOGIC",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StudioCyan
                )

                if (isDurationsEqual) {
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        color = StudioCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.4f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = StudioCyan,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "1:1 DURATION MATCH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = StudioCyan
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Option 1: Normalized Mapping
            MappingOptionItem(
                title = TimelineMappingMode.NORMALIZED.title,
                subtitle = TimelineMappingMode.NORMALIZED.subtitle,
                selected = mappingMode == TimelineMappingMode.NORMALIZED,
                enabled = !isDurationsEqual,
                onClick = { onMappingModeChanged(TimelineMappingMode.NORMALIZED) },
                testTag = "mapping_normalized_option"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Option 2: Exact Time Mapping
            MappingOptionItem(
                title = TimelineMappingMode.EXACT_TIME.title,
                subtitle = TimelineMappingMode.EXACT_TIME.subtitle,
                selected = mappingMode == TimelineMappingMode.EXACT_TIME || isDurationsEqual,
                enabled = true,
                onClick = { onMappingModeChanged(TimelineMappingMode.EXACT_TIME) },
                testTag = "mapping_exact_time_option"
            )

            if (referenceVideo != null && originalVideo != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = StudioSurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = KeyframeAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isDurationsEqual) {
                                "Videos have equal duration (${referenceVideo.durationFormatted}). Sub-second timestamps align exactly 1:1."
                            } else {
                                "Ref (${referenceVideo.durationFormatted}) vs Orig (${originalVideo.durationFormatted}). Normalized mapping stretches motion curves proportionally."
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MappingOptionItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) StudioCyan.copy(alpha = 0.1f) else StudioSurfaceElevated,
        border = BorderStroke(
            1.dp,
            if (selected) StudioCyan.copy(alpha = 0.6f) else StudioSurfaceBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = if (enabled) onClick else null,
                colors = RadioButtonDefaults.colors(selectedColor = StudioCyan)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
