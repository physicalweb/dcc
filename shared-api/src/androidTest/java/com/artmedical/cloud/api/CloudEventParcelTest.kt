package com.artmedical.cloud.api

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudEventParcelTest {

    private fun roundTrip(original: CloudEventParcel): CloudEventParcel {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(original, 0)
            parcel.setDataPosition(0)
            return parcel.readParcelable(CloudEventParcel::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun parcelRoundTrip_allFieldsPreserved() {
        val original = CloudEventParcel(
            id = "evt-001",
            source = "pump-controller",
            type = "sys/device/dev001/status",
            time = 1708819200000L,
            priority = 2,
            dataContentType = "application/json",
            dataJson = """{"state":"FEEDING","battery":{"percent":78}}"""
        )
        val restored = roundTrip(original)
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun parcelRoundTrip_emptyStrings() {
        val original = CloudEventParcel(
            id = "", source = "", type = "", time = 0L,
            priority = 0, dataContentType = "", dataJson = ""
        )
        val restored = roundTrip(original)
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun parcelRoundTrip_largeDataJson() {
        val largeJson = "{\"data\":\"${"x".repeat(100_000)}\"}"
        val original = CloudEventParcel(
            id = "big", source = "test", type = "test/large",
            time = 1L, priority = 0,
            dataContentType = "application/json",
            dataJson = largeJson
        )
        val restored = roundTrip(original)
        assertThat(restored.dataJson).hasLength(largeJson.length)
        assertThat(restored).isEqualTo(original)
    }
}
