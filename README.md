# DCC - Decoupled Connectivity Component

A headless Android service that provides cloud connectivity to other applications on the same device via AIDL IPC. Client apps bind to the DCC to publish events and receive commands without bundling any networking or cloud logic themselves.

> For cloud-side architecture (IoT Rules, topic routing, data pipeline), see the separate cloud documentation.

## Architecture

The project is structured as a multi-module Android application to ensure a clean separation of concerns.

### Modules

*   ### `:app` (The DCC Service)
    This is the core of the project. It is an Android application with no user interface (no Activities). Its sole purpose is to run a `ForegroundService` (`ConnectivityService`) that:
    1.  Manages a persistent MQTT connection to the cloud backend.
    2.  Exposes an AIDL interface (`ICloudConnectService`) that other applications can bind to.
    3.  Receives data from client apps, persists them in a Room database, and publishes them to the cloud with priority-based ordering.
    4.  Receives commands from the cloud and forwards them to any bound client apps.
    5.  Automatically recovers and flushes queued events on network restoration or MQTT reconnect.

*   ### `:client` (Sample Client App)
    This is a standard Android application with a simple UI that demonstrates how to consume the DCC service. It shows how to:
    1.  Bind to the external `ConnectivityService` using an `Intent`.
    2.  Receive an `IBinder` object and cast it to the `ICloudConnectService` AIDL interface.
    3.  Call methods on the service to publish events (device status, therapy, alarms) and upload PDF reports.
    4.  Implement a listener (`ICloudEventListener`) to receive callbacks from the service when a command is received from the cloud.

*   ### `:shared-api` (Shared Interface Library)
    This is the most critical module for ensuring the system works. It is a simple Android Library module that contains the "contract" for the communication between the service and its clients. It contains the AIDL files that define the interfaces and the Kotlin definitions for any custom data types.
    
    Both the `:app` and `:client` modules depend on this library. This guarantees that they are always compiled against the exact same interface, preventing runtime mismatches.

## Key Features

*   **Multi-module Android Project:** Clean, scalable structure with shared AIDL contracts.
*   **AIDL IPC:** Android's native, high-performance inter-process communication.
*   **Foreground Service:** Persistent background connectivity on modern Android.
*   **Stable Device Identity:** UUID generated once, persisted in `SharedPreferences`, consistent across restarts.
*   **Persistent Event Queue (Room):** Events survive service restarts; only deleted after MQTT delivery acknowledgment.
*   **Priority-Based Queue:** High-priority events (alarms) drain first; low-priority (telemetry) processed between priority checks.
*   **Network Recovery:** Auto-flushes queue on network restoration and MQTT reconnect via `ConnectivityManager.NetworkCallback`.
*   **PDF Report Upload:** Client apps send reports via `ParcelFileDescriptor`; the service uploads to S3 and publishes MQTT metadata.
*   **Publish Timeout:** MQTT publishes time out after 15 seconds to prevent hung callbacks from blocking the queue.
*   **mTLS Auth:** MQTT connects via X.509 mutual TLS. ECDSA P-256 keypair generated on first run in hardware-backed Android Keystore (private key non-extractable), cert obtained via CSR-based self-enrollment to a cloud HTTPS endpoint. PDF reports upload via cloud-issued presigned PUT URLs over the same MQTT connection — the device holds no AWS credentials.

## Security Model

Interaction between external apps and the DCC service is protected by a custom Android permission. The protection level can be configured for development or production use.

### Development Mode (`normal`)

**The project is currently configured for development.** The `app` module's manifest defines the permission with a `protectionLevel` of `normal`.

```xml
<!-- WARNING: For development only. Change back to "signature" for release. -->
<permission
    android:name="com.artmedical.permission.BIND_DCC"
    android:protectionLevel="normal" />
```

This is a permissive level that allows **any application** that requests the permission in its manifest to bind to the service. This is useful for development, as it allows multiple developers to test their client apps without needing to share a signing key.

### Production Mode (`signature`)

For a production release, the `protectionLevel` **must** be changed to `signature`.

```xml
<permission
    android:name="com.artmedical.permission.BIND_DCC"
    android:protectionLevel="signature" />
```

This is a robust security model that tells the Android OS to only grant the permission to apps that are signed with the **exact same digital certificate** as the `:app` module. This ensures that only your own trusted suite of applications can interact with the service.

Any client app wishing to connect must still request this permission in its own manifest:
```xml
<uses-permission android:name="com.artmedical.permission.BIND_DCC" />
```

## Getting Started

