package com.adriennezoo.companion

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adriennezoo.companion.data.AppDatabase
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Statement

class DatabaseMigrationTest {

    private fun executeSchemaFile(statement: Statement, schemaVersion: Int) {
        val schemaFile = File("schemas/com.adriennezoo.companion.data.AppDatabase/$schemaVersion.json")
        assertTrue("Exported schema $schemaVersion.json must exist", schemaFile.exists())
        val schemaContent = schemaFile.readText(Charsets.UTF_8)
        val schemaObj = JsonParser.parseString(schemaContent).asJsonObject
        val entities = schemaObj.getAsJsonObject("database").getAsJsonArray("entities")

        for (elem in entities) {
            val entityObj = elem.asJsonObject
            val tableName = entityObj.get("tableName").asString
            val createSql = entityObj.get("createSql").asString.replace("\${TABLE_NAME}", tableName)
            statement.execute(createSql)
            val indices = entityObj.getAsJsonArray("indices")
            if (indices != null) {
                for (idxElem in indices) {
                    val idxSql = idxElem.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", tableName)
                    statement.execute(idxSql)
                }
            }
        }
    }

    private fun createSupportDbProxy(statement: Statement): SupportSQLiteDatabase {
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                val sql = args[0] as String
                statement.execute(sql)
                null
            } else {
                throw UnsupportedOperationException("Method ${method.name} not implemented in test proxy")
            }
        } as SupportSQLiteDatabase
    }

    @Test
    fun testMigrationFromV1ToV2ToV3PreservesDataAndBackfillsSegments() {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        val statement = conn.createStatement()
        executeSchemaFile(statement, 1)

        // Insert v1 test records: 1 activated animal, 1 unactivated animal, codes, pack
        statement.execute(
            """
            INSERT INTO animals (
                animalId, species, scientificName, researchId, sourceStudy, license, source,
                displayName, description, intro, accent, imagePath, trackFilePath,
                firstObservation, lastObservation, observationCount, distanceKm, isActivated, packId
            ) VALUES (
                'blue_whale:01', 'Blue Whale', 'Balaenoptera musculus', '01', 'Study A', 'CC-BY', 'Source A',
                'Mara', 'A gentle giant', 'Intro Mara', '#2f5d6e', null, '/path/track1.json',
                '2020-01-01', '2020-01-10', 100, 1500.0, 1, 'starter-pack'
            )
            """.trimIndent()
        )
        statement.execute(
            """
            INSERT INTO animals (
                animalId, species, scientificName, researchId, sourceStudy, license, source,
                displayName, description, intro, accent, imagePath, trackFilePath,
                firstObservation, lastObservation, observationCount, distanceKm, isActivated, packId
            ) VALUES (
                'caribou:02', 'Caribou', 'Rangifer tarandus', '02', 'Study B', 'CC-BY', 'Source B',
                'Kestrel', 'Tundra runner', 'Intro Kestrel', '#a6643c', null, '/path/track2.json',
                '2020-02-01', '2020-02-10', 50, 400.0, 0, 'starter-pack'
            )
            """.trimIndent()
        )
        statement.execute(
            "INSERT INTO activation_codes (code, animalId) VALUES ('WILD-0003-CODE', 'blue_whale:01')"
        )
        statement.execute(
            "INSERT INTO packs (packId, title, importTimestamp, animalCount) VALUES ('starter-pack', 'Starters', 1600000000000, 2)"
        )

        val supportDb = createSupportDbProxy(statement)

        // Run v1 -> v2 -> v3 sequentially
        AppDatabase.MIGRATION_1_2.migrate(supportDb)
        AppDatabase.MIGRATION_2_3.migrate(supportDb)

        // Verify unlocked_animals table was populated with activated animal
        val unlockRs = statement.executeQuery(
            "SELECT animalId, unlockMethod FROM unlocked_animals WHERE animalId = 'blue_whale:01'"
        )
        assertTrue(unlockRs.next())
        assertEquals("MIGRATION", unlockRs.getString("unlockMethod"))
        unlockRs.close()

        // Verify unactivated animal is not in unlocked_animals
        val unactivatedRs = statement.executeQuery(
            "SELECT COUNT(*) as cnt FROM unlocked_animals WHERE animalId = 'caribou:02'"
        )
        assertTrue(unactivatedRs.next())
        assertEquals(0, unactivatedRs.getInt("cnt"))
        unactivatedRs.close()

        // Verify packs received default schemaVersion=1, packVersion=1, replayStartAtUtcMs=NULL
        val packRs = statement.executeQuery(
            "SELECT title, schemaVersion, packVersion, replayStartAtUtcMs FROM packs WHERE packId = 'starter-pack'"
        )
        assertTrue(packRs.next())
        assertEquals("Starters", packRs.getString("title"))
        assertEquals(1, packRs.getInt("schemaVersion"))
        assertEquals(1, packRs.getInt("packVersion"))
        assertEquals(null, packRs.getObject("replayStartAtUtcMs"))
        packRs.close()

        // Verify timeline_segments were backfilled for both animals
        val segRs = statement.executeQuery("SELECT COUNT(*) as cnt FROM timeline_segments")
        assertTrue(segRs.next())
        assertEquals(2, segRs.getInt("cnt"))
        segRs.close()

        statement.close()
        conn.close()
    }

    @Test
    fun testMigrationFromV2ToV3PreservesUnlocksAndAddsTimelineMetadata() {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        val statement = conn.createStatement()
        executeSchemaFile(statement, 2)

        statement.execute(
            """
            INSERT INTO animals (
                animalId, species, scientificName, researchId, sourceStudy, license, source,
                displayName, description, intro, accent, imagePath, trackFilePath,
                firstObservation, lastObservation, observationCount, distanceKm, isActivated, packId
            ) VALUES (
                'green_turtle:03', 'Green Sea Turtle', 'Chelonia mydas', '03', 'Study C', 'CC-BY', 'Source C',
                'Nalu', 'Ocean navigator', 'Intro Nalu', '#5a8f63', null, '/path/track3.json',
                '2021-01-01', '2021-02-01', 80, 900.0, 1, 'starter-pack'
            )
            """.trimIndent()
        )
        statement.execute(
            "INSERT INTO unlocked_animals (animalId, unlockedAt, unlockMethod, usedCode) VALUES ('green_turtle:03', 1700000000000, 'CODE', 'WILD-0002-CODE')"
        )
        statement.execute(
            "INSERT INTO packs (packId, title, importTimestamp, animalCount) VALUES ('starter-pack', 'Starters', 1700000000000, 1)"
        )

        val supportDb = createSupportDbProxy(statement)
        AppDatabase.MIGRATION_2_3.migrate(supportDb)

        // Verify unlock record intact
        val unlockRs = statement.executeQuery("SELECT usedCode FROM unlocked_animals WHERE animalId = 'green_turtle:03'")
        assertTrue(unlockRs.next())
        assertEquals("WILD-0002-CODE", unlockRs.getString("usedCode"))
        unlockRs.close()

        // Verify backfilled timeline segment
        val segRs = statement.executeQuery("SELECT trackFilePath, observationCount, replayStartAtUtcMs FROM timeline_segments WHERE animalId = 'green_turtle:03'")
        assertTrue(segRs.next())
        assertEquals("/path/track3.json", segRs.getString("trackFilePath"))
        assertEquals(80, segRs.getInt("observationCount"))
        assertEquals(null, segRs.getObject("replayStartAtUtcMs"))
        segRs.close()

        statement.close()
        conn.close()
    }
}
