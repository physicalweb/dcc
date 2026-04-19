# DCC Design Document

## Overview

The DCC (Decoupled Connectivity Component) is a headless Android service that provides cloud connectivity to other applications on the same device. Client apps (e.g. `medical.apk`) bind to the DCC via AIDL IPC to publish telemetry events and upload PDF reports without bundling any networking or cloud logic.

```
┌────────────────────────────────────────────────────────────┐
│                      Android Device                        │
│                                                            │
│  ┌────────────┐             ┌───────────────────────────┐  │
│  │            │  AIDL IPC   │  DCC ConnectivityService  │  │
│  │ medical.apk│────────────>│                           │  │
│  │            │  events     │  ┌────────┐ ┌──────────┐  │  │
│  │            │<────────────│  │ Room   │ │  MQTT    │  │  │
│  │            │  commands   │  │  DB    │ │ Manager  │  │  │
│  └────────────┘             │  └───┬────┘ └────┬─────┘  │  │
│                             │      │           │        │  │
│                             │  ┌───┴───────────┴─────┐  │  │
│                             │  │ Priority Queue      │  │  │
│                             │  │ Processor           │  │  │
│                             │  └──────────┬──────────┘  │  │
│                             │             │             │  │
│                             │  ┌──────────┴──────────┐  │  │
│                             │  │ Report Upload Mgr   │  │  │
│                             │  │ (S3 TransferUtility)│  │  │
│                             │  └─────────────────────┘  │  │
│                             └─────────────┬─────────────┘  │
│                                           │                │
└───────────────────────────────────────────┼────────────────┘
                                            │ MQTT / HTTPS
                                            ▼
                                  ┌───────────────────┐
                                  │   AWS IoT Core    │
                                  │  (Basic Ingest)   │
                                  └─────────┬─────────┘
                                            │
                              ┌─────────────┼─────────────┐
                              ▼             ▼             ▼
                         IoT Rules    Firehose → S3   Live Subs
                         (Athena)     (data lake)    (dashboard)
```

## Module Structure

### `:shared-api`

The contract layer. Contains AIDL interface definitions and Kotlin parcelable types shared between the service and all clients. Both `:app` and `:client` depend on this module.

- `ICloudConnectService.aidl` — the service interface (publishEvent, uploadReport, register/unregister listener)
- `ICloudEventListener.aidl` — callback interface for downlink commands
- `CloudEventParcel.kt` / `.aidl` — the universal event envelope
- `ReportMetadata.kt` / `.aidl` — metadata for PDF report uploads

### `:app` (The DCC Service)

The core service. No UI. Runs as a foreground service with a persistent notification.

Key components:
- **`ConnectivityService`** — the main service. Manages MQTT connection, event queue processing, report uploads, and the AIDL binder.
- **`AppDatabase`** (Room) — persistent storage for queued events (`EventEntity`) and pending reports (`ReportEntity`). Survives process restarts.
- **`ReportUploadManager`** — handles S3 uploads via `TransferUtility` with `suspendCancellableCoroutine` bridge.

### `:client` (Sample Client App)

A test UI demonstrating how to bind to the DCC and exercise all features: device status, therapy events, safety alarms, burst events, and PDF report uploads.

## Data Flow — Event Publishing

1. **Client** constructs a `CloudEventParcel` and calls `publishEvent()` via the AIDL binder.
2. **Binder** inserts the event into Room as an `EventEntity`, then calls `processQueue()`.
3. **processQueue()** runs in a coroutine on `Dispatchers.IO` (single-thread limited):
   - **Fast lane**: drain all high-priority events (priority >= 1) first
   - **Slow lane**: process one low-priority event (priority 0), then loop back to check high-priority again
   - Each event is published via `uploadEventSafely()` which wraps `mqttManager.publishString()` in a `suspendCancellableCoroutine` with a 15-second timeout
   - On success: event is deleted from Room
   - On failure or timeout: processing stops; the queue will be retried on next trigger
4. **Queue triggers**: `processQueue()` is called from three places:
   - The AIDL `publishEvent()` binder (after every insert)
   - `NetworkCallback.onAvailable()` (network restored)
   - MQTT `Connected` callback (connection established/restored)
5. **Concurrency guard**: `AtomicBoolean isProcessing` prevents overlapping processing. If a `processQueue()` call arrives while one is running, it returns immediately.

## Data Flow — Report Upload

1. **Client** opens a PDF file, creates a `ParcelFileDescriptor` and `ReportMetadata`, calls `uploadReport()`.
2. **Binder** reads the file via PFD into a local copy under `filesDir/pending_reports/`, inserts a `ReportEntity` into Room, then calls `processReportQueue()`.
3. **processReportQueue()** picks the next pending report, calls `ReportUploadManager.uploadToS3()`:
   - S3 key: `reports/{deviceSerial}/{date}/{reportId}.pdf`
   - On success: publishes an MQTT metadata event (topic `report/jobs`) and deletes the local file + DB record
   - On failure: increments retry count; gives up after 3 attempts
4. Report queue processing is independent from event queue processing (separate `AtomicBoolean`).

## MQTT Topic Structure

The DCC publishes to AWS IoT Core using the **Basic Ingest** prefix to bypass the message broker and route directly to IoT Rules (cost reduction):

```
$aws/rules/smart_ingest/pump-fleet/{device_serial}/{event.type}
```

Where `event.type` is one of the ICD-aligned topics (e.g. `system/metadata`, `pump/status`, `events/clinical`). See [interface.md](interface.md) for the full topic taxonomy.

## Device Identity

A UUID is generated on first launch and persisted in `SharedPreferences` (`dcc_device_prefs`). This becomes the device serial used in all MQTT topics as `pump-fleet/{serial}`. The serial is stable across app restarts; clearing app data generates a new identity.

## Authentication

The DCC uses **Cognito Identity Pool** (unauthenticated flow) to obtain temporary AWS credentials. These credentials authorize:
- `iot:Connect`, `iot:Publish`, `iot:Subscribe`, `iot:Receive` — for MQTT
- `s3:PutObject` — for PDF report uploads

The `CognitoCachingCredentialsProvider` caches credentials in SharedPreferences and auto-refreshes them before expiry.

> **Critical:** The Cognito Identity Pool must be in the **same AWS account** as the IoT Core endpoint and cloud infrastructure. A mismatch results in events being published to the wrong account.

## Resilience

| Scenario | Behavior |
|----------|----------|
| Network lost | Events queue in Room. `NetworkCallback.onAvailable()` triggers flush. |
| MQTT disconnected | SDK auto-reconnects (up to 10 attempts, exponential backoff). `Connected` callback triggers flush. |
| Publish hangs | 15-second timeout per publish prevents blocking the queue. |
| Process killed | Room persists events. On restart, `Connected` callback flushes the queue. |
| S3 upload fails | Retries up to 3 times. Failed reports stay in Room for manual inspection. |

## Security

- **AIDL permission**: `com.artmedical.permission.BIND_DCC` gates access to the service.
  - Development: `protectionLevel="normal"` (any app can bind)
  - Production: must change to `protectionLevel="signature"` (only apps signed with the same certificate)
- **AWS credentials**: never stored in the APK. Obtained at runtime via Cognito and cached in SharedPreferences.
- **No secrets in source**: endpoint, pool ID, and bucket name come from `local.properties` (gitignored) via `BuildConfig`.
