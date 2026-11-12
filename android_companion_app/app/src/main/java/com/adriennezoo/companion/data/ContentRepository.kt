package com.adriennezoo.companion.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

class ContentRepository(
    private val context: Context,
    private val animalDao: AnimalDao,
    baseFilesDir: File? = null
) : ContentSource {

    private val catalogReconciler = CatalogReconciler(context, animalDao, baseFilesDir)
    private val packImporter = PackImporter(context, animalDao, baseFilesDir)

    suspend fun reconcileBundledCatalog(): Int {
        return catalogReconciler.reconcile()
    }

    override suspend fun loadBundledCatalog(): List<BundledAnimal> = withContext(Dispatchers.IO) {
        val list = mutableListOf<BundledAnimal>()
        try {
            val stream = context.assets.open("starters.json")
            val reader = InputStreamReader(stream)
            val array = JsonParser.parseReader(reader).asJsonArray
            for (i in 0 until array.size()) {
                val item = array.get(i).asJsonObject
                val codes = mutableListOf<String>()
                val codesArray = item.getAsJsonArray("activationCodes")
                if (codesArray != null) {
                    for (c in 0 until codesArray.size()) {
                        codes.add(codesArray.get(c).asString.trim().uppercase())
                    }
                }
                list.add(
                    BundledAnimal(
                        animalId = item.get("animalId").asString,
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
                        firstObservation = item.get("firstObservation")?.asString,
                        lastObservation = item.get("lastObservation")?.asString,
                        observationCount = item.get("observationCount")?.asInt ?: 0,
                        distanceKm = item.get("distanceKm")?.asDouble,
                        activationCodes = codes
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading bundled starters", e)
        }
        list
    }

    override suspend fun getTrackPayload(animal: AnimalEntity): String? = withContext(Dispatchers.IO) {
        var file = File(animal.trackFilePath)
        if (!file.exists()) {
            // Self-repair: run catalog reconciler to restore bundled track files if missing
            catalogReconciler.reconcile()
            file = File(animal.trackFilePath)
            if (!file.exists()) return@withContext null
        }

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
            Log.e(TAG, "Error reading track payload: ${animal.trackFilePath}", e)
            null
        }
    }

    override suspend fun importPackFromStream(stream: InputStream): ImportResult {
        return packImporter.importFromStream(stream)
    }

    override suspend fun importPackFromUri(uri: Uri): ImportResult {
        return packImporter.importFromUri(uri)
    }

    override fun getInstalledPacks(): Flow<List<PackEntity>> {
        return animalDao.getAllPacks()
    }

    companion object {
        private const val TAG = "ContentRepository"
    }
}
