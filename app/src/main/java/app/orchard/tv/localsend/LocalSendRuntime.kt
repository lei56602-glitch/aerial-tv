package app.orchard.tv.localsend

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject

/**
 * Owns LocalSend's persistent device identity and multicast discovery lifetime.
 * The private key is stored only in app-private preferences and is never logged.
 */
internal class LocalSendRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var discoverySessionId: String? = null

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
        check(!generated.has("error")) {
            "LocalSend identity generation failed: ${generated.optString("error")}"
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
    fun startDiscovery(alias: String = "Aerial TV"): String {
        discoverySessionId?.let { return it }
        acquireMulticast()

        return try {
            val identity = getOrCreateIdentity()
            val result = JSONObject(
                LocalSendNative.startDiscovery(
                    alias,
                    identity.certificatePem,
                    identity.privateKeyPem,
                    identity.fingerprint
                )
            )
            check(!result.has("error")) {
                "LocalSend discovery failed: ${result.optString("error")}"
            }
            result.getString("sessionId").also { discoverySessionId = it }
        } catch (error: Throwable) {
            releaseMulticast()
            throw error
        }
    }

    fun getDiscoveredDevices(): JSONObject {
        val session = discoverySessionId
            ?: return JSONObject().put("devices", org.json.JSONArray())
        return JSONObject(LocalSendNative.getDiscoveredDevices(session))
    }

    @Synchronized
    fun announcePresence(): Boolean {
        val session = discoverySessionId ?: return false
        return JSONObject(LocalSendNative.announceDiscovery(session)).optBoolean("queued")
    }

    @Synchronized
    fun setReceiveServerReady(ready: Boolean): Boolean {
        val session = discoverySessionId ?: return false
        return JSONObject(
            LocalSendNative.setAnswerAnnouncements(session, ready)
        ).optBoolean("queued")
    }

    @Synchronized
    fun probePeer(host: String, port: Int = 53317, https: Boolean = true): Boolean {
        require(host.isNotBlank()) { "Peer host cannot be empty" }
        require(port in 1..65535) { "Peer port must be between 1 and 65535" }
        val session = discoverySessionId ?: return false
        return JSONObject(
            LocalSendNative.probeDevice(session, host, port, https)
        ).optBoolean("queued")
    }

    @Synchronized
    fun stopDiscovery() {
        val session = discoverySessionId ?: run {
            releaseMulticast()
            return
        }
        discoverySessionId = null
        try {
            LocalSendNative.stopDiscovery(session)
        } finally {
            releaseMulticast()
        }
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
