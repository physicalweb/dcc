package com.artmedical.dcc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
import com.artmedical.dcc.BuildConfig
import com.artmedical.dcc.service.data.AppDatabase
import com.artmedical.dcc.service.data.EventEntity
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

    // This is where 'pump-fleet' is defined
    private val DEVICE_SERIAL = "pump-fleet/" + UUID.randomUUID().toString()
    private val DOWNLINK_TOPIC = "$DEVICE_SERIAL/cmd/#"

    private lateinit var mqttManager: AWSIotMqttManager
    private lateinit var credentialsProvider: CognitoCachingCredentialsProvider

    private val medicalListeners = RemoteCallbackList<ICloudEventListener>()
    // Replaced ConcurrentLinkedQueue with Room Database
    private lateinit var database: AppDatabase
    private val isProcessing = AtomicBoolean(false)

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
            }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())

        database =
                Room.databaseBuilder(applicationContext, AppDatabase::class.java, "dcc-database")
                        .build()

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
        if (isProcessing.getAndSet(true)) return // Prevent concurrent processing

        serviceScope.launch {
            try {
                while (isActive) {
                    val events = database.eventDao().getAllEvents()
                    if (events.isEmpty()) break

                    for (event in events) {
                        // Construct the topic: pump-fleet/{uuid}/{type}
                        val topic = "$DEVICE_SERIAL/${event.type}"

                        val qos =
                                when (event.priority) {
                                    0 -> AWSIotMqttQos.QOS0
                                    1, 2 -> AWSIotMqttQos.QOS1
                                    else -> AWSIotMqttQos.QOS0
                                }

                        // Critical Fix: Wait for callback before deleting
                        val success = publishStringSuspending(event.dataJson, topic, qos)

                        if (success) {
                            Log.d(tag, "Uploading to Cloud -> Topic: $topic")
                            database.eventDao().delete(event)
                        } else {
                            Log.e(tag, "Upload failed for ${event.id}")
                            delay(5000)
                            break // Stop processing, retry later
                        }
                    }
                    if (events.isEmpty()) delay(1000)
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun publishStringSuspending(
            payload: String,
            topic: String,
            qos: AWSIotMqttQos
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            mqttManager.publishString(
                    payload,
                    topic,
                    qos,
                    { status, _ ->
                        if (continuation.isActive) {
                            val isSuccess =
                                    status ==
                                            AWSIotMqttMessageDeliveryCallback.MessageDeliveryStatus
                                                    .Success
                            if (!isSuccess) {
                                Log.w(tag, "MQTT Delivery Status: $status")
                            }
                            continuation.resume(isSuccess, null)
                        }
                    },
                    null
            )
        } catch (e: Exception) {
            Log.e(tag, "Exception during publishString", e)
            if (continuation.isActive) {
                continuation.resume(false, null)
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttManager.disconnect()
        } catch (e: Exception) {
            Log.e(tag, "Error disconnecting MQTT client", e)
        }
        serviceScope.cancel()
        medicalListeners.kill()
    }
}
