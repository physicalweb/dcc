# DCC Deployment Guide

## Prerequisites

- Android Studio (latest stable)
- Android SDK with platform tools (adb)
- Target device or emulator with network access
- AWS credentials for the target account (the same account running the cloud infrastructure)

---

## 1. AWS Infrastructure (cloud-managed)

The DCC connects via X.509 mutual TLS — there are no AWS credentials on the device. The cloud team owns these resources via CDK:

- **IoT Thing + cert + IoT policy** per device, provisioned by `provision-device.py` (cloud repo)
- **`smart-report-upload-url` Lambda** — issues 1-hour presigned PUT URLs over MQTT
- **IoT Rules** for ingest + report-URL request/response
- **S3 bucket** `smart-reports-<account-id>-dev`

DCC-side prerequisites: just the IoT endpoint hostname (stable per AWS account).

```bash
aws iot describe-endpoint --endpoint-type iot:Data-ATS --region us-east-1 --profile <your-profile>
```

---

## 2. Local Configuration

Create or update `local.properties` in the project root:

```properties
sdk.dir=/path/to/android/sdk
AWS_IOT_ENDPOINT=<iot-endpoint>.iot.us-east-1.amazonaws.com
```

The endpoint has a sensible dev default in `app/build.gradle.kts`, so this entry is optional unless pointing at a non-dev account. `local.properties` is gitignored.

---

## 3. Build

```bash
./gradlew clean assembleDebug
```

This produces two APKs:
- `app/build/outputs/apk/debug/app-debug.apk` — the DCC service
- `client/build/outputs/apk/debug/client-debug.apk` — the test client

Verify the build config:
```bash
grep AWS_IOT_ENDPOINT \
  app/build/generated/source/buildConfig/debug/com/artmedical/dcc/BuildConfig.java
```

---

## 4. Deploy

### Install both APKs

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r client/build/outputs/apk/debug/client-debug.apk
```

### Provision the mTLS device cert

The DCC requires an X.509 cert + private key to connect to AWS IoT Core. Get the `.p12` bundle for your device serial from the cloud team's `provision-device.py` script, then sideload it:

```bash
# First, ensure the provisioning dir exists (DCC creates it on first launch, but this is faster):
adb shell run-as com.artmedical.dcc mkdir -p files/provisioning  # may not work pre-Android 10
# Or: just push to the external app-specific dir (no permissions required):
adb push <serial>.p12 /sdcard/Android/data/com.artmedical.dcc/files/provisioning/<serial>.p12
```

On first service start, the DCC will:
1. Detect the `.p12`
2. Import it into hardware-backed AndroidKeyStore (alias `mtls-device-cert`)
3. Delete the source `.p12`
4. Persist a `mtls.provisioned` flag in SharedPreferences

Subsequent launches skip provisioning and connect directly.

### Launch the client

```bash
adb shell am start -n com.artmedical.dccclient/.MainActivity
```

Then tap **"Bind to DCC Service"** in the client UI. The DCC service starts as a foreground service.

### Re-provision (if switching devices/accounts)

To force re-provisioning (new cert) on the next launch, clear app data:

```bash
adb shell pm clear com.artmedical.dcc
```

This also wipes the AndroidKeyStore alias `mtls-device-cert` and the device serial UUID. Push a fresh `.p12` to the provisioning dir before re-launching.

---

## 5. Verification

### Check MQTT connection

```bash
adb logcat -s "DCC-Service" "AWSIotMqttManager"
```

Look for:
```
I DCC-Service: Connecting to endpoint: <your-endpoint>
I DCC-Service: Connected to AWS IoT
```

### Check event publishing

After tapping "Send System Metadata" or "Burst" in the client:
```
V DCC-Service: Received Upstream: system/metadata [Pri: 0]
D DCC-Service: processQueue: started
D DCC-Service: Uploaded: $aws/rules/smart_ingest/pump-fleet/<serial>/system/metadata
D DCC-Service: processQueue: finished
```

### Check report uploads

After tapping "Upload Sample PDF Report":
```
I DCC-Service: Received report upload: <uuid>
I DCC-ReportUpload: Upload complete: reports/<serial>/<date>/<uuid>.pdf
D DCC-Service: Uploaded: $aws/rules/smart_ingest/pump-fleet/<serial>/report/jobs
```

The `Upload complete` log means the OkHttp PUT to the presigned URL succeeded. Verify the file landed:
```bash
aws s3 ls s3://smart-reports-<account-id>-dev/reports/<serial>/ --profile <your-profile>
```

### Dashboard visibility

The device appears in the cloud dashboard's device picker once:
1. At least one `system/metadata` event reaches IoT Core
2. Firehose flushes to S3 (up to 60 seconds buffer)
3. The Athena query in `getActiveDevices` finds events with the device serial in the last 24 hours

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Cannot connect: device not provisioned` | No `.p12` in provisioning dir | `adb push <serial>.p12 /sdcard/Android/data/com.artmedical.dcc/files/provisioning/` and restart the service. |
| `Connecting...` but never `Connected` | Wrong endpoint, expired/revoked cert, or clientId mismatch | Verify `AWS_IOT_ENDPOINT` matches the target account. Check IoT Core CloudWatch logs for the connection rejection reason. |
| `Reconnect failed` repeatedly | Emulator/device has no internet | Check `adb logcat` for DNS failures. Cold boot the emulator or check proxy settings. |
| `Received Upstream` but no `Uploaded` | processQueue stuck (timeout or isProcessing deadlock) | Look for `processQueue: skipped (already processing)` or `Publish timed out`. Restart the DCC service. |
| `Delivery failed: Fail` | MQTT publish failed (connection dropped mid-publish) | Transient — the queue retries on next trigger. Check if connection is stable. |
| `URL request timed out` (report upload) | Cloud `smart-report-upload-url` Lambda not responding within 10s | Check Lambda CloudWatch logs. Verify IoT Rule for `reports/upload-url/request` is enabled. |
| Report upload `HTTP 403` | Presigned URL expired or signature mismatch | Re-trigger the upload — DCC will request a fresh URL. |
| Device not in dashboard picker | No telemetry events reaching data lake | Ensure client sends `system/metadata` events. Wait 60s for Firehose flush. |
| Events reach IoT Core but not Athena | Topic doesn't match IoT Rule filter | Verify topic is one of the ICD-aligned topics (e.g. `system/metadata`, `pump/status`). |

---

## Production Checklist

- [ ] Change `protectionLevel` from `"normal"` to `"signature"` in `app/src/main/AndroidManifest.xml`
- [ ] Tighten IoT policy on the cloud side: scope `iot:Connect` and `iot:Publish` to the specific Thing/topics
- [ ] Configure ProGuard/R8 rules for AWS SDK classes
- [ ] Set up monitoring: CloudWatch alarms on IoT Rule errors, Firehose delivery failures
- [ ] Verify Room database migration path for schema changes beyond v2
