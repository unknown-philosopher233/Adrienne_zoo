package com.adriennezoo.companion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "packs")
data class PackEntity(
    @PrimaryKey
    val packId: String,
    val title: String,
    val importTimestamp: Long,
    val animalCount: Int = 0
)
