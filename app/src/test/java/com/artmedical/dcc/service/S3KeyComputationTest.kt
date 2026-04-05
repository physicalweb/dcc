package com.artmedical.dcc.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class S3KeyComputationTest {

    @Test
    fun computeS3Key_normalInputs_returnsExpectedPath() {
        val key = ReportUploadManager.computeS3Key("serial-abc", "2026-01-15", "rpt-001")
        assertThat(key).isEqualTo("reports/serial-abc/2026-01-15/rpt-001.pdf")
    }

    @Test
    fun computeS3Key_serialWithUuid_returnsCorrectPath() {
        val key = ReportUploadManager.computeS3Key(
            "2bbdd854-2655-4699-b46a-16fe15a5710d",
            "2026-03-26",
            "550e8400-e29b-41d4-a716-446655440000"
        )
        assertThat(key).isEqualTo(
            "reports/2bbdd854-2655-4699-b46a-16fe15a5710d/2026-03-26/550e8400-e29b-41d4-a716-446655440000.pdf"
        )
    }

    @Test
    fun computeS3Key_emptyStrings_returnsSkeletonPath() {
        val key = ReportUploadManager.computeS3Key("", "", "")
        assertThat(key).isEqualTo("reports///.pdf")
    }
}
