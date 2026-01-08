package com.artmedical.dcc.service.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_events")
data class EventEntity(
        @PrimaryKey val id: String,
        val source: String,
        val type: String,
        val time: Long,
        val priority: Int,
        val dataContentType: String,
        val dataJson: String
)
