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

        val bindButton = Button(this).apply {
            text = "Bind to DCC Service"
            setOnClickListener { bindToService() }
        }
        layout.addView(bindButton)

        // --- Event buttons ---
        val publishButton = Button(this).apply {
            text = "Publish Weight Event"
            setOnClickListener { publishWeightEvent() }
        }
        layout.addView(publishButton)

        val burstButton = Button(this).apply {
            text = "Burst: 10 Mixed Events"
            setOnClickListener { publishBurstEvents(10) }
        }
        layout.addView(burstButton)

        val alarmButton = Button(this).apply {
            text = "Publish High-Priority Alarm"
            setOnClickListener { publishAlarmEvent() }
        }
        layout.addView(alarmButton)

        // --- Report button ---
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

    private fun publishWeightEvent() {
        if (!isBound) { appendLog("Not bound!"); return }

        val event = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = "patient-monitor",
            type = "clinical/patient/weight",
            time = System.currentTimeMillis(),
            priority = 0,
            dataContentType = "application/json",
            dataJson = """{"value": ${65 + (Math.random() * 20).toInt()}.${(Math.random() * 10).toInt()}, "unit": "kg"}"""
        )
        try {
            cloudService?.publishEvent(event)
            appendLog(">>> Weight event [pri=0]: ${event.dataJson}")
        } catch (e: Exception) {
            appendLog("ERROR: ${e.message}")
        }
    }

    private fun publishAlarmEvent() {
        if (!isBound) { appendLog("Not bound!"); return }

        val event = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = "patient-monitor",
            type = "clinical/alarm/occlusion",
            time = System.currentTimeMillis(),
            priority = 2,
            dataContentType = "application/json",
            dataJson = """{"alarm": "LINE_OCCLUSION", "severity": "CRITICAL", "channel": 1}"""
        )
        try {
            cloudService?.publishEvent(event)
            appendLog(">>> ALARM event [pri=2]: ${event.type}")
        } catch (e: Exception) {
            appendLog("ERROR: ${e.message}")
        }
    }

    private fun publishBurstEvents(count: Int) {
        if (!isBound) { appendLog("Not bound!"); return }

        appendLog("--- Burst: sending $count events ---")
        val types = listOf(
            Triple("clinical/patient/weight", 0, """{"value": %.1f, "unit": "kg"}"""),
            Triple("clinical/patient/heart_rate", 0, """{"value": %d, "unit": "bpm"}"""),
            Triple("clinical/patient/temperature", 0, """{"value": %.1f, "unit": "C"}"""),
            Triple("clinical/pump/flow_rate", 0, """{"value": %.2f, "unit": "ml/h"}"""),
            Triple("clinical/alarm/occlusion", 2, """{"alarm": "LINE_OCCLUSION", "severity": "CRITICAL"}"""),
        )

        for (i in 1..count) {
            val (type, basePri, template) = types[i % types.size]
            val json = when {
                template.contains("%.1f") && template.contains("kg") ->
                    String.format(template, 60.0 + Math.random() * 30)
                template.contains("%d") ->
                    String.format(template, (60 + (Math.random() * 40).toInt()))
                template.contains("%.1f") && template.contains("C") ->
                    String.format(template, 36.0 + Math.random() * 2.5)
                template.contains("%.2f") ->
                    String.format(template, 10.0 + Math.random() * 90)
                else -> template
            }
            val event = CloudEventParcel(
                id = UUID.randomUUID().toString(),
                source = "patient-monitor",
                type = type,
                time = System.currentTimeMillis(),
                priority = basePri,
                dataContentType = "application/json",
                dataJson = json
            )
            try {
                eventCounter++
                cloudService?.publishEvent(event)
                appendLog("  #$eventCounter $type [pri=$basePri]")
            } catch (e: Exception) {
                appendLog("  ERROR #$eventCounter: ${e.message}")
            }
        }
        appendLog("--- Burst complete ---")
    }

    private fun uploadSampleReport() {
        if (!isBound) { appendLog("Not bound!"); return }

        try {
            // Generate a small dummy PDF (valid PDF header + minimal content)
            val reportId = UUID.randomUUID().toString()
            val testFile = File(filesDir, "test_report_$reportId.pdf")
            val pdfContent = buildDummyPdf()
            testFile.writeBytes(pdfContent)

            val pfd = ParcelFileDescriptor.open(testFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val metadata = ReportMetadata(
                reportId = reportId,
                deviceSerial = "client-test",
                patientId = "patient-001",
                reportType = "DAILY_SUMMARY",
                reportDate = java.time.LocalDate.now().toString(),
                generatedAt = System.currentTimeMillis(),
                pageCount = 1,
                fileSizeBytes = testFile.length()
            )

            cloudService?.uploadReport(metadata, pfd)
            appendLog(">>> Report upload requested: $reportId (${testFile.length()} bytes)")

            // Clean up local copy (DCC already read it via PFD)
            testFile.delete()
        } catch (e: Exception) {
            appendLog("ERROR uploading report: ${e.message}")
            Log.e(tag, "Report upload failed", e)
        }
    }

    private fun buildDummyPdf(): ByteArray {
        // Minimal valid PDF with one page containing text
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
