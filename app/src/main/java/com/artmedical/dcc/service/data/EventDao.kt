package com.artmedical.dcc.service.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EventDao {
    @Insert suspend fun insert(event: EventEntity)

    @Query("SELECT * FROM cloud_events ORDER BY time ASC")
    suspend fun getAllEvents(): List<EventEntity>

    // Fast Lane: Get the oldest High Priority event (Priority 1 or higher)
    @Query("SELECT * FROM cloud_events WHERE priority >= 1 ORDER BY priority DESC, time ASC LIMIT 1")
    suspend fun getNextHighPriorityEvent(): EventEntity?

    // Slow Lane: Get the oldest Low Priority event (Priority 0)
    @Query("SELECT * FROM cloud_events WHERE priority < 1 ORDER BY time ASC LIMIT 1")
    suspend fun getNextLowPriorityEvent(): EventEntity?

    @Delete suspend fun delete(event: EventEntity)
}
