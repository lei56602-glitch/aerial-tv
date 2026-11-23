package app.orchard.tv.localsend

/**
 * JNI boundary to the pinned LocalSend Rust core.
 * Methods exchange versioned JSON strings rather than Rust data layouts.
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

    external fun announceDiscovery(sessionId: String): String

    external fun setAnswerAnnouncements(sessionId: String, enabled: Boolean): String

    external fun probeDevice(
        sessionId: String,
        host: String,
        port: Int,
        https: Boolean
    ): String

    external fun stopDiscovery(sessionId: String): String

    external fun startHttpServer(
        alias: String,
        certificate: String,
        privateKey: String,
        fingerprint: String,
        requestedPort: Int = 53317
    ): String

    external fun pollHttpServerEvents(sessionId: String): String

    external fun respondPrepareUpload(
        serverSessionId: String,
        transferSessionId: String,
        acceptedFileIdsJson: String
    ): String

    external fun acceptFileUpload(
        serverSessionId: String,
        transferSessionId: String,
        fileId: String,
        destinationPath: String
    ): String

    external fun stopHttpServer(sessionId: String): String
}
