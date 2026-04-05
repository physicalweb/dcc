package com.artmedical.dcc.service

import com.artmedical.dcc.service.data.ReportEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportMetadataJsonTest {

    private fun makeReport() = ReportEntity(
        reportId = "rpt-abc-123",
        deviceSerial = "serial-001",
        patientId = "pat-001",
        reportType = "DAILY_SUMMARY",
        reportDate = "2026-03-26",
        generatedAt = 1000L,
        pageCount = 5,
        fileSizeBytes = 245760,
        localFilePath = "/tmp/report.pdf",
        s3Key = "reports/serial-001/2026-03-26/rpt-abc-123.pdf",
        status = "UPLOADED"
    )

    @Test
    fun buildJson_containsAllRequiredFields() {
        val json = ConnectivityService.buildReportMetadataJson(makeReport(), timestamp = 9999L)
        assertThat(json).contains(""""report_id":"rpt-abc-123"""")
        assertThat(json).contains(""""s3_key":"reports/serial-001/2026-03-26/rpt-abc-123.pdf"""")
        assertThat(json).contains(""""report_date":"2026-03-26"""")
        assertThat(json).contains(""""report_type":"DAILY_SUMMARY"""")
        assertThat(json).contains(""""size_bytes":245760""")
        assertThat(json).contains(""""timestamp":9999""")
    }

    @Test
    fun buildJson_isValidJsonStructure() {
        val json = ConnectivityService.buildReportMetadataJson(makeReport(), timestamp = 0L)
        assertThat(json).startsWith("{")
        assertThat(json).endsWith("}")
    }
}
