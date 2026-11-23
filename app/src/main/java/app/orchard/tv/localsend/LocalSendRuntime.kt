package app.orchard.tv.localsend

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject

/**
 * Owns the persistent LocalSend device identity and Android's Wi-Fi multicast
 * receive lock. Call acquireMulticast() while discovery is active and always
 * release it when discovery stops.
 *
 * The private key is kept only in app-private SharedPreferences. Never log the
 * returned JSON or the private key.
 */
internal class LocalSendRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val wifiManager = appContext.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    data class Identity(
        val privateKeyPem: String,
        val publicKeyPem: String,
        val certificatePem: String,
        val fingerprint: String
    )

    @Synchronized
    fun getOrCreateIdentity(): Identity {
        val privateKey = preferences.getString(KEY_PRIVATE_KEY, null)
        val publicKey = preferences.getString(KEY_PUBLIC_KEY, null)
        val certificate = preferences.getString(KEY_CERTIFICATE, null)
        val fingerprint = preferences.getString(KEY_FINGERPRINT, null)

        if (!privateKey.isNullOrBlank() &&
            !publicKey.isNullOrBlank() &&
            !certificate.isNullOrBlank() &&
            !fingerprint.isNullOrBlank()
        ) {
            return Identity(privateKey, publicKey, certificate, fingerprint)
        }

        val generated = JSONObject(LocalSendNative.generateSecurityContext())
        if (generated.has("error")) {
            throw IllegalStateException(
                "LocalSend identity generation failed: ${generated.optString("error")}"
            )
        }

        val identity = Identity(
            privateKeyPem = generated.getString("privateKey"),
            publicKeyPem = generated.getString("publicKey"),
            certificatePem = generated.getString("certificate"),
            fingerprint = generated.getString("fingerprint")
        )

        check(
            preferences.edit()
                .putString(KEY_PRIVATE_KEY, identity.privateKeyPem)
                .putString(KEY_PUBLIC_KEY, identity.publicKeyPem)
                .putString(KEY_CERTIFICATE, identity.certificatePem)
                .putString(KEY_FINGERPRINT, identity.fingerprint)
                .commit()
        ) { "Could not persist LocalSend device identity" }

        return identity
    }

    @Synchronized
    fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("AerialLocalSend").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @Synchronized
    fun releaseMulticast() {
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
    }

    companion object {
        private const val PREFS_NAME = "localsend_identity"
        private const val KEY_PRIVATE_KEY = "private_key_pem"
        private const val KEY_PUBLIC_KEY = "public_key_pem"
        private const val KEY_CERTIFICATE = "certificate_pem"
        private const val KEY_FINGERPRINT = "fingerprint"
    }
}
