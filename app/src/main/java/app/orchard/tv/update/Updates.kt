package app.orchard.tv.update

import android.content.Context
import android.content.Intent
import app.orchard.tv.BuildConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Automatic updates are intentionally disabled in this fork.
 *
 * This application must never query or download releases from the upstream Aerial TV project.
 * A first-party updater can be added later when this repository has its own release channel.
 */
object Updates {
    val current: String = BuildConfig.VERSION_NAME.substringBefore('-')

    var available by mutableStateOf<String?>(null)
        private set

    var notes by mutableStateOf<List<ReleaseNotes>>(emptyList())
        private set

    sealed interface Install {
        data object Idle : Install
        data class Downloading(val fraction: Float) : Install
        data object Waiting : Install
        data object Opened : Install
        data object NeedsPermission : Install
        data object NeedsStoragePermission : Install
        data class Failed(val reason: Failure) : Install
    }

    enum class Failure { Download, Blocked, NotOfficial, NotNewer }

    var install by mutableStateOf<Install>(Install.Idle)
        private set

    const val CONTROL_CENTER_MAX_AGE_MS = 0L

    fun init(context: Context) {
        // No network calls, cached upstream version, or background updater initialization.
        available = null
        notes = emptyList()
        install = Install.Idle
    }

    fun checkIfStale(
        force: Boolean = false,
        maxAgeMs: Long = 0L,
        networkCameUp: Boolean = false,
    ) {
        // Deliberately disabled: no update checks against upstream or third-party services.
    }

    fun diagnostics(): String = "automatic updates disabled; no upstream update requests"

    fun loadNotes() {
        notes = emptyList()
    }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun canInstall(context: Context): Boolean = false

    fun install(context: Context) {
        // Installation from upstream release servers is disabled.
        install = Install.Idle
    }

    fun resetInstall() {
        install = Install.Idle
    }

    fun onSessionResult(context: Context, intent: Intent) = Unit
}
