# DCC Interface Reference

## AIDL Interface

### ICloudConnectService

The primary interface exposed by the DCC service. Client apps obtain this via `ServiceConnection.onServiceConnected()`.

```kotlin
interface ICloudConnectService {
    void publishEvent(in CloudEventParcel event);
    void uploadReport(in ReportMetadata metadata, in ParcelFileDescriptor pfd);
    void registerListener(ICloudEventListener listener);
    void unregisterListener(ICloudEventListener listener);
}
```

| Method | Description |
|--------|-------------|
| `publishEvent` | Queue an event for MQTT publishing. Returns immediately; delivery is asynchronous. |
| `uploadReport` | Upload a PDF report to S3. The PFD is read immediately; the caller can close it after this returns. |
| `registerListener` | Register for downlink command callbacks from the cloud. |
| `unregisterListener` | Unregister a previously registered listener. |

### ICloudEventListener

Callback interface for receiving cloud-to-device commands.

```kotlin
interface ICloudEventListener {
    void onEventReceived(in CloudEventParcel event);
}
```

Commands arrive on the MQTT topic `pump-fleet/{serial}/cmd/#` and are forwarded to all registered listeners.

---

## Data Types

### CloudEventParcel

The universal event envelope. Every uplink event uses this structure.

```kotlin
@Parcelize
data class CloudEventParcel(
    val id: String,              // UUID — unique per event
    val source: String,          // Subsystem name (e.g. "pump-controller")
    val type: String,            // MQTT sub-topic (e.g. "sys/device/dev001/status")
    val time: Long,              // System.currentTimeMillis() — epoch ms
    val priority: Int,           // 0, 1, or 2 (see Priority section)
    val dataContentType: String, // Always "application/json"
    val dataJson: String         // The JSON payload
) : Parcelable
```

### ReportMetadata

Metadata for PDF report uploads.

```kotlin
@Parcelize
data class ReportMetadata(
    val reportId: String,        // UUID for this report
    val deviceSerial: String,    // Device identifier
    val patientId: String,       // Patient identifier
    val reportType: String,      // DAILY_SUMMARY, INCIDENT, DISCHARGE
    val reportDate: String,      // ISO date, e.g. "2026-03-26"
    val generatedAt: Long,       // Epoch ms when report was generated
    val pageCount: Int,          // Number of pages
    val fileSizeBytes: Long      // File size in bytes
) : Parcelable
```

---

## Priority and QoS

| Priority | MQTT QoS | Behavior | Use For |
|----------|----------|----------|---------|
| `0` | QoS 0 (fire-and-forget) | May be lost; no retransmission | Status telemetry (1Hz), consumables |
| `1` | QoS 1 (at-least-once) | DCC retries until acknowledged | Therapy, power, fluidics, GRV, maintenance |
| `2` | QoS 1 (at-least-once) | Same as 1, but **drained first** in queue | Safety alarms |

The queue processor drains all priority >= 1 events before processing any priority 0 events. Within the high-priority pool, events are processed FIFO.

---

## MQTT Topic Taxonomy — ICD-Aligned

Topics follow the ICD Aggregated Telemetry API (Rev 04). Each topic maps to one subsystem at a specific cadence.

The full MQTT topic on the wire is:
```
$aws/rules/smart_ingest/pump-fleet/{device_serial}/{event.type}
```

### Topic Tree

```
pump-fleet/{device_serial}/
│
├── system/
│   ├── metadata           ← Battery, CPU temp, uptime, network (1-5s, QoS 0)
│   └── connection         ← UP / DOWN / DEGRADED (on change, QoS 1)
│
├── pump/
│   ├── status             ← Dose program state, motor, source states (1s, QoS 0)
│   └── dose               ← Per-dose rate, cumulative volume (1s while feeding, QoS 0)
│
├── plan/
│   ├── settings           ← VTBD, basal rate, max rate (on change, QoS 1)
│   └── status             ← Efficiency, net/expected/delivered (1-5s, QoS 0)
│
├── tube/
│   ├── status             ← 16-state feeding tube position (1s, QoS 0)
│   └── impedance          ← 6-channel z1-z6, s1-s3 (0.5-2s, QoS 0)
│
├── grv/status             ← Drainage bag state, session volume (1-5s, QoS 0)
├── ree/status             ← REE state, VCO2, breath state (5-30s, QoS 0)
├── reflux/status          ← Hourly pre-aggregated counts/durations (per packet, QoS 0)
│
├── events/
│   ├── clinical           ← 60+ clinical event types with correlation (on event, QoS 1)
│   └── mechanical         ← Console errors, system errors (on event, QoS 1)
│
├── report/jobs            ← PDF upload confirmation metadata (on upload, QoS 1)
│
└── cmd/                   ← Downlink commands (cloud → device)
    ├── config
    └── control
```

### Event Type Summary

