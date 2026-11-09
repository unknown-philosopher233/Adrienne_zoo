package com.adriennezoo.companion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT * FROM packs ORDER BY importTimestamp DESC")
    fun getAllPacks(): Flow<List<PackEntity>>
}
