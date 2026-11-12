package com.adriennezoo.companion.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

data class BundledAnimal(
    val animalId: String,
    val species: String,
    val scientificName: String?,
    val researchId: String,
    val sourceStudy: String,
    val license: String,
    val source: String,
    val displayName: String,
    val description: String,
    val intro: String,
    val accent: String,
    val firstObservation: String?,
    val lastObservation: String?,
    val observationCount: Int,
    val distanceKm: Double?,
    val activationCodes: List<String>
)

interface ContentSource {
    suspend fun loadBundledCatalog(): List<BundledAnimal>
    suspend fun getTrackPayload(animal: AnimalEntity): String?
    suspend fun importPackFromStream(stream: InputStream): ImportResult
    suspend fun importPackFromUri(uri: Uri): ImportResult
    fun getInstalledPacks(): Flow<List<PackEntity>>
}
