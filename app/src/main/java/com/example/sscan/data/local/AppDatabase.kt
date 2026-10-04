package com.example.sscan.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.sscan.data.local.dao.ScanHistoryDao
import com.example.sscan.data.local.entity.ScanHistoryEntity

@Database(
    entities = [ScanHistoryEntity::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanHistoryDao(): ScanHistoryDao

    companion object {
        const val DATABASE_NAME = "s_scan.db"
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_history_result ON scan_history(result)")
            }
        }
    }
}
