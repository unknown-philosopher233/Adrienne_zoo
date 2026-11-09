package com.adriennezoo.companion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "animals")
data class AnimalEntity(
    @PrimaryKey
    val animalId: String,
    val species: String,
    val scientificName: String?,
    val researchId: String,
    val sourceStudy: String,
    val license: String,
    val source: String,
    val displayName: String,
    val description: String,
    val intro: String,
    val activationCode: String,

    // Path to the local track file
    val trackFilePath: String,

    val firstObservation: String?,
    val lastObservation: String?,
    val observationCount: Int,

    val isActivated: Boolean = false,
    val packId: String? = null
)
