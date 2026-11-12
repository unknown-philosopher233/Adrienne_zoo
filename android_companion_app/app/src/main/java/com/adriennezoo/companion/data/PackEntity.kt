package com.adriennezoo.companion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "packs")
data class PackEntity(
    @PrimaryKey
    val packId: String,
    val title: String,
    val importTimestamp: Long, // Local installation timestamp; never affects replay position
    val animalCount: Int = 0,
    val schemaVersion: Int = 1,
    val packVersion: Int = 1,
    val publishedAtUtcMs: Long? = null,
    val replayStartAtUtcMs: Long? = null, // Mandatory for v2+ replay-enabled packs; null for legacy v1
    val contentHash: String? = null
) {
    val isReplaySynchronized: Boolean
        get() = replayStartAtUtcMs != null && schemaVersion >= 2
}
