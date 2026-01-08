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

    @Delete suspend fun delete(event: EventEntity)
}
