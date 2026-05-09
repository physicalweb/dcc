package com.artmedical.dcc.service

import android.util.Log
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.artmedical.dcc.service.data.ReportDao
import com.artmedical.dcc.service.data.ReportEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Uploads PDF reports to S3 via a presigned URL flow.
 *
 * Flow:
 *   1. Subscribe to pump-fleet/<thing>/reports/upload-url/response
 *   2. Publish reportId to pump-fleet/<thing>/reports/upload-url/request
 *   3. Cloud Lambda responds with a 1-hour presigned PUT URL
 *   4. HTTP PUT the PDF bytes to that URL (no auth headers — embedded in URL)
 *
 * The MQTT connection (mTLS) carries the request/response. The device never
 * holds AWS credentials directly.
 */
class ReportUploadManager(
    private val mqttManager: AWSIotMqttManager,
    private val reportDao: ReportDao,
    private val thingName: String,
) {
    private val tag = "DCC-ReportUpload"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun uploadToS3(report: ReportEntity): Boolean {
        val file = File(report.localFilePath)
        if (!file.exists()) {
            Log.e(tag, "Local file not found: ${report.localFilePath}")
            reportDao.updateStatus(report.reportId, "FAILED", "Local file not found")
            return false
        }

        val url = withTimeoutOrNull(URL_REQUEST_TIMEOUT_MS) {
            requestPresignedUrl(report.reportId)
        }
        if (url == null) {
            Log.e(tag, "Presigned URL request timed out for ${report.reportId}")
            reportDao.updateStatus(report.reportId, "FAILED", "URL request timed out")
            return false
        }

        reportDao.setStatus(report.reportId, "UPLOADING")
        return try {
            val request = Request.Builder()
                .url(url)
                .put(file.asRequestBody("application/pdf".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(tag, "Upload complete: ${report.s3Key}")
                    true
                } else {
                    Log.e(tag, "Upload failed HTTP ${response.code}: ${report.s3Key}")
                    reportDao.updateStatus(report.reportId, "FAILED", "HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Upload exception for ${report.s3Key}", e)
            reportDao.updateStatus(report.reportId, "FAILED", e.message)
            false
        }
    }

    private suspend fun requestPresignedUrl(reportId: String): String =
        suspendCancellableCoroutine { continuation ->
            val responseTopic = "pump-fleet/$thingName/reports/upload-url/response"
            val requestTopic = "pump-fleet/$thingName/reports/upload-url/request"

            try {
                // Subscribe FIRST, then publish — avoid the race where the response
                // arrives before our subscription is established.
                mqttManager.subscribeToTopic(
                    responseTopic,
                    AWSIotMqttQos.QOS0,
                    AWSIotMqttNewMessageCallback { _, data ->
                        try {
                            val json = JSONObject(String(data))
                            val url = json.getString("url")
                            if (continuation.isActive) {
                                continuation.resume(url)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to parse upload-url response", e)
                            if (continuation.isActive) continuation.cancel(e)
                        } finally {
                            try { mqttManager.unsubscribeTopic(responseTopic) } catch (_: Exception) {}
                        }
                    }
                )
                val payload = JSONObject().put("reportId", reportId).toString()
                mqttManager.publishString(payload, requestTopic, AWSIotMqttQos.QOS0)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.cancel(e)
            }
        }

    companion object {
        private const val URL_REQUEST_TIMEOUT_MS = 10_000L

        fun computeS3Key(deviceSerial: String, reportDate: String, reportId: String): String =
            "reports/$deviceSerial/$reportDate/$reportId.pdf"
    }
}
