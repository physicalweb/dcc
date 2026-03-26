package com.artmedical.dcc.service.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ReportDao {
    @Insert
    suspend fun insert(report: ReportEntity)

    @Query("SELECT * FROM pending_reports WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextPendingReport(): ReportEntity?

    @Query("UPDATE pending_reports SET status = :status, lastError = :error, retryCount = retryCount + 1 WHERE reportId = :reportId")
    suspend fun updateStatus(reportId: String, status: String, error: String? = null)

    @Query("UPDATE pending_reports SET status = :status WHERE reportId = :reportId")
    suspend fun setStatus(reportId: String, status: String)

    @Query("DELETE FROM pending_reports WHERE reportId = :reportId")
    suspend fun delete(reportId: String)
}
