# DCC Deployment Guide

## Prerequisites

- Android Studio (latest stable)
- Android SDK with platform tools (adb)
- Target device or emulator with network access
- AWS credentials for the target account (the same account running the cloud infrastructure)

---

## 1. AWS Infrastructure Setup

Before the DCC can connect, the following resources must exist in the target AWS account:

### Cognito Identity Pool

The DCC uses a Cognito Identity Pool with **unauthenticated access** to obtain temporary AWS credentials at runtime.

```bash
# Create the identity pool
aws cognito-identity create-identity-pool \
  --identity-pool-name "DCC_Device_Pool" \
  --allow-unauthenticated-identities \
  --region us-east-1 \
  --profile <your-profile>

# Create the IAM role for unauthenticated access
aws iam create-role \
  --role-name DCC_Unauth_Role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Federated": "cognito-identity.amazonaws.com"},
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {"cognito-identity.amazonaws.com:aud": "<identity-pool-id>"},
        "ForAnyValue:StringLike": {"cognito-identity.amazonaws.com:amr": "unauthenticated"}
      }
    }]
  }'

# Attach permissions
aws iam put-role-policy \
  --role-name DCC_Unauth_Role \
  --policy-name DCC_IoT_S3_Access \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Action": ["iot:Connect", "iot:Publish", "iot:Subscribe", "iot:Receive"],
        "Resource": "*"
      },
      {
        "Effect": "Allow",
        "Action": "s3:PutObject",
        "Resource": "arn:aws:s3:::<reports-bucket>/reports/*"
      }
    ]
  }'

# Associate the role with the identity pool
aws cognito-identity set-identity-pool-roles \
  --identity-pool-id "<identity-pool-id>" \
  --roles unauthenticated="arn:aws:iam::<account-id>:role/DCC_Unauth_Role" \
  --region us-east-1
```

### Get the IoT Endpoint

```bash
aws iot describe-endpoint --endpoint-type iot:Data-ATS --region us-east-1 --profile <your-profile>
```

---

## 2. Local Configuration

Create or update `local.properties` in the project root:

```properties
sdk.dir=/path/to/android/sdk
AWS_IOT_ENDPOINT=<iot-endpoint>.iot.us-east-1.amazonaws.com
COGNITO_POOL_ID=us-east-1:<identity-pool-uuid>
S3_REPORTS_BUCKET=smart-reports-<account-id>-dev
```

> **Critical:** All three AWS values must point to the **same AWS account** where the cloud infrastructure (IoT Rules, Firehose, dashboard) is deployed. A mismatch results in MQTT connections succeeding but events not reaching the data pipeline.

`local.properties` is gitignored and never committed.

---

## 3. Build

```bash
./gradlew clean assembleDebug
```

This produces two APKs:
- `app/build/outputs/apk/debug/app-debug.apk` — the DCC service
- `client/build/outputs/apk/debug/client-debug.apk` — the test client

Verify the build config is correct:
```bash
grep -E "AWS_IOT|COGNITO|S3_REPORTS" \
  app/build/generated/source/buildConfig/debug/com/artmedical/dcc/BuildConfig.java
```

All three fields should be non-empty.

---

## 4. Deploy

### Install both APKs

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r client/build/outputs/apk/debug/client-debug.apk
```

### Launch the client

```bash
adb shell am start -n com.artmedical.dccclient/.MainActivity
```

Then tap **"Bind to DCC Service"** in the client UI. The DCC service starts as a foreground service.

### Clear cached credentials (if switching accounts)

When changing the Cognito pool or AWS account, clear the DCC's app data to flush cached credentials:

```bash
adb shell pm clear com.artmedical.dcc
```

This also resets the device serial (a new UUID will be generated on next launch).

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

After tapping "Send Device Status" or "Burst" in the client:
```
V DCC-Service: Received Upstream: sys/device/dev001/status [Pri: 0]
D DCC-Service: processQueue: started
D DCC-Service: Uploaded: $aws/rules/smart_ingest/pump-fleet/<serial>/sys/device/dev001/status
D DCC-Service: processQueue: finished
```

### Check report uploads

After tapping "Upload Sample PDF Report":
```
I DCC-Service: Received report upload: <uuid>
D DCC-ReportUpload: S3 upload state: IN_PROGRESS for reports/<serial>/<date>/<uuid>.pdf
D DCC-Service: Uploaded: $aws/rules/smart_ingest/pump-fleet/<serial>/sys/device/<serial>/report
```

### Dashboard visibility

The device appears in the cloud dashboard's device picker once:
1. At least one `sys/device/{deviceId}/status` event reaches IoT Core
2. Firehose flushes to S3 (up to 60 seconds buffer)
3. The Athena query in `getActiveDevices` finds events with `full_topic LIKE '%/status'` in the last 24 hours

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connecting...` but never `Connected` | Wrong IoT endpoint or Cognito pool in different account | Verify `local.properties` values match target account. Run `aws iot describe-endpoint` with the correct profile. |
| `Reconnect failed` repeatedly | Emulator/device has no internet | Check `adb logcat` for DNS failures. Cold boot the emulator or check proxy settings. |
| `Received Upstream` but no `Uploaded` | processQueue stuck (timeout or isProcessing deadlock) | Look for `processQueue: skipped (already processing)` or `Publish timed out`. Restart the DCC service. |
| `Delivery failed: Fail` | MQTT publish failed (connection dropped mid-publish) | Transient — the queue retries on next trigger. Check if connection is stable. |
| S3 `403 Access Denied` | Cognito unauth role missing `s3:PutObject` | Add `s3:PutObject` permission to the role for `arn:aws:s3:::<bucket>/reports/*`. |
| Device not in dashboard picker | No `status` events reaching data lake | Ensure client sends `sys/device/{id}/status` events (not just therapy/alarm). Wait 60s for Firehose flush. |
| Events reach IoT Core but not Athena | Topic doesn't match IoT Rule filter | Verify event type starts with `sys/`. The rule matches `FROM 'pump-fleet/+/sys/#'`. |
| `UninitializedPropertyAccessException: mqttManager` | Race condition (should be fixed) | Update to latest build with `!::mqttManager.isInitialized` guard. |

---

## Production Checklist

- [ ] Change `protectionLevel` from `"normal"` to `"signature"` in `app/src/main/AndroidManifest.xml`
- [ ] Tighten IoT IAM policy: scope `iot:Connect` to specific client ID pattern, `iot:Publish` to `pump-fleet/*/sys/*` topics
- [ ] Enable certificate-based auth (X.509) instead of Cognito unauthenticated flow
- [ ] Configure ProGuard/R8 rules for AWS SDK classes
- [ ] Set up monitoring: CloudWatch alarms on IoT Rule errors, Firehose delivery failures
- [ ] Verify Room database migration path for schema changes beyond v2
