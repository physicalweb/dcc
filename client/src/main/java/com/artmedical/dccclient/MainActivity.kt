package com.artmedical.dccclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.artmedical.cloud.api.CloudEventParcel
import com.artmedical.cloud.api.ICloudConnectService
import com.artmedical.cloud.api.ICloudEventListener
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val tag = "DCC-Client"

    // Test to prove shared-api visibility
    private val heartbeatTest: com.artmedical.cloud.api.IHeartbeatService? = null

    private var cloudService: ICloudConnectService? = null
    private var isBound = false

    private lateinit var statusText: TextView
    private lateinit var receivedText: TextView

    /** Listener to receive events from the DCC Service */
    private val eventListener = object : ICloudEventListener.Stub() {
        override fun onEventReceived(event: CloudEventParcel) {
            Log.i(tag, "Event received from DCC: ${event.type}")
            runOnUiThread {
                receivedText.text = "Received command:\nType: ${event.type}\nPayload: ${event.dataJson}"
            }
        }
    }

    /** ServiceConnection to manage the lifecycle of the connection to the service. */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(tag, "Service connected")
            cloudService = ICloudConnectService.Stub.asInterface(service)
            isBound = true
            statusText.text = "Status: Connected"

            // Register our listener to receive callbacks
            try {
                cloudService?.registerListener(eventListener)
            } catch (e: Exception) {
                Log.e(tag, "Failed to register listener", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(tag, "Service disconnected")
            cloudService = null
            isBound = false
            statusText.text = "Status: Disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Simple UI for this example. Consider using XML layouts for a real app.
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        statusText = TextView(this).apply { text = "Status: Not Connected"; textSize = 18f }
        receivedText = TextView(this).apply { text = "Waiting for commands..."; textSize = 16f }

        val bindButton = Button(this).apply {
            text = "Bind to DCC Service"
            setOnClickListener { bindToService() }
        }

        val publishButton = Button(this).apply {
            text = "Publish 'Patient Weight' Event"
            setOnClickListener { publishWeightEvent() }
        }

        layout.addView(bindButton)
        layout.addView(publishButton)
        layout.addView(statusText)
        layout.addView(receivedText)
        setContentView(layout)
    }

    private fun bindToService() {
        if (!isBound) {
            val serviceIntent = Intent("com.artmedical.dcc.START_SERVICE").apply {
                // The component must be explicitly set for an external service
                component = ComponentName("com.artmedical.dcc", "com.artmedical.dcc.service.ConnectivityService")
            }
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun publishWeightEvent() {
        if (!isBound) {
            Log.w(tag, "Service not bound. Cannot publish event.")
            return
        }

        val weightEvent = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = "patient-monitor",
            type = "clinical/patient/weight",
            time = System.currentTimeMillis(),
            priority = 0,
            dataContentType = "application/json",
            dataJson = """{"value": 75.5, "unit": "kg"}"""
        )

        try {
            cloudService?.publishEvent(weightEvent)
            Log.i(tag, "Published 'Patient Weight' event to DCC.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to publish event", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            cloudService?.unregisterListener(eventListener)
            unbindService(connection)
            isBound = false
        }
    }
}