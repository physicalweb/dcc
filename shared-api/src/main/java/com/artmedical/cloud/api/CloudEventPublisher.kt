package com.artmedical.cloud.api

import java.util.UUID

/**
 * Typed helper for publishing ICD-aligned telemetry events to the DCC.
 *
 * Each method maps to a canonical DCC topic from the Aggregated Telemetry API.
 * The DCC service publishes to: pump-fleet/{serial}/{type}
 *
 * Usage:
 *   val publisher = CloudEventPublisher(dccService)
 *   publisher.sendSystemMetadata(jsonPayload)
 *   publisher.sendPumpDose(jsonPayload)
 */
class CloudEventPublisher(private val dccService: ICloudConnectService) {

    // ── Core publish method ──────────────────────────────────────

    /**
     * Send any event. Prefer the typed helpers below.
     *
     * @param type     MQTT sub-topic, e.g. "system/metadata"
     * @param source   Subsystem name, e.g. "pump-controller"
     * @param priority 0=fire-and-forget, 1=at-least-once, 2=critical
     * @param payload  JSON string — the actual event data
     */
    fun send(type: String, source: String, priority: Int, payload: String) {
        val parcel = CloudEventParcel(
            id = UUID.randomUUID().toString(),
            source = source,
            type = type,
            time = System.currentTimeMillis(),
            priority = priority,
            dataContentType = "application/json",
            dataJson = payload
        )
        dccService.publishEvent(parcel)
    }

    // ── System ───────────────────────────────────────────────────

    /** System metadata: battery, CPU temp, uptime, network speed. 1-5s, QoS 0. */
    fun sendSystemMetadata(payload: String) =
        send("system/metadata", "system", 0, payload)

    /** Connectivity state change: UP/DOWN/DEGRADED. On-change, QoS 1. */
    fun sendSystemConnection(payload: String) =
        send("system/connection", "system", 1, payload)

    // ── Pump ─────────────────────────────────────────────────────

    /** Pump status: pump state, motor, dose program state. 1s, QoS 0. */
    fun sendPumpStatus(payload: String) =
        send("pump/status", "pump-controller", 0, payload)

    /** Per-dose delivery: rate, cumulative volume, source state. 1s while feeding, QoS 0. */
    fun sendPumpDose(payload: String) =
        send("pump/dose", "pump-controller", 0, payload)

    // ── Plan ─────────────────────────────────────────────────────

    /** Plan settings: vtbd, basal rate, max rate, deliverOver. On-change, QoS 1. */
    fun sendPlanSettings(payload: String) =
        send("plan/settings", "pump-controller", 1, payload)

    /** Plan status: FW-computed efficiency, net/expected/delivered volume. 1-5s, QoS 0. */
    fun sendPlanStatus(payload: String) =
        send("plan/status", "pump-controller", 0, payload)

    // ── Clinical Subsystems ──────────────────────────────────────

    /** GRV/drainage: bag state, session volume, GRV open volume. 1-5s, QoS 0. */
    fun sendGrvStatus(payload: String) =
        send("grv/status", "patient-monitor", 0, payload)

    /** REE: ree_state + current REE, VCO2, breath state. 5-30s, QoS 0. */
    fun sendReeStatus(payload: String) =
        send("ree/status", "sensor-hub", 0, payload)

    /** Feeding tube: 16-state ft_state enum. 1s, QoS 0. */
    fun sendTubeStatus(payload: String) =
        send("tube/status", "sensor-hub", 0, payload)

    /** Impedance: 6-channel z1-z6, s1-s3. 0.5-2s, QoS 0. */
    fun sendTubeImpedance(payload: String) =
        send("tube/impedance", "sensor-hub", 0, payload)

    /** Reflux hourly pre-aggregated: minor/massive counts and durations. Per-packet, QoS 0. */
    fun sendRefluxStatus(payload: String) =
        send("reflux/status", "patient-monitor", 0, payload)

    // ── Events ───────────────────────────────────────────────────

    /** Clinical events: 60+ event types with correlation. On-event, QoS 1. */
    fun sendClinicalEvent(payload: String) =
        send("events/clinical", "patient-monitor", 1, payload)

    /** Mechanical events: console_err, system_err. On-event, QoS 1. */
    fun sendMechanicalEvent(payload: String) =
        send("events/mechanical", "system", 1, payload)

    // ── Reports ──────────────────────────────────────────────────

    /** Report metadata (after S3 upload). On-upload, QoS 1. */
    fun sendReportMetadata(payload: String) =
        send("report/jobs", "system", 1, payload)

    // ── Deprecated (backward compatibility) ──────────────────────

    /** @deprecated Use sendSystemMetadata + sendPumpStatus + sendTubeStatus */
    @Deprecated(
        message = "Fat status blob. Migrate to ICD-aligned topics.",
        replaceWith = ReplaceWith("sendSystemMetadata(payload)")
    )
    fun sendStatus(deviceId: String, payload: String) =
        send("sys/device/$deviceId/status", "pump-controller", 0, payload)

    /** @deprecated Use sendClinicalEvent */
    @Deprecated(
        message = "Use sendClinicalEvent with ICD event taxonomy.",
        replaceWith = ReplaceWith("sendClinicalEvent(payload)")
    )
    fun sendAlarm(patientId: String, payload: String) =
        send("sys/clinical/$patientId/safety/alarm", "patient-monitor", 2, payload)

    /** @deprecated Use sendClinicalEvent */
    @Deprecated(
        message = "Use sendClinicalEvent with ICD event taxonomy.",
        replaceWith = ReplaceWith("sendClinicalEvent(payload)")
    )
    fun sendTherapyNutrition(patientId: String, payload: String) =
        send("sys/clinical/$patientId/therapy/nutrition", "patient-monitor", 1, payload)
}
