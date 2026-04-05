package com.artmedical.dcc

import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.artmedical.cloud.api.CloudEventParcel
import com.artmedical.cloud.api.ICloudConnectService
import com.artmedical.dcc.service.data.AppDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DccSmokeTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private fun bindService(): ICloudConnectService {
        val intent = Intent("com.artmedical.dcc.CLOUD_CONNECT")
        intent.setPackage("com.artmedical.dcc")
        val binder = serviceRule.bindService(intent)
        return ICloudConnectService.Stub.asInterface(binder)
    }

    @Test
    fun serviceCanBind() {
        val service = bindService()
        assertThat(service).isNotNull()
    }

    @Test
    fun publishSingleEvent_doesNotCrash() {
        val service = bindService()
        val event = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = "test",
            type = "system/metadata",
            time = System.currentTimeMillis(),
            priority = 0,
            dataContentType = "application/json",
            dataJson = """{"test":true}"""
        )
        service.publishEvent(event)
        // No exception = pass
    }

    @Test
    fun publishHighPriorityEvent_doesNotCrash() {
        val service = bindService()
        val event = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = "test",
            type = "events/clinical",
            time = System.currentTimeMillis(),
            priority = 2,
            dataContentType = "application/json",
            dataJson = """{"event_type":"OCCLUSION","severity":"CRITICAL"}"""
        )
        service.publishEvent(event)
    }

    @Test
    fun databaseOpens_withoutMigrationCrash() {
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            "dcc-smoke-test-db"
        ).addMigrations(AppDatabase.MIGRATION_1_2).build()

        assertThat(db.eventDao()).isNotNull()
        assertThat(db.reportDao()).isNotNull()
        db.close()
    }
}
