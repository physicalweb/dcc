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

*   Android Studio (latest stable version)
*   Cloud backend configured (see cloud documentation for setup).

### 1. Configuration

The IoT endpoint has a sensible dev default in `app/build.gradle.kts`. To override (e.g. point at a different account), add to `local.properties`:

```properties
AWS_IOT_ENDPOINT=<your-iot-endpoint>
```

> **Note:** The DCC self-enrolls on first run — generates an ECDSA keypair in Android Keystore, sends a CSR to the cloud enrollment endpoint, stores the returned cert. No manual cert sideload. See [docs/deployment.md](docs/deployment.md) for details.

### 2. Build the Project

You can build the entire project from the command line using the Gradle wrapper:

```bash
./gradlew clean assembleDebug
```

This will compile all three modules and produce two APK files: `app-debug.apk` and `client-debug.apk`.

### 3. Deploy and Run

You must install both applications on the same device or emulator.

1.  **Install the Service App:** Install the `:app` module from Android Studio. Since it has no UI, it will just install.
2.  **Install and Run the Client App:** Install and run the `:client` module from Android Studio.
3.  **Using the Client:**
    *   Click the **"Bind to DCC Service"** button. The service will start (you will see a persistent notification), and the status text will change to "Connected".
    *   Use the buttons to send device status, therapy events, safety alarms, burst events, or upload a sample PDF report.

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

