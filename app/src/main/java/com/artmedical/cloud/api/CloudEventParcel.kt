package com.artmedical.cloud.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CloudEventParcel(
    val id: String,
    val source: String,
    val type: String,
    val time: Long,
    val priority: Int,
    val dataContentType: String,
    val dataJson: String
) : Parcelable
