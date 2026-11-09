package com.adriennezoo.companion.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adriennezoo.companion.data.ActivationCodeEntity
import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.data.AppDatabase
import com.adriennezoo.companion.data.ImportResult
import com.adriennezoo.companion.data.PackEntity
import com.adriennezoo.companion.data.PackImporter
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

sealed class ActivationResult {
    data class Success(val animal: AnimalEntity) : ActivationResult()
    data class AlreadyActivated(val animal: AnimalEntity) : ActivationResult()
    data class NotFound(val message: String) : ActivationResult()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val animalDao = db.animalDao()
    private val packImporter = PackImporter(application, animalDao)
    private val gson = Gson()

    val activatedAnimals: StateFlow<List<AnimalEntity>> = animalDao.getActivatedAnimals()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allAnimals: StateFlow<List<AnimalEntity>> = animalDao.getAllAnimals()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allPacks: StateFlow<List<PackEntity>> = animalDao.getAllPacks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _activationResult = MutableStateFlow<ActivationResult?>(null)
    val activationResult: StateFlow<ActivationResult?> = _activationResult.asStateFlow()

    private val _importStatus = MutableStateFlow<ImportResult?>(null)
    val importStatus: StateFlow<ImportResult?> = _importStatus.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            checkAndLoadStarters()
        }
    }

    private suspend fun checkAndLoadStarters() {
        val count = animalDao.getAnimalCount()
        if (count > 0) return

        try {
            val context = getApplication<Application>()
            val assetManager = context.assets
            val inputStream = assetManager.open("starters.json")
            val reader = InputStreamReader(inputStream)
            val jsonArray = JsonParser.parseReader(reader).asJsonArray

            val tracksDir = File(context.filesDir, "tracks").apply { mkdirs() }
            val animalsToInsert = mutableListOf<AnimalEntity>()
            val codesToInsert = mutableListOf<ActivationCodeEntity>()

            for (i in 0 until jsonArray.size()) {
                val item = jsonArray.get(i).asJsonObject
                val animalId = item.get("animalId").asString
                val safeId = animalId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

                // Write track points to internal storage file
                val trackFile = File(tracksDir, "${safeId}.json")
                val trackObj = JsonObject()
                trackObj.addProperty("animalId", animalId)
                trackObj.add("points", item.getAsJsonArray("points"))
                FileOutputStream(trackFile).use { fos ->
                    fos.write(trackObj.toString().toByteArray(Charsets.UTF_8))
                }

                val points = item.getAsJsonArray("points")
                val firstObs = points.firstOrNull()?.asJsonArray?.get(2)?.asString
                val lastObs = points.lastOrNull()?.asJsonArray?.get(2)?.asString
                val obsCount = points.size()

                val animal = AnimalEntity(
                    animalId = animalId,
                    species = item.get("species").asString,
                    scientificName = item.get("scientificName")?.asString,
                    researchId = item.get("researchId").asString,
                    sourceStudy = item.get("sourceStudy").asString,
                    license = item.get("license").asString,
                    source = item.get("source").asString,
                    displayName = item.get("displayName").asString,
                    description = item.get("description").asString,
                    intro = item.get("intro").asString,
                    accent = item.get("accent")?.asString ?: "#27382F",
                    imagePath = null,
                    trackFilePath = trackFile.absolutePath,
                    firstObservation = item.get("firstObservation")?.asString ?: firstObs,
                    lastObservation = item.get("lastObservation")?.asString ?: lastObs,
                    observationCount = item.get("observationCount")?.asInt ?: obsCount,
                    distanceKm = item.get("distanceKm")?.asDouble,
                    isActivated = false,
                    packId = "starter-pack"
                )
                animalsToInsert.add(animal)

                // Codes
                val codesArray = item.getAsJsonArray("activationCodes")
                if (codesArray != null) {
                    for (c in 0 until codesArray.size()) {
                        val codeStr = codesArray.get(c).asString.trim().uppercase()
                        codesToInsert.add(ActivationCodeEntity(code = codeStr, animalId = animalId))
                    }
                } else {
                    val codeStr = item.get("activationCode")?.asString?.trim()?.uppercase()
                    if (!codeStr.isNullOrEmpty()) {
                        codesToInsert.add(ActivationCodeEntity(code = codeStr, animalId = animalId))
                    }
                }
            }

            animalDao.insertAnimals(animalsToInsert)
            animalDao.insertActivationCodes(codesToInsert)
            animalDao.insertPack(
                PackEntity(
                    packId = "starter-pack",
                    title = "Adrienne Zoo Starters",
                    importTimestamp = System.currentTimeMillis(),
                    animalCount = animalsToInsert.size
                )
            )
            Log.i("MainViewModel", "Loaded ${animalsToInsert.size} starter animals into database")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to load starter animals", e)
        }
    }

    fun activateCode(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = code.trim().uppercase()
            if (normalized.isEmpty()) {
                _activationResult.value = ActivationResult.NotFound("Please enter an activation code.")
                return@launch
            }

            // Look up by code
            val animal = animalDao.getAnimalByCode(normalized)
            if (animal == null) {
                _activationResult.value = ActivationResult.NotFound(
                    "Code not found. Import the animal pack containing this code first."
                )
                return@launch
            }

            if (animal.isActivated) {
                _activationResult.value = ActivationResult.AlreadyActivated(animal)
                return@launch
            }

            animalDao.markAsActivated(animal.animalId)
            val activated = animal.copy(isActivated = true)
            _activationResult.value = ActivationResult.Success(activated)
        }
    }

    fun clearActivationResult() {
        _activationResult.value = null
    }

    fun importPack(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            val result = packImporter.importFromUri(uri)
            _isImporting.value = false
            _importStatus.value = result
        }
    }

    fun clearImportStatus() {
        _importStatus.value = null
    }

    fun getAnimalById(id: String): Flow<AnimalEntity?> {
        return animalDao.getAnimalById(id)
    }

    suspend fun getTrackJson(animal: AnimalEntity): String? = withContext(Dispatchers.IO) {
        val file = File(animal.trackFilePath)
        if (!file.exists()) return@withContext null
        try {
            val trackJsonStr = file.readText(Charsets.UTF_8)
            val trackObj = JsonParser.parseString(trackJsonStr).asJsonObject
            val points = trackObj.getAsJsonArray("points")

            val outObj = JsonObject()
            outObj.addProperty("animalId", animal.animalId)
            outObj.addProperty("displayName", animal.displayName)
            outObj.addProperty("accent", animal.accent)
            outObj.addProperty("startDate", animal.firstObservation ?: "")
            outObj.addProperty("endDate", animal.lastObservation ?: "")
            outObj.add("points", points)
            outObj.toString()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error reading track file: ${animal.trackFilePath}", e)
            null
        }
    }
}
