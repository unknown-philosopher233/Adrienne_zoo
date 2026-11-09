package com.adriennezoo.companion.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.data.AppDatabase
import com.adriennezoo.companion.data.PackEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val animalDao = db.animalDao()
    private val gson = Gson()

    val activatedAnimals: StateFlow<List<AnimalEntity>> = animalDao.getActivatedAnimals()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allPacks: StateFlow<List<PackEntity>> = animalDao.getAllPacks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            checkAndLoadStarters()
        }
    }

    private suspend fun checkAndLoadStarters() {
        val count = animalDao.getAllAnimals().stateIn(viewModelScope).value.size
        // Wait, flow doesn't have a one-shot size like this.
        // I need a one-shot query.
    }
}
