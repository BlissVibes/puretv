package com.puretv.twitch.android.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Room refuses to open a migrated database whose table differs from the one it
 * generates, and there is no device to find that out on. So the migration's SQL
 * is compared with the `createSql` Room exported to `schemas/.../2.json`.
 */
class VodProgressMigrationTest {

    @Test
    fun migrationSqlMatchesTheExportedSchema() {
        val schema = File("schemas/com.puretv.twitch.android.data.db.PureTvDatabase/2.json")
        assertTrue(schema.exists(), "run :app-android:assembleDebug to export ${schema.path}")
        val text = schema.readText()
        // The vod_progress entity's createSql, unescaped from the JSON string.
        val match = Regex("\"tableName\"\\s*:\\s*\"vod_progress\"\\s*,\\s*\"createSql\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(text) ?: error("vod_progress is missing from 2.json")
        val createSql = match.groupValues[1].replace("\\u0060", "`").replace("\\\"", "\"")
        assertEquals(createSql.replace("\${TABLE_NAME}", "vod_progress"), VOD_PROGRESS_CREATE_SQL)
    }
}
