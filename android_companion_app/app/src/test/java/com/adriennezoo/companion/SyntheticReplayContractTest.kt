package com.adriennezoo.companion

import com.adriennezoo.companion.data.ImportResult
import com.adriennezoo.companion.data.PackImporter
import com.adriennezoo.companion.replay.FixedReplayClock
import com.adriennezoo.companion.replay.ReplayPhase
import com.adriennezoo.companion.replay.ReplayTimeContract
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Automated tests proving the shared P1/P3 replay-clock contract using
 * two tiny synthetic test tracks stored strictly in src/test/resources
 * (never shipped in production assets).
 */
class SyntheticReplayContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun loadSyntheticResource(fileName: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("synthetic_replay/$fileName")
        assertNotNull("Synthetic test fixture synthetic_replay/$fileName must exist on test classpath", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun buildSyntheticV2Pack(
        replayStartAt: String = "2026-11-12T00:00:00Z",
        publishedAt: String = "2026-11-12T00:00:00Z"
    ): ByteArray {
        val alphaTrack = loadSyntheticResource("synthetic_track_alpha.json")
        val betaTrack = loadSyntheticResource("synthetic_track_beta.json")

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(
                """
                {
                  "format": "wildlife-pack",
                  "version": 2,
                  "packId": "synthetic-sync-test-pack",
                  "packVersion": 1,
                  "title": "Synthetic Synchronization Test Pack",
                  "publishedAt": "$publishedAt",
                  "replayStartAt": "$replayStartAt",
                  "animals": [
                    {
                      "animalId": "synthetic:alpha",
                      "profile": "animals/alpha.json",
                      "track": "tracks/alpha.json"
                    },
                    {
                      "animalId": "synthetic:beta",
                      "profile": "animals/beta.json",
                      "track": "tracks/beta.json"
                    }
                  ]
                }
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("animals/alpha.json"))
            zos.write("""{"animalId":"synthetic:alpha","species":"Synthetic Test Tern","displayName":"Alpha Test"}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("animals/beta.json"))
            zos.write("""{"animalId":"synthetic:beta","species":"Synthetic Test Seal","displayName":"Beta Test"}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("tracks/alpha.json"))
            zos.write(alphaTrack.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("tracks/beta.json"))
            zos.write(betaTrack.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun resolveLatestVisibleObservationIndex(trackFilePath: String, elapsedMillis: Long): Int {
        val trackJson = JsonParser.parseString(File(trackFilePath).readText(Charsets.UTF_8)).asJsonObject
        val points = trackJson.getAsJsonArray("points")
        var latestIndex = -1
        for (i in 0 until points.size()) {
            val offsetMs = points.get(i).asJsonArray.get(3).asLong
            if (offsetMs <= elapsedMillis) {
                latestIndex = i
            }
        }
        return latestIndex
    }

    @Test
    fun testTwoInstallationsAtDifferentTimesResolveIdenticalReplayState() = runBlocking {
        val packBytes = buildSyntheticV2Pack(replayStartAt = "2026-11-12T00:00:00Z")

        // Installation 1: Installed Day 0 + 1 hour
        val daoInstall1 = FakeAnimalDao()
        val importer1 = PackImporter(null, daoInstall1, tempFolder.newFolder("install1"))
        val res1 = importer1.importFromStream(ByteArrayInputStream(packBytes))
        assertTrue(res1 is ImportResult.Success)
        // Simulate local installation timestamp at T + 1h
        val pack1 = daoInstall1.getPackByIdSync("synthetic-sync-test-pack")!!.copy(
            importTimestamp = ReplayTimeContract.parseUtcTimestampMillis("2026-11-12T01:00:00Z")!!
        )

        // Installation 2: Installed Day 1 + 18 hours (42 hours after replayStartAt)
        val daoInstall2 = FakeAnimalDao()
        val importer2 = PackImporter(null, daoInstall2, tempFolder.newFolder("install2"))
        val res2 = importer2.importFromStream(ByteArrayInputStream(packBytes))
        assertTrue(res2 is ImportResult.Success)
        val pack2 = daoInstall2.getPackByIdSync("synthetic-sync-test-pack")!!.copy(
            importTimestamp = ReplayTimeContract.parseUtcTimestampMillis("2026-11-13T18:00:00Z")!!
        )

        // Both installations checked at the EXACT same UTC instant: 2026-11-13T18:00:00Z (42h elapsed)
        val sharedClock = FixedReplayClock(ReplayTimeContract.parseUtcTimestampMillis("2026-11-13T18:00:00Z")!!)

        val segAlpha1 = daoInstall1.getTimelineSegmentsForAnimal("synthetic:alpha").single()
        val segAlpha2 = daoInstall2.getTimelineSegmentsForAnimal("synthetic:alpha").single()

        val status1 = ReplayTimeContract.resolveWindowStatus(pack1.replayStartAtUtcMs, segAlpha1.endOffsetMs, sharedClock)
        val status2 = ReplayTimeContract.resolveWindowStatus(pack2.replayStartAtUtcMs, segAlpha2.endOffsetMs, sharedClock)

        assertEquals(status1, status2)
        assertEquals(ReplayPhase.ACTIVE, status1.phase)
        assertEquals(42L * 3600L * 1000L, status1.elapsedMillis)

        // Both must reveal the same observation (offset 36h = index 3; index 4 at 48h is not revealed yet)
        val idx1 = resolveLatestVisibleObservationIndex(segAlpha1.trackFilePath, status1.elapsedMillis)
        val idx2 = resolveLatestVisibleObservationIndex(segAlpha2.trackFilePath, status2.elapsedMillis)
        assertEquals(3, idx1)
        assertEquals(idx1, idx2)
    }

    @Test
    fun testPreStartActiveAndIndependentEndOfDataAcrossSyntheticTracks() = runBlocking {
        val packBytes = buildSyntheticV2Pack(replayStartAt = "2026-11-12T00:00:00Z")
        val dao = FakeAnimalDao()
        val importer = PackImporter(null, dao, tempFolder.newFolder("sync"))
        assertTrue(importer.importFromStream(ByteArrayInputStream(packBytes)) is ImportResult.Success)

        val pack = dao.getPackByIdSync("synthetic-sync-test-pack")!!
        val segAlpha = dao.getTimelineSegmentsForAnimal("synthetic:alpha").single() // 48h duration
        val segBeta = dao.getTimelineSegmentsForAnimal("synthetic:beta").single()   // 72h duration

        // 1. Pre-start: 2 hours before replayStartAt
        val clock = FixedReplayClock(ReplayTimeContract.parseUtcTimestampMillis("2026-11-11T22:00:00Z")!!)
        val preAlpha = ReplayTimeContract.resolveWindowStatus(pack.replayStartAtUtcMs, segAlpha.endOffsetMs, clock)
        assertEquals(ReplayPhase.PRE_START, preAlpha.phase)
        assertEquals(0L, preAlpha.elapsedMillis)

        // 2. Active at T + 60h: Alpha (48h) has reached END_OF_DATA while Beta (72h) is still ACTIVE
        clock.setNowUtcMillis(ReplayTimeContract.parseUtcTimestampMillis("2026-11-14T12:00:00Z")!!)
        val alphaAt60h = ReplayTimeContract.resolveWindowStatus(pack.replayStartAtUtcMs, segAlpha.endOffsetMs, clock)
        val betaAt60h = ReplayTimeContract.resolveWindowStatus(pack.replayStartAtUtcMs, segBeta.endOffsetMs, clock)

        assertEquals(ReplayPhase.END_OF_DATA, alphaAt60h.phase)
        assertEquals(ReplayPhase.ACTIVE, betaAt60h.phase)
        assertEquals(4, resolveLatestVisibleObservationIndex(segAlpha.trackFilePath, alphaAt60h.elapsedMillis))
        assertEquals(2, resolveLatestVisibleObservationIndex(segBeta.trackFilePath, betaAt60h.elapsedMillis))
    }

    @Test
    fun testTimezoneChangesDoNotAffectUtcReplayResolution() = runBlocking {
        val packBytes = buildSyntheticV2Pack(replayStartAt = "2026-11-12T00:00:00Z")
        val dao = FakeAnimalDao()
        val importer = PackImporter(null, dao, tempFolder.newFolder("tz"))
        assertTrue(importer.importFromStream(ByteArrayInputStream(packBytes)) is ImportResult.Success)

        val pack = dao.getPackByIdSync("synthetic-sync-test-pack")!!
        val segBeta = dao.getTimelineSegmentsForAnimal("synthetic:beta").single()
        val fixedUtc = ReplayTimeContract.parseUtcTimestampMillis("2026-11-13T00:00:00Z")!!
        val clock = FixedReplayClock(fixedUtc)

        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val nyStatus = ReplayTimeContract.resolveWindowStatus(pack.replayStartAtUtcMs, segBeta.endOffsetMs, clock)

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val tokyoStatus = ReplayTimeContract.resolveWindowStatus(pack.replayStartAtUtcMs, segBeta.endOffsetMs, clock)

            assertEquals(nyStatus, tokyoStatus)
            assertEquals(24L * 3600L * 1000L, nyStatus.elapsedMillis)
            assertEquals(ReplayPhase.ACTIVE, nyStatus.phase)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }
}
