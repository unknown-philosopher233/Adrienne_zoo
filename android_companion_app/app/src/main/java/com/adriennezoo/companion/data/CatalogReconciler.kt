package com.adriennezoo.companion.data

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class CatalogReconciler(
    private val context: Context?,
    private val animalDao: AnimalDao,
    private val baseFilesDir: File? = null,
    private val catalogStreamProvider: (() -> java.io.InputStream)? = null
) {
    private fun getStorageDir(): File {
        return baseFilesDir ?: context?.filesDir ?: File("build/tmp/files")
    }

    suspend fun reconcile(): Int = withContext(Dispatchers.IO) {
        val tracksDir = File(getStorageDir(), "tracks").apply { mkdirs() }

        val animalsFromCatalog = try {
            val stream = catalogStreamProvider?.invoke() ?: context?.assets?.open("starters.json")
                ?: return@withContext 0
            val reader = InputStreamReader(stream)
            JsonParser.parseReader(reader).asJsonArray
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read starters.json from assets", e)
            return@withContext 0
        }

        var reconciledCount = 0
        val starterPackId = "starter-pack"

        for (i in 0 until animalsFromCatalog.size()) {
            val item = animalsFromCatalog.get(i).asJsonObject
            val animalId = item.get("animalId").asString
            val safeId = animalId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

            val trackFile = File(tracksDir, "${safeId}.json")
            val pointsArray = item.getAsJsonArray("points")
            // Ensure track file exists; repair if missing or zero bytes
            if (!trackFile.exists() || trackFile.length() == 0L) {
                val trackObj = JsonObject()
                trackObj.addProperty("animalId", animalId)
                trackObj.add("points", pointsArray)
                FileOutputStream(trackFile).use { fos ->
                    fos.write(trackObj.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val firstObs = pointsArray.firstOrNull()?.asJsonArray?.get(2)?.asString
            val lastObs = pointsArray.lastOrNull()?.asJsonArray?.get(2)?.asString
            val obsCount = pointsArray.size()

            val existing = animalDao.getAnimalByIdSync(animalId)
            val isUnlocked = animalDao.isAnimalUnlocked(animalId) || (existing?.isActivated == true)

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
                imagePath = existing?.imagePath,
                trackFilePath = trackFile.absolutePath,
                firstObservation = item.get("firstObservation")?.asString ?: firstObs,
                lastObservation = item.get("lastObservation")?.asString ?: lastObs,
                observationCount = item.get("observationCount")?.asInt ?: obsCount,
                distanceKm = item.get("distanceKm")?.asDouble,
                isActivated = isUnlocked, // Unlocks can never be revoked by catalog updates
                packId = existing?.packId ?: starterPackId
            )

            val codesToInsert = mutableListOf<ActivationCodeEntity>()
            val codesArray = item.getAsJsonArray("activationCodes")
            if (codesArray != null) {
                for (c in 0 until codesArray.size()) {
                    val codeStr = codesArray.get(c).asString.trim().uppercase()
                    if (codeStr.isNotEmpty()) {
                        codesToInsert.add(ActivationCodeEntity(code = codeStr, animalId = animalId))
                    }
                }
            } else {
                val codeStr = item.get("activationCode")?.asString?.trim()?.uppercase()
                if (!codeStr.isNullOrEmpty()) {
                    codesToInsert.add(ActivationCodeEntity(code = codeStr, animalId = animalId))
                }
            }

            val segment = TimelineSegmentEntity(
                segmentId = "$starterPackId:$animalId:0",
                animalId = animalId,
                timelineId = animalId,
                packId = starterPackId,
                packVersion = BUNDLED_CATALOG_VERSION,
                segmentOrder = 0,
                trackFilePath = trackFile.absolutePath,
                firstObservation = animal.firstObservation,
                lastObservation = animal.lastObservation,
                startOffsetMs = 0L,
                endOffsetMs = 0L,
                observationCount = animal.observationCount,
                replayStartAtUtcMs = null
            )

            animalDao.reconcileAnimal(animal, codesToInsert, listOf(segment))
            reconciledCount++
        }

        // Record catalog version
        animalDao.setCatalogMeta(
            CatalogMetaEntity(
                key = "starter_catalog_version",
                version = BUNDLED_CATALOG_VERSION,
                updatedAt = System.currentTimeMillis()
            )
        )

        // Ensure starter-pack descriptor is registered in packs table
        animalDao.insertPack(
            PackEntity(
                packId = starterPackId,
                title = "ZooApp Starters",
                importTimestamp = System.currentTimeMillis(),
                animalCount = reconciledCount,
                schemaVersion = 1,
                packVersion = BUNDLED_CATALOG_VERSION,
                publishedAtUtcMs = null,
                replayStartAtUtcMs = null,
                contentHash = null
            )
        )

        Log.i(TAG, "Idempotently reconciled $reconciledCount animals from bundled catalog (v$BUNDLED_CATALOG_VERSION)")
        reconciledCount
    }

    companion object {
        private const val TAG = "CatalogReconciler"
        const val BUNDLED_CATALOG_VERSION = 1
    }
}
