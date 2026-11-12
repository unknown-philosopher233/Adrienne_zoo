package com.adriennezoo.companion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AnimalEntity::class,
        ActivationCodeEntity::class,
        PackEntity::class,
        UnlockedAnimalEntity::class,
        CatalogMetaEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun animalDao(): AnimalDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create unlocked_animals table to separate ownership from catalog metadata
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `unlocked_animals` (
                        `animalId` TEXT NOT NULL,
                        `unlockedAt` INTEGER NOT NULL,
                        `unlockMethod` TEXT NOT NULL,
                        `usedCode` TEXT,
                        PRIMARY KEY(`animalId`)
                    )
                    """.trimIndent()
                )

                // 2. Migrate existing activated animals into unlocked_animals without data loss
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `unlocked_animals` (`animalId`, `unlockedAt`, `unlockMethod`, `usedCode`)
                    SELECT `animalId`, CAST(strftime('%s', 'now') AS INTEGER) * 1000, 'MIGRATION', NULL
                    FROM `animals` WHERE `isActivated` = 1
                    """.trimIndent()
                )

                // 3. Create catalog_meta table to track catalog versions idempotently
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_meta` (
                        `key` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "adrienne_zoo_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
