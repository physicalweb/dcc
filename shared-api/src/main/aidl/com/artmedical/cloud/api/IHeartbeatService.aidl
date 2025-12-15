package com.artmedical.cloud.api;

// A simple test interface to verify shared library visibility.
interface IHeartbeatService {
    void sendHeartbeat(long timestamp);
}
