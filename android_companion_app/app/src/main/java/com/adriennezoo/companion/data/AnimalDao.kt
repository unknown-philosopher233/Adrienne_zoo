package com.adriennezoo.companion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimalDao {
    @Query("SELECT * FROM animals ORDER BY displayName ASC")
    fun getAllAnimals(): Flow<List<AnimalEntity>>

    @Query("SELECT * FROM animals WHERE isActivated = 1 ORDER BY displayName ASC")
    fun getActivatedAnimals(): Flow<List<AnimalEntity>>

    @Query("SELECT * FROM animals WHERE animalId = :id")
    fun getAnimalById(id: String): Flow<AnimalEntity?>

    @Query("SELECT * FROM animals WHERE animalId = :id LIMIT 1")
    suspend fun getAnimalByIdSync(id: String): AnimalEntity?

    @Query("SELECT a.* FROM animals a INNER JOIN activation_codes c ON a.animalId = c.animalId WHERE c.code = :code COLLATE NOCASE LIMIT 1")
    suspend fun getAnimalByCode(code: String): AnimalEntity?

    @Query("SELECT animalId FROM activation_codes WHERE code = :code COLLATE NOCASE LIMIT 1")
    suspend fun getAnimalIdByCode(code: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimal(animal: AnimalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimals(animals: List<AnimalEntity>)

    @Query("UPDATE animals SET isActivated = 1 WHERE animalId = :animalId")
    suspend fun markAsActivated(animalId: String)

    @Query("SELECT COUNT(*) FROM animals")
    suspend fun getAnimalCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivationCodes(codes: List<ActivationCodeEntity>)

    @Query("SELECT code FROM activation_codes WHERE animalId = :animalId")
    suspend fun getCodesForAnimal(animalId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPack(pack: PackEntity)

    @Query("SELECT * FROM packs WHERE packId = :packId LIMIT 1")
    suspend fun getPackByIdSync(packId: String): PackEntity?

    @Query("SELECT * FROM packs ORDER BY importTimestamp DESC")
    fun getAllPacks(): Flow<List<PackEntity>>

    // Timeline segments (supports multiple segments per animal without duplicating track payloads in DB)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimelineSegments(segments: List<TimelineSegmentEntity>)

    @Query("SELECT * FROM timeline_segments WHERE animalId = :animalId ORDER BY segmentOrder ASC")
    suspend fun getTimelineSegmentsForAnimal(animalId: String): List<TimelineSegmentEntity>

    @Query("SELECT * FROM timeline_segments WHERE animalId = :animalId ORDER BY segmentOrder ASC")
    fun observeTimelineSegmentsForAnimal(animalId: String): Flow<List<TimelineSegmentEntity>>

    // Unlocked animals (ownership separation)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlock(unlock: UnlockedAnimalEntity)

    @Query("SELECT * FROM unlocked_animals WHERE animalId = :animalId LIMIT 1")
    suspend fun getUnlock(animalId: String): UnlockedAnimalEntity?

    @Query("SELECT animalId FROM unlocked_animals")
    suspend fun getAllUnlockedAnimalIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM unlocked_animals WHERE animalId = :animalId)")
    suspend fun isAnimalUnlocked(animalId: String): Boolean

    // Atomic unlock preserving ownership across updates (works with or without activation code)
    @Transaction
    suspend fun unlockAnimal(
        animalId: String,
        code: String?,
        method: String,
        timestamp: Long
    ) {
        insertUnlock(
            UnlockedAnimalEntity(
                animalId = animalId,
                unlockedAt = timestamp,
                unlockMethod = method,
                usedCode = code
            )
        )
        markAsActivated(animalId)
    }

    // Catalog Meta
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCatalogMeta(meta: CatalogMetaEntity)

    @Query("SELECT * FROM catalog_meta WHERE `key` = :key LIMIT 1")
    suspend fun getCatalogMeta(key: String): CatalogMetaEntity?

    // Idempotent animal reconciliation preserving existing unlocks
    @Transaction
    suspend fun reconcileAnimal(
        animal: AnimalEntity,
        codes: List<ActivationCodeEntity>,
        segments: List<TimelineSegmentEntity>
    ) {
        val existing = getAnimalByIdSync(animal.animalId)
        val unlocked = isAnimalUnlocked(animal.animalId) || (existing?.isActivated == true)

        val toInsert = animal.copy(
            isActivated = unlocked,
            imagePath = animal.imagePath ?: existing?.imagePath
        )
        insertAnimal(toInsert)
        if (unlocked) {
            insertUnlock(
                UnlockedAnimalEntity(
                    animalId = animal.animalId,
                    unlockedAt = System.currentTimeMillis(),
                    unlockMethod = "MIGRATION"
                )
            )
        }
        if (codes.isNotEmpty()) {
            insertActivationCodes(codes)
        }
        if (segments.isNotEmpty()) {
            insertTimelineSegments(segments)
        }
    }

    @Transaction
    suspend fun insertPackWithAnimals(
        pack: PackEntity,
        animals: List<AnimalEntity>,
        codes: List<ActivationCodeEntity>,
        segments: List<TimelineSegmentEntity>
    ) {
        insertPack(pack)
        for (animal in animals) {
            val existing = getAnimalByIdSync(animal.animalId)
            val unlocked = isAnimalUnlocked(animal.animalId) || (existing?.isActivated == true)
            val toInsert = animal.copy(
                isActivated = unlocked,
                imagePath = animal.imagePath ?: existing?.imagePath
            )
            insertAnimal(toInsert)
            if (unlocked) {
                insertUnlock(
                    UnlockedAnimalEntity(
                        animalId = animal.animalId,
                        unlockedAt = System.currentTimeMillis(),
                        unlockMethod = "MIGRATION"
                    )
                )
            }
        }
        if (codes.isNotEmpty()) {
            insertActivationCodes(codes)
        }
        if (segments.isNotEmpty()) {
            insertTimelineSegments(segments)
        }
    }
}
