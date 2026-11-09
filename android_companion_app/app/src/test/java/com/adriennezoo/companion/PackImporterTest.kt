package com.adriennezoo.companion

import com.adriennezoo.companion.data.ActivationCodeEntity
import com.adriennezoo.companion.data.AnimalDao
import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.data.ImportResult
import com.adriennezoo.companion.data.PackEntity
import com.adriennezoo.companion.data.PackImporter
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
    override suspend fun insertPack(pack: PackEntity) { packs.add(pack) }
    override fun getAllPacks(): Flow<List<PackEntity>> = flowOf(packs)
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
        assertTrue((result as ImportResult.Error).message.contains("Invalid entry name"))
    }
}
