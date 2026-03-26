package com.artmedical.cloud.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ReportMetadata(
    val reportId: String,
    val deviceSerial: String,
    val patientId: String,
    val reportType: String,
    val reportDate: String,
    val generatedAt: Long,
    val pageCount: Int,
    val fileSizeBytes: Long
) : Parcelable
