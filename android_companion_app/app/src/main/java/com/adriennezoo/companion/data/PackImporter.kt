package com.adriennezoo.companion.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
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
                        return@withContext ImportResult.Error("Pack contains too many files (maximum $maxEntries)")
                    }

                    val name = entry.name.replace('\\', '/')
                    // Zip slip and unsafe path protection
                    if (name.contains("..") || name.startsWith("/") || name.contains(":/")) {
                        return@withContext ImportResult.Error("Unsafe or invalid entry name in archive: $name")
                    }

                    if (!entry.isDirectory) {
                        val baos = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            totalBytes += read
                            if (totalBytes > maxTotalBytes) {
                                return@withContext ImportResult.Error("Pack uncompressed size exceeds maximum allowed (50MB)")
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

        val manifestJson: JsonObject
        try {
            val manifestString = manifestBytes.toString(Charsets.UTF_8)
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
        val claimedCodesInThisPack = mutableMapOf<String, String>() // code -> animalId

        for (i in 0 until animalsArray.size()) {
            val itemElement = animalsArray.get(i)
            if (!itemElement.isJsonObject) continue
            val itemObj = itemElement.asJsonObject

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

            // Validate coordinates: [lon, lat, timestamp]
            val validPoints = JsonArray()
            var firstValidObs: String? = null
            var lastValidObs: String? = null

            for (pIdx in 0 until pointsArray.size()) {
                val ptElem = pointsArray.get(pIdx)
                if (!ptElem.isJsonArray) continue
                val pt = ptElem.asJsonArray
                if (pt.size() < 2) continue

                val lon = try { pt.get(0).asDouble } catch (_: Exception) { Double.NaN }
                val lat = try { pt.get(1).asDouble } catch (_: Exception) { Double.NaN }

                if (lon.isNaN() || lat.isNaN()) continue
                if (lat < -90.0 || lat > 90.0) continue
                // Longitude between -180 and 180 (allow -360..360 for world wrap)
                if (lon < -360.0 || lon > 360.0) continue

                val ts = if (pt.size() >= 3) {
                    val tsEl = pt.get(2)
                    if (tsEl.isJsonPrimitive) {
                        val prim = tsEl.asJsonPrimitive
                        if (prim.isNumber) prim.asLong.toString() else prim.asString
                    } else null
                } else null

                if (firstValidObs == null && ts != null) firstValidObs = ts
                if (ts != null) lastValidObs = ts

                validPoints.add(pt)
            }

            if (validPoints.size() == 0) {
                return@withContext ImportResult.Error("Track JSON for $animalId contains no valid coordinate points")
            }

            val safeId = animalId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

            // Write validated track to app internal storage
            val trackFile = File(tracksDir, "${safeId}.json")
            val sanitizedTrackObj = JsonObject()
            sanitizedTrackObj.addProperty("animalId", animalId)
            sanitizedTrackObj.add("points", validPoints)
            FileOutputStream(trackFile).use {
                it.write(sanitizedTrackObj.toString().toByteArray(Charsets.UTF_8))
            }

            // Handle optional image
            var localImagePath: String? = null
            val imgRef = profileJson.get("image")?.asString
            if (!imgRef.isNullOrBlank()) {
                val imgBytes = entriesMap[imgRef]
                    ?: entriesMap.entries.find { it.key.endsWith(File(imgRef).name) }?.value
                if (imgBytes != null) {
                    val rawExt = if (imgRef.contains('.')) imgRef.substringAfterLast('.').lowercase() else "png"
                    val ext = when (rawExt) {
                        "jpg", "jpeg" -> "jpg"
                        "webp" -> "webp"
                        "svg" -> "svg"
                        else -> "png"
                    }
                    val imgFile = File(imagesDir, "${safeId}.${ext}")
                    if (imgFile.canonicalPath.startsWith(imagesDir.canonicalPath)) {
                        FileOutputStream(imgFile).use { it.write(imgBytes) }
                        localImagePath = imgFile.absolutePath
                    }
                }
            }

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
            val isActivated = animalDao.isAnimalUnlocked(animalId) || (existing?.isActivated == true)

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
                firstObservation = firstValidObs ?: existing?.firstObservation,
                lastObservation = lastValidObs ?: existing?.lastObservation,
                observationCount = validPoints.size(),
                distanceKm = distanceKm ?: existing?.distanceKm,
                isActivated = isActivated,
                packId = packId
            )
            animalsToInsert.add(entity)

            // Process activation codes deterministically with fallbacks
            val codesList = mutableListOf<String>()
            val codesArray = itemObj.getAsJsonArray("activationCodes")
                ?: profileJson.getAsJsonArray("activationCodes")
            if (codesArray != null) {
                for (c in 0 until codesArray.size()) {
                    val codeStr = codesArray.get(c).asString.trim().uppercase()
                    if (codeStr.isNotEmpty()) codesList.add(codeStr)
                }
            }
            val singleCode = itemObj.get("activationCode")?.asString
                ?: profileJson.get("activationCode")?.asString
            if (!singleCode.isNullOrBlank()) {
                val codeStr = singleCode.trim().uppercase()
                if (codeStr.isNotEmpty()) codesList.add(codeStr)
            }

            for (codeStr in codesList.distinct()) {
                // Check duplicate mapping: does this code belong to a different animal?
                val existingOwner = animalDao.getAnimalIdByCode(codeStr) ?: claimedCodesInThisPack[codeStr]
                if (existingOwner != null && existingOwner != animalId) {
                    Log.w(TAG, "Code $codeStr is already assigned to $existingOwner, ignoring for $animalId")
                    continue
                }
                claimedCodesInThisPack[codeStr] = animalId
                codesToInsert.add(ActivationCodeEntity(code = codeStr, animalId = animalId))
            }

            importedCount++
        }

        // Insert into database atomically inside a Room transaction
        animalDao.insertPackWithAnimals(
            pack = PackEntity(
                packId = packId,
                title = packTitle,
                importTimestamp = System.currentTimeMillis(),
                animalCount = importedCount
            ),
            animals = animalsToInsert,
            codes = codesToInsert
        )

        Log.i(TAG, "Successfully imported pack '$packTitle' with $importedCount animals")
        ImportResult.Success(packTitle, importedCount)
    }

    companion object {
        private const val TAG = "PackImporter"
    }
}
