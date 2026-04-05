package com.artmedical.dcc.service.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EventEntityTest {

    private fun makeEvent(
        id: String = "evt-001",
        priority: Int = 0,
        type: String = "sys/device/dev001/status"
    ) = EventEntity(
        id = id,
        source = "pump-app",
        type = type,
        time = 1000L,
        priority = priority,
        dataContentType = "application/json",
        dataJson = """{"state":"IDLE"}"""
    )

    @Test
    fun construction_allFieldsSet() {
        val event = makeEvent()
        assertThat(event.id).isEqualTo("evt-001")
        assertThat(event.source).isEqualTo("pump-app")
        assertThat(event.type).isEqualTo("sys/device/dev001/status")
        assertThat(event.time).isEqualTo(1000L)
        assertThat(event.priority).isEqualTo(0)
        assertThat(event.dataContentType).isEqualTo("application/json")
        assertThat(event.dataJson).isEqualTo("""{"state":"IDLE"}""")
    }

    @Test
    fun equality_sameFieldsAreEqual() {
        val a = makeEvent()
        val b = makeEvent()
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun equality_differentIdNotEqual() {
        val a = makeEvent(id = "evt-001")
        val b = makeEvent(id = "evt-002")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun copy_changesOnlySpecifiedField() {
        val original = makeEvent(priority = 0)
        val copied = original.copy(priority = 2)
        assertThat(copied.priority).isEqualTo(2)
        assertThat(copied.id).isEqualTo(original.id)
        assertThat(copied.type).isEqualTo(original.type)
    }
}
