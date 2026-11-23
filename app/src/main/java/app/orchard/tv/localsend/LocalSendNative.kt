package app.orchard.tv.localsend

/**
 * JNI boundary to the pinned LocalSend Rust core.
 *
 * Methods exchange versioned JSON strings so the Kotlin UI layer does not
 * depend on Rust data-layout details.
 */
internal object LocalSendNative {
    init {
        System.loadLibrary("aerial_localsend")
    }

    external fun generateSecurityContext(): String

    external fun startDiscovery(
        alias: String,
        certificate: String,
        privateKey: String,
        fingerprint: String
    ): String

    external fun getDiscoveredDevices(sessionId: String): String

    external fun stopDiscovery(sessionId: String): String
}
