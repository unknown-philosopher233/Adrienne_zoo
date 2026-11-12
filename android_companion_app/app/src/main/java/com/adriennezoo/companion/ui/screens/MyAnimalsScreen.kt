package com.adriennezoo.companion.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adriennezoo.companion.data.ImportResult
import com.adriennezoo.companion.ui.components.AnimalCard
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAnimalsScreen(
    viewModel: MainViewModel,
    onNavigateToActivate: () -> Unit,
    onNavigateToAnimal: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activatedAnimals by viewModel.activatedAnimals.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()
    val allPacks by viewModel.allPacks.collectAsState()

    var showPacksDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showImportSuccessDialog by remember { mutableStateOf<ImportResult.Success?>(null) }
    var showImportErrorDialog by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importPack(it) }
    }

    LaunchedEffect(importStatus) {
        when (val status = importStatus) {
            is ImportResult.Success -> {
                showImportSuccessDialog = status
                viewModel.clearImportStatus()
            }
            is ImportResult.Error -> {
                showImportErrorDialog = status.message
                viewModel.clearImportStatus()
            }
            null -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "FIELD NOTES",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = ForestGreenContainer,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "ZooApp",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showPacksDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Installed Packs",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Import Pack",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "About ZooApp",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ForestGreenPrimary,
                    titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            if (activatedAnimals.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToActivate,
                    icon = { Icon(Icons.Default.Key, contentDescription = null) },
                    text = { Text("Activate Animal", fontWeight = FontWeight.SemiBold) },
                    containerColor = ForestGreenPrimary,
                    contentColor = Color.White
                )
            }
        },
        containerColor = LinenCanvas
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (activatedAnimals.isEmpty()) {
                // Empty state matching the design requirement
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(ForestGreenContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pets,
                            contentDescription = null,
                            tint = ForestGreenPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Your animal collection is waiting to be discovered.",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Enter an activation code from your field notes or import a .wildpack file to unlock a real tracked animal.",
                        fontSize = 14.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = onNavigateToActivate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForestGreenPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enter Activation Code", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ForestGreenPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ForestGreenLight),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import .wildpack File", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Starter animals helper card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = LinenCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "STARTER CODES",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "• Mara (Blue Whale): WILD-0003-CODE\n• Nalu (Green Turtle): WILD-0002-CODE\n• Kestrel (Caribou): WILD-0001-CODE\n• Harbor (Sea Lion): WILD-0004-CODE\n• Sol (Albatross): WILD-0000-CODE",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "MY ANIMALS",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "${activatedAnimals.size} unlocked",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = ForestGreenLight
                            )
                        }
                    }

                    items(
                        items = activatedAnimals,
                        key = { it.animalId }
                    ) { animal ->
                        AnimalCard(
                            animal = animal,
                            onClick = { onNavigateToAnimal(animal.animalId) }
                        )
                    }
                }
            }

            if (isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LinenSurface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = ForestGreenPrimary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Importing wildlife pack...", fontWeight = FontWeight.Medium, color = TextPrimary)
                        }
                    }
                }
            }
        }
    }

    // Success Dialog
    showImportSuccessDialog?.let { result ->
        AlertDialog(
            onDismissRequest = { showImportSuccessDialog = null },
            title = { Text("Pack Imported Successfully", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Imported '${result.packTitle}' containing ${result.animalCount} animal${if (result.animalCount == 1) "" else "s"}.\n\nEnter an activation code from the pack to unlock your new companion.",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImportSuccessDialog = null
                        onNavigateToActivate()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary)
                ) {
                    Text("Enter Code")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportSuccessDialog = null }) {
                    Text("Done", color = TextSecondary)
                }
            },
            containerColor = LinenSurface
        )
    }

    // Error Dialog
    showImportErrorDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { showImportErrorDialog = null },
            title = { Text("Import Failed", fontWeight = FontWeight.Bold, color = Color(0xFF8A3030)) },
            text = { Text(msg, fontSize = 14.sp, color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { showImportErrorDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary)
                ) {
                    Text("OK")
                }
            },
            containerColor = LinenSurface
        )
    }

    // Installed Packs Dialog
    if (showPacksDialog) {
        AlertDialog(
            onDismissRequest = { showPacksDialog = false },
            title = { Text("Installed Wildlife Packs", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (allPacks.isEmpty()) {
                        Text("No packs installed yet.", color = TextSecondary)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(allPacks) { pack ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = LinenCard),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(pack.title, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "ID: ${pack.packId} · ${pack.animalCount} animals",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPacksDialog = false
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary)
                ) {
                    Text("Import Another Pack")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPacksDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = LinenSurface
        )
    }

    // About & Historical Data Disclosure Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About ZooApp", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "ZooApp uses real historical wildlife tracking observations presented as a time-released journey. Field notes are generated from movement data and may describe inferred activity. Locations shown are not live animal positions.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary)
                ) {
                    Text("OK")
                }
            },
            containerColor = LinenSurface
        )
    }
}