| # | Topic (`type` field) | Cadence | QoS | Publisher Method |
|---|---------------------|---------|-----|-----------------|
| 1 | `system/metadata` | 1-5s | 0 | `sendSystemMetadata` |
| 2 | `system/connection` | On change | 1 | `sendSystemConnection` |
| 3 | `pump/status` | 1s | 0 | `sendPumpStatus` |
| 4 | `pump/dose` | 1s (while feeding) | 0 | `sendPumpDose` |
| 5 | `plan/settings` | On change | 1 | `sendPlanSettings` |
| 6 | `plan/status` | 1-5s | 0 | `sendPlanStatus` |
| 7 | `grv/status` | 1-5s | 0 | `sendGrvStatus` |
| 8 | `ree/status` | 5-30s | 0 | `sendReeStatus` |
| 9 | `tube/status` | 1s | 0 | `sendTubeStatus` |
| 10 | `tube/impedance` | 0.5-2s | 0 | `sendTubeImpedance` |
| 11 | `reflux/status` | Per packet | 0 | `sendRefluxStatus` |
| 12 | `events/clinical` | On event | 1 | `sendClinicalEvent` |
| 13 | `events/mechanical` | On event | 1 | `sendMechanicalEvent` |
| 14 | `report/jobs` | On upload | 1 | `sendReportMetadata` |

---

## CloudEventPublisher

The `CloudEventPublisher` class in `shared-api` provides typed methods for each ICD topic. Use this instead of constructing `CloudEventParcel` directly:

```kotlin
val publisher = CloudEventPublisher(dccService)
publisher.sendSystemMetadata(json)    // system/metadata   QoS 0
publisher.sendPumpStatus(json)        // pump/status        QoS 0
publisher.sendClinicalEvent(json)     // events/clinical    QoS 1
publisher.sendReportMetadata(json)    // report/jobs        QoS 1
// ... 14 typed methods total
```

---

## Payload Schemas

The MQTT payload is `CloudEventParcel.dataJson` — only the inner JSON, not the full envelope. The envelope fields (`id`, `source`, `time`, `priority`) are reconstructed cloud-side by the IoT Rule.

> Full payload schemas (field-level reference with types, enums, and examples) are maintained in the cloud repository at `Cloud/docs/dcc-integration-guide.md`.

### 1. System Metadata

```json
{
  "system_state": "SYSTEM_COLLECTING_DATA",
  "battery_pct": 78,
  "cpu_temp_c": 52,
  "mains_connected": true,
  "os_uptime_sec": 14523,
  "gui_alive_counter": 8842,
  "network_speed_kbps": 1200,
  "timestamp": 1708819200000
}
```

### 2. Pump Status

```json
{
  "dose_program_state": "NUTRIENTS_DOSE_RELEASE",
  "motor_state": "RUNNING",
  "instant_rate_ml_hr": 125.0,
  "nutrients_source_state": "DETECTED",
  "fluids_source_state": "DETECTED",
  "nutrients_prime_state": "PRIMED",
  "fluids_prime_state": "PRIMED",
  "nutrients_fluids_ratio": 80,
  "timestamp": 1708819200000
}
```

### 3. Clinical Event

```json
{
  "event_type": "OCCLUSION",
  "severity": "CRITICAL",
  "message": "Occlusion detected on feeding line",
  "correlation_id": "corr-8f3a-4b2c",
  "timestamp": 1708819200000
}
```

### 4. Report Upload Metadata

Published automatically by the DCC after a successful S3 upload:

```json
{
  "report_id": "550e8400-e29b-41d4-a716-446655440000",
  "s3_key": "reports/{serial}/{date}/{uuid}.pdf",
  "report_date": "2026-03-26",
  "report_type": "DAILY_SUMMARY",
  "size_bytes": 245760,
  "timestamp": 1711411200000
}
```

---

## Binding to the DCC

```kotlin
private var cloudService: ICloudConnectService? = null
private var publisher: CloudEventPublisher? = null

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        cloudService = ICloudConnectService.Stub.asInterface(binder)
        publisher = CloudEventPublisher(cloudService!!)
    }
    override fun onServiceDisconnected(name: ComponentName) {
        cloudService = null
        publisher = null
    }
}

// Bind
val intent = Intent("com.artmedical.dcc.START_SERVICE")
intent.component = ComponentName("com.artmedical.dcc", "com.artmedical.dcc.service.ConnectivityService")
bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

The client app must declare the permission in its manifest:
```xml
<uses-permission android:name="com.artmedical.permission.BIND_DCC" />
```

---

## Cloud-Side References

For full payload field reference, Athena table schema, S3 storage layout, and GraphQL types, see the cloud repository:

- `Cloud/docs/dcc-integration-guide.md` — complete ICD-aligned integration guide with all payload schemas, state enums, and Kotlin helpers
- `Cloud/docs/data-model.md` — Athena DDL, S3 partitioning, and GraphQL types
