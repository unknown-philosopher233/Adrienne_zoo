package com.adriennezoo.companion

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adriennezoo.companion.data.AppDatabase
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.DriverManager

class DatabaseMigrationTest {

    @Test
    fun testMigrationFromV1ToV2PreservesDataAndMigratesUnlocks() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val statement = conn.createStatement()

        // 1. Load schema v1 definition from exported 1.json
        val schema1File = File("schemas/com.adriennezoo.companion.data.AppDatabase/1.json")
        val schema1Content = schema1File.readText(Charsets.UTF_8)
        val schemaObj = JsonParser.parseString(schema1Content).asJsonObject
        val entities = schemaObj.getAsJsonObject("database").getAsJsonArray("entities")

        for (elem in entities) {
            val entityObj = elem.asJsonObject
            val tableName = entityObj.get("tableName").asString
            val createSql = entityObj.get("createSql").asString.replace("\${TABLE_NAME}", tableName)
            statement.execute(createSql)
        }

        // 2. Insert v1 test records: 1 activated animal, 1 unactivated animal, codes, pack
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

        // 3. Run AppDatabase.MIGRATION_1_2 via a lightweight SupportSQLiteDatabase proxy
        val supportDb = Proxy.newProxyInstance(
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

        AppDatabase.MIGRATION_1_2.migrate(supportDb)

        // 4. Verify unlocked_animals table was populated with the activated animal
        val unlockRs = statement.executeQuery(
            "SELECT animalId, unlockMethod FROM unlocked_animals WHERE animalId = 'blue_whale:01'"
        )
        assertTrue("Activated animal must be present in unlocked_animals", unlockRs.next())
        assertEquals("blue_whale:01", unlockRs.getString("animalId"))
        assertEquals("MIGRATION", unlockRs.getString("unlockMethod"))
        unlockRs.close()

        // 5. Verify unactivated animal was NOT added to unlocked_animals
        val unactivatedRs = statement.executeQuery(
            "SELECT COUNT(*) as cnt FROM unlocked_animals WHERE animalId = 'caribou:02'"
        )
        assertTrue(unactivatedRs.next())
        assertEquals(0, unactivatedRs.getInt("cnt"))
        unactivatedRs.close()

        // 6. Verify catalog_meta table exists and can be queried
        statement.execute(
            "INSERT INTO catalog_meta (`key`, `version`, `updatedAt`) VALUES ('starter_catalog_version', 1, 1700000000000)"
        )
        val metaRs = statement.executeQuery("SELECT `version` FROM catalog_meta WHERE `key` = 'starter_catalog_version'")
        assertTrue(metaRs.next())
        assertEquals(1, metaRs.getInt("version"))
        metaRs.close()

        // 7. Verify all original animal data, codes, and packs are intact
        val animalRs = statement.executeQuery("SELECT COUNT(*) as cnt FROM animals")
        assertTrue(animalRs.next())
        assertEquals(2, animalRs.getInt("cnt"))
        animalRs.close()

        val codesRs = statement.executeQuery("SELECT code, animalId FROM activation_codes")
        assertTrue(codesRs.next())
        assertEquals("WILD-0003-CODE", codesRs.getString("code"))
        assertEquals("blue_whale:01", codesRs.getString("animalId"))
        codesRs.close()

        val packsRs = statement.executeQuery("SELECT title, animalCount FROM packs")
        assertTrue(packsRs.next())
        assertEquals("Starters", packsRs.getString("title"))
        assertEquals(2, packsRs.getInt("animalCount"))
        packsRs.close()

        statement.close()
        conn.close()
    }
}
