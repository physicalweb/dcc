package com.artmedical.cloud.api;

import com.artmedical.cloud.api.CloudEventParcel;

oneway interface ICloudEventListener {
    void onEventReceived(in CloudEventParcel event);
}
