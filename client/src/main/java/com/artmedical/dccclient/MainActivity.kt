package com.artmedical.dccclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.artmedical.cloud.api.CloudEventParcel
import com.artmedical.cloud.api.ICloudConnectService
import com.artmedical.cloud.api.ICloudEventListener
import com.artmedical.cloud.api.ReportMetadata
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val tag = "DCC-Client"

    // Simulated device/patient identifiers (matches simulator convention)
    private val deviceId = "dev001"
    private val patientId = "pat001"

    private var cloudService: ICloudConnectService? = null
    private var isBound = false
    private var eventCounter = 0

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val eventListener = object : ICloudEventListener.Stub() {
        override fun onEventReceived(event: CloudEventParcel) {
            Log.i(tag, "Event received from DCC: ${event.type}")
            appendLog("<<< Command: ${event.type}\n    ${event.dataJson}")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(tag, "Service connected")
            cloudService = ICloudConnectService.Stub.asInterface(service)
            isBound = true
            runOnUiThread { statusText.text = "Status: Connected" }
            appendLog("Bound to DCC service")

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
            runOnUiThread { statusText.text = "Status: Disconnected" }
            appendLog("Disconnected from DCC service")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        statusText = TextView(this).apply {
            text = "Status: Not Connected"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        layout.addView(statusText)

        val deviceInfoText = TextView(this).apply {
            text = "Device: $deviceId | Patient: $patientId\nTopics: sys/device/$deviceId/status, sys/clinical/$patientId/..."
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 12)
        }
        layout.addView(deviceInfoText)

        val bindButton = Button(this).apply {
            text = "Bind to DCC Service"
            setOnClickListener { bindToService() }
        }
        layout.addView(bindButton)

        val statusButton = Button(this).apply {
            text = "Send Device Status"
            setOnClickListener { publishDeviceStatus() }
        }
        layout.addView(statusButton)

        val therapyButton = Button(this).apply {
            text = "Send Therapy Event"
            setOnClickListener { publishTherapyEvent() }
        }
        layout.addView(therapyButton)

        val alarmButton = Button(this).apply {
            text = "Send Safety Alarm"
            setOnClickListener { publishAlarmEvent() }
        }
        layout.addView(alarmButton)

        val burstButton = Button(this).apply {
            text = "Burst: 10 Mixed Events"
            setOnClickListener { publishBurstEvents(10) }
        }
        layout.addView(burstButton)

        val reportButton = Button(this).apply {
            text = "Upload Sample PDF Report"
            setOnClickListener { uploadSampleReport() }
        }
        layout.addView(reportButton)

        // --- Log area ---
        val logLabel = TextView(this).apply {
            text = "Event Log:"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(logLabel)

        logScroll = ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        logText = TextView(this).apply {
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.DKGRAY)
        }
        logScroll.addView(logText)
        layout.addView(logScroll)

        setContentView(layout)
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            logText.append("$msg\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun bindToService() {
        if (!isBound) {
            val serviceIntent = Intent("com.artmedical.dcc.START_SERVICE").apply {
                component = ComponentName("com.artmedical.dcc", "com.artmedical.dcc.service.ConnectivityService")
            }
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    // --- sys/device/{deviceId}/status  (pri=0, 1Hz telemetry) ---
    private fun publishDeviceStatus() {
        if (!isBound) { appendLog("Not bound!"); return }

        val ts = System.currentTimeMillis()
        val volDelivered = 100.0 + Math.random() * 400
        val flowRate = 50.0 + Math.random() * 100
        val batteryPct = 40 + (Math.random() * 60).toInt()
        val pressureMmhg = 10.0 + Math.random() * 20
        val tempC = 36.0 + Math.random() * 2.5

        val json = """{"state":"FEEDING","sub_state":"DELIVERING","uptime_sec":${(ts / 1000) % 86400},"battery":{"percent":$batteryPct,"is_mains":true,"voltage_mv":${11800 + (Math.random() * 1000).toInt()},"charging":true},"pump":{"vol_delivered_ml":${"%.1f".format(volDelivered)},"flow_rate_ml_hr":${"%.1f".format(flowRate)},"target_vol_ml":500.0,"is_prime":false,"motor_rpm":12},"sensors":{"ft_connected":true,"ft_type":"NGT-12FR","pressure_mmhg":${"%.1f".format(pressureMmhg)},"temperature_c":${"%.1f".format(tempC)},"impedance":{"z1":1245.3,"z2":1102.7,"z3":1389.1,"s1":82.4,"s2":78.9}},"timestamp":$ts}"""

        val event = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = "pump-app",
            type = "sys/device/$deviceId/status",
            time = ts,
            priority = 0,
            dataContentType = "application/json",
            dataJson = json
        )
        try {
            cloudService?.publishEvent(event)
            appendLog(">>> status [pri=0] bat=$batteryPct% flow=${"%.0f".format(flowRate)}ml/h")
        } catch (e: Exception) {
            appendLog("ERROR: ${e.message}")
        }
    }

    // --- sys/clinical/{patientId}/therapy/nutrition  (pri=1) ---
    private fun publishTherapyEvent() {
        if (!isBound) { appendLog("Not bound!"); return }

        val ts = System.currentTimeMillis()
        val json = """{"event":"SESSION_START","plan_id":"plan-${UUID.randomUUID().toString().take(8)}","settings":{"vtbd_ml":500,"basal_rate_ml_hr":125,"product_name":"Jevity 1.5 Cal","product_kcal_ml":1.5,"ramp_up_min":15,"max_rate_ml_hr":150},"patient_weight_kg":72.0,"timestamp":$ts}"""

        val event = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = "pump-app",
            type = "sys/clinical/$patientId/therapy/nutrition",
            time = ts,
            priority = 1,
            dataContentType = "application/json",
            dataJson = json
        )
        try {
            cloudService?.publishEvent(event)
            appendLog(">>> therapy/nutrition SESSION_START [pri=1]")
        } catch (e: Exception) {
            appendLog("ERROR: ${e.message}")
        }
    }

    // --- sys/clinical/{patientId}/safety/alarm  (pri=2) ---
    private fun publishAlarmEvent() {
        if (!isBound) { appendLog("Not bound!"); return }

        val ts = System.currentTimeMillis()
        val corrId = "corr-${UUID.randomUUID().toString().take(8)}"
        val json = """{"event_code":"ALM_OCCLUSION","lifecycle":"RAISED","severity":"CRITICAL","message":"Occlusion detected on feeding line","correlation_id":"$corrId","technical_context":{"pressure_mmhg":${"%.1f".format(60.0 + Math.random() * 30)},"threshold_mmhg":60.0,"motor_stall":true,"line_position":"PROXIMAL"},"timestamp":$ts}"""

        val event = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = "pump-app",
            type = "sys/clinical/$patientId/safety/alarm",
            time = ts,
            priority = 2,
            dataContentType = "application/json",
            dataJson = json
        )
        try {
            cloudService?.publishEvent(event)
            appendLog(">>> ALARM ALM_OCCLUSION [pri=2] $corrId")
        } catch (e: Exception) {
            appendLog("ERROR: ${e.message}")
        }
    }

    // --- Burst: mixed realistic events ---
    private fun publishBurstEvents(count: Int) {
        if (!isBound) { appendLog("Not bound!"); return }

        appendLog("--- Burst: sending $count events ---")
        for (i in 1..count) {
            val ts = System.currentTimeMillis()
            val event = when (i % 5) {
                // Device status (most frequent)
                0, 1, 2 -> {
                    val bp = 40 + (Math.random() * 60).toInt()
                    val fr = 50.0 + Math.random() * 100
                    CloudEventParcel(
                        id = UUID.randomUUID().toString(),
                        source = "pump-app",
                        type = "sys/device/$deviceId/status",
                        time = ts,
                        priority = 0,
                        dataContentType = "application/json",
                        dataJson = """{"state":"FEEDING","sub_state":"DELIVERING","uptime_sec":${(ts / 1000) % 86400},"battery":{"percent":$bp,"is_mains":true,"voltage_mv":12100,"charging":true},"pump":{"vol_delivered_ml":${"%.1f".format(100 + Math.random() * 400)},"flow_rate_ml_hr":${"%.1f".format(fr)},"target_vol_ml":500.0,"is_prime":false,"motor_rpm":12},"sensors":{"ft_connected":true,"ft_type":"NGT-12FR","pressure_mmhg":${"%.1f".format(10 + Math.random() * 20)},"temperature_c":${"%.1f".format(36 + Math.random() * 2.5)}},"timestamp":$ts}"""
                    )
                }
                // Therapy event
                3 -> CloudEventParcel(
                    id = UUID.randomUUID().toString(),
                    source = "pump-app",
                    type = "sys/clinical/$patientId/therapy/nutrition",
                    time = ts,
                    priority = 1,
                    dataContentType = "application/json",
                    dataJson = """{"event":"SESSION_START","plan_id":"plan-${UUID.randomUUID().toString().take(8)}","settings":{"vtbd_ml":500,"basal_rate_ml_hr":125,"product_name":"Jevity 1.5 Cal","product_kcal_ml":1.5,"ramp_up_min":15,"max_rate_ml_hr":150},"patient_weight_kg":72.0,"timestamp":$ts}"""
                )
                // Alarm
                else -> CloudEventParcel(
                    id = UUID.randomUUID().toString(),
                    source = "pump-app",
                    type = "sys/clinical/$patientId/safety/alarm",
                    time = ts,
                    priority = 2,
                    dataContentType = "application/json",
                    dataJson = """{"event_code":"ALM_OCCLUSION","lifecycle":"RAISED","severity":"CRITICAL","message":"Occlusion detected","correlation_id":"corr-${UUID.randomUUID().toString().take(8)}","technical_context":{"pressure_mmhg":82.1,"threshold_mmhg":60.0,"motor_stall":true,"line_position":"PROXIMAL"},"timestamp":$ts}"""
                )
            }
            try {
                eventCounter++
                cloudService?.publishEvent(event)
                appendLog("  #$eventCounter ${event.type} [pri=${event.priority}]")
            } catch (e: Exception) {
                appendLog("  ERROR #$eventCounter: ${e.message}")
            }
        }
        appendLog("--- Burst complete ---")
    }

    private fun uploadSampleReport() {
        if (!isBound) { appendLog("Not bound!"); return }

        try {
            val reportId = UUID.randomUUID().toString()
            val testFile = File(filesDir, "test_report_$reportId.pdf")
            testFile.writeBytes(buildDummyPdf())

            val pfd = ParcelFileDescriptor.open(testFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val metadata = ReportMetadata(
                reportId = reportId,
                deviceSerial = deviceId,
                patientId = patientId,
                reportType = "DAILY_SUMMARY",
                reportDate = java.time.LocalDate.now().toString(),
                generatedAt = System.currentTimeMillis(),
                pageCount = 1,
                fileSizeBytes = testFile.length()
            )

            cloudService?.uploadReport(metadata, pfd)
            appendLog(">>> Report upload: $reportId (${testFile.length()} bytes)")
            testFile.delete()
        } catch (e: Exception) {
            appendLog("ERROR uploading report: ${e.message}")
            Log.e(tag, "Report upload failed", e)
        }
    }

    private fun buildDummyPdf(): ByteArray {
        val content = """
            %PDF-1.4
            1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
            2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
            3 0 obj<</Type/Page/MediaBox[0 0 612 792]/Parent 2 0 R/Resources<</Font<</F1 4 0 R>>>>>>endobj
            4 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
            xref
            0 5
            trailer<</Size 5/Root 1 0 R>>
            startxref
            0
            %%EOF
        """.trimIndent()
        return content.toByteArray()
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
