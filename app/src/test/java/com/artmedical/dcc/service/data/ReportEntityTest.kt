package com.artmedical.dcc.service.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportEntityTest {

    private fun makeReport(
        reportId: String = "rpt-001",
        status: String = "PENDING",
        retryCount: Int = 0,
        lastError: String? = null
    ) = ReportEntity(
        reportId = reportId,
        deviceSerial = "serial-abc",
        patientId = "pat-001",
        reportType = "DAILY_SUMMARY",
        reportDate = "2026-03-26",
        generatedAt = 1000L,
        pageCount = 5,
        fileSizeBytes = 245760,
        localFilePath = "/data/pending_reports/rpt-001.pdf",
        s3Key = "reports/serial-abc/2026-03-26/rpt-001.pdf",
        status = status,
        retryCount = retryCount,
        lastError = lastError
    )

    @Test
    fun defaults_retryCountIsZero() {
        val report = makeReport()
        assertThat(report.retryCount).isEqualTo(0)
    }

    @Test
    fun defaults_lastErrorIsNull() {
        val report = makeReport()
        assertThat(report.lastError).isNull()
    }

    @Test
    fun defaults_createdAtIsNonZero() {
        val before = System.currentTimeMillis()
        val report = makeReport()
        val after = System.currentTimeMillis()
        assertThat(report.createdAt).isAtLeast(before)
        assertThat(report.createdAt).isAtMost(after)
    }

    @Test
    fun explicitOverrides_respectExplicitValues() {
        val report = makeReport(retryCount = 5, lastError = "timeout")
        assertThat(report.retryCount).isEqualTo(5)
        assertThat(report.lastError).isEqualTo("timeout")
    }

    @Test
    fun equality_sameFieldsAreEqual() {
        val a = ReportEntity(
            reportId = "r1", deviceSerial = "s", patientId = "p",
            reportType = "DAILY_SUMMARY", reportDate = "2026-01-01",
            generatedAt = 0, pageCount = 1, fileSizeBytes = 100,
            localFilePath = "/f", s3Key = "k", status = "PENDING",
            createdAt = 999, retryCount = 0, lastError = null
        )
        val b = a.copy()
        assertThat(a).isEqualTo(b)
    }
}