### Prerequisites

*   Android Studio (latest stable version) with SDK installed
*   Access to the team's GitLab (`gitlab.artmedical.cloud`) — SSH key registered
*   A device or emulator (Android 9+ / API 28+) with internet access
*   A nudge to the cloud team to allowlist your device serial — see step 4 below

### 1. Clone

```bash
git clone git@gitlab.artmedical.cloud:arnonz/dcc.git
cd dcc
```

> Already cloned from GitHub? Repoint `origin` to GitLab:
> ```bash
> git remote set-url origin git@gitlab.artmedical.cloud:arnonz/dcc.git
> git fetch origin --prune
> ```

### 2. Configure `local.properties`

The build needs at minimum `sdk.dir` (the path to your local Android SDK). Copy the template and edit:

```bash
cp local.properties.example local.properties
# edit local.properties — set sdk.dir to your Android SDK path
```

The IoT endpoint, enrollment URL, and enrollment token have sensible dev defaults baked into `app/build.gradle.kts` that point at the team's dev cloud. Leave those overrides commented out unless you need to point at a different AWS account.

`local.properties` is gitignored — do not commit it.

### 3. Build

```bash
./gradlew clean assembleDebug
```

This compiles all three modules and produces:
- `app/build/outputs/apk/debug/app-debug.apk` — the DCC service
- `client/build/outputs/apk/debug/client-debug.apk` — the test client

### 4. First-run cloud allowlist (one-time, REQUIRED)

The DCC self-enrolls on first launch by sending a CSR to the cloud enrollment endpoint. The cloud only accepts CSRs from device serials that are already registered as IoT Things. **Your first launch will fail with HTTP 404 from enrollment unless your serial is allowlisted.**

The serial is a UUID generated locally on first launch. To register it:

1. Install both APKs (`adb install -r <path>`) and launch the client — let the first enrollment attempt fail.
2. Grab the serial from logcat:
   ```bash
   adb logcat -s "DCC-Service" | grep "Device serial:"
   ```
   It will look like `Device serial: 550e8400-e29b-41d4-a716-446655440000`.
3. Ping the cloud team with that UUID; they add it to the IoT Thing allowlist (or you do this yourself via the cloud repo's `provision-device.py`).
4. Force-restart the DCC service (or reboot the device). Enrollment will succeed and you'll see `Connected to AWS IoT` in logcat.

Subsequent launches skip enrollment. To re-enroll (new keypair + cert), clear app data: `adb shell pm clear com.artmedical.dcc`.

### 5. Deploy and run

Install both APKs on the same device/emulator (Android Studio "Run" works fine for each module, or `adb install -r`).

1. **Service app (`:app`):** no UI; installing it is enough.
2. **Client app (`:client`):** installs as `com.artmedical.dccclient`. Launch it.
3. Tap **"Bind to DCC Service"**. The service starts (persistent notification appears) and the status text changes to "Connected".
4. Use the client buttons to exercise device status, therapy events, safety alarms, burst events, and PDF report upload. Watch logcat:
   ```bash
   adb logcat -s "DCC-Service" "DCC-Provisioner" "DCC-ReportUpload"
   ```

### 6. Troubleshooting

If anything doesn't work as expected, the symptom-cause-fix table in [docs/deployment.md](docs/deployment.md#troubleshooting) covers the common failures (404 on enrollment, MQTT never connects, presigned URL timeout, etc.).

## MQTT Topics

*   **Uplink (publish):** `$aws/rules/smart_ingest_icd/pump-fleet/{device-serial}/{topic}` (Basic Ingest prefix bypasses broker, routes directly to IoT Rule)
*   **Downlink (subscribe):** `pump-fleet/{device-serial}/cmd/#`
*   **Topics (ICD-aligned):** `system/metadata`, `pump/status`, `pump/dose`, `plan/settings`, `plan/status`, `tube/status`, `tube/impedance`, `grv/status`, `ree/status`, `events/clinical`, `events/mechanical`, `report/jobs`, etc.

The `device-serial` is a UUID generated on first launch and persisted in `SharedPreferences`.

## Limitations & Future Work

*   **Hardcoded Region:** AWS region is baked into the default IoT endpoint and enrollment URL in `app/build.gradle.kts` (us-east-1). Override via `local.properties` for other regions.
*   **No Exponential Backoff:** On publish failure, processing stops until the next network/reconnect trigger.
*   **Dev-mode Security:** `BIND_DCC` permission is set to `normal`. Must change to `signature` for production (see Security Model above).

## License

