package app.orchard.tv.localsend

/**
 * Private JNI entry point for the embedded LocalSend protocol engine.
 *
 * Keep all private-key material inside the app process and persist it only in
 * Aerial's app-private storage. Never include it in diagnostics or logs.
 */
internal object LocalSendNative {
    init {
        System.loadLibrary("aerial_localsend")
    }

    external fun generateSecurityContext(): String
}
