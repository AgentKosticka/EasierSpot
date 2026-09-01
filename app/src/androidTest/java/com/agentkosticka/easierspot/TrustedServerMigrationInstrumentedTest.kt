package com.agentkosticka.easierspot

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentkosticka.easierspot.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustedServerMigrationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun deleteFixtureDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration5To6_retainsTrustedServerAndDefaultsPresenceFlagsToZero() {
        openHelper(
            version = 5,
            onCreate = { db ->
                db.execSQL(
                    """
                    CREATE TABLE trusted_servers (
                        fingerprint TEXT NOT NULL PRIMARY KEY,
                        discoveryToken TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        nickname TEXT,
                        ssid TEXT NOT NULL,
                        advertisedRevision INTEGER NOT NULL,
                        provisionedRevision INTEGER NOT NULL,
                        securityType TEXT NOT NULL,
                        isHidden INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        serverPublicKey TEXT NOT NULL,
                        wakeCounter INTEGER NOT NULL,
                        alertsEnabled INTEGER NOT NULL,
                        lastSuccessfulMethod TEXT,
                        suggestionLatencyMs INTEGER NOT NULL,
                        shizukuLatencyMs INTEGER NOT NULL,
                        controlCounter INTEGER NOT NULL,
                        lastAlertAt INTEGER NOT NULL,
                        lastAlertRevision INTEGER NOT NULL,
                        lastPresenceAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX index_trusted_servers_discoveryToken " +
                        "ON trusted_servers(discoveryToken)"
                )
                db.execSQL(
                    """
                    INSERT INTO trusted_servers (
                        fingerprint, discoveryToken, displayName, nickname, ssid,
                        advertisedRevision, provisionedRevision, securityType, isHidden,
                        lastSeen, serverPublicKey, wakeCounter, alertsEnabled,
                        lastSuccessfulMethod, suggestionLatencyMs, shizukuLatencyMs,
                        controlCounter, lastAlertAt, lastAlertRevision, lastPresenceAt
                    ) VALUES (
                        'fixture-fingerprint', 'fixture-token', 'Fixture Phone', NULL, 'FixtureSpot',
                        7, 7, 'WPA2_PSK', 0,
                        1234, 'fixture-key', 4, 1,
                        'SUGGESTION', 100, 200,
                        9, 1111, 7, 2222
                    )
                    """.trimIndent()
                )
            }
        ).also { helper ->
            helper.writableDatabase
            helper.close()
        }

        val migrated = openHelper(
            version = 6,
            onCreate = { error("Migration fixture unexpectedly recreated") },
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(5, oldVersion)
                assertEquals(6, newVersion)
                AppDatabase.MIGRATION_5_6.migrate(db)
            }
        )

        migrated.writableDatabase.useDatabase { db ->
            val columns = mutableMapOf<String, String?>()
            db.query("PRAGMA table_info(trusted_servers)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) {
                    columns[cursor.getString(nameIndex)] = cursor.getString(defaultIndex)
                }
            }
            assertTrue(columns.containsKey("lastPresenceFlags"))
            assertEquals("0", columns["lastPresenceFlags"])

            db.query(
                "SELECT fingerprint, lastPresenceFlags FROM trusted_servers " +
                    "WHERE fingerprint = 'fixture-fingerprint'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-fingerprint", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
        migrated.close()
    }

    private fun openHelper(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }
    ): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = onCreate.invoke(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                onUpgrade.invoke(db, oldVersion, newVersion)
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    private inline fun <T> SupportSQLiteDatabase.useDatabase(block: (SupportSQLiteDatabase) -> T): T =
        block(this)

    companion object {
        private const val DB_NAME = "migration-5-6-test.db"
    }
}
