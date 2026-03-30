package com.agentkosticka.easierspot.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentkosticka.easierspot.data.model.RememberedServer
import com.agentkosticka.easierspot.data.model.RememberedServer.Companion.APPROVAL_POLICY_APPROVED

@Database(entities = [RememberedServer::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rememberedServerDao(): RememberedServerDao

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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "easierspot_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
