package com.adriennezoo.companion.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.adriennezoo.companion.replay.ReplayTimeContract
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
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

        val contentHash = computeArchiveContentHash(entriesMap)

        // Find and validate manifest.json
        val manifestBytes = entriesMap["manifest.json"]
            ?: entriesMap.entries.find { it.key.endsWith("manifest.json") }?.value
            ?: return@withContext ImportResult.Error("Missing manifest.json in pack archive")

        val manifestJson: JsonObject = try {
            val manifestString = manifestBytes.toString(Charsets.UTF_8)
            JsonParser.parseString(manifestString).asJsonObject
        } catch (e: Exception) {
            return@withContext ImportResult.Error("Malformed manifest.json: ${e.message}")
        }

        val format = manifestJson.get("format")?.asString
        if (format != "wildlife-pack") {
            return@withContext ImportResult.Error("Unsupported pack format: '$format'. Expected 'wildlife-pack'.")
        }

        val schemaVersion = parsePositiveInt(manifestJson.get("version"))
            ?: return@withContext ImportResult.Error("Missing or invalid pack schema version.")
        if (schemaVersion !in 1..2) {
            return@withContext ImportResult.Error("Unsupported pack schema version: $schemaVersion. Supported versions: 1, 2.")
        }

        val rawPackId = manifestJson.get("packId")?.asString?.trim()
        val packId = if (schemaVersion >= 2) {
            if (rawPackId.isNullOrBlank()) {
                return@withContext ImportResult.Error("Missing required 'packId' in version 2 pack manifest.")
            }
            rawPackId
        } else {
            if (rawPackId.isNullOrBlank()) "pack-${System.currentTimeMillis()}" else rawPackId
        }

        val packTitle = manifestJson.get("title")?.asString?.takeIf { it.isNotBlank() }
            ?: "Imported Wildlife Pack"

        val packVersion = if (schemaVersion >= 2) {
            parsePositiveInt(manifestJson.get("packVersion"))
                ?: return@withContext ImportResult.Error("Missing or invalid 'packVersion' in version 2 pack manifest.")
        } else {
            parsePositiveInt(manifestJson.get("packVersion")) ?: 1
        }

        // Validate timing fields according to schema version
        val replayEnabledFlag = manifestJson.get("replayEnabled")?.let {
            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
        } ?: false

        val rawPublishedAt = manifestJson.get("publishedAt")?.let { elementToString(it) }
        val publishedAtUtcMs: Long? = if (rawPublishedAt != null) {
            ReplayTimeContract.parseUtcTimestampMillis(rawPublishedAt)
                ?: return@withContext ImportResult.Error("Invalid 'publishedAt' UTC timestamp: '$rawPublishedAt'")
        } else if (schemaVersion >= 2) {
            return@withContext ImportResult.Error("Missing required 'publishedAt' UTC timestamp in version 2 pack.")
        } else {
            null
        }

        val rawReplayStartAt = manifestJson.get("replayStartAt")?.let { elementToString(it) }
        val replayStartAtUtcMs: Long? = if (rawReplayStartAt != null) {
            ReplayTimeContract.parseUtcTimestampMillis(rawReplayStartAt)
                ?: return@withContext ImportResult.Error("Invalid 'replayStartAt' UTC timestamp: '$rawReplayStartAt'")
        } else if (schemaVersion >= 2 || replayEnabledFlag) {
            return@withContext ImportResult.Error("Missing required 'replayStartAt' UTC timestamp for replay-enabled pack.")
        } else {
            null
        }

        // Verify package identity & immutability rules
        val existingPack = animalDao.getPackByIdSync(packId)
        if (existingPack != null) {
            if (packVersion < existingPack.packVersion) {
                return@withContext ImportResult.Error(
                    "Cannot downgrade pack '$packId' from version ${existingPack.packVersion} to $packVersion."
                )
            }
            if (packVersion == existingPack.packVersion &&
                existingPack.contentHash != null &&
                existingPack.contentHash != contentHash
            ) {
                return@withContext ImportResult.Error(
                    "Pack '$packId' version $packVersion is already installed with different contents. Bump packVersion for updated releases."
                )
            }
        }

        val animalsArray = manifestJson.getAsJsonArray("animals")
            ?: return@withContext ImportResult.Error("manifest.json does not contain an 'animals' array")

        if (animalsArray.size() == 0) {
            return@withContext ImportResult.Error("Pack contains 0 animals")
        }

        var importedCount = 0
        val animalsToInsert = mutableListOf<AnimalEntity>()
        val codesToInsert = mutableListOf<ActivationCodeEntity>()
        val segmentsToInsert = mutableListOf<TimelineSegmentEntity>()
        val claimedCodesInThisPack = mutableMapOf<String, String>() // code -> animalId
        val seenAnimalIdsInPack = mutableSetOf<String>()

        for (i in 0 until animalsArray.size()) {
            val itemElement = animalsArray.get(i)
            if (!itemElement.isJsonObject) continue
            val itemObj = itemElement.asJsonObject

            val animalId = itemObj.get("animalId")?.asString?.trim()
            if (animalId.isNullOrBlank()) {
                return@withContext ImportResult.Error("Animal entry $i is missing animalId")
            }
            if (!seenAnimalIdsInPack.add(animalId)) {
                return@withContext ImportResult.Error("Duplicate animalId '$animalId' in pack manifest")
            }

            val profilePath = itemObj.get("profile")?.asString ?: "animals/${animalId}.json"
            val trackPath = itemObj.get("track")?.asString ?: "tracks/${animalId}.json"

            // Get profile bytes
            val profileBytes = findArchiveEntry(entriesMap, profilePath)
                ?: return@withContext ImportResult.Error("Missing profile file '$profilePath' for animal '$animalId'")

            // Get track bytes
            val trackBytes = findArchiveEntry(entriesMap, trackPath)
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

            // Validate optional journal reference if declared
            val journalPath = itemObj.get("journal")?.asString ?: profileJson.get("journal")?.asString
            if (!journalPath.isNullOrBlank()) {
                val journalBytes = findArchiveEntry(entriesMap, journalPath)
                    ?: return@withContext ImportResult.Error("Missing referenced journal file '$journalPath' for animal '$animalId'")
                try {
                    JsonParser.parseString(journalBytes.toString(Charsets.UTF_8))
                } catch (e: Exception) {
                    return@withContext ImportResult.Error("Malformed journal JSON '$journalPath' for $animalId: ${e.message}")
                }
            }

            val pointsArray = trackJson.getAsJsonArray("points")
                ?: trackJson.getAsJsonArray("observations")
                ?: return@withContext ImportResult.Error("Track JSON for $animalId has no 'points' or 'observations' array")

            if (pointsArray.size() == 0) {
                return@withContext ImportResult.Error("Track JSON for $animalId has empty points array")
            }

            // Validate coordinates & chronology
            val validPoints = JsonArray()
            var firstValidObs: String? = null
            var lastValidObs: String? = null
            var prevTimestampMs: Long? = null
            var prevOffsetMs: Long? = null
            var firstOffsetMs = 0L
            var lastOffsetMs = 0L
            var hasSetFirstOffset = false

            for (pIdx in 0 until pointsArray.size()) {
                val ptElem = pointsArray.get(pIdx)
                val parsed = parseObservationElement(ptElem)
                if (parsed == null) {
                    if (schemaVersion >= 2) {
                        return@withContext ImportResult.Error("Invalid observation format at index $pIdx for $animalId")
                    }
                    continue
                }

                val (lon, lat, origTsRaw, origTsMs, explicitOffsetMs) = parsed
                val latInvalid = lat.isNaN() || lat < -90.0 || lat > 90.0
                val lonInvalid = lon.isNaN() ||
                    (if (schemaVersion >= 2) (lon < -180.0 || lon > 180.0) else (lon < -360.0 || lon > 360.0))

                if (latInvalid || lonInvalid) {
                    if (schemaVersion >= 2) {
                        return@withContext ImportResult.Error(
                            "Observation $pIdx for $animalId has out-of-bounds coordinates ($lon, $lat)"
                        )
                    }
                    continue
                }

                if (schemaVersion >= 2) {
                    if (origTsMs == null) {
                        return@withContext ImportResult.Error(
                            "Observation $pIdx for $animalId is missing a valid observation timestamp"
                        )
                    }
                    if (prevTimestampMs != null && origTsMs < prevTimestampMs) {
                        return@withContext ImportResult.Error(
                            "Track chronology error for $animalId at index $pIdx: timestamp precedes previous observation"
                        )
                    }
                    prevTimestampMs = origTsMs

                    val resolvedOffset = explicitOffsetMs ?: (origTsMs - (firstObsMsFrom(validPoints) ?: origTsMs))
                    if (resolvedOffset < 0L) {
                        return@withContext ImportResult.Error(
                            "Negative replayOffsetMs at index $pIdx for $animalId"
                        )
                    }
                    if (prevOffsetMs != null && resolvedOffset < prevOffsetMs) {
                        return@withContext ImportResult.Error(
                            "Replay offset chronology error for $animalId at index $pIdx"
                        )
                    }
                    prevOffsetMs = resolvedOffset
                    if (!hasSetFirstOffset) {
                        firstOffsetMs = resolvedOffset
                        hasSetFirstOffset = true
                    }
                    lastOffsetMs = resolvedOffset
                } else {
                    if (origTsMs != null && prevTimestampMs == null) {
                        prevTimestampMs = origTsMs
                    }
                    if (origTsMs != null && prevTimestampMs != null) {
                        lastOffsetMs = maxOf(0L, origTsMs - prevTimestampMs)
                    }
                }

                if (firstValidObs == null && origTsRaw != null) firstValidObs = origTsRaw
                if (origTsRaw != null) lastValidObs = origTsRaw

                val normalizedPoint = JsonArray()
                normalizedPoint.add(lon)
                normalizedPoint.add(lat)
                if (origTsRaw != null) {
                    val asLong = origTsRaw.toLongOrNull()
                    if (asLong != null) normalizedPoint.add(asLong) else normalizedPoint.add(origTsRaw)
                }
                if (explicitOffsetMs != null) {
                    normalizedPoint.add(explicitOffsetMs)
                }
                validPoints.add(normalizedPoint)
            }

            if (validPoints.size() == 0) {
                return@withContext ImportResult.Error("Track JSON for $animalId contains no valid coordinate points")
            }

            val safeId = animalId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val timelineId = itemObj.get("timelineId")?.asString?.takeIf { it.isNotBlank() } ?: animalId
            val segmentOrder = itemObj.get("segmentOrder")?.asInt ?: 0
            val segmentId = itemObj.get("segmentId")?.asString?.takeIf { it.isNotBlank() }
                ?: "$packId:$animalId:$segmentOrder"

            // Write validated track to app internal storage without colliding across segments
            val trackFileName = if (segmentOrder == 0) "${safeId}.json" else "${safeId}_seg${segmentOrder}.json"
            val trackFile = File(tracksDir, trackFileName)
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
                val imgBytes = findArchiveEntry(entriesMap, imgRef)
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

            segmentsToInsert.add(
                TimelineSegmentEntity(
                    segmentId = segmentId,
                    animalId = animalId,
                    timelineId = timelineId,
                    packId = packId,
                    packVersion = packVersion,
                    segmentOrder = segmentOrder,
                    trackFilePath = trackFile.absolutePath,
                    firstObservation = firstValidObs,
                    lastObservation = lastValidObs,
                    startOffsetMs = firstOffsetMs,
                    endOffsetMs = lastOffsetMs,
                    observationCount = validPoints.size(),
                    replayStartAtUtcMs = replayStartAtUtcMs
                )
            )

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
                animalCount = importedCount,
                schemaVersion = schemaVersion,
                packVersion = packVersion,
                publishedAtUtcMs = publishedAtUtcMs,
                replayStartAtUtcMs = replayStartAtUtcMs,
                contentHash = contentHash
            ),
            animals = animalsToInsert,
            codes = codesToInsert,
            segments = segmentsToInsert
        )

        Log.i(TAG, "Successfully imported pack '$packTitle' (v$packVersion, schema v$schemaVersion) with $importedCount animals")
        ImportResult.Success(packTitle, importedCount)
    }

    private data class ParsedObservation(
        val lon: Double,
        val lat: Double,
        val rawTimestamp: String?,
        val timestampMs: Long?,
        val explicitOffsetMs: Long?
    )

    private fun parseObservationElement(elem: JsonElement): ParsedObservation? {
        if (elem.isJsonArray) {
            val pt = elem.asJsonArray
            if (pt.size() < 2) return null
            val lon = try { pt.get(0).asDouble } catch (_: Exception) { return null }
            val lat = try { pt.get(1).asDouble } catch (_: Exception) { return null }
            val rawTs = if (pt.size() >= 3) elementToString(pt.get(2)) else null
            val tsMs = ReplayTimeContract.parseUtcTimestampMillis(rawTs)
            val offsetMs = if (pt.size() >= 4) {
                try { pt.get(3).asLong } catch (_: Exception) { null }
            } else null
            return ParsedObservation(lon, lat, rawTs, tsMs, offsetMs)
        } else if (elem.isJsonObject) {
            val obj = elem.asJsonObject
            val lon = try { obj.get("lon")?.asDouble ?: obj.get("longitude")?.asDouble } catch (_: Exception) { null }
                ?: return null
            val lat = try { obj.get("lat")?.asDouble ?: obj.get("latitude")?.asDouble } catch (_: Exception) { null }
                ?: return null
            val rawTs = elementToString(obj.get("timestamp") ?: obj.get("observedAt"))
            val tsMs = ReplayTimeContract.parseUtcTimestampMillis(rawTs)
            val offsetMs = try { obj.get("replayOffsetMs")?.asLong } catch (_: Exception) { null }
            return ParsedObservation(lon, lat, rawTs, tsMs, offsetMs)
        }
        return null
    }

    private fun firstObsMsFrom(points: JsonArray): Long? {
        if (points.size() == 0) return null
        val first = points.get(0).asJsonArray
        if (first.size() < 3) return null
        return ReplayTimeContract.parseUtcTimestampMillis(elementToString(first.get(2)))
    }

    private fun findArchiveEntry(entriesMap: Map<String, ByteArray>, relativePath: String): ByteArray? {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        return entriesMap[normalized]
            ?: entriesMap.entries.find { it.key.endsWith("/$normalized") }?.value
    }

    private fun parsePositiveInt(element: JsonElement?): Int? {
        if (element == null || !element.isJsonPrimitive) return null
        val prim = element.asJsonPrimitive
        if (!prim.isNumber) return null
        return try {
            val value = prim.asInt
            if (value >= 1) value else null
        } catch (_: Exception) {
            null
        }
    }

    private fun elementToString(element: JsonElement?): String? {
        if (element == null || !element.isJsonPrimitive) return null
        val prim = element.asJsonPrimitive
        return when {
            prim.isNumber -> prim.asLong.toString()
            prim.isString -> prim.asString
            else -> null
        }
    }

    private fun computeArchiveContentHash(entriesMap: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entriesMap.keys.sorted().forEach { key ->
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(entriesMap.getValue(key))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "PackImporter"
    }
}
