package com.artmedical.dcc.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.artmedical.dcc.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.json.JSONObject
import java.io.StringWriter
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/**
 * On first run, generate an ECDSA P-256 keypair in AndroidKeyStore (private
 * key never leaves), build a CSR, POST it to the enrollment endpoint, and
 * store the returned signed cert alongside the keypair.
 *
 * After successful enrollment, the AndroidKeyStore alias [KEYSTORE_ALIAS]
 * holds: the EC private key (hardware-backed) + the AWS-signed leaf cert.
 * That's everything mTLS needs.
 *
 * No `.p12` files. No SharedPreferences flag — AndroidKeyStore is the source
 * of truth, and `isProvisioned()` distinguishes the dummy self-signed cert
 * (created at keypair generation) from a real AWS-signed cert.
 */
class DeviceCertProvisioner(@Suppress("unused") private val context: Context) {

    fun isProvisioned(): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEYSTORE_ALIAS)) return false
        val cert = ks.getCertificate(KEYSTORE_ALIAS) as? X509Certificate ?: return false
        // AndroidKeyStore generates a self-signed dummy cert when the keypair is
        // created. A real AWS-signed cert has issuer != subject. Use that to
        // tell whether enrollment has actually completed.
        return cert.subjectX500Principal != cert.issuerX500Principal
    }

    /**
     * Idempotent. Returns true if the device is provisioned at exit (already
     * was, or just enrolled). Returns false on any failure during enrollment.
     */
    fun provisionIfNeeded(serial: String): Boolean {
        if (isProvisioned()) {
            Log.d(tag, "Already provisioned, skipping")
            return true
        }
        return try {
            Log.i(tag, "Generating EC keypair for $serial")
            val keyPair = generateKeyPair()
            Log.i(tag, "POSTing CSR to ${BuildConfig.ENROLLMENT_URL}")
            val csrPem = buildCsr(keyPair, serial)
            val certPem = enroll(serial, csrPem)
            storeCert(keyPair.private, certPem)
            deleteLegacyRsaEntry()
            Log.i(tag, "Enrolled $serial — cert in AndroidKeyStore")
            true
        } catch (e: Exception) {
            Log.e(tag, "Enrollment failed", e)
            // Best-effort cleanup: a half-provisioned key without a real cert
            // would block future enrollment because isProvisioned() trips on
            // the dummy cert if subject==issuer. We delete the alias so the
            // next attempt starts fresh.
            deleteKeyEntry()
            false
        }
    }

    fun loadDeviceKeyStore(@Suppress("UNUSED_PARAMETER") serial: String): KeyStore {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(ks.containsAlias(KEYSTORE_ALIAS)) {
            "Device not provisioned: alias '$KEYSTORE_ALIAS' not found"
        }
        return ks
    }

    // --- internals ----------------------------------------------------

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return kpg.generateKeyPair()
    }

    private fun buildCsr(keyPair: KeyPair, serial: String): String {
        val subject = X500Name("CN=$serial")
        val csrBuilder = JcaPKCS10CertificationRequestBuilder(subject, keyPair.public)
        // No setProvider("AndroidKeyStore") — AndroidKeyStore doesn't expose
        // SHA256withECDSA as a Signature service, so that path throws
        // NoSuchAlgorithmException. The default provider chain finds
        // Conscrypt's Signature impl, which routes signing of
        // AndroidKeyStore-resident PrivateKey objects back into the keystore
        // — the key never leaves (non-extractable; signing is delegated to
        // keymaster). If Conscrypt isn't picked up automatically on a given
        // platform, "AndroidKeyStoreBCWorkaround" is the bridge fallback.
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val csr = csrBuilder.build(signer)
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(csr) }
        return sw.toString()
    }

    private fun enroll(serial: String, csrPem: String): String {
        val token = BuildConfig.ENROLLMENT_TOKEN
        val body = JSONObject().apply {
            put("serial", serial)
            put("csrPem", csrPem)
            if (token.isNotEmpty()) put("token", token)
        }
        val req = Request.Builder()
            .url(BuildConfig.ENROLLMENT_URL)
            .post(body.toString().toRequestBody(JSON))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("enroll HTTP ${resp.code}: $responseBody")
            }
            return JSONObject(responseBody).getString("certPem")
        }
    }

    private fun storeCert(privateKey: PrivateKey, certPem: String) {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certPem.byteInputStream()) as X509Certificate
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Replaces the AndroidKeyStore-generated self-signed dummy cert with
        // the real one. The private key stays in AndroidKeyStore — we pass
        // the AndroidKeyStore-resident PrivateKey reference, not its bytes.
        ks.setKeyEntry(KEYSTORE_ALIAS, privateKey, null, arrayOf(cert))
    }

    private fun deleteKeyEntry() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            Log.w(tag, "Failed to clean up partial keystore entry", e)
        }
    }

    /**
     * Removes the legacy RSA entry from earlier mTLS provisioning, if present.
     * Idempotent and best-effort: failure here doesn't block enrollment.
     */
    private fun deleteLegacyRsaEntry() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(LEGACY_RSA_ALIAS)) {
                ks.deleteEntry(LEGACY_RSA_ALIAS)
                Log.i(tag, "Removed legacy RSA entry '$LEGACY_RSA_ALIAS'")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to remove legacy RSA entry", e)
        }
    }

    companion object {
        const val KEYSTORE_ALIAS = "mtls-device-cert-ec"
        private const val LEGACY_RSA_ALIAS = "mtls-device-cert"
        private const val tag = "DCC-Provisioner"
        private val JSON = "application/json".toMediaType()
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
