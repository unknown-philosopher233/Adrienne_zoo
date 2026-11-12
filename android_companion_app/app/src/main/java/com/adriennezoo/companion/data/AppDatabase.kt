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
        CatalogMetaEntity::class,
        TimelineSegmentEntity::class
    ],
    version = 3,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add versioned pack and replay timing metadata columns to packs
                db.execSQL("ALTER TABLE `packs` ADD COLUMN `schemaVersion` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `packs` ADD COLUMN `packVersion` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `packs` ADD COLUMN `publishedAtUtcMs` INTEGER")
                db.execSQL("ALTER TABLE `packs` ADD COLUMN `replayStartAtUtcMs` INTEGER")
                db.execSQL("ALTER TABLE `packs` ADD COLUMN `contentHash` TEXT")

                // 2. Create timeline_segments table supporting multiple timeline segments per animal
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `timeline_segments` (
                        `segmentId` TEXT NOT NULL,
                        `animalId` TEXT NOT NULL,
                        `timelineId` TEXT NOT NULL,
                        `packId` TEXT NOT NULL,
                        `packVersion` INTEGER NOT NULL,
                        `segmentOrder` INTEGER NOT NULL,
                        `trackFilePath` TEXT NOT NULL,
                        `firstObservation` TEXT,
                        `lastObservation` TEXT,
                        `startOffsetMs` INTEGER NOT NULL,
                        `endOffsetMs` INTEGER NOT NULL,
                        `observationCount` INTEGER NOT NULL,
                        `replayStartAtUtcMs` INTEGER,
                        PRIMARY KEY(`segmentId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_timeline_segments_animalId` ON `timeline_segments` (`animalId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_timeline_segments_packId` ON `timeline_segments` (`packId`)"
                )

                // 3. Backfill timeline segments for existing installed animals without duplicating track payloads
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `timeline_segments` (
                        `segmentId`, `animalId`, `timelineId`, `packId`, `packVersion`,
                        `segmentOrder`, `trackFilePath`, `firstObservation`, `lastObservation`,
                        `startOffsetMs`, `endOffsetMs`, `observationCount`, `replayStartAtUtcMs`
                    )
                    SELECT
                        COALESCE(`packId`, 'legacy') || ':' || `animalId` || ':0',
                        `animalId`,
                        `animalId`,
                        COALESCE(`packId`, 'legacy'),
                        1,
                        0,
                        `trackFilePath`,
                        `firstObservation`,
                        `lastObservation`,
                        0,
                        0,
                        `observationCount`,
                        NULL
                    FROM `animals`
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
