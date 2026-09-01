package com.agentkosticka.easierspot.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentkosticka.easierspot.data.model.RememberedServer
import com.agentkosticka.easierspot.data.model.TrustedServerEntity
import com.agentkosticka.easierspot.data.model.RememberedServer.Companion.APPROVAL_POLICY_APPROVED

@Database(
    entities = [RememberedServer::class, TrustedServerEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rememberedServerDao(): RememberedServerDao
    abstract fun trustedServerDao(): TrustedServerDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE remembered_servers ADD COLUMN deviceAddress TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE remembered_servers ADD COLUMN nickname TEXT"
                )
                db.execSQL(
                    "ALTER TABLE remembered_servers ADD COLUMN approvalPolicy TEXT NOT NULL DEFAULT '$APPROVAL_POLICY_APPROVED'"
                )
                db.execSQL(
                    "ALTER TABLE remembered_servers ADD COLUMN lastApprovedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS trusted_servers (
                        fingerprint TEXT NOT NULL PRIMARY KEY,
                        discoveryToken TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        nickname TEXT,
                        ssid TEXT NOT NULL,
                        networkRevision INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        serverPublicKey TEXT NOT NULL,
                        wakeCounter INTEGER NOT NULL,
                        alertsEnabled INTEGER NOT NULL,
                        lastSuccessfulMethod TEXT,
                        lastAlertAt INTEGER NOT NULL,
                        lastAlertRevision INTEGER NOT NULL,
                        lastPresenceAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_trusted_servers_discoveryToken " +
                        "ON trusted_servers(discoveryToken)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trusted_servers RENAME TO trusted_servers_v4")
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
                    """
                    INSERT INTO trusted_servers (
                        fingerprint, discoveryToken, displayName, nickname, ssid,
                        advertisedRevision, provisionedRevision, securityType, isHidden,
                        lastSeen, serverPublicKey, wakeCounter, alertsEnabled,
                        lastSuccessfulMethod, suggestionLatencyMs, shizukuLatencyMs,
                        controlCounter, lastAlertAt, lastAlertRevision, lastPresenceAt
                    ) SELECT
                        fingerprint, discoveryToken, displayName, nickname, ssid,
                        networkRevision, 0, 'UNKNOWN', 0,
                        lastSeen, serverPublicKey, wakeCounter, alertsEnabled,
                        lastSuccessfulMethod, 0, 0, 0, lastAlertAt,
                        lastAlertRevision, lastPresenceAt
                    FROM trusted_servers_v4
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE trusted_servers_v4")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_trusted_servers_discoveryToken " +
                        "ON trusted_servers(discoveryToken)"
                )
                db.execSQL("ALTER TABLE remembered_servers ADD COLUMN clientPublicKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE remembered_servers ADD COLUMN wakeCounter INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remembered_servers ADD COLUMN controlCounter INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remembered_servers ADD COLUMN lastControlSeen INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "easierspot_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
