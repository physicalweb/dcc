package com.artmedical.dcc.service.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ReportDao

    private fun makeReport(
        reportId: String = "rpt-001",
        status: String = "PENDING",
        createdAt: Long = 1000L,
        retryCount: Int = 0
    ) = ReportEntity(
        reportId = reportId,
        deviceSerial = "serial-abc",
        patientId = "pat-001",
        reportType = "DAILY_SUMMARY",
        reportDate = "2026-03-26",
        generatedAt = 1000L,
        pageCount = 5,
        fileSizeBytes = 245760,
        localFilePath = "/data/pending_reports/$reportId.pdf",
        s3Key = "reports/serial-abc/2026-03-26/$reportId.pdf",
        status = status,
        createdAt = createdAt,
        retryCount = retryCount
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.reportDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_thenGetNextPending_returnsIt() = runTest {
        dao.insert(makeReport())
        val next = dao.getNextPendingReport()
        assertThat(next).isNotNull()
        assertThat(next!!.reportId).isEqualTo("rpt-001")
    }

    @Test
    fun getNextPendingReport_empty_returnsNull() = runTest {
        assertThat(dao.getNextPendingReport()).isNull()
    }

    @Test
    fun getNextPendingReport_onlyUploadedReports_returnsNull() = runTest {
        dao.insert(makeReport(status = "UPLOADED"))
        assertThat(dao.getNextPendingReport()).isNull()
    }

    @Test
    fun getNextPendingReport_failedReportIsRetried() = runTest {
        dao.insert(makeReport(status = "FAILED"))
        val next = dao.getNextPendingReport()
        assertThat(next).isNotNull()
        assertThat(next!!.status).isEqualTo("FAILED")
    }

    @Test
    fun getNextPendingReport_orderedByCreatedAt() = runTest {
        dao.insert(makeReport(reportId = "newer", createdAt = 2000L))
        dao.insert(makeReport(reportId = "older", createdAt = 1000L))
        val next = dao.getNextPendingReport()
        assertThat(next!!.reportId).isEqualTo("older")
    }

    @Test
    fun updateStatus_incrementsRetryCount() = runTest {
        dao.insert(makeReport())
        dao.updateStatus("rpt-001", "FAILED", "connection timeout")
        val report = dao.getNextPendingReport()
        assertThat(report!!.retryCount).isEqualTo(1)
        assertThat(report.lastError).isEqualTo("connection timeout")
        assertThat(report.status).isEqualTo("FAILED")
    }

    @Test
    fun updateStatus_calledTwice_incrementsTwice() = runTest {
        dao.insert(makeReport())
        dao.updateStatus("rpt-001", "FAILED", "err1")
        dao.updateStatus("rpt-001", "FAILED", "err2")
        val report = dao.getNextPendingReport()
        assertThat(report!!.retryCount).isEqualTo(2)
        assertThat(report.lastError).isEqualTo("err2")
    }

    @Test
    fun setStatus_doesNotIncrementRetryCount() = runTest {
        dao.insert(makeReport())
        dao.setStatus("rpt-001", "UPLOADING")
        // UPLOADING is not PENDING or FAILED, so won't be returned by getNextPendingReport
        // Query directly via a separate insert+check
        dao.setStatus("rpt-001", "PENDING")
        val report = dao.getNextPendingReport()
        assertThat(report!!.retryCount).isEqualTo(0)
    }

    @Test
    fun delete_removesReport() = runTest {
        dao.insert(makeReport())
        dao.delete("rpt-001")
        assertThat(dao.getNextPendingReport()).isNull()
    }
}
