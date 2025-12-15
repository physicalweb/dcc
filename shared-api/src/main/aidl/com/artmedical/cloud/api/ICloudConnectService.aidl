// Forcing a rewrite to restore the correct AIDL file structure.
package com.artmedical.cloud.api;

import com.artmedical.cloud.api.CloudEventParcel;
import com.artmedical.cloud.api.ICloudEventListener;

interface ICloudConnectService {
    void publishEvent(in CloudEventParcel event);
    void registerListener(ICloudEventListener listener);
    void unregisterListener(ICloudEventListener listener);
}
