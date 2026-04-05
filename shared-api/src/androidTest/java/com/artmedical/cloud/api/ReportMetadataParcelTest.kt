package com.artmedical.cloud.api

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportMetadataParcelTest {

    private fun roundTrip(original: ReportMetadata): ReportMetadata {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(original, 0)
            parcel.setDataPosition(0)
            return parcel.readParcelable(ReportMetadata::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun parcelRoundTrip_allFieldsPreserved() {
        val original = ReportMetadata(
            reportId = "rpt-001",
            deviceSerial = "serial-abc",
            patientId = "pat-001",
            reportType = "DAILY_SUMMARY",
            reportDate = "2026-03-26",
            generatedAt = 1708819200000L,
            pageCount = 12,
            fileSizeBytes = 245760L
        )
        val restored = roundTrip(original)
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun parcelRoundTrip_zeroBoundaryValues() {
        val original = ReportMetadata(
            reportId = "rpt-zero",
            deviceSerial = "",
            patientId = "",
            reportType = "",
            reportDate = "",
            generatedAt = 0L,
            pageCount = 0,
            fileSizeBytes = 0L
        )
        val restored = roundTrip(original)
        assertThat(restored).isEqualTo(original)
    }
}
