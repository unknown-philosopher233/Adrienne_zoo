package com.adriennezoo.companion.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

sealed class ImportResult {
    data class Success(val packTitle: String, val animalCount: Int) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

class PackImporter(
    private val context: Context?,
    private val animalDao: AnimalDao,
    private val baseFilesDir: File? = null
) {
    private val gson = Gson()

    private fun getStorageDir(): File {
        return baseFilesDir ?: context?.filesDir ?: File("build/tmp/files")
    }

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        if (context == null) return@withContext ImportResult.Error("Context is not available")
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Error("Unable to open selected file")
            importFromStream(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import pack from uri: $uri", e)
            ImportResult.Error("Failed to import pack: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun importFromStream(inputStream: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val rootDir = getStorageDir()
        val tracksDir = File(rootDir, "tracks").apply { mkdirs() }
        val imagesDir = File(rootDir, "images").apply { mkdirs() }

        val entriesMap = mutableMapOf<String, ByteArray>()
        var totalBytes = 0L
        val maxTotalBytes = 50 * 1024 * 1024L // 50MB
        var entryCount = 0
        val maxEntries = 200

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > maxEntries) {
                        return@withContext ImportResult.Error("Pack contains too many files (max $maxEntries)")
                    }

                    val name = entry.name.replace('\\', '/')
                    // Zip slip protection
                    if (name.contains("..")) {
                        return@withContext ImportResult.Error("Invalid entry name in archive: $name")
                    }

                    if (!entry.isDirectory) {
                        val baos = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var read: Int
                        var entryBytes = 0L
                        while (zis.read(buffer).also { read = it } != -1) {
                            entryBytes += read
                            totalBytes += read
                            if (totalBytes > maxTotalBytes) {
                                return@withContext ImportResult.Error("Pack exceeds maximum allowed size (50MB)")
                            }
                            baos.write(buffer, 0, read)
                        }
                        entriesMap[name] = baos.toByteArray()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return@withContext ImportResult.Error("Corrupted or invalid ZIP archive: ${e.message}")
        }

        // Find and validate manifest.json
        val manifestBytes = entriesMap["manifest.json"]
            ?: entriesMap.entries.find { it.key.endsWith("manifest.json") }?.value
            ?: return@withContext ImportResult.Error("Missing manifest.json in pack archive")

        val manifestString = manifestBytes.toString(Charsets.UTF_8)
        val manifestJson: JsonObject
        try {
            manifestJson = JsonParser.parseString(manifestString).asJsonObject
        } catch (e: Exception) {
            return@withContext ImportResult.Error("Malformed manifest.json: ${e.message}")
        }

        val format = manifestJson.get("format")?.asString
        if (format != "wildlife-pack") {
            return@withContext ImportResult.Error("Unsupported pack format: '$format'. Expected 'wildlife-pack'.")
        }

        val version = manifestJson.get("version")?.asInt ?: 1
        if (version != 1) {
            return@withContext ImportResult.Error("Unsupported pack version: $version. Expected version 1.")
        }

        val packId = manifestJson.get("packId")?.asString ?: "pack-${System.currentTimeMillis()}"
        val packTitle = manifestJson.get("title")?.asString ?: "Imported Wildlife Pack"

        val animalsArray = manifestJson.getAsJsonArray("animals")
            ?: return@withContext ImportResult.Error("manifest.json does not contain an 'animals' array")

        if (animalsArray.size() == 0) {
            return@withContext ImportResult.Error("Pack contains 0 animals")
        }

        var importedCount = 0
        val animalsToInsert = mutableListOf<AnimalEntity>()
        val codesToInsert = mutableListOf<ActivationCodeEntity>()

        for (i in 0 until animalsArray.size()) {
            val itemObj = animalsArray.get(i).asJsonObject
            val animalId = itemObj.get("animalId")?.asString
                ?: return@withContext ImportResult.Error("Animal entry $i is missing animalId")

            val profilePath = itemObj.get("profile")?.asString ?: "animals/${animalId}.json"
            val trackPath = itemObj.get("track")?.asString ?: "tracks/${animalId}.json"

            // Get profile bytes
            val profileBytes = entriesMap[profilePath]
                ?: entriesMap.entries.find { it.key.endsWith(File(profilePath).name) }?.value
                ?: return@withContext ImportResult.Error("Missing profile file '$profilePath' for animal '$animalId'")

            // Get track bytes
            val trackBytes = entriesMap[trackPath]
                ?: entriesMap.entries.find { it.key.endsWith(File(trackPath).name) }?.value
                ?: return@withContext ImportResult.Error("Missing track file '$trackPath' for animal '$animalId'")

            val profileJson = try {
                JsonParser.parseString(profileBytes.toString(Charsets.UTF_8)).asJsonObject
            } catch (e: Exception) {
                return@withContext ImportResult.Error("Failed to parse profile JSON for $animalId: ${e.message}")
            }

            val trackJson = try {
                JsonParser.parseString(trackBytes.toString(Charsets.UTF_8)).asJsonObject
            } catch (e: Exception) {
                return@withContext ImportResult.Error("Failed to parse track JSON for $animalId: ${e.message}")
            }

            val pointsArray = trackJson.getAsJsonArray("points")
                ?: return@withContext ImportResult.Error("Track JSON for $animalId has no 'points' array")

            if (pointsArray.size() == 0) {
                return@withContext ImportResult.Error("Track JSON for $animalId has empty points array")
            }

            val safeId = animalId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

            // Write track to app internal storage
            val trackFile = File(tracksDir, "${safeId}.json")
            FileOutputStream(trackFile).use { it.write(trackBytes) }

            // Handle optional image
            var localImagePath: String? = null
            val imgRef = profileJson.get("image")?.asString
            if (!imgRef.isNullOrBlank()) {
                val imgBytes = entriesMap[imgRef]
                    ?: entriesMap.entries.find { it.key.endsWith(File(imgRef).name) }?.value
                if (imgBytes != null) {
                    val ext = if (imgRef.contains('.')) imgRef.substringAfterLast('.') else "png"
                    val imgFile = File(imagesDir, "${safeId}.${ext}")
                    FileOutputStream(imgFile).use { it.write(imgBytes) }
                    localImagePath = imgFile.absolutePath
                }
            }

            // Calculate track observations
            val firstObs = try {
                pointsArray.get(0).asJsonArray.get(2).asString
            } catch (_: Exception) {
                null
            }
            val lastObs = try {
                pointsArray.get(pointsArray.size() - 1).asJsonArray.get(2).asString
            } catch (_: Exception) {
                null
            }
            val count = pointsArray.size()

            val species = profileJson.get("species")?.asString ?: "Unknown Species"
            val scientificName = profileJson.get("scientificName")?.asString
            val researchId = profileJson.get("researchId")?.asString ?: safeId
            val sourceStudy = profileJson.get("sourceStudy")?.asString ?: "Field Research"
            val license = profileJson.get("license")?.asString ?: "Open Data"
            val source = profileJson.get("source")?.asString ?: "Migration Watch"
            val displayName = profileJson.get("displayName")?.asString ?: species
            val description = profileJson.get("description")?.asString ?: ""
            val intro = profileJson.get("intro")?.asString ?: ""
            val accent = profileJson.get("accent")?.asString ?: "#27382F"
            val distanceKm = profileJson.get("distanceKm")?.asDouble

            // Check if existing animal in DB to preserve activation state
            val existing = animalDao.getAnimalByIdSync(animalId)
            val isActivated = existing?.isActivated ?: false

            val entity = AnimalEntity(
                animalId = animalId,
                species = species,
                scientificName = scientificName,
                researchId = researchId,
                sourceStudy = sourceStudy,
                license = license,
                source = source,
                displayName = displayName,
                description = description,
                intro = intro,
                accent = accent,
                imagePath = localImagePath ?: existing?.imagePath,
                trackFilePath = trackFile.absolutePath,
                firstObservation = firstObs,
                lastObservation = lastObs,
                observationCount = count,
                distanceKm = distanceKm ?: existing?.distanceKm,
                isActivated = isActivated,
                packId = packId
            )
            animalsToInsert.add(entity)

            // Process activation codes
            val codesArray = itemObj.getAsJsonArray("activationCodes")
            if (codesArray != null) {
                for (c in 0 until codesArray.size()) {
                    val codeStr = codesArray.get(c).asString.trim().uppercase()
                    if (codeStr.isNotEmpty()) {
                        codesToInsert.add(ActivationCodeEntity(code = codeStr, animalId = animalId))
                    }
                }
            }

            importedCount++
        }

        // Insert into database
        animalDao.insertAnimals(animalsToInsert)
        if (codesToInsert.isNotEmpty()) {
            animalDao.insertActivationCodes(codesToInsert)
        }
        animalDao.insertPack(
            PackEntity(
                packId = packId,
                title = packTitle,
                importTimestamp = System.currentTimeMillis(),
                animalCount = importedCount
            )
        )

        Log.i(TAG, "Successfully imported pack '$packTitle' with $importedCount animals")
        ImportResult.Success(packTitle, importedCount)
    }

    companion object {
        private const val TAG = "PackImporter"
    }
}
