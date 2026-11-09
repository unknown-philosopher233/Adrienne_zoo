package com.adriennezoo.companion

import com.adriennezoo.companion.data.ActivationCodeEntity
import com.adriennezoo.companion.data.AnimalEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ActivationTest {
    private lateinit var fakeDao: FakeAnimalDao

    @Before
    fun setUp() {
        fakeDao = FakeAnimalDao()

        // Setup the 5 starter animals and their codes
        val starters = listOf(
            Triple("blue_whale:001", "Mara", "WILD-0003-CODE"),
            Triple("green_turtle:002", "Nalu", "WILD-0002-CODE"),
            Triple("caribou:003", "Kestrel", "WILD-0001-CODE"),
            Triple("seal:004", "Harbor", "WILD-0004-CODE"),
            Triple("albatross:005", "Sol", "WILD-0000-CODE")
        )

        starters.forEach { (id, name, code) ->
            fakeDao.animals[id] = AnimalEntity(
                animalId = id,
                species = name,
                scientificName = null,
                researchId = id,
                sourceStudy = "Study",
                license = "CC-BY",
                source = "Movebank",
                displayName = name,
                description = "Desc",
                intro = "Intro",
                accent = "#27382F",
                trackFilePath = "/dummy/$id.json",
                firstObservation = "2020-01-01",
                lastObservation = "2020-02-01",
                observationCount = 10,
                isActivated = false
            )
            fakeDao.codes[code] = id
        }
    }

    @Test
    fun testStarterCodesResolveCorrectAnimals() = runBlocking {
        assertEquals("Mara", fakeDao.getAnimalByCode("WILD-0003-CODE")?.displayName)
        assertEquals("Nalu", fakeDao.getAnimalByCode("WILD-0002-CODE")?.displayName)
        assertEquals("Kestrel", fakeDao.getAnimalByCode("WILD-0001-CODE")?.displayName)
        assertEquals("Harbor", fakeDao.getAnimalByCode("WILD-0004-CODE")?.displayName)
        assertEquals("Sol", fakeDao.getAnimalByCode("WILD-0000-CODE")?.displayName)
    }

    @Test
    fun testCodeLookupTrimsWhitespaceAndIsCaseInsensitive() = runBlocking {
        assertEquals("Mara", fakeDao.getAnimalByCode("  wild-0003-code  ")?.displayName)
        assertEquals("Sol", fakeDao.getAnimalByCode("WILD-0000-code")?.displayName)
    }

    @Test
    fun testUnknownCodeReturnsNull() = runBlocking {
        assertNull(fakeDao.getAnimalByCode("WILD-9999-XXXX"))
        assertNull(fakeDao.getAnimalByCode(""))
    }

    @Test
    fun testActivationMarksAnimalAsActivated() = runBlocking {
        val animal = fakeDao.getAnimalByCode("WILD-0003-CODE")
        assertNotNull(animal)
        assertEquals(false, animal?.isActivated)

        fakeDao.markAsActivated(animal!!.animalId)

        val updated = fakeDao.getAnimalByIdSync(animal.animalId)
        assertEquals(true, updated?.isActivated)
    }
}
