package com.artmedical.cloud.api

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CloudEventPublisherTest {

    private val publishedEvents = mutableListOf<CloudEventParcel>()

    private lateinit var publisher: CloudEventPublisher

    @Before
    fun setUp() {
        publishedEvents.clear()
        // Create a fake ICloudConnectService that captures published events
        val fakeService = object : ICloudConnectService {
            override fun publishEvent(event: CloudEventParcel) {
                publishedEvents.add(event)
            }
            override fun registerListener(listener: ICloudEventListener?) {}
            override fun unregisterListener(listener: ICloudEventListener?) {}
            override fun uploadReport(metadata: ReportMetadata?, pfd: android.os.ParcelFileDescriptor?) {}
            override fun asBinder(): android.os.IBinder? = null
        }
        publisher = CloudEventPublisher(fakeService)
    }

    @Test
    fun sendSystemMetadata_correctTopicAndPriority() {
        publisher.sendSystemMetadata("""{"battery_pct":80}""")
        assertEquals(1, publishedEvents.size)
        assertEquals("system/metadata", publishedEvents[0].type)
        assertEquals("system", publishedEvents[0].source)
        assertEquals(0, publishedEvents[0].priority)
    }

    @Test
    fun sendPumpStatus_correctTopicAndPriority() {
        publisher.sendPumpStatus("""{"motor_state":"RUNNING"}""")
        assertEquals("pump/status", publishedEvents[0].type)
        assertEquals("pump-controller", publishedEvents[0].source)
        assertEquals(0, publishedEvents[0].priority)
    }

    @Test
    fun sendPumpDose_correctTopicAndPriority() {
        publisher.sendPumpDose("""{"domain":"NUTRIENTS"}""")
        assertEquals("pump/dose", publishedEvents[0].type)
        assertEquals(0, publishedEvents[0].priority)
    }

    @Test
    fun sendPlanSettings_qos1() {
        publisher.sendPlanSettings("""{"vtbd_ml":500}""")
        assertEquals("plan/settings", publishedEvents[0].type)
        assertEquals(1, publishedEvents[0].priority)
    }

    @Test
    fun sendPlanStatus_qos0() {
        publisher.sendPlanStatus("""{"efficiency":0.95}""")
        assertEquals("plan/status", publishedEvents[0].type)
        assertEquals(0, publishedEvents[0].priority)
    }

    @Test
    fun sendTubeStatus_correctTopic() {
        publisher.sendTubeStatus("""{"ft_state":"FT_IN_POSITION"}""")
        assertEquals("tube/status", publishedEvents[0].type)
        assertEquals("sensor-hub", publishedEvents[0].source)
    }

    @Test
    fun sendClinicalEvent_qos1() {
        publisher.sendClinicalEvent("""{"event_type":"OCCLUSION"}""")
        assertEquals("events/clinical", publishedEvents[0].type)
        assertEquals("patient-monitor", publishedEvents[0].source)
        assertEquals(1, publishedEvents[0].priority)
    }

    @Test
    fun sendMechanicalEvent_qos1() {
        publisher.sendMechanicalEvent("""{"event_type":"CONSOLE_ERR"}""")
        assertEquals("events/mechanical", publishedEvents[0].type)
        assertEquals("system", publishedEvents[0].source)
        assertEquals(1, publishedEvents[0].priority)
    }

    @Test
    fun sendReportMetadata_correctTopic() {
        publisher.sendReportMetadata("""{"report_id":"r1"}""")
        assertEquals("report/jobs", publishedEvents[0].type)
        assertEquals(1, publishedEvents[0].priority)
    }

    @Test
    fun send_setsCommonFields() {
        publisher.sendSystemMetadata("""{"test":true}""")
        val event = publishedEvents[0]
        assertNotNull(event.id)
        assertTrue(event.id.isNotEmpty())
        assertEquals("application/json", event.dataContentType)
        assertTrue(event.time > 0)
        assertEquals("""{"test":true}""", event.dataJson)
    }

    @Test
    fun sendSystemConnection_qos1() {
        publisher.sendSystemConnection("""{"state":"UP"}""")
        assertEquals("system/connection", publishedEvents[0].type)
        assertEquals(1, publishedEvents[0].priority)
    }

    @Test
    fun sendGrvStatus_correctTopic() {
        publisher.sendGrvStatus("""{"bag_state":"CONNECTED"}""")
        assertEquals("grv/status", publishedEvents[0].type)
        assertEquals("patient-monitor", publishedEvents[0].source)
    }

    @Test
    fun sendReeStatus_correctTopic() {
        publisher.sendReeStatus("""{"ree_state":"MEASURING"}""")
        assertEquals("ree/status", publishedEvents[0].type)
        assertEquals("sensor-hub", publishedEvents[0].source)
    }

    @Test
    fun sendTubeImpedance_correctTopic() {
        publisher.sendTubeImpedance("""{"z1":1200}""")
        assertEquals("tube/impedance", publishedEvents[0].type)
    }

    @Test
    fun sendRefluxStatus_correctTopic() {
        publisher.sendRefluxStatus("""{"minor_count":3}""")
        assertEquals("reflux/status", publishedEvents[0].type)
    }
}
