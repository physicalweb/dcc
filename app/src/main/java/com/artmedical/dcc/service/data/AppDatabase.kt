package com.artmedical.dcc.service.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [EventEntity::class, ReportEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun reportDao(): ReportDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pending_reports` (
                        `reportId` TEXT NOT NULL,
                        `deviceSerial` TEXT NOT NULL,
                        `patientId` TEXT NOT NULL,
                        `reportType` TEXT NOT NULL,
                        `reportDate` TEXT NOT NULL,
                        `generatedAt` INTEGER NOT NULL,
                        `pageCount` INTEGER NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `localFilePath` TEXT NOT NULL,
                        `s3Key` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`reportId`)
                    )
                """.trimIndent())
            }
        }
    }
}
