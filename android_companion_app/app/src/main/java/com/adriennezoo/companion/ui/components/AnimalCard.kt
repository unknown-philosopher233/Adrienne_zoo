package com.adriennezoo.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.ui.theme.BorderColor
import com.adriennezoo.companion.ui.theme.ForestGreenLight
import androidx.core.graphics.toColorInt
import com.adriennezoo.companion.ui.theme.ForestGreenPrimary
import com.adriennezoo.companion.ui.theme.LinenCard
import com.adriennezoo.companion.ui.theme.TextMuted
import com.adriennezoo.companion.ui.theme.TextPrimary
import com.adriennezoo.companion.ui.theme.TextSecondary

@Composable
fun AnimalCard(
    animal: AnimalEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = try {
        Color(animal.accent.toColorInt())
    } catch (_: Exception) {
        ForestGreenPrimary
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = LinenCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = animal.displayName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = animal.species,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = ForestGreenLight
                            )
                            if (!animal.scientificName.isNullOrBlank()) {
                                Text(
                                    text = " · ${animal.scientificName}",
                                    fontSize = 12.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "View profile",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (animal.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = animal.description,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Observed: ${formatDateDisplay(animal.lastObservation)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }

                if (animal.observationCount > 0) {
                    Text(
                        text = "${animal.observationCount} fixes",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        modifier = Modifier
                            .background(
                                color = Color(0xFFEAECE4),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

fun formatDateDisplay(raw: String?): String {
    if (raw.isNullOrBlank()) return "Historical"
    return try {
        // Try parsing ISO or timestamp string
        if (raw.all { it.isDigit() }) {
            val millis = raw.toLong()
            val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            sdf.format(java.util.Date(millis))
        } else {
            // ISO format like 2008-07-25T23:45:00.000Z
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            val d = parser.parse(raw.take(19))
            val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            if (d != null) sdf.format(d) else raw.take(10)
        }
    } catch (_: Exception) {
        raw.take(10)
    }
}
