package com.adriennezoo.companion.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_meta")
data class CatalogMetaEntity(
    @PrimaryKey
    val key: String,
    val version: Int,
    val updatedAt: Long
)
