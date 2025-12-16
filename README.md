# DCC - Decoupled Connectivity Component

This project is a demonstration of a "headless" Android service that provides cloud connectivity to other applications on the same device. It uses Android's native Inter-Process Communication (IPC) mechanism, AIDL, to expose its functionality securely and efficiently.

The primary goal is to create a single, updatable component (the `dcc` app) that manages all cloud communication, while other "client" apps can use its services without needing to bundle any networking or security logic themselves.

## Architecture

The project is structured as a multi-module Android application to ensure a clean separation of concerns.

### Modules

*   ### `:app` (The DCC Service)
    This is the core of the project. It is an Android application with no user interface (no Activities). Its sole purpose is to run a `ForegroundService` (`ConnectivityService`) that:
    1.  Manages a persistent connection to a cloud backend (in this case, AWS IoT Core).
    2.  Exposes an AIDL interface (`ICloudConnectService`) that other applications can bind to.
    3.  Receives data from client apps and publishes it to the cloud.
    4.  Receives commands from the cloud and forwards them to any bound client apps.

*   ### `:client` (Sample Client App)
    This is a standard Android application with a simple UI that demonstrates how to consume the DCC service. It shows how to:
    1.  Bind to the external `ConnectivityService` using an `Intent`.
    2.  Receive an `IBinder` object and cast it to the `ICloudConnectService` AIDL interface.
    3.  Call methods on the service to publish events (e.g., "Publish Patient Weight").
    4.  Implement a listener (`ICloudEventListener`) to receive callbacks from the service when a command is received from the cloud.

*   ### `:shared-api` (Shared Interface Library)
    This is the most critical module for ensuring the system works. It is a simple Android Library module that contains the "contract" for the communication between the service and its clients.
    1.  It contains the AIDL files (`.aidl`) that define the interfaces (`ICloudConnectService`, `ICloudEventListener`).
    2.  It also contains the Kotlin definition for any custom data types that need to be sent across the process boundary (e.g., `CloudEventParcel.kt`).
    
    Both the `:app` and `:client` modules depend on this library. This guarantees that they are always compiled against the exact same interface, preventing runtime mismatches.

## Key Features

*   **Multi-module Android Project:** Demonstrates a clean, scalable project structure.
*   **AIDL for Inter-Process Communication:** Uses Android's native, high-performance IPC mechanism.
*   **Foreground Service:** Shows the correct way to run persistent background tasks on modern Android.
*   **AWS IoT Integration:** Includes a full implementation for connecting to AWS IoT Core using Cognito for authentication.
*   **Kotlin Coroutines:** Used for managing background tasks and asynchronous operations within the service.

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

1.  **Install the Service App:** Install the `app` module. You can do this from Android Studio by selecting the `app` run configuration and clicking "Run". Since it has no UI, it will install and then stop.
2.  **Install and Run the Client App:** Install and run the `client` module by selecting its run configuration and clicking "Run".
3.  **Using the Client:**
    *   Click the **"Bind to DCC Service"** button. The service will start (you will see a persistent notification), and the status text will change to "Connected".
    *   Click the **"Publish 'Patient Weight' Event"** button. This will send a message through the service to AWS IoT. You can view this message in the AWS IoT Console's MQTT Test Client by subscribing to the topic `pump-fleet/#`.

## Limitations & Future Work

This project is a proof of concept and is not production-ready. Key limitations include:

*   **No Persistent Queue:** If the service cannot reach the cloud, events sent from the client are held in an in-memory queue and will be lost if the service is killed. A production implementation would use a database (like Room) or file-based queue.
*   **Minimal Error Handling:** The error handling for network issues or invalid data is very basic.
*   **Basic Security:** The service is protected by a `signature`-level permission, which means only apps signed with the same key can bind to it. This is a good baseline but may not be sufficient for all use cases.
*   **Hardcoded AWS Region:** The AWS Region is currently hardcoded to `us-east-1` in `ConnectivityService.kt`.

## License

This is a public, open-source project intended for demonstration purposes. It contains no proprietary IP. It is recommended to use a standard permissive license like MIT or Apache 2.0 if you intend to publish it.
