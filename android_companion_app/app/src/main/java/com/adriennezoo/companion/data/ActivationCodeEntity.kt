package com.adriennezoo.companion.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "activation_codes",
    indices = [Index(value = ["animalId"])]
)
data class ActivationCodeEntity(
    @PrimaryKey
    val code: String, // Normalized uppercase
    val animalId: String
)
