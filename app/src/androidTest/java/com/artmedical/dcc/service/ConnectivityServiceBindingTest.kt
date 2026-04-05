package com.artmedical.dcc.service

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.artmedical.cloud.api.CloudEventParcel
import com.artmedical.cloud.api.ICloudConnectService
import com.artmedical.cloud.api.ICloudEventListener
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConnectivityServiceBindingTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private fun bindService(): ICloudConnectService {
        val intent = Intent("com.artmedical.dcc.CLOUD_CONNECT")
        intent.setPackage("com.artmedical.dcc")
        val binder = serviceRule.bindService(intent)
        return ICloudConnectService.Stub.asInterface(binder)
    }

    @Test
    fun bindService_returnsNonNullBinder() {
        val intent = Intent("com.artmedical.dcc.CLOUD_CONNECT")
        intent.setPackage("com.artmedical.dcc")
        val binder = serviceRule.bindService(intent)
        assertThat(binder).isNotNull()
    }

    @Test
    fun bindService_binderImplementsICloudConnectService() {
        val service = bindService()
        assertThat(service).isNotNull()
    }

    @Test
    fun registerAndUnregisterListener_doesNotThrow() {
        val service = bindService()
        val listener = object : ICloudEventListener.Stub() {
            override fun onEventReceived(event: CloudEventParcel) {
                // no-op
            }
        }
        service.registerListener(listener)
        service.unregisterListener(listener)
    }

    @Test
    fun publishMultipleEvents_allSucceed() {
        val service = bindService()
        repeat(5) { i ->
            val event = CloudEventParcel(
                id = UUID.randomUUID().toString(),
                source = "test",
                type = "system/metadata",
                time = System.currentTimeMillis(),
                priority = i % 3, // mix of 0, 1, 2
                dataContentType = "application/json",
                dataJson = """{"index":$i}"""
            )
            service.publishEvent(event)
        }
        // No exceptions = pass
    }
}
