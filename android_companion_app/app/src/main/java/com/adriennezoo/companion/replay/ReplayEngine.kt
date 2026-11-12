package com.adriennezoo.companion.replay

import android.util.Log
import com.adriennezoo.companion.data.AnimalEntity
import com.adriennezoo.companion.data.TimelineSegmentEntity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

data class JournalEntry(
    val id: String,
    val replayOffsetMs: Long,
    val timestampUtc: String,
    val title: String,
    val body: String,
    val type: String,
    val lon: Double? = null,
    val lat: Double? = null
)

data class ReplayState(
    val animalId: String,
    val phase: ReplayPhase,
    val elapsedMillis: Long,
    val durationMillis: Long,
    val revealedPointsJson: JsonArray, // The subset of track points up to elapsedMillis
    val latestObservationDate: String?,
    val revealedJournals: List<JournalEntry>
)

object ReplayEngine {

    suspend fun resolveState(
        animal: AnimalEntity,
        segment: TimelineSegmentEntity,
        clock: ReplayClock = SystemReplayClock
    ): ReplayState? = withContext(Dispatchers.IO) {
        try {
            val status = ReplayTimeContract.resolveWindowStatus(
                replayStartAtUtcMillis = segment.replayStartAtUtcMs,
                timelineDurationMillis = segment.endOffsetMs,
                clock = clock
            )

            // Read track points
            val trackFile = File(segment.trackFilePath)
            if (!trackFile.exists()) return@withContext null
            val trackJsonStr = trackFile.readText(Charsets.UTF_8)
            val trackObj = JsonParser.parseString(trackJsonStr).asJsonObject
            val allPoints = trackObj.getAsJsonArray("points")

            val revealedPoints = JsonArray()
            var latestObsDate: String? = null

            for (i in 0 until allPoints.size()) {
                val point = allPoints.get(i).asJsonArray
                // point format: [lon, lat, timestampUtc, replayOffsetMs]
                // For v1 formats without explicit offsets, we handle it
                val offsetMs = if (point.size() >= 4) {
                    point.get(3).asLong
                } else {
                    // Fallback to elapsed estimation if missing, but v2 has it
                    val tsStr = point.get(2).asString
                    val pointMs = ReplayTimeContract.parseUtcTimestampMillis(tsStr)
                    val firstMs = ReplayTimeContract.parseUtcTimestampMillis(segment.firstObservation)
                    if (pointMs != null && firstMs != null) maxOf(0L, pointMs - firstMs) else 0L
                }

                val isRevealed = when (status.phase) {
                    ReplayPhase.PRE_START -> false
                    ReplayPhase.LEGACY_UNSYNCHRONIZED -> true
                    ReplayPhase.ACTIVE, ReplayPhase.END_OF_DATA -> offsetMs <= status.elapsedMillis
                }

                if (isRevealed) {
                    revealedPoints.add(point)
                    latestObsDate = point.get(2).asString
                } else {
                    break // Since points are chronologically sorted
                }
            }

            // Read journals
            val journals = mutableListOf<JournalEntry>()
            if (segment.journalFilePath != null) {
                val journalFile = File(segment.journalFilePath)
                if (journalFile.exists()) {
                    val journalStr = journalFile.readText(Charsets.UTF_8)
                    val journalObj = JsonParser.parseString(journalStr).asJsonObject
                    val entriesArr = journalObj.getAsJsonArray("entries")
                    for (i in 0 until entriesArr.size()) {
                        val entryObj = entriesArr.get(i).asJsonObject
                        val offsetMs = entryObj.get("replayOffsetMs").asLong

                        val isRevealed = when (status.phase) {
                            ReplayPhase.PRE_START -> false
                            ReplayPhase.LEGACY_UNSYNCHRONIZED -> true
                            ReplayPhase.ACTIVE, ReplayPhase.END_OF_DATA -> offsetMs <= status.elapsedMillis
                        }

                        if (isRevealed) {
                            var lon: Double? = null
                            var lat: Double? = null
                            if (entryObj.has("location")) {
                                val loc = entryObj.getAsJsonArray("location")
                                lon = loc.get(0).asDouble
                                lat = loc.get(1).asDouble
                            }

                            journals.add(
                                JournalEntry(
                                    id = entryObj.get("id").asString,
                                    replayOffsetMs = offsetMs,
                                    timestampUtc = entryObj.get("timestampUtc").asString,
                                    title = entryObj.get("title").asString,
                                    body = entryObj.get("body").asString,
                                    type = entryObj.get("type").asString,
                                    lon = lon,
                                    lat = lat
                                )
                            )
                        } else {
                            break // Journals are sorted by offset
                        }
                    }
                }
            }

            ReplayState(
                animalId = animal.animalId,
                phase = status.phase,
                elapsedMillis = status.elapsedMillis,
                durationMillis = status.durationMillis,
                revealedPointsJson = revealedPoints,
                latestObservationDate = latestObsDate,
                revealedJournals = journals
            )
        } catch (e: Exception) {
            Log.e("ReplayEngine", "Error resolving replay state for ${animal.animalId}", e)
            null
        }
    }
}
