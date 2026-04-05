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

## MQTT Topic Taxonomy

All event types use the `sys/` prefix to match the cloud IoT Rule `FROM 'pump-fleet/+/sys/#'`.

The full MQTT topic on the wire is:
```
$aws/rules/smart_ingest/pump-fleet/{device_serial}/{event.type}
```

### Topic Tree

```
pump-fleet/{device_serial}/
│
├── sys/device/{deviceId}/
│   ├── status             ← 1Hz telemetry snapshot (pri=0)
│   ├── power              ← Battery/mains events (pri=1)
│   ├── fluidics           ← Motor, pressure events (pri=1)
│   ├── consumables        ← Tube RFID, cassette (pri=0)
│   ├── maintenance        ← Error codes, self-test (pri=1)
│   └── report             ← PDF report upload metadata (pri=1)
│
├── sys/clinical/{patientId}/
│   ├── therapy/
│   │   ├── nutrition      ← Feeding plan lifecycle (pri=1)
│   │   └── fluids         ← Hydration plan events (pri=1)
│   └── safety/
│       ├── alarm          ← Critical safety alarms (pri=2)
│       └── grv            ← Gastric residual volume (pri=1)
│
└── cmd/                   ← Downlink commands (cloud → device)
    ├── config
    └── control
```

### Event Type Summary

| # | Event Type | `type` field value | Priority | Frequency |
|---|-----------|-------------------|----------|-----------|
| 1 | Device Status | `sys/device/{deviceId}/status` | 0 | 1Hz continuous |
| 2 | Safety Alarm | `sys/clinical/{patientId}/safety/alarm` | 2 | Event-driven |
| 3 | Therapy Nutrition | `sys/clinical/{patientId}/therapy/nutrition` | 1 | Event-driven |
| 4 | Therapy Fluids | `sys/clinical/{patientId}/therapy/fluids` | 1 | Event-driven |
| 5 | Power | `sys/device/{deviceId}/power` | 1 | Event-driven |
| 6 | Fluidics | `sys/device/{deviceId}/fluidics` | 1 | Event-driven |
| 7 | GRV | `sys/clinical/{patientId}/safety/grv` | 1 | Event-driven |
| 8 | Consumables | `sys/device/{deviceId}/consumables` | 0 | Event-driven |
| 9 | Maintenance | `sys/device/{deviceId}/maintenance` | 1 | Event-driven |
| 10 | Report Metadata | `sys/device/{deviceId}/report` | 1 | After S3 upload |

---

## Payload Schemas

The MQTT payload is `CloudEventParcel.dataJson` — only the inner JSON, not the full envelope. The envelope fields (`id`, `source`, `time`, `priority`) are reconstructed cloud-side by the IoT Rule.

> Full payload schemas (field-level reference with types, enums, and examples) are maintained in the cloud repository at `Cloud/docs/data-model.md`. The schemas below are summaries for quick reference.

### 1. Device Status

```json
{
  "state": "FEEDING",
  "sub_state": "DELIVERING",
  "uptime_sec": 14523,
  "battery": { "percent": 78, "is_mains": true, "voltage_mv": 12450, "charging": true },
  "pump": { "vol_delivered_ml": 245.5, "flow_rate_ml_hr": 125.0, "target_vol_ml": 500.0, "is_prime": false, "motor_rpm": 12 },
  "sensors": { "ft_connected": true, "ft_type": "NGT-12FR", "pressure_mmhg": 15.2, "temperature_c": 36.8, "impedance": { "z1": 1245.3, "z2": 1102.7, "z3": 1389.1, "s1": 82.4, "s2": 78.9 }, "ree_kcal_day": 1680 }
}
```

**States:** `IDLE`, `FEEDING`, `PAUSED`, `PRIMING`, `ALARM`, `FLUSHING`, `MAINTENANCE`, `OFF`

### 2. Safety Alarm

```json
{
  "event_code": "ALM_OCCLUSION",
  "lifecycle": "RAISED",
  "severity": "CRITICAL",
  "message": "Occlusion detected on feeding line",
  "correlation_id": "corr-8f3a-4b2c",
  "technical_context": { "pressure_mmhg": 85.3, "threshold_mmhg": 60.0, "motor_stall": true, "line_position": "PROXIMAL" }
}
```

**Codes:** `ALM_OCCLUSION`, `ALM_AIR_IN_LINE`, `ALM_BAG_EMPTY`, `ALM_BAG_FULL`, `ALM_LINE_DISPLACED`, `ALM_PUMP_MALFUNCTION`, `ALM_BATTERY_CRITICAL`, `ALM_SENSOR_FAULT`
**Lifecycle:** `RAISED`, `ACKNOWLEDGED`, `RESOLVED`, `ESCALATED`
**Severity:** `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`

### 3. Therapy / Nutrition

```json
{
  "event": "SESSION_START",
  "plan_id": "plan-abc123",
  "settings": { "vtbd_ml": 500, "basal_rate_ml_hr": 125, "product_name": "Jevity 1.5 Cal", "product_kcal_ml": 1.5, "ramp_up_min": 15, "max_rate_ml_hr": 150 },
  "patient_weight_kg": 72.0,
  "timestamp": 1708819200000
}
```

**Events:** `SESSION_START`, `SESSION_STOP`, `SESSION_PAUSE`, `SESSION_RESUME`, `PLAN_COMPLETE`, `PLAN_MODIFIED`, `BOLUS_START`, `BOLUS_COMPLETE`, `FLUSH_START`, `FLUSH_COMPLETE`

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

**Report types:** `DAILY_SUMMARY`, `INCIDENT`, `DISCHARGE`

---

## Binding to the DCC

```kotlin
private var dccService: ICloudConnectService? = null

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        dccService = ICloudConnectService.Stub.asInterface(binder)
    }
    override fun onServiceDisconnected(name: ComponentName) {
        dccService = null
    }
}

// Bind
val intent = Intent("com.artmedical.dcc.CLOUD_CONNECT")
intent.setPackage("com.artmedical.dcc")
bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

The client app must declare the permission in its manifest:
```xml
<uses-permission android:name="com.artmedical.permission.BIND_DCC" />
```

---

## Cloud-Side References

For full payload field reference, Athena table schema, S3 storage layout, and GraphQL types, see the cloud repository:

- `Cloud/docs/data-model.md` — complete data model with all payload schemas, Athena DDL, and S3 partitioning
- `Cloud/docs/dcc-integration-guide.md` — integration guide for medical.apk developers with Kotlin helpers and examples
