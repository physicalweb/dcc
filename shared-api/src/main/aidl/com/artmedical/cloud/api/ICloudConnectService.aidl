package com.artmedical.cloud.api;

import com.artmedical.cloud.api.CloudEventParcel;
import com.artmedical.cloud.api.ICloudEventListener;
import com.artmedical.cloud.api.ReportMetadata;

interface ICloudConnectService {
    void publishEvent(in CloudEventParcel event);
    void registerListener(ICloudEventListener listener);
    void unregisterListener(ICloudEventListener listener);
    void uploadReport(in ReportMetadata metadata, in ParcelFileDescriptor pfd);
}
