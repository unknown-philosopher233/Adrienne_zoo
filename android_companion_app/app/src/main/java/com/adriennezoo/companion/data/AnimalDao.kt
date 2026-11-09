package com.adriennezoo.companion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimalDao {
    @Query("SELECT * FROM animals")
    fun getAllAnimals(): Flow<List<AnimalEntity>>
    
    @Query("SELECT * FROM animals WHERE isActivated = 1")
    fun getActivatedAnimals(): Flow<List<AnimalEntity>>

    @Query("SELECT * FROM animals WHERE animalId = :id")
    fun getAnimalById(id: String): Flow<AnimalEntity?>

    @Query("SELECT * FROM animals WHERE activationCode = :code COLLATE NOCASE LIMIT 1")
    suspend fun getAnimalByCode(code: String): AnimalEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimals(animals: List<AnimalEntity>)

    @Query("UPDATE animals SET isActivated = 1 WHERE animalId = :animalId")
    suspend fun markAsActivated(animalId: String)
    
    @Query("SELECT COUNT(*) FROM animals")
    suspend fun getAnimalCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPack(pack: PackEntity)
    
    @Query("SELECT * FROM packs")
    fun getAllPacks(): Flow<List<PackEntity>>
}
