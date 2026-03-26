package com.artmedical.dcc.service.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_reports")
data class ReportEntity(
    @PrimaryKey val reportId: String,
    val deviceSerial: String,
    val patientId: String,
    val reportType: String,
    val reportDate: String,
    val generatedAt: Long,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val localFilePath: String,
    val s3Key: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null
)
