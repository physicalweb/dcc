package com.artmedical.dcc.service.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: EventDao

    private fun makeEvent(
        id: String = "evt-001",
        priority: Int = 0,
        time: Long = 1000L,
        type: String = "sys/device/dev001/status"
    ) = EventEntity(
        id = id, source = "pump-app", type = type,
        time = time, priority = priority,
        dataContentType = "application/json",
        dataJson = """{"state":"IDLE"}"""
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.eventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_thenGetAll_returnsInsertedEvent() = runTest {
        val event = makeEvent()
        dao.insert(event)
        val all = dao.getAllEvents()
        assertThat(all).hasSize(1)
        assertThat(all[0].id).isEqualTo("evt-001")
    }

    @Test
    fun getAllEvents_empty_returnsEmptyList() = runTest {
        assertThat(dao.getAllEvents()).isEmpty()
    }

    @Test
    fun getNextHighPriorityEvent_withMixedPriorities_returnsHighestFirst() = runTest {
        dao.insert(makeEvent(id = "low", priority = 0, time = 1))
        dao.insert(makeEvent(id = "med", priority = 1, time = 2))
        dao.insert(makeEvent(id = "high", priority = 2, time = 3))
        val next = dao.getNextHighPriorityEvent()
        assertThat(next).isNotNull()
        assertThat(next!!.id).isEqualTo("high")
    }

    @Test
    fun getNextHighPriorityEvent_onlyLowPriority_returnsNull() = runTest {
        dao.insert(makeEvent(id = "low1", priority = 0))
        dao.insert(makeEvent(id = "low2", priority = 0))
        assertThat(dao.getNextHighPriorityEvent()).isNull()
    }

    @Test
    fun getNextHighPriorityEvent_samePriority_returnsOldestFirst() = runTest {
        dao.insert(makeEvent(id = "older", priority = 1, time = 100))
        dao.insert(makeEvent(id = "newer", priority = 1, time = 200))
        val next = dao.getNextHighPriorityEvent()
        assertThat(next!!.id).isEqualTo("older")
    }

    @Test
    fun getNextLowPriorityEvent_withMixed_returnsOnlyPriority0() = runTest {
        dao.insert(makeEvent(id = "high", priority = 2, time = 1))
        dao.insert(makeEvent(id = "low", priority = 0, time = 2))
        val next = dao.getNextLowPriorityEvent()
        assertThat(next).isNotNull()
        assertThat(next!!.id).isEqualTo("low")
    }

    @Test
    fun getNextLowPriorityEvent_noLowPriority_returnsNull() = runTest {
        dao.insert(makeEvent(id = "high", priority = 1))
        assertThat(dao.getNextLowPriorityEvent()).isNull()
    }

    @Test
    fun getNextLowPriorityEvent_multiple_returnsOldestFirst() = runTest {
        dao.insert(makeEvent(id = "older", priority = 0, time = 100))
        dao.insert(makeEvent(id = "newer", priority = 0, time = 200))
        val next = dao.getNextLowPriorityEvent()
        assertThat(next!!.id).isEqualTo("older")
    }

    @Test
    fun delete_removesEvent() = runTest {
        val event = makeEvent()
        dao.insert(event)
        dao.delete(event)
        assertThat(dao.getAllEvents()).isEmpty()
    }

    @Test
    fun priorityQueue_drainsHighBeforeLow() = runTest {
        dao.insert(makeEvent(id = "low1", priority = 0, time = 1))
        dao.insert(makeEvent(id = "high1", priority = 1, time = 2))
        dao.insert(makeEvent(id = "low2", priority = 0, time = 3))
        dao.insert(makeEvent(id = "high2", priority = 2, time = 4))

        // Drain high priority
        val first = dao.getNextHighPriorityEvent()!!
        assertThat(first.id).isEqualTo("high2") // highest priority first
        dao.delete(first)

        val second = dao.getNextHighPriorityEvent()!!
        assertThat(second.id).isEqualTo("high1")
        dao.delete(second)

        // No more high priority
        assertThat(dao.getNextHighPriorityEvent()).isNull()

        // Low priority still available
        val low = dao.getNextLowPriorityEvent()!!
        assertThat(low.id).isEqualTo("low1") // oldest first
    }
}
