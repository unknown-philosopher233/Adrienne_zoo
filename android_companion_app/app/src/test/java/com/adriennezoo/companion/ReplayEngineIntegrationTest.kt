package com.adriennezoo.companion

import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.data.TimelineSegmentEntity
import com.adriennezoo.companion.replay.FixedReplayClock
import com.adriennezoo.companion.replay.ReplayEngine
import com.adriennezoo.companion.replay.ReplayPhase
import com.adriennezoo.companion.replay.ReplayTimeContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReplayEngineIntegrationTest {

    @Test
    fun testStarterPack1ReplaySynchronization() = runBlocking {
        // Find the starter-pack-1 outputs
        val candidates = listOf(
            File("../packs/starter-pack-1.wildpack"),
            File("../../packs/starter-pack-1.wildpack"),
            File("packs/starter-pack-1.wildpack")
        )
        val packFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Expected generated packs/starter-pack-1.wildpack to exist", packFile)

        // Let's create dummy entities for the test
        val animal = AnimalEntity(
            animalId = "blue_whale:2008CA-Bmu-00830",
            species = "Blue Whale",
            scientificName = "Balaenoptera musculus",
            researchId = "2008CA-Bmu-00830",
            sourceStudy = "Test",
            license = "CC0",
            source = "Test",
            displayName = "Mara",
            description = "",
            intro = "",
            trackFilePath = "", // Will be mocked
            firstObservation = "2008-07-25T23:45:00Z",
            lastObservation = "2008-11-12T21:09:23Z",
            observationCount = 127
        )

        // Extract the track and journal files for the blue whale from the zip
        val zip = java.util.zip.ZipFile(packFile)
        val trackEntry = zip.getEntry("tracks/mara-blue-whale.json")
        val journalEntry = zip.getEntry("journals/mara-blue-whale.json")

        val trackFileObj = File.createTempFile("mara_track", ".json")
        val journalFileObj = File.createTempFile("mara_journal", ".json")

        zip.getInputStream(trackEntry)!!.use { input ->
            trackFileObj.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        zip.getInputStream(journalEntry)!!.use { input ->
            journalFileObj.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val segment = TimelineSegmentEntity(
            segmentId = "test_segment",
            animalId = animal.animalId,
            timelineId = animal.animalId,
            packId = "starter-pack-1",
            packVersion = 1,
            segmentOrder = 0,
            trackFilePath = trackFileObj.absolutePath,
            journalFilePath = journalFileObj.absolutePath,
            firstObservation = animal.firstObservation,
            lastObservation = animal.lastObservation,
            startOffsetMs = 0L,
            endOffsetMs = 9494663000L, // 109.89 days
            observationCount = 127,
            replayStartAtUtcMs = ReplayTimeContract.parseUtcTimestampMillis("2026-11-12T00:00:00Z")!!
        )

        // Test 1: Pre-start
        var clock = FixedReplayClock(ReplayTimeContract.parseUtcTimestampMillis("2026-11-11T23:59:59Z")!!)
        var state = ReplayEngine.resolveState(animal, segment, clock)
        assertNotNull(state)
        assertEquals(ReplayPhase.PRE_START, state!!.phase)
        assertEquals(0, state.revealedPointsJson.size())
        assertEquals(0, state.revealedJournals.size)

        // Test 2: Day 1 (First observation should be revealed, offset 0)
        clock.setNowUtcMillis(ReplayTimeContract.parseUtcTimestampMillis("2026-11-12T00:00:01Z")!!)
        state = ReplayEngine.resolveState(animal, segment, clock)
        assertNotNull(state)
        assertEquals(ReplayPhase.ACTIVE, state!!.phase)
        assertTrue(state.revealedPointsJson.size() > 0)
        assertEquals(1, state.revealedJournals.size)
        assertEquals("Journey Begins", state.revealedJournals[0].title)

        // Test 3: Day 50 (Midway point should be revealed)
        clock.setNowUtcMillis(ReplayTimeContract.parseUtcTimestampMillis("2026-12-25T00:00:00Z")!!) // + 43 days
        state = ReplayEngine.resolveState(animal, segment, clock)
        assertNotNull(state)
        assertEquals(ReplayPhase.ACTIVE, state!!.phase)
        val numRevealed = state.revealedJournals.size
        assertTrue("Expected multiple journals to be revealed by day 43", numRevealed > 1)
        val midwayRevealed = state.revealedJournals.any { it.title == "Midway Point" }
        assertTrue("Midway Point journal should be revealed", midwayRevealed)

        // Test 4: End of Data
        clock.setNowUtcMillis(ReplayTimeContract.parseUtcTimestampMillis("2027-03-02T00:00:00Z")!!) // + 110 days
        state = ReplayEngine.resolveState(animal, segment, clock)
        assertNotNull(state)
        assertEquals(ReplayPhase.END_OF_DATA, state!!.phase)
        assertEquals(127, state.revealedPointsJson.size())
        val endRevealed = state.revealedJournals.any { it.title == "Journey Concludes" }
        assertTrue("End journal should be revealed", endRevealed)

        trackFileObj.delete()
        journalFileObj.delete()
        Unit
    }
}
