package io.github.ccch1mneyyy.dsh_app.phone.shell

import android.content.Context
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Root transport for Android Shell: `su -c` starts the very same [AndroidShellMain] the ADB
 * path starts, so the frame protocol, [ShellProtocol.validUid] and the job core are shared and
 * only the two pipes differ. Nothing here invents a second command path.
 *
 * su is only run from [connect] (an explicit user action in the settings page) and from the
 * cached [suAvailable] probe; status reads never start a root shell that keeps running.
 */
internal class ShellRootBackend(private val context: Context, private val onChanged: () -> Unit) {

    private val guard = Any()
    private var process: Process? = null
    private var channel: ShellLink? = null

    /** Cached so a page that polls status does not spawn `su -c id` on every frame. */
    @Volatile private var suProbe: Pair<Long, Boolean>? = null

    fun status(): Map<String, Any?> {
        val alive = synchronized(guard) { channel?.alive() == true }
        return mapOf("suAvailable" to suAvailable(), "connected" to alive)
    }

    fun connect(wanted: () -> Boolean): ShellConnection {
        synchronized(guard) { channel?.takeIf { it.alive() && wanted() } }?.let { return ShellConnection(it) }
        val info = context.applicationInfo
        val classpath = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).joinToString(":")
        if (classpath.isBlank() || '\u0000' in classpath) return ShellConnection(errorCode = "invalidArguments")
        val command = "CLASSPATH=" + quote(classpath) +
            " exec /system/bin/app_process /system/bin " + AndroidShellMain::class.java.name
        val started = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (_: Exception) {
            suProbe = System.currentTimeMillis() to false
            return ShellConnection(errorCode = "rootUnavailable")
        }
        // An unread stderr pipe fills up and wedges the helper; the app's uid cannot drain it later.
        drain(started.errorStream)
        val candidate = try {
            ShellLink.root(started)
        } catch (_: Exception) {
            started.destroyForcibly()
            return ShellConnection(errorCode = "connectFailed")
        }
        if (!candidate.probe()) {
            candidate.close()
            started.destroyForcibly()
            return ShellConnection(errorCode = "identityChanged")
        }
        synchronized(guard) {
            if (!wanted()) {
                candidate.close(); started.destroyForcibly()
                return ShellConnection(errorCode = "cancelled")
            }
            channel?.close(); process?.destroyForcibly()
            channel = candidate
            process = started
        }
        onChanged()
        return ShellConnection(candidate)
    }

    fun disconnect() {
        val pair = synchronized(guard) {
            (channel to process).also { channel = null; process = null }
        }
        pair.first?.close()
        pair.second?.destroyForcibly()
    }

    /** `su -c id -u` → "0" means a root shell is reachable; anything else is reported as unavailable. */
    private fun suAvailable(): Boolean {
        val now = System.currentTimeMillis()
        suProbe?.let { (at, value) -> if (now - at < SU_CACHE_MS) return value }
        val value = try {
            val probe = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val text = probe.inputStream.bufferedReader().use { it.readText() }
            probe.waitFor(3, TimeUnit.SECONDS)
            probe.destroyForcibly()
            text.lines().lastOrNull { it.isNotBlank() }?.trim() == "0"
        } catch (_: Exception) {
            false
        }
        suProbe = now to value
        return value
    }

    private fun drain(stream: InputStream) {
        Thread({
            runCatching {
                val buffer = ByteArray(4096)
                while (stream.read(buffer) >= 0) Unit
            }
        }, "dsh-shell-root-stderr").apply { isDaemon = true }.start()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        /** Long enough that a settings page does not re-probe, short enough to notice a revoked grant. */
        const val SU_CACHE_MS = 30_000L
    }
}
