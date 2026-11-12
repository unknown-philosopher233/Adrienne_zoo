package com.adriennezoo.companion.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.data.AppDatabase
import com.adriennezoo.companion.data.ContentRepository
import com.adriennezoo.companion.data.ImportResult
import com.adriennezoo.companion.data.PackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ActivationResult {
    data class Success(val animal: AnimalEntity) : ActivationResult()
    data class AlreadyActivated(val animal: AnimalEntity) : ActivationResult()
    data class NotFound(val message: String) : ActivationResult()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val animalDao = db.animalDao()
    private val contentRepository = ContentRepository(application, animalDao)

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
            try {
                contentRepository.reconcileBundledCatalog()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Catalog reconciliation failed", e)
            }
        }
    }

    fun activateCode(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = code.trim().uppercase()
            if (normalized.isEmpty()) {
                _activationResult.value = ActivationResult.NotFound("Please enter an activation code.")
                return@launch
            }

            // Look up animal mapped to this code
            val animal = animalDao.getAnimalByCode(normalized)
            if (animal == null) {
                _activationResult.value = ActivationResult.NotFound(
                    "Code not found. Import the animal pack containing this code first."
                )
                return@launch
            }

            val isAlreadyUnlocked = animalDao.isAnimalUnlocked(animal.animalId) || animal.isActivated
            if (isAlreadyUnlocked) {
                _activationResult.value = ActivationResult.AlreadyActivated(animal)
                return@launch
            }

            // Unlock and store ownership record atomically
            animalDao.unlockAnimal(
                animalId = animal.animalId,
                code = normalized,
                method = "CODE",
                timestamp = System.currentTimeMillis()
            )
            val activated = animal.copy(isActivated = true)
            _activationResult.value = ActivationResult.Success(activated)
        }
    }

    fun collectAnimal(animalId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val animal = animalDao.getAnimalByIdSync(animalId) ?: return@launch
            if (!animalDao.isAnimalUnlocked(animalId) && !animal.isActivated) {
                animalDao.unlockAnimal(
                    animalId = animalId,
                    code = null,
                    method = "COLLECTION",
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }

    fun clearActivationResult() {
        _activationResult.value = null
    }

    fun importPack(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            val result = contentRepository.importPackFromUri(uri)
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

    suspend fun getTrackJson(animal: AnimalEntity): String? {
        return contentRepository.getTrackPayload(animal)
    }
}
