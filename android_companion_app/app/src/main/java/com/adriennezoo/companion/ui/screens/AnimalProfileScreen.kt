package com.adriennezoo.companion.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adriennezoo.companion.ui.components.JourneyMapView
import com.adriennezoo.companion.ui.components.formatDateDisplay
import com.adriennezoo.companion.ui.theme.BorderColor
import com.adriennezoo.companion.ui.theme.BorderSubtle
import com.adriennezoo.companion.ui.theme.ForestGreenContainer
import com.adriennezoo.companion.ui.theme.ForestGreenLight
import com.adriennezoo.companion.ui.theme.ForestGreenPrimary
import com.adriennezoo.companion.ui.theme.LinenCanvas
import com.adriennezoo.companion.ui.theme.LinenCard
import com.adriennezoo.companion.ui.theme.LinenSurface
import com.adriennezoo.companion.ui.theme.TextMuted
import com.adriennezoo.companion.ui.theme.TextPrimary
import com.adriennezoo.companion.ui.theme.TextSecondary
import com.adriennezoo.companion.viewmodel.MainViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimalProfileScreen(
    animalId: String,
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onOpenFullMap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val animalFlow = remember(animalId) { viewModel.getAnimalById(animalId) }
    val animal by animalFlow.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        animal?.displayName ?: "Animal Profile",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    animal?.let {
                        IconButton(onClick = { onOpenFullMap(it.animalId) }) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "Full Map",
                                tint = Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ForestGreenPrimary,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = LinenCanvas
    ) { padding ->
        val currentAnimal = animal
        if (currentAnimal == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ForestGreenPrimary)
            }
        } else {
            val accentColor = try {
                Color(android.graphics.Color.parseColor(currentAnimal.accent))
            } catch (_: Exception) {
                ForestGreenPrimary
            }

            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Profile Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LinenCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(accentColor)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = currentAnimal.displayName,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = currentAnimal.species,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ForestGreenLight
                                    )
                                }
                            }

                            // Research ID Chip
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = ForestGreenContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = currentAnimal.license,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = ForestGreenPrimary
                                )
                            }
                        }

                        if (!currentAnimal.scientificName.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = currentAnimal.scientificName,
                                fontSize = 13.sp,
                                fontStyle = FontStyle.Italic,
                                color = TextSecondary
                            )
                        }

                        // Optional custom profile image if present
                        if (!currentAnimal.imagePath.isNullOrBlank()) {
                            val imgFile = File(currentAnimal.imagePath)
                            if (imgFile.exists()) {
                                val bitmap = remember(currentAnimal.imagePath) {
                                    BitmapFactory.decodeFile(imgFile.absolutePath)?.asImageBitmap()
                                }
                                if (bitmap != null) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = currentAnimal.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                                    )
                                }
                            }
                        }

                        if (currentAnimal.intro.isNotBlank()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = Color(0xFFF1EFEA),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "\"${currentAnimal.intro}\"",
                                    fontSize = 13.sp,
                                    color = TextPrimary,
                                    lineHeight = 18.sp,
                                    fontStyle = FontStyle.Italic
                                )
                            }
                        }

                        if (currentAnimal.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = currentAnimal.description,
                                fontSize = 13.sp,
                                color = TextSecondary,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }

                // Interactive Map Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LinenCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RECORDED JOURNEY MAP",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = ForestGreenLight,
                                letterSpacing = 1.sp
                            )
                            Button(
                                onClick = { onOpenFullMap(currentAnimal.animalId) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Full Screen", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Embedded Journey Map
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, BorderColor, RoundedCornerShape(6.dp))
                        ) {
                            JourneyMapView(
                                animal = currentAnimal,
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Scientific Research & Tracking Metadata Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LinenCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "RESEARCH METADATA",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("RESEARCH ID", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                                Text(currentAnimal.researchId, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("OBSERVATIONS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                                Text("${currentAnimal.observationCount} fixes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("FIRST RECORDED", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                                Text(formatDateDisplay(currentAnimal.firstObservation), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("LAST RECORDED", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                                Text(formatDateDisplay(currentAnimal.lastObservation), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            }
                        }

                        if (currentAnimal.distanceKm != null && currentAnimal.distanceKm > 0) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("RECORDED DISTANCE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                            Text(
                                "${String.format("%,.0f", currentAnimal.distanceKm)} km",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BorderSubtle)
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Text("SOURCE STUDY", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
                        Text(currentAnimal.sourceStudy, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ForestGreenLight)

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(currentAnimal.source, fontSize = 11.sp, color = TextMuted, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}
