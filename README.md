# DCC - Decoupled Connectivity Component

This project is a demonstration of a "headless" Android service that provides cloud connectivity to other applications on the same device. It uses Android's native Inter-Process Communication (IPC) mechanism, AIDL, to expose its functionality securely and efficiently.

The primary goal is to create a single, updatable component (the `dcc` app) that manages all cloud communication, while other "client" apps can use its services without needing to bundle any networking or security logic themselves.

## Architecture

The project is structured as a multi-module Android application to ensure a clean separation of concerns.

### Modules

*   ### `:app` (The DCC Service)
    This is the core of the project. It is an Android application with no user interface (no Activities). Its sole purpose is to run a `ForegroundService` (`ConnectivityService`) that:
    1.  Manages a persistent connection to AWS IoT Core via MQTT.
    2.  Exposes an AIDL interface (`ICloudConnectService`) that other applications can bind to.
    3.  Receives data from client apps, persists them in a Room database, and publishes them to the cloud with priority-based ordering.
    4.  Receives commands from the cloud and forwards them to any bound client apps.
    5.  Automatically recovers and flushes queued events on network restoration or MQTT reconnect.

*   ### `:client` (Sample Client App)
    This is a standard Android application with a simple UI that demonstrates how to consume the DCC service. It shows how to:
    1.  Bind to the external `ConnectivityService` using an `Intent`.
    2.  Receive an `IBinder` object and cast it to the `ICloudConnectService` AIDL interface.
    3.  Call methods on the service to publish events (e.g., "Publish Patient Weight").
    4.  Implement a listener (`ICloudEventListener`) to receive callbacks from the service when a command is received from the cloud.

*   ### `:shared-api` (Shared Interface Library)
    This is the most critical module for ensuring the system works. It is a simple Android Library module that contains the "contract" for the communication between the service and its clients. It contains the AIDL files that define the interfaces and the Kotlin definitions for any custom data types.
    
    Both the `:app` and `:client` modules depend on this library. This guarantees that they are always compiled against the exact same interface, preventing runtime mismatches.

## Key Features

*   **Multi-module Android Project:** Clean, scalable project structure with shared AIDL contracts.
*   **AIDL for Inter-Process Communication:** Uses Android's native, high-performance IPC mechanism.
*   **Foreground Service:** Correct implementation of persistent background tasks on modern Android.
*   **AWS IoT Core + Basic Ingest:** Publishes via `$aws/rules/<rule>/` topics for 12.5× cost reduction ($0.08/M vs $1.00/M). Downlink subscriptions use standard topics.
*   **Stable Device Identity:** Device serial is a UUID generated once and persisted in `SharedPreferences`, ensuring consistent identity across service restarts.
*   **Persistent Event Queue (Room):** Events are stored in a Room database before publishing. Events are only deleted after receiving an MQTT delivery acknowledgment, preventing data loss.
*   **Priority-Based Queue Processing:** High-priority events (alarms, priority ≥ 1) are drained first via a fast lane. Low-priority events (telemetry, priority 0) are processed one at a time, re-checking the high-priority queue between each.
*   **Network Recovery:** Registers a `ConnectivityManager.NetworkCallback` to automatically flush the queue when network becomes available. Also triggers queue processing on MQTT reconnect.
*   **Kotlin Coroutines:** `suspendCancellableCoroutine` ensures the service waits for MQTT delivery confirmation before marking events as sent.

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
*   An Amazon Web Services (AWS) account with configured IoT Core and Cognito Identity Pool.

### 1. Configuration

Before you can build, you must provide your AWS credentials. Create a file named `local.properties` in the root directory of the project (`/dcc/local.properties`) with the following content:

```properties
# AWS IoT Core Configuration
AWS_IOT_ENDPOINT=xxxxxxxxxxxxxx-ats.iot.your-region.amazonaws.com
COGNITO_POOL_ID=your-region:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

*   `AWS_IOT_ENDPOINT`: The unique endpoint for your AWS IoT Core service. Found in the AWS IoT Console under **Settings**.
*   `COGNITO_POOL_ID`: The ID of the Cognito Identity Pool used to grant guest access to IoT Core.

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
    *   Click the **"Publish 'Patient Weight' Event"** button. This will send a message through the service to AWS IoT. You can view this message in the AWS IoT Console's MQTT Test Client by subscribing to the topic `pump-fleet/#`.

## MQTT Topics

*   **Uplink (publish):** `$aws/rules/smart_ingest/pump-fleet/{device-serial}/{event-type}` — routed via Basic Ingest directly to the `smart_ingest` IoT Rule.
*   **Downlink (subscribe):** `pump-fleet/{device-serial}/cmd/#` — standard topic for receiving cloud commands.

The `device-serial` is a UUID generated on first launch and persisted in `SharedPreferences` (`dcc_device_prefs`). The cloud extracts it via `topic(2)` in IoT Rule SQL to partition data by device.

## Limitations & Future Work

*   **Hardcoded AWS Region:** The AWS Region is currently hardcoded to `us-east-1` in `ConnectivityService.kt`.
*   **No Exponential Backoff:** On publish failure, the queue processor stops and waits for a network/reconnect trigger rather than implementing exponential retry.
*   **Security:** The `BIND_DCC` permission is set to `normal` for development. Must be changed to `signature` for production (see Security Model above).

## License

This is a public, open-source project intended for demonstration purposes. It contains no proprietary IP. It is recommended to use a standard permissive license like MIT or Apache 2.0 if you intend to publish it.
