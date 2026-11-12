package com.adriennezoo.companion.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_segments",
    indices = [
        Index(value = ["animalId"]),
        Index(value = ["packId"])
    ]
)
data class TimelineSegmentEntity(
    @PrimaryKey
    val segmentId: String,
    val animalId: String,
    val timelineId: String,
    val packId: String,
    val packVersion: Int,
    val segmentOrder: Int,
    val trackFilePath: String,
    val firstObservation: String?,
    val lastObservation: String?,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val observationCount: Int,
    val replayStartAtUtcMs: Long?
)
