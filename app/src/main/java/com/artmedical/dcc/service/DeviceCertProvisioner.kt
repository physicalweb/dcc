package com.artmedical.dcc.service

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Log
import com.artmedical.dcc.BuildConfig
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * One-time provisioning: read a .p12 (cert + private key) from external app storage,
 * import the PrivateKeyEntry into AndroidKeyStore, then delete the source file.
 *
 * Provisioning workflow on a new device:
 *   adb push <serial>.p12 /sdcard/Android/data/com.artmedical.dcc/files/provisioning/<serial>.p12
 *   (App will pick it up on next service start, import, and delete)
 */
class DeviceCertProvisioner(private val context: Context) {

    companion object {
        const val KEYSTORE_ALIAS = "mtls-device-cert"
        private const val PREFS = "dcc_mtls_prefs"
        private const val FLAG_PROVISIONED = "mtls.provisioned"
        private const val PROVISIONING_DIR = "provisioning"
        private const val P12_PASSWORD = "mtls"
        private const val tag = "DCC-Provisioner"
    }

    fun isProvisioned(): Boolean {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(FLAG_PROVISIONED, false)) {
            return false
        }
        // Verify the entry actually exists in the keystore
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.containsAlias(KEYSTORE_ALIAS)
    }

    /**
     * Looks for a .p12 file in the provisioning directory and imports it.
     * Returns true if provisioning succeeded (or was already done), false if no .p12 was found.
     */
    fun provisionIfNeeded(serial: String): Boolean {
        if (isProvisioned()) {
            Log.d(tag, "Already provisioned, skipping")
            return true
        }

        val p12File = findP12File(serial)
        if (p12File == null) {
            Log.w(tag, "No .p12 found in ${provisioningDir().absolutePath}")
            return false
        }

        return try {
            importP12(p12File)
            p12File.delete()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(FLAG_PROVISIONED, true).apply()
            Log.i(tag, "Provisioned successfully (deleted ${p12File.name})")
            true
        } catch (e: Exception) {
            Log.e(tag, "Provisioning failed", e)
            false
        }
    }

    private fun provisioningDir(): File {
        val dir = File(context.getExternalFilesDir(null), PROVISIONING_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun findP12File(serial: String): File? {
        val expected = File(provisioningDir(), "$serial.p12")
        if (expected.exists()) return expected
        // Fallback: any .p12 in the dir
        return provisioningDir().listFiles { f -> f.name.endsWith(".p12") }?.firstOrNull()
    }

    private fun importP12(p12File: File) {
        // Load the PKCS12 bundle
        val pkcs12 = KeyStore.getInstance("PKCS12")
        p12File.inputStream().use { pkcs12.load(it, P12_PASSWORD.toCharArray()) }

        // Find the private key entry (there should be exactly one)
        val alias = pkcs12.aliases().toList().firstOrNull { pkcs12.isKeyEntry(it) }
            ?: error("No key entry found in PKCS12")

        val privateKey = pkcs12.getKey(alias, P12_PASSWORD.toCharArray()) as PrivateKey
        val chain = pkcs12.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()

        // Import into AndroidKeyStore with broad TLS-handshake-friendly purposes:
        // - PURPOSE_SIGN + RSA-PSS is required for TLS 1.3 CertificateVerify.
        // - PURPOSE_SIGN + RSA-PKCS1 covers TLS 1.2.
        // - PURPOSE_DECRYPT + RSA-PKCS1 covers older RSA key-exchange suites.
        // Without RSA-PSS the handshake fails with conscrypt's
        // "RSA routines:OPENSSL_internal:internal error".
        val androidKs = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        androidKs.setEntry(
            KEYSTORE_ALIAS,
            KeyStore.PrivateKeyEntry(privateKey, chain),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                    KeyProperties.DIGEST_SHA1,
                )
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                )
                .build()
        )
    }

    /**
     * Returns a KeyStore handle suitable for AWSIotMqttManager.connect(KeyStore, ...).
     * Throws if the device isn't provisioned.
     *
     * Debug builds only: if a `<serial>.p12` is present in the provisioning
     * dir at call time, that PKCS12 is loaded as a transient in-memory
     * KeyStore instead of going through AndroidKeyStore. This is a
     * workaround for the Android emulator's software keymaster, which fails
     * to perform RSA-PSS signing required by TLS 1.3 (`RSA routines:
     * OPENSSL_internal:internal error` inside conscrypt). On real devices
     * with hardware keymasters, AndroidKeyStore is used as intended; the
     * debug fallback is dead code in release builds (compiled out by
     * ProGuard/R8 along with the BuildConfig.DEBUG branch).
     */
    fun loadDeviceKeyStore(serial: String): KeyStore {
        if (BuildConfig.DEBUG) {
            val p12 = File(provisioningDir(), "$serial.p12")
            if (p12.exists()) {
                Log.w(tag, "Debug build: loading PKCS12 from ${p12.absolutePath}")
                val ks = KeyStore.getInstance("PKCS12")
                p12.inputStream().use { ks.load(it, P12_PASSWORD.toCharArray()) }
                return ks
            }
        }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(ks.containsAlias(KEYSTORE_ALIAS)) {
            "Device not provisioned: alias '$KEYSTORE_ALIAS' not found in AndroidKeyStore"
        }
        return ks
    }
}
