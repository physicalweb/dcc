package com.artmedical.dcc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttMessageDeliveryCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.amazonaws.regions.Regions
import com.artmedical.cloud.api.CloudEventParcel
import com.artmedical.cloud.api.ICloudConnectService
import com.artmedical.cloud.api.ICloudEventListener
import com.artmedical.cloud.api.ReportMetadata
import com.artmedical.dcc.BuildConfig
import com.artmedical.dcc.service.data.AppDatabase
import com.artmedical.dcc.service.data.EventEntity
import com.artmedical.dcc.service.data.ReportEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class ConnectivityService : Service() {

    private val tag = "DCC-Service"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Test to prove shared-api visibility
    private val heartbeatTest: com.artmedical.cloud.api.IHeartbeatService? = null

    private val NOTIFICATION_CHANNEL_ID = "DCC_Service_Channel"
    private val NOTIFICATION_ID = 1

    // --- AWS IoT Core Configuration ---
    // Make sure these are set in your local.properties!
    private val CUSTOMER_SPECIFIC_ENDPOINT = BuildConfig.AWS_IOT_ENDPOINT
    private val COGNITO_POOL_ID = BuildConfig.COGNITO_POOL_ID
    private val AWS_REGION = Regions.US_EAST_1

    // Stable device identity — UUID generated once, persisted in SharedPreferences
    private lateinit var DEVICE_SERIAL: String
    private lateinit var DOWNLINK_TOPIC: String

    // Basic Ingest prefix — bypasses broker, routes directly to IoT Rule ($0.08/M vs $1.00/M)
    private val BASIC_INGEST_PREFIX = "\$aws/rules/smart_ingest"

    private lateinit var mqttManager: AWSIotMqttManager
    private lateinit var credentialsProvider: CognitoCachingCredentialsProvider
    private lateinit var connectivityManager: ConnectivityManager

    private val medicalListeners = RemoteCallbackList<ICloudEventListener>()
    private lateinit var database: AppDatabase
    private val isProcessing = AtomicBoolean(false)
    private val isProcessingReports = AtomicBoolean(false)
    private var reportUploadManager: ReportUploadManager? = null
    private lateinit var rawDeviceSerial: String

    private val networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(tag, "Network available, triggering queue processing.")
                    processQueue()
                    processReportQueue()
                }
            }

    private val binder =
            object : ICloudConnectService.Stub() {
                override fun publishEvent(event: CloudEventParcel) {
                    Log.v(tag, "Received Upstream: ${event.type} [Pri: ${event.priority}]")
                    serviceScope.launch {
                        val entity =
                                EventEntity(
                                        id = event.id,
                                        source = event.source,
                                        type = event.type,
                                        time = event.time,
                                        priority = event.priority,
                                        dataContentType = event.dataContentType,
                                        dataJson = event.dataJson
                                )
                        database.eventDao().insert(entity)
                        processQueue()
                    }
                }

                override fun registerListener(listener: ICloudEventListener) {
                    medicalListeners.register(listener)
                    Log.i(tag, "Medical APK registered for commands.")
                }

                override fun unregisterListener(listener: ICloudEventListener) {
                    medicalListeners.unregister(listener)
                    Log.i(tag, "Medical APK unregistered.")
                }

                override fun uploadReport(metadata: ReportMetadata, pfd: ParcelFileDescriptor) {
                    Log.i(tag, "Received report upload: ${metadata.reportId}")
                    serviceScope.launch {
                        try {
                            val reportsDir = File(filesDir, "pending_reports")
                            reportsDir.mkdirs()
                            val localFile = File(reportsDir, "${metadata.reportId}.pdf")

                            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                                localFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            val s3Key = ReportUploadManager.computeS3Key(
                                rawDeviceSerial, metadata.reportDate, metadata.reportId
                            )

                            val entity = ReportEntity(
                                reportId = metadata.reportId,
                                deviceSerial = rawDeviceSerial,
                                patientId = metadata.patientId,
                                reportType = metadata.reportType,
                                reportDate = metadata.reportDate,
                                generatedAt = metadata.generatedAt,
                                pageCount = metadata.pageCount,
                                fileSizeBytes = metadata.fileSizeBytes,
                                localFilePath = localFile.absolutePath,
                                s3Key = s3Key,
                                status = "PENDING"
                            )
                            database.reportDao().insert(entity)
                            processReportQueue()
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to handle report upload", e)
                        }
                    }
                }
            }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())

        // Resolve device serial — priority order:
        // 1. Manufacturing-assigned system property (production devices)
        // 2. Build config override (lab/staging)
        // 3. Persisted UUID fallback (development only)
        rawDeviceSerial = resolveDeviceSerial()
        DEVICE_SERIAL = "pump-fleet/$rawDeviceSerial"
        DOWNLINK_TOPIC = "$DEVICE_SERIAL/cmd/#"
        Log.i(tag, "Device serial: $DEVICE_SERIAL")

        database =
                Room.databaseBuilder(applicationContext, AppDatabase::class.java, "dcc-database")
                        .addMigrations(AppDatabase.MIGRATION_1_2)
                        .build()

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        serviceScope.launch { connectToAwsIot() }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                                    NOTIFICATION_CHANNEL_ID,
                                    "DCC Connectivity Service",
                                    NotificationManager.IMPORTANCE_LOW
                            )
                            .apply {
                                description = "Notification channel for the DCC background service."
                            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("DCC Service")
                .setContentText("Connected to the cloud gateway.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
    }

    private suspend fun connectToAwsIot() {
        credentialsProvider =
                CognitoCachingCredentialsProvider(applicationContext, COGNITO_POOL_ID, AWS_REGION)

        reportUploadManager = ReportUploadManager(
            applicationContext, credentialsProvider, database.reportDao()
        )

        Log.i(tag, "Connecting to endpoint: $CUSTOMER_SPECIFIC_ENDPOINT")
        Log.i(tag, "Connecting with Client ID: $DEVICE_SERIAL")

        mqttManager = AWSIotMqttManager(DEVICE_SERIAL, CUSTOMER_SPECIFIC_ENDPOINT)

        try {
            mqttManager.connect(
                    credentialsProvider,
                    AWSIotMqttClientStatusCallback { status, throwable ->
                        when (status) {
                            AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                                Log.i(tag, "Connected to AWS IoT")
                                subscribeToDownlink()
                                processQueue()
                                processReportQueue()
                            }
                            AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connecting ->
                                    Log.i(tag, "Connecting...")
                            AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Reconnecting ->
                                    Log.i(tag, "Reconnecting...")
                            AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus
                                    .ConnectionLost -> {
                                Log.e(tag, "Connection lost: ", throwable)
                            }
                            else -> {}
                        }
                    }
            )
        } catch (e: Exception) {
            Log.e(tag, "Connection to AWS IoT failed!", e)
        }
    }

    private fun subscribeToDownlink() {
        try {
            mqttManager.subscribeToTopic(
                    DOWNLINK_TOPIC,
                    AWSIotMqttQos.QOS1,
                    AWSIotMqttNewMessageCallback { topic, data ->
                        Log.d(tag, "Downlink message received: $topic")
                        onCloudCommandReceived(topic, String(data))
                    }
            )
        } catch (e: Exception) {
            Log.e(tag, "Subscription error", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(tag, "Medical APK Binding...")
        return binder
    }

    private fun processQueue() {
        if (!::mqttManager.isInitialized) {
            Log.d(tag, "processQueue: skipped (mqtt not initialized)")
            return
        }
        if (isProcessing.getAndSet(true)) {
            Log.d(tag, "processQueue: skipped (already processing)")
            return
        }

        serviceScope.launch {
            try {
                Log.d(tag, "processQueue: started")
                while (isActive) {
                    // 1. Fast Lane: Drain all high priority events first
                    var highPriEvent = database.eventDao().getNextHighPriorityEvent()
                    while (highPriEvent != null && isActive) {
                        if (uploadEventSafely(highPriEvent)) {
                            // Success: check for more high priority events immediately
                            highPriEvent = database.eventDao().getNextHighPriorityEvent()
                        } else {
                            // Failure: break inner loop to retry later (network likely down)
                            break
                        }
                    }

                    // If we broke out due to error or shutdown, stop outer loop too
                    if (highPriEvent != null) break

                    // 2. Slow Lane: Process ONE low priority event
                    val lowPriEvent = database.eventDao().getNextLowPriorityEvent()
                    if (lowPriEvent != null && isActive) {
                        if (!uploadEventSafely(lowPriEvent)) {
                            // Failure: break outer loop
                            break
                        }
                        // Success: Loop back to top to check High Priority queue again
                    } else {
                        // Queue is empty (both High and Low priority)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in processing loop", e)
            } finally {
                isProcessing.set(false)
                Log.d(tag, "processQueue: finished")
            }
        }
    }

    /**
     * Uploads an event and waits for the MQTT callback before returning result. Deletes the event
     * from DB only on success. Times out after 15s to prevent hanging the queue forever.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun uploadEventSafely(event: EventEntity): Boolean {
        // Basic Ingest: $aws/rules/smart_ingest/pump-fleet/{serial}/{type}
        val topic = "$BASIC_INGEST_PREFIX/$DEVICE_SERIAL/${event.type}"

        val qos = if (mapPriorityToQos(event.priority) == 0)
                AWSIotMqttQos.QOS0 else AWSIotMqttQos.QOS1

        val result = withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    mqttManager.publishString(
                            event.dataJson,
                            topic,
                            qos,
                            { status, _ ->
                                if (continuation.isActive) {
                                    val isSuccess =
                                            status ==
                                                    AWSIotMqttMessageDeliveryCallback
                                                            .MessageDeliveryStatus.Success
                                    if (isSuccess) {
                                        Log.d(tag, "Uploaded: $topic")
                                        serviceScope.launch { database.eventDao().delete(event) }
                                    } else {
                                        Log.w(tag, "Delivery failed: $status")
                                    }
                                    continuation.resume(isSuccess, null)
                                }
                            },
                            null
                    )
                } catch (e: Exception) {
                    Log.e(tag, "Exception during publish", e)
                    if (continuation.isActive) {
                        continuation.resume(false, null)
                    }
                }
            }
        }

        if (result == null) {
            Log.w(tag, "Publish timed out: $topic")
        }
        return result ?: false
    }

    private fun processReportQueue() {
        if (!::mqttManager.isInitialized) return // MQTT not ready yet
        if (isProcessingReports.getAndSet(true)) return

        serviceScope.launch {
            try {
                val manager = reportUploadManager
                if (manager == null) {
                    Log.w(tag, "ReportUploadManager not initialized yet")
                    return@launch
                }

                while (isActive) {
                    val report = database.reportDao().getNextPendingReport() ?: break

                    if (report.retryCount >= MAX_REPORT_RETRIES) {
                        database.reportDao().updateStatus(
                            report.reportId, "FAILED", "Max retries exceeded"
                        )
                        continue
                    }

                    val s3Success = manager.uploadToS3(report)
                    if (!s3Success) {
                        database.reportDao().updateStatus(
                            report.reportId, "FAILED", "S3 upload failed"
                        )
                        break
                    }

                    // Publish MQTT metadata via existing event pipeline
                    val metadataEvent = EventEntity(
                        id = UUID.randomUUID().toString(),
                        source = DEVICE_SERIAL,
                        type = "report/jobs",
                        time = System.currentTimeMillis(),
                        priority = 1,
                        dataContentType = "application/json",
                        dataJson = buildReportMetadataJson(report)
                    )
                    database.eventDao().insert(metadataEvent)

                    database.reportDao().setStatus(report.reportId, "UPLOADED")
                    File(report.localFilePath).delete()
                    Log.i(tag, "Report uploaded: ${report.reportId}")

                    processQueue()
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in report processing loop", e)
            } finally {
                isProcessingReports.set(false)
            }
        }
    }

    fun onCloudCommandReceived(topic: String, jsonPayload: String) {
        val cmdEvent =
                CloudEventParcel(
                        id = UUID.randomUUID().toString(),
                        source = "urn:cloud:control-center",
                        type = topic,
                        time = System.currentTimeMillis(),
                        priority = 2,
                        dataContentType = "application/json",
                        dataJson = jsonPayload
                )

        val count = medicalListeners.beginBroadcast()
        for (i in 0 until count) {
            try {
                medicalListeners.getBroadcastItem(i).onEventReceived(cmdEvent)
            } catch (e: Exception) {
                Log.e(tag, "Failed to deliver command to listener $i", e)
            }
        }
        medicalListeners.finishBroadcast()
    }

    /**
     * Resolves the device serial number using a priority chain:
     * 1. Manufacturing-assigned system property: ro.art.serial
     * 2. Persisted value from SharedPreferences (dev/testing fallback)
     *
     * Production devices have ro.art.serial flashed during manufacturing.
     * Until X.509 client certificates are provisioned, this is the device identity.
     */
    private fun resolveDeviceSerial(): String {
        val prefs = getSharedPreferences("dcc_device_prefs", Context.MODE_PRIVATE)
        var serial = prefs.getString("device_serial", null)
        if (serial == null) {
            serial = UUID.randomUUID().toString()
            prefs.edit().putString("device_serial", serial).apply()
            Log.i(tag, "Generated new device serial: $serial")
        } else {
            Log.i(tag, "Using persisted device serial: $serial")
        }
        return serial
    }

    companion object {
        private const val MAX_REPORT_RETRIES = 3
        private const val PUBLISH_TIMEOUT_MS = 15_000L

        /** Maps event priority to MQTT QoS level. */
        fun mapPriorityToQos(priority: Int): Int = when (priority) {
            0 -> 0    // QoS 0: fire-and-forget
            1, 2 -> 1 // QoS 1: at-least-once
            else -> 0
        }

        /** Builds the MQTT metadata JSON for a successfully uploaded report. */
        fun buildReportMetadataJson(report: ReportEntity, timestamp: Long = System.currentTimeMillis()): String {
            return """{"report_id":"${report.reportId}","s3_key":"${report.s3Key}","report_date":"${report.reportDate}","report_type":"${report.reportType}","size_bytes":${report.fileSizeBytes},"timestamp":$timestamp}"""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            mqttManager.disconnect()
        } catch (e: Exception) {
            Log.e(tag, "Error disconnecting", e)
        }
        serviceScope.cancel()
        medicalListeners.kill()
    }
}
