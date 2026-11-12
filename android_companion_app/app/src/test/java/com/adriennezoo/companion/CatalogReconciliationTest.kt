package com.adriennezoo.companion

import com.adriennezoo.companion.data.CatalogReconciler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class CatalogReconciliationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeDao: FakeAnimalDao
    private lateinit var testStorageDir: File

    private val sampleStartersJson = """
    [
      {
        "animalId": "blue_whale:2008CA-Bmu-00830",
        "species": "Blue Whale",
        "scientificName": "Balaenoptera musculus",
        "researchId": "2008CA-Bmu-00830",
        "sourceStudy": "Blue Whale Study",
        "license": "CC-BY",
        "source": "Movebank",
        "displayName": "Mara",
        "description": "Updated description",
        "intro": "Updated intro",
        "accent": "#2f5d6e",
        "activationCodes": ["WILD-0003-CODE"],
        "points": [
          [-120.0, 34.0, 1200000000000],
          [-120.5, 34.2, 1200003600000]
        ],
        "firstObservation": "2008-07-25",
        "lastObservation": "2008-10-15",
        "observationCount": 2,
        "distanceKm": 120.0
      },
      {
        "animalId": "green_turtle:173362",
        "species": "Green Sea Turtle",
        "scientificName": "Chelonia mydas",
        "researchId": "173362",
        "sourceStudy": "Sea Turtle Study",
        "license": "CC-BY",
        "source": "Movebank",
        "displayName": "Nalu",
        "description": "A green turtle",
        "intro": "Hello Nalu",
        "accent": "#2f5d6e",
        "activationCodes": ["WILD-0002-CODE"],
        "points": [
          [50.0, -10.0, 1500000000000],
          [50.1, -10.1, 1500003600000]
        ],
        "firstObservation": "2018-09-01",
        "lastObservation": "2018-11-01",
        "observationCount": 2,
        "distanceKm": 25.0
      }
    ]
    """.trimIndent()

    @Before
    fun setUp() {
        fakeDao = FakeAnimalDao()
        testStorageDir = tempFolder.newFolder("storage")
    }

    @Test
    fun testIdempotentReconciliationAndUnlockPreservation() = runBlocking {
        val reconciler = CatalogReconciler(
            context = null,
            animalDao = fakeDao,
            baseFilesDir = testStorageDir,
            catalogStreamProvider = { ByteArrayInputStream(sampleStartersJson.toByteArray(Charsets.UTF_8)) }
        )

        // Run reconciliation 1st time
        val count1 = reconciler.reconcile()
        assertEquals(2, count1)
        assertEquals(2, fakeDao.getAnimalCount())
        assertFalse(fakeDao.isAnimalUnlocked("blue_whale:2008CA-Bmu-00830"))
        assertFalse(fakeDao.isAnimalUnlocked("green_turtle:173362"))

        // Unlock Mara using activation code
        fakeDao.unlockAnimal("blue_whale:2008CA-Bmu-00830", code = "WILD-0003-CODE", method = "CODE")
        assertTrue(fakeDao.isAnimalUnlocked("blue_whale:2008CA-Bmu-00830"))
        assertTrue(fakeDao.getAnimalByIdSync("blue_whale:2008CA-Bmu-00830")!!.isActivated)

        // Run reconciliation 2nd time (simulating app restart or catalog update)
        val count2 = reconciler.reconcile()
        assertEquals(2, count2)
        assertEquals(2, fakeDao.getAnimalCount()) // No duplicate entries

        // Mara MUST still be unlocked
        assertTrue(
            "Reconciliation must NEVER revoke an existing unlock",
            fakeDao.isAnimalUnlocked("blue_whale:2008CA-Bmu-00830")
        )
        assertTrue(
            "AnimalEntity isActivated must remain true",
            fakeDao.getAnimalByIdSync("blue_whale:2008CA-Bmu-00830")!!.isActivated
        )

        // Nalu remains unactivated
        assertFalse(fakeDao.isAnimalUnlocked("green_turtle:173362"))
        assertFalse(fakeDao.getAnimalByIdSync("green_turtle:173362")!!.isActivated)

        // Verify track files were written to storage
        val tracksDir = File(testStorageDir, "tracks")
        val maraTrack = File(tracksDir, "blue_whale_2008CA-Bmu-00830.json")
        assertTrue(maraTrack.exists())
        assertTrue(maraTrack.length() > 0)
    }

    @Test
    fun testSelfRepairMissingTrackFiles() = runBlocking {
        val reconciler = CatalogReconciler(
            context = null,
            animalDao = fakeDao,
            baseFilesDir = testStorageDir,
            catalogStreamProvider = { ByteArrayInputStream(sampleStartersJson.toByteArray(Charsets.UTF_8)) }
        )

        reconciler.reconcile()

        // Delete track file on disk to simulate partial file corruption/deletion
        val tracksDir = File(testStorageDir, "tracks")
        val maraTrack = File(tracksDir, "blue_whale_2008CA-Bmu-00830.json")
        assertTrue(maraTrack.delete())
        assertFalse(maraTrack.exists())

        // Reconcile again
        reconciler.reconcile()

        // Track file must be repaired/recreated
        assertTrue("Track file must be restored upon reconciliation", maraTrack.exists())
        assertTrue(maraTrack.length() > 0)
    }
}
