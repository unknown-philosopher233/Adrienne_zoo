package com.adriennezoo.companion

import com.adriennezoo.companion.data.ActivationCodeEntity
import com.adriennezoo.companion.data.AnimalDao
import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.data.CatalogMetaEntity
import com.adriennezoo.companion.data.ImportResult
import com.adriennezoo.companion.data.PackEntity
import com.adriennezoo.companion.data.PackImporter
import com.adriennezoo.companion.data.TimelineSegmentEntity
import com.adriennezoo.companion.data.UnlockedAnimalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FakeAnimalDao : AnimalDao {
    val animals = mutableMapOf<String, AnimalEntity>()
    val codes = mutableMapOf<String, String>()
    val packs = mutableListOf<PackEntity>()
    val unlocks = mutableMapOf<String, UnlockedAnimalEntity>()
    val catalogMeta = mutableMapOf<String, CatalogMetaEntity>()
    val segments = mutableMapOf<String, TimelineSegmentEntity>()

    override fun getAllAnimals(): Flow<List<AnimalEntity>> = flowOf(animals.values.toList())
    override fun getActivatedAnimals(): Flow<List<AnimalEntity>> = flowOf(animals.values.filter { it.isActivated })
    override fun getAnimalById(id: String): Flow<AnimalEntity?> = flowOf(animals[id])
    override suspend fun getAnimalByIdSync(id: String): AnimalEntity? = animals[id]
    override suspend fun getAnimalByCode(code: String): AnimalEntity? {
        val id = codes[code.trim().uppercase()] ?: return null
        return animals[id]
    }
    override suspend fun getAnimalIdByCode(code: String): String? = codes[code.trim().uppercase()]
    override suspend fun insertAnimal(animal: AnimalEntity) { animals[animal.animalId] = animal }
    override suspend fun insertAnimals(animalsToInsert: List<AnimalEntity>) {
        animalsToInsert.forEach { animals[it.animalId] = it }
    }
    override suspend fun markAsActivated(animalId: String) {
        animals[animalId]?.let { animals[animalId] = it.copy(isActivated = true) }
    }
    override suspend fun getAnimalCount(): Int = animals.size
    override suspend fun insertActivationCodes(codesToInsert: List<ActivationCodeEntity>) {
        codesToInsert.forEach { codes[it.code.trim().uppercase()] = it.animalId }
    }
    override suspend fun getCodesForAnimal(animalId: String): List<String> =
        codes.filter { it.value == animalId }.map { it.key }
    override suspend fun insertPack(pack: PackEntity) {
        packs.removeAll { it.packId == pack.packId }
        packs.add(pack)
    }
    override suspend fun getPackByIdSync(packId: String): PackEntity? =
        packs.find { it.packId == packId }
    override fun getAllPacks(): Flow<List<PackEntity>> = flowOf(packs)

    override suspend fun insertTimelineSegments(segmentsToInsert: List<TimelineSegmentEntity>) {
        segmentsToInsert.forEach { segments[it.segmentId] = it }
    }
    override suspend fun getTimelineSegmentsForAnimal(animalId: String): List<TimelineSegmentEntity> =
        segments.values.filter { it.animalId == animalId }.sortedBy { it.segmentOrder }
    override fun observeTimelineSegmentsForAnimal(animalId: String): Flow<List<TimelineSegmentEntity>> =
        flowOf(segments.values.filter { it.animalId == animalId }.sortedBy { it.segmentOrder })

    override suspend fun insertUnlock(unlock: UnlockedAnimalEntity) {
        unlocks.putIfAbsent(unlock.animalId, unlock)
    }
    override suspend fun getUnlock(animalId: String): UnlockedAnimalEntity? = unlocks[animalId]
    override suspend fun getAllUnlockedAnimalIds(): List<String> = unlocks.keys.toList()
    override suspend fun isAnimalUnlocked(animalId: String): Boolean =
        unlocks.containsKey(animalId) || animals[animalId]?.isActivated == true

    override suspend fun setCatalogMeta(meta: CatalogMetaEntity) {
        catalogMeta[meta.key] = meta
    }
    override suspend fun getCatalogMeta(key: String): CatalogMetaEntity? = catalogMeta[key]
}

class PackImporterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeDao: FakeAnimalDao
    private lateinit var testStorageDir: File
    private lateinit var importer: PackImporter

    @Before
    fun setUp() {
        fakeDao = FakeAnimalDao()
        testStorageDir = tempFolder.newFolder("storage")
        importer = PackImporter(
            context = null,
            animalDao = fakeDao,
            baseFilesDir = testStorageDir
        )
    }

    private fun createValidZipStream(): ByteArrayInputStream {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // manifest.json
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = """
            {
              "format": "wildlife-pack",
              "version": 1,
              "packId": "test-pack-1",
              "title": "Test Wildlife Collection",
              "animals": [
                {
                  "animalId": "blue_whale:test01",
                  "profile": "animals/whale.json",
                  "track": "tracks/whale.json",
                  "activationCodes": ["WILD-TEST-1234"]
                }
              ]
            }
            """.trimIndent()
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // animals/whale.json
            zos.putNextEntry(ZipEntry("animals/whale.json"))
            val profile = """
            {
              "animalId": "blue_whale:test01",
              "species": "Blue Whale",
              "scientificName": "Balaenoptera musculus",
              "researchId": "test01",
              "sourceStudy": "North Pacific Study",
              "license": "CC-BY",
              "source": "Movebank",
              "displayName": "Echo",
              "description": "A graceful whale.",
              "intro": "Hello from Echo!",
              "accent": "#2f5d6e",
              "distanceKm": 1200.0
            }
            """.trimIndent()
            zos.write(profile.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // tracks/whale.json
            zos.putNextEntry(ZipEntry("tracks/whale.json"))
            val track = """
            {
              "animalId": "blue_whale:test01",
              "points": [
                [-120.5, 34.2, 1217029500000],
                [-120.8, 34.5, 1217036700000]
              ]
            }
            """.trimIndent()
            zos.write(track.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return ByteArrayInputStream(baos.toByteArray())
    }

    @Test
    fun testValidPackImport() = runBlocking {
        val stream = createValidZipStream()
        val result = importer.importFromStream(stream)

        assertTrue("Expected Success but got $result", result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals("Test Wildlife Collection", success.packTitle)
        assertEquals(1, success.animalCount)

        val animal = fakeDao.animals["blue_whale:test01"]
        assertNotNull("Animal should be inserted into DB", animal)
        assertEquals("Echo", animal?.displayName)
        assertEquals("Blue Whale", animal?.species)
        assertEquals(false, animal?.isActivated)
        assertEquals(2, animal?.observationCount)

        // Verify activation code
        val lookup = fakeDao.getAnimalByCode("WILD-TEST-1234")
        assertNotNull(lookup)
        assertEquals("blue_whale:test01", lookup?.animalId)

        // Case insensitivity
        val lowercaseLookup = fakeDao.getAnimalByCode("wild-test-1234")
        assertNotNull(lowercaseLookup)
    }

    @Test
    fun testReimportPreservesActivationState() = runBlocking {
        // First import
        importer.importFromStream(createValidZipStream())
        fakeDao.markAsActivated("blue_whale:test01")
        assertEquals(true, fakeDao.animals["blue_whale:test01"]?.isActivated)

        // Re-import same pack (updated metadata)
        val reimportResult = importer.importFromStream(createValidZipStream())
        assertTrue(reimportResult is ImportResult.Success)

        // Animal must remain activated!
        val animalAfter = fakeDao.animals["blue_whale:test01"]
        assertNotNull(animalAfter)
        assertEquals("Animal must remain activated after reimport", true, animalAfter?.isActivated)
    }

    @Test
    fun testDuplicateCodeCollisionHandledDeterministically() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = """
            {
              "format": "wildlife-pack",
              "version": 1,
              "packId": "collision-pack",
              "title": "Collision Test",
              "animals": [
                {
                  "animalId": "study:first",
                  "profile": "animals/first.json",
                  "track": "tracks/first.json",
                  "activationCodes": ["WILD-SHARED-CODE"]
                },
                {
                  "animalId": "study:second",
                  "profile": "animals/second.json",
                  "track": "tracks/second.json",
                  "activationCodes": ["WILD-SHARED-CODE"]
                }
              ]
            }
            """.trimIndent()
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            listOf("first", "second").forEach { id ->
                zos.putNextEntry(ZipEntry("animals/$id.json"))
                zos.write("""{"displayName":"Animal $id","species":"Species $id"}""".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("tracks/$id.json"))
                zos.write("""{"animalId":"study:$id","points":[[-120.0, 34.0, 1000]]}""".toByteArray())
                zos.closeEntry()
            }
        }

        val result = importer.importFromStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(result is ImportResult.Success)

        // The code should belong to the first animal, not overwritten by second
        val owner = fakeDao.getAnimalByCode("WILD-SHARED-CODE")
        assertEquals("study:first", owner?.animalId)
    }

    @Test
    fun testInvalidCoordinatesFilteredOut() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = """
            {
              "format": "wildlife-pack",
              "version": 1,
              "packId": "coord-pack",
              "title": "Coord Test",
              "animals": [
                {
                  "animalId": "study:coord",
                  "profile": "animals/coord.json",
                  "track": "tracks/coord.json",
                  "activationCodes": ["WILD-COORD-CODE"]
                }
              ]
            }
            """.trimIndent()
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("animals/coord.json"))
            zos.write("""{"displayName":"Coord Animal","species":"Test"}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("tracks/coord.json"))
            // Point 1: valid, Point 2: invalid latitude (150 > 90), Point 3: valid
            val track = """
            {
              "animalId": "study:coord",
              "points": [
                [-120.0, 34.0, 1000],
                [-120.0, 150.0, 2000],
                [-121.0, 35.0, 3000]
              ]
            }
            """.trimIndent()
            zos.write(track.toByteArray())
            zos.closeEntry()
        }

        val result = importer.importFromStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(result is ImportResult.Success)

        val animal = fakeDao.animals["study:coord"]
        assertNotNull(animal)
        // Only 2 valid points should be kept
        assertEquals(2, animal?.observationCount)
    }

    @Test
    fun testMissingManifestRejected() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("random.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val result = importer.importFromStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(result is ImportResult.Error)
        assertTrue((result as ImportResult.Error).message.contains("Missing manifest.json"))
    }

    @Test
    fun testZipSlipProtection() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../malicious.txt"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
        }
        val result = importer.importFromStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(result is ImportResult.Error)
        assertTrue((result as ImportResult.Error).message.contains("Unsafe or invalid entry name"))
    }

    @Test
    fun testSingularActivationCodeSupported() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = """
            {
              "format": "wildlife-pack",
              "version": 1,
              "packId": "singular-code-pack",
              "title": "Singular Code Test",
              "animals": [
                {
                  "animalId": "study:singular",
                  "profile": "animals/singular.json",
                  "track": "tracks/singular.json",
                  "activationCode": "WILD-SNGL-9999"
                }
              ]
            }
            """.trimIndent()
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("animals/singular.json"))
            zos.write("""{"displayName":"Singular Animal","species":"Test"}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("tracks/singular.json"))
            zos.write("""{"animalId":"study:singular","points":[[-120.0, 34.0, 1000]]}""".toByteArray())
            zos.closeEntry()
        }

        val result = importer.importFromStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(result is ImportResult.Success)

        val owner = fakeDao.getAnimalByCode("WILD-SNGL-9999")
        assertEquals("study:singular", owner?.animalId)
    }

    @Test
    fun testProfileLevelActivationCodeFallback() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = """
            {
              "format": "wildlife-pack",
              "version": 1,
              "packId": "profile-code-pack",
              "title": "Profile Code Test",
              "animals": [
                {
                  "animalId": "study:profcode",
                  "profile": "animals/profcode.json",
                  "track": "tracks/profcode.json"
                }
              ]
            }
            """.trimIndent()
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("animals/profcode.json"))
            zos.write("""{"displayName":"ProfCode Animal","species":"Test","activationCodes":["WILD-PROF-7777"]}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("tracks/profcode.json"))
            zos.write("""{"animalId":"study:profcode","points":[[-120.0, 34.0, 1000]]}""".toByteArray())
            zos.closeEntry()
        }

        val result = importer.importFromStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(result is ImportResult.Success)

        val owner = fakeDao.getAnimalByCode("WILD-PROF-7777")
        assertEquals("study:profcode", owner?.animalId)
    }

    private fun createV2PackStream(
        packVersion: Int = 1,
        replayStartAt: String? = "2026-11-12T00:00:00Z",
        publishedAt: String? = "2026-11-12T00:00:00Z",
        schemaVersion: Int = 2,
        pointsJson: String = """[[-120.5, 34.2, "2020-01-01T00:00:00Z", 0], [-120.8, 34.5, "2020-01-01T12:00:00Z", 43200000]]""",
        description: String = "Replay whale",
        journalRef: String? = null,
        includeJournalFile: Boolean = false
    ): ByteArrayInputStream {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val replayField = if (replayStartAt != null) ""","replayStartAt": "$replayStartAt"""" else ""
            val pubField = if (publishedAt != null) ""","publishedAt": "$publishedAt"""" else ""
            val journalField = if (journalRef != null) ""","journal": "$journalRef"""" else ""

            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = """
            {
              "format": "wildlife-pack",
              "version": $schemaVersion,
              "packId": "starter-replay-pack",
              "packVersion": $packVersion,
              "title": "Starter Replay Pack"
              $pubField
              $replayField,
              "animals": [
                {
                  "animalId": "blue_whale:v2_01",
                  "profile": "animals/whale.json",
                  "track": "tracks/whale.json"
                  $journalField
                }
              ]
            }
            """.trimIndent()
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("animals/whale.json"))
            zos.write("""{"animalId":"blue_whale:v2_01","species":"Blue Whale","displayName":"Mara V2","description":"$description"}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("tracks/whale.json"))
            zos.write("""{"animalId":"blue_whale:v2_01","points":$pointsJson}""".toByteArray())
            zos.closeEntry()

            if (includeJournalFile && journalRef != null) {
                zos.putNextEntry(ZipEntry(journalRef))
                zos.write("""[{"entryId":"e1","replayOffsetMs":0,"text":"Started journey."}]""".toByteArray())
                zos.closeEntry()
            }
        }
        return ByteArrayInputStream(baos.toByteArray())
    }

    @Test
    fun testValidV2ReplayPackImportAndLegacyFlag() = runBlocking {
        val result = importer.importFromStream(createV2PackStream())
        assertTrue("Expected Success but got $result", result is ImportResult.Success)

        val pack = fakeDao.getPackByIdSync("starter-replay-pack")
        assertNotNull(pack)
        assertEquals(2, pack?.schemaVersion)
        assertEquals(1, pack?.packVersion)
        assertNotNull(pack?.replayStartAtUtcMs)
        assertNotNull(pack?.publishedAtUtcMs)
        assertNotNull(pack?.contentHash)
        assertTrue(pack?.isReplaySynchronized == true)

        val segments = fakeDao.getTimelineSegmentsForAnimal("blue_whale:v2_01")
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startOffsetMs)
        assertEquals(43200000L, segments[0].endOffsetMs)
        assertEquals(2, segments[0].observationCount)

        // Verify legacy v1 pack without replayStartAt is NOT treated as replay-synchronized
        val v1Result = importer.importFromStream(createValidZipStream())
        assertTrue(v1Result is ImportResult.Success)
        val legacyPack = fakeDao.getPackByIdSync("test-pack-1")
        assertNotNull(legacyPack)
        assertEquals(false, legacyPack?.isReplaySynchronized)
        assertEquals(null, legacyPack?.replayStartAtUtcMs)
    }

    @Test
    fun testV2MissingReplayStartAtRejected() = runBlocking {
        val result = importer.importFromStream(createV2PackStream(replayStartAt = null))
        assertTrue(result is ImportResult.Error)
        assertTrue((result as ImportResult.Error).message.contains("replayStartAt"))
    }

    @Test
    fun testV2InvalidReplayStartAtRejected() = runBlocking {
        val result = importer.importFromStream(createV2PackStream(replayStartAt = "not-a-timestamp"))
        assertTrue(result is ImportResult.Error)
        assertTrue((result as ImportResult.Error).message.contains("replayStartAt"))
    }

    @Test
    fun testV2MissingPublishedAtRejected() = runBlocking {
        val result = importer.importFromStream(createV2PackStream(publishedAt = null))
        assertTrue(result is ImportResult.Error)
        assertTrue((result as ImportResult.Error).message.contains("publishedAt"))
    }

    @Test
    fun testUnsupportedSchemaVersionRejected() = runBlocking {
        val result = importer.importFromStream(createV2PackStream(schemaVersion = 99))
        assertTrue(result is ImportResult.Error)
        assertTrue((result as ImportResult.Error).message.contains("Unsupported pack schema version"))
    }

    @Test
    fun testImmutableReleaseProtectionAndVersionUpgradePreservesUnlocks() = runBlocking {
        // Install v1 of starter-replay-pack
        assertTrue(importer.importFromStream(createV2PackStream(packVersion = 1, description = "Original")) is ImportResult.Success)
        fakeDao.unlockAnimal("blue_whale:v2_01", code = null, method = "COLLECTION", timestamp = System.currentTimeMillis())

        // Reimport exact same pack v1 -> idempotent success, unlock preserved
        val idempotent = importer.importFromStream(createV2PackStream(packVersion = 1, description = "Original"))
        assertTrue(idempotent is ImportResult.Success)
        assertTrue(fakeDao.isAnimalUnlocked("blue_whale:v2_01"))

        // Reimport modified content with SAME packVersion=1 -> MUST reject
        val tampered = importer.importFromStream(createV2PackStream(packVersion = 1, description = "Silently Altered"))
        assertTrue("Modified content with same packVersion must be rejected", tampered is ImportResult.Error)
        assertTrue((tampered as ImportResult.Error).message.contains("different contents"))

        // Upgrade to packVersion=2 -> succeeds and preserves unlock
        val upgrade = importer.importFromStream(createV2PackStream(packVersion = 2, description = "Updated Release"))
        assertTrue(upgrade is ImportResult.Success)
        assertTrue(fakeDao.isAnimalUnlocked("blue_whale:v2_01"))
        assertEquals("Updated Release", fakeDao.animals["blue_whale:v2_01"]?.description)

        // Downgrade back to packVersion=1 -> MUST reject
        val downgrade = importer.importFromStream(createV2PackStream(packVersion = 1, description = "Original"))
        assertTrue(downgrade is ImportResult.Error)
        assertTrue((downgrade as ImportResult.Error).message.contains("downgrade"))
    }

    @Test
    fun testV2TrackChronologyAndBoundsValidation() = runBlocking {
        // Out of order timestamps
        val outOfOrder = createV2PackStream(
            pointsJson = """[[-120.5, 34.2, "2020-01-02T00:00:00Z", 0], [-120.8, 34.5, "2020-01-01T00:00:00Z", 1000]]"""
        )
        val resChrono = importer.importFromStream(outOfOrder)
        assertTrue(resChrono is ImportResult.Error)
        assertTrue((resChrono as ImportResult.Error).message.contains("chronology"))

        // Out of bounds coordinates
        val badCoords = createV2PackStream(
            pointsJson = """[[-200.0, 34.2, "2020-01-01T00:00:00Z", 0]]"""
        )
        val resCoords = importer.importFromStream(badCoords)
        assertTrue(resCoords is ImportResult.Error)
        assertTrue((resCoords as ImportResult.Error).message.contains("out-of-bounds"))
    }

    @Test
    fun testMissingJournalReferenceRejectedAndValidAccepted() = runBlocking {
        val missingJournal = createV2PackStream(journalRef = "journals/whale.json", includeJournalFile = false)
        val resMissing = importer.importFromStream(missingJournal)
        assertTrue(resMissing is ImportResult.Error)
        assertTrue((resMissing as ImportResult.Error).message.contains("journal"))

        val validJournal = createV2PackStream(journalRef = "journals/whale.json", includeJournalFile = true)
        val resValid = importer.importFromStream(validJournal)
        assertTrue(resValid is ImportResult.Success)
    }

    @Test
    fun testStarterPack1V2PipelineArtifactImportsCleanly() = runBlocking {
        val candidates = listOf(
            File("../packs/starter-pack-1.wildpack"),
            File("../../packs/starter-pack-1.wildpack"),
            File("packs/starter-pack-1.wildpack")
        )
        val packFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Expected generated packs/starter-pack-1.wildpack to exist", packFile)

        val reportFile = File(packFile!!.parentFile, "starter-pack-1.build-report.json")
        assertTrue("Expected starter-pack-1.build-report.json to exist", reportFile.exists())
        val expectedHash = com.google.gson.JsonParser.parseString(reportFile.readText())
            .asJsonObject.get("contentHash").asString

        val firstImport = packFile.inputStream().use { importer.importFromStream(it) }
        assertTrue("Expected Success but got $firstImport", firstImport is ImportResult.Success)
        val success = firstImport as ImportResult.Success
        assertEquals("Starter Pack 1", success.packTitle)
        assertEquals(5, success.animalCount)

        val pack = fakeDao.getPackByIdSync("starter-pack-1")
        assertNotNull(pack)
        assertEquals(2, pack?.schemaVersion)
        assertEquals(1, pack?.packVersion)
        assertEquals(1794441600000L, pack?.replayStartAtUtcMs)
        assertTrue(pack?.isReplaySynchronized == true)
        assertEquals(expectedHash, pack?.contentHash)

        // All 5 curated CC0 animals should have non-zero timeline durations
        val expectedIds = listOf(
            "blue_whale:2008CA-Bmu-00830",
            "seal:seal 2004025",
            "green_turtle:52189",
            "albatross:1163-1163",
            "godwit:Bordeaux"
        )
        for (animalId in expectedIds) {
            val segments = fakeDao.getTimelineSegmentsForAnimal(animalId)
            assertEquals("Expected 1 segment for $animalId", 1, segments.size)
            assertEquals(0L, segments[0].startOffsetMs)
            assertTrue("Expected positive duration for $animalId", segments[0].endOffsetMs > 0L)
            assertTrue("Expected >= 100 observations for $animalId", segments[0].observationCount >= 100)
        }

        // Re-importing the identical pack must be idempotent
        val reimport = packFile.inputStream().use { importer.importFromStream(it) }
        assertTrue("Expected idempotent re-import Success but got $reimport", reimport is ImportResult.Success)
    }
}
