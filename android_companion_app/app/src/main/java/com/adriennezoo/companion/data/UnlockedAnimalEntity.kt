package com.adriennezoo.companion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "unlocked_animals")
data class UnlockedAnimalEntity(
    @PrimaryKey
    val animalId: String,
    val unlockedAt: Long,
    val unlockMethod: String, // "STARTER", "CODE", "MANUAL", "MIGRATION"
    val usedCode: String? = null
)
