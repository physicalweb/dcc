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
import com.artmedical.cloud.api.CloudEventPublisher
import com.artmedical.cloud.api.ICloudConnectService
import com.artmedical.cloud.api.ICloudEventListener
import com.artmedical.cloud.api.ReportMetadata
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val tag = "DCC-Client"

    private var cloudService: ICloudConnectService? = null
    private var publisher: CloudEventPublisher? = null
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
            publisher = CloudEventPublisher(cloudService!!)
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
            publisher = null
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
            text = "ICD-aligned topics: system/metadata, pump/status, tube/status, ..."
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
            text = "Send System Metadata"
            setOnClickListener { publishSystemMetadata() }
        }
        layout.addView(statusButton)

        val pumpButton = Button(this).apply {
            text = "Send Pump Status + Dose"
            setOnClickListener { publishPumpStatus(); publishPumpDose() }
        }
        layout.addView(pumpButton)

        val tubeButton = Button(this).apply {
            text = "Send Tube Status"
            setOnClickListener { publishTubeStatus() }
        }
        layout.addView(tubeButton)

        val planButton = Button(this).apply {
            text = "Send Plan Settings + Status"
            setOnClickListener { publishPlanSettings(); publishPlanStatus() }
        }
        layout.addView(planButton)

        val clinicalButton = Button(this).apply {
            text = "Send Clinical Event (Alarm)"
            setOnClickListener { publishClinicalEvent() }
        }
        layout.addView(clinicalButton)

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

    // ── system/metadata (1-5s, QoS 0) ───────────────────────────

    private fun publishSystemMetadata() {
        val p = publisher ?: run { appendLog("Not bound!"); return }
        val ts = System.currentTimeMillis()
        val batteryPct = 40 + (Math.random() * 60).toInt()

        val json = """{"system_state":"SYSTEM_COLLECTING_DATA","battery_pct":$batteryPct,"cpu_temp_c":${45 + (Math.random() * 15).toInt()},"mains_connected":true,"os_uptime_sec":${(ts / 1000) % 86400},"gui_alive_counter":${eventCounter++},"network_speed_kbps":${500 + (Math.random() * 1500).toInt()},"timestamp":$ts}"""

        try {
            p.sendSystemMetadata(json)
            appendLog(">>> system/metadata bat=$batteryPct% state=COLLECTING_DATA")
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    // ── pump/status (1s, QoS 0) ──────────────────────────────────

    private fun publishPumpStatus() {
        val p = publisher ?: run { appendLog("Not bound!"); return }
        val ts = System.currentTimeMillis()
        val flowRate = 50.0 + Math.random() * 100

        val json = """{"dose_program_state":"NUTRIENTS_DOSE_RELEASE","motor_state":"RUNNING","instant_rate_ml_hr":${"%.1f".format(flowRate)},"nutrients_source_state":"DETECTED","fluids_source_state":"DETECTED","nutrients_prime_state":"PRIMED","fluids_prime_state":"PRIMED","nutrients_fluids_ratio":80,"timestamp":$ts}"""

        try {
            p.sendPumpStatus(json)
            appendLog(">>> pump/status dose=NUTRIENTS flow=${"%.0f".format(flowRate)}ml/h")
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    // ── pump/dose (1s while feeding, QoS 0) ──────────────────────

    private fun publishPumpDose() {
        val p = publisher ?: run { appendLog("Not bound!"); return }
        val ts = System.currentTimeMillis()
        val volDelivered = 100.0 + Math.random() * 400

        val json = """{"domain":"NUTRIENTS","instant_rate_ml_hr":${"%.1f".format(50 + Math.random() * 100)},"cumulative_volume_ml":${"%.1f".format(volDelivered)},"dose_rel_current_ma":${120 + (Math.random() * 30).toInt()},"timestamp":$ts}"""

        try {
            p.sendPumpDose(json)
            appendLog(">>> pump/dose vol=${"%.0f".format(volDelivered)}mL")
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    // ── tube/status (1s, QoS 0) ──────────────────────────────────

    private fun publishTubeStatus() {
        val p = publisher ?: run { appendLog("Not bound!"); return }
        val ts = System.currentTimeMillis()
        val states = arrayOf("FT_IN_POSITION", "FT_READY_FOR_FEEDING", "FT_IN_STOMACH", "FT_MINOR_REFLUX")
        val state = states[(Math.random() * states.size).toInt()]

        val json = """{"ft_state":"$state","ft_type":"NGT-14FR","impedance":{"z1":1245.3,"z2":1102.7,"z3":1389.1,"z4":980.5,"z5":1150.2,"z6":1320.8,"s1":82.4,"s2":78.9,"s3":85.1},"imp_sensor_state":"IMP_SENSOR_SWEEP","timestamp":$ts}"""

        try {
            p.sendTubeStatus(json)
            appendLog(">>> tube/status ft_state=$state")
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    // ── plan/settings (on-change, QoS 1) ─────────────────────────

    private fun publishPlanSettings() {
        val p = publisher ?: run { appendLog("Not bound!"); return }
        val ts = System.currentTimeMillis()

        val json = """{"nutrition":{"plan_start_timestamp":$ts,"basal_rate_ml_hr":125,"max_rate_ml_hr":150,"vtbd_ml":500,"deliver_over_hr":8,"volume_offset_ml":0},"fluid":{"plan_start_timestamp":$ts,"basal_rate_ml_hr":50,"max_rate_ml_hr":75,"vtbd_ml":200,"deliver_over_hr":8},"timestamp":$ts}"""

        try {
            p.sendPlanSettings(json)
            appendLog(">>> plan/settings nut=500mL@125ml/h flu=200mL@50ml/h")
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    // ── plan/status (1-5s, QoS 0) ────────────────────────────────

    private fun publishPlanStatus() {
        val p = publisher ?: run { appendLog("Not bound!"); return }
        val ts = System.currentTimeMillis()
        val efficiency = 70 + (Math.random() * 30).toInt()
        val delivered = 100.0 + Math.random() * 300
        val expected = delivered + Math.random() * 100

        val json = """{"nutrition":{"plan_duration_sec":${3600 + (Math.random() * 7200).toInt()},"plan_delivered_volume_ml":${"%.1f".format(delivered)},"plan_expected_volume_ml":${"%.0f".format(expected)},"plan_net_volume_ml":${"%.1f".format(delivered * 0.95)},"plan_efficiency_pct":$efficiency,"plan_grv_volume_ml":${(Math.random() * 50).toInt()}},"fluid":{"plan_delivered_volume_ml":${"%.1f".format(delivered * 0.3)},"plan_expected_volume_ml":${"%.0f".format(expected * 0.3)},"plan_net_volume_ml":${"%.1f".format(delivered * 0.28)},"plan_efficiency_pct":${efficiency + 5}},"timestamp":$ts}"""

        try {
            p.sendPlanStatus(json)
            appendLog(">>> plan/status nut_eff=${efficiency}% del=${"%.0f".format(delivered)}mL")
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    // ── events/clinical (on-event, QoS 1) ────────────────────────

    private fun publishClinicalEvent() {
        val p = publisher ?: run { appendLog("Not bound!"); return }
        val ts = System.currentTimeMillis()
        val corrId = "corr-${UUID.randomUUID().toString().take(8)}"

        val json = """{"event_type":"ALM_OCCLUSION","record_type":"ALERT","lifecycle":"RAISED","severity":"CRITICAL","message":"Occlusion detected on feeding line","correlation_id":"$corrId","start_time":$ts,"states":{"reason":"PRESSURE_EXCEEDED"},"context":{"pressure_mmhg":${"%.1f".format(60.0 + Math.random() * 30)},"threshold_mmhg":60.0,"motor_stall":true},"timestamp":$ts}"""

        try {
            p.sendClinicalEvent(json)
            appendLog(">>> events/clinical ALM_OCCLUSION RAISED $corrId")
        } catch (e: Exception) { appendLog("ERROR: ${e.message}") }
    }

    // ── Burst: mixed ICD-aligned events ──────────────────────────

    private fun publishBurstEvents(count: Int) {
        val p = publisher ?: run { appendLog("Not bound!"); return }

        appendLog("--- Burst: sending $count events ---")
        for (i in 1..count) {
            val ts = System.currentTimeMillis()
            try {
                eventCounter++
                when (i % 6) {
                    0, 1 -> {
                        p.sendSystemMetadata("""{"system_state":"SYSTEM_COLLECTING_DATA","battery_pct":${40 + (Math.random() * 60).toInt()},"cpu_temp_c":${48 + (Math.random() * 10).toInt()},"mains_connected":true,"os_uptime_sec":${(ts / 1000) % 86400},"timestamp":$ts}""")
                        appendLog("  #$eventCounter system/metadata [pri=0]")
                    }
                    2 -> {
                        p.sendPumpDose("""{"domain":"NUTRIENTS","instant_rate_ml_hr":${"%.1f".format(50 + Math.random() * 100)},"cumulative_volume_ml":${"%.1f".format(100 + Math.random() * 400)},"timestamp":$ts}""")
                        appendLog("  #$eventCounter pump/dose [pri=0]")
                    }
                    3 -> {
                        p.sendTubeStatus("""{"ft_state":"FT_IN_POSITION","ft_type":"NGT-14FR","impedance":{"z1":1245.3,"z2":1102.7,"z3":1389.1},"timestamp":$ts}""")
                        appendLog("  #$eventCounter tube/status [pri=0]")
                    }
                    4 -> {
                        p.sendPlanStatus("""{"nutrition":{"plan_delivered_volume_ml":${"%.1f".format(100 + Math.random() * 300)},"plan_expected_volume_ml":350,"plan_efficiency_pct":${70 + (Math.random() * 30).toInt()}},"timestamp":$ts}""")
                        appendLog("  #$eventCounter plan/status [pri=0]")
                    }
                    5 -> {
                        p.sendClinicalEvent("""{"event_type":"ALM_OCCLUSION","record_type":"ALERT","lifecycle":"RAISED","severity":"CRITICAL","message":"Occlusion detected","correlation_id":"corr-${UUID.randomUUID().toString().take(8)}","start_time":$ts,"timestamp":$ts}""")
                        appendLog("  #$eventCounter events/clinical [pri=1]")
                    }
                }
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
                deviceSerial = "test-device",
                patientId = "test-patient",
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
