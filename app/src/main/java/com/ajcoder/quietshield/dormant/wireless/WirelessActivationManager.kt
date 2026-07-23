package com.ajcoder.quietshield.dormant.wireless

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.ajcoder.quietshield.dormant.engine.DormantEngineClient
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.SSLException

sealed interface WirelessActivationResult {
    data object Success : WirelessActivationResult
    data class Failure(val message: String) : WirelessActivationResult
}

/**
 * Pairs QuietShield Dormant with this phone's Wireless Debugging service, starts
 * the small shell helper, verifies it, and then disconnects the ADB session.
 */
class WirelessActivationManager(private val context: Context) {
    private val engineClient = DormantEngineClient(context)

    fun hasSavedPairing(): Boolean =
        DormantAdbConnectionManager.hasSavedIdentity(context)

    fun forgetPairing(): Boolean {
        runCatching { context.filesDir.resolve("engine_token").delete() }
        return DormantAdbConnectionManager.forgetIdentity(context)
    }

    suspend fun pairAndStart(
        host: String,
        port: Int,
        pairingCode: String,
    ): WirelessActivationResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext WirelessActivationResult.Failure(
                "Wireless setup requires Android 11 or newer.",
            )
        }
        if (host.isBlank() || port !in 1..65535) {
            return@withContext WirelessActivationResult.Failure(
                "Dormant could not find Android's current pairing screen. [DISCOVERY-01]",
            )
        }
        if (!pairingCode.matches(Regex("\\d{6}"))) {
            return@withContext WirelessActivationResult.Failure(
                "Enter the six-digit pairing code shown by Android. [CODE-01]",
            )
        }

        val endpointReachable = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PAIRING_SOCKET_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
        if (!endpointReachable) {
            return@withContext WirelessActivationResult.Failure(
                "Android's pairing port changed or expired. Open a fresh pairing code and try again. [PORT-01]",
            )
        }

        val manager = runCatching {
            DormantAdbConnectionManager.getInstance(context)
        }.getOrElse { error ->
            Log.e(TAG, "Unable to prepare ADB identity", error)
            return@withContext WirelessActivationResult.Failure(
                "Dormant could not prepare its secure pairing identity. [IDENTITY-01]",
            )
        }

        val pairAttempt = runCatching {
            manager.pair(host, port, pairingCode)
        }
        if (pairAttempt.isFailure) {
            val error = pairAttempt.exceptionOrNull()
            Log.e(TAG, "Wireless pairing handshake failed", error)
            safeDisconnect(manager)
            return@withContext WirelessActivationResult.Failure(pairingFailureMessage(error))
        }
        if (pairAttempt.getOrDefault(false).not()) {
            safeDisconnect(manager)
            return@withContext WirelessActivationResult.Failure(
                "Android did not confirm the pairing. Open a fresh code and try again. [PAIR-01]",
            )
        }

        connectAndStart(manager)
    }

    suspend fun restoreAndStart(): WirelessActivationResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext WirelessActivationResult.Failure(
                "Wireless setup requires Android 11 or newer.",
            )
        }
        if (!hasSavedPairing()) {
            return@withContext WirelessActivationResult.Failure(
                "Pair this phone first using Android's six-digit pairing code.",
            )
        }
        val manager = runCatching {
            DormantAdbConnectionManager.getInstance(context)
        }.getOrElse {
            return@withContext WirelessActivationResult.Failure(
                "The saved wireless setup could not be opened.",
            )
        }
        connectAndStart(manager)
    }

    private suspend fun connectAndStart(
        manager: DormantAdbConnectionManager,
    ): WirelessActivationResult {
        if (engineClient.ping()) return WirelessActivationResult.Success

        var connected = false
        repeat(3) { attempt ->
            connected = try {
                manager.autoConnect(context, 8_000)
            } catch (_: AdbPairingRequiredException) {
                false
            } catch (error: Throwable) {
                Log.e(TAG, "Wireless ADB connection attempt failed", error)
                false
            }
            if (connected) return@repeat
            if (attempt < 2) delay(1_000L)
        }
        if (!connected) {
            safeDisconnect(manager)
            return WirelessActivationResult.Failure(
                "Pairing was accepted, but Dormant could not find Android's connection service. Return to the main Wireless Debugging screen and tap Restore. [CONNECT-01]",
            )
        }

        val token = generateToken()
        context.filesDir.resolve("engine_token").writeText(token, Charsets.UTF_8)
        val startCommand = buildHelperStartCommand(
            installedApkPath = context.applicationInfo.sourceDir,
            token = token,
        )

        val launchError = runCatching {
            dispatchShellCommand(manager, startCommand)
        }.exceptionOrNull()
        if (launchError != null) {
            Log.e(TAG, "Unable to dispatch Dormant helper launch command", launchError)
            safeDisconnect(manager)
            return WirelessActivationResult.Failure(
                "Android paired successfully, but Dormant could not open the command channel. [HELPER-CHANNEL-01]",
            )
        }

        repeat(HELPER_PING_ATTEMPTS) {
            delay(HELPER_PING_INTERVAL_MS)
            if (engineClient.ping()) {
                safeDisconnect(manager)
                return WirelessActivationResult.Success
            }
        }

        val diagnosticText = runCatching {
            readShellOutputBounded(
                manager = manager,
                command = buildHelperDiagnosticCommand(),
                timeoutMs = HELPER_DIAGNOSTIC_TIMEOUT_MS,
            )
        }.getOrElse { error ->
            Log.e(TAG, "Unable to read Dormant helper diagnostics", error)
            ""
        }
        safeDisconnect(manager)
        Log.e(TAG, "Dormant helper did not answer. Diagnostic: ${diagnosticText.take(4_000)}")
        return WirelessActivationResult.Failure(helperFailureMessage(diagnosticText))
    }

    /**
     * R3 used a broad process-name kill with the helper class name. The current adb shell command
     * also contained that class name, so the kill could terminate its own launcher
     * shell before app_process was started. R4 uses a verified PID file instead.
     *
     * The APK is copied to /data/local/tmp before app_process starts. This keeps
     * the helper class path stable and matches the normal Android shell-server
     * pattern used by tools that launch a Java main class through app_process.
     */
    private fun buildHelperStartCommand(
        installedApkPath: String,
        token: String,
    ): String {
        val className = "com.ajcoder.quietshield.dormant.engine.DormantShellMain"
        return buildString {
            append("rm -f $HELPER_LAUNCH_LOG $HELPER_ENGINE_LOG; ")
            append("{ ")
            append("echo LAUNCH_BEGIN; ")
            append("oldpid=\$(cat $HELPER_PID_FILE 2>/dev/null || true); ")
            append("case \"\$oldpid\" in ''|*[!0-9]*) ;; *) ")
            append("oldcmd=\$(tr '\\000' ' ' </proc/\$oldpid/cmdline 2>/dev/null || true); ")
            append("case \"\$oldcmd\" in *DormantShellMain*) kill \"\$oldpid\" 2>/dev/null || true ;; esac ;; esac; ")
            append("rm -f $HELPER_PID_FILE; ")
            append("cp ")
            append(shellQuote(installedApkPath))
            append(" $HELPER_APK_FILE || { echo APK_COPY_FAILED; exit 43; }; ")
            append("chmod 0644 $HELPER_APK_FILE 2>/dev/null || true; ")
            append("if [ ! -x /system/bin/app_process ]; then echo APP_PROCESS_MISSING; exit 44; fi; ")
            append("if command -v nohup >/dev/null 2>&1; then ")
            append("nohup env CLASSPATH=$HELPER_APK_FILE /system/bin/app_process /system/bin ")
            append(className)
            append(" --port ${DormantEngineClient.PORT} --token ")
            append(shellQuote(token))
            append(" >$HELPER_ENGINE_LOG 2>&1 </dev/null & ")
            append("else env CLASSPATH=$HELPER_APK_FILE /system/bin/app_process /system/bin ")
            append(className)
            append(" --port ${DormantEngineClient.PORT} --token ")
            append(shellQuote(token))
            append(" >$HELPER_ENGINE_LOG 2>&1 </dev/null & fi; ")
            append("newpid=\$!; echo \"\$newpid\" >$HELPER_PID_FILE; echo START_SENT:\$newpid; ")
            append("} >$HELPER_LAUNCH_LOG 2>&1")
        }
    }

    private fun buildHelperDiagnosticCommand(): String =
        "printf 'LAUNCHER\\n'; cat $HELPER_LAUNCH_LOG 2>/dev/null; " +
            "printf '\\nENGINE\\n'; tail -c 4096 $HELPER_ENGINE_LOG 2>/dev/null; " +
            "printf '\\nPID\\n'; cat $HELPER_PID_FILE 2>/dev/null"

    /**
     * Opens a one-shot shell service and waits for the remote shell to finish,
     * but does not call readText(). LibADB's input stream reports a normal
     * no-output remote close as IOException("Stream closed."), which R3 treated
     * as a failed launch even when Android had accepted the command.
     */
    private suspend fun dispatchShellCommand(
        manager: DormantAdbConnectionManager,
        command: String,
    ) {
        val stream = manager.openStream("shell:$command")
        try {
            val deadline = SystemClock.elapsedRealtime() + HELPER_COMMAND_TIMEOUT_MS
            while (!stream.isClosed && SystemClock.elapsedRealtime() < deadline) {
                delay(50L)
            }
        } finally {
            closeStreamQuietly(stream)
        }
    }

    /**
     * Reads only bytes already reported as available. This avoids waiting for an
     * end-of-stream signal that LibADB represents as a Stream closed exception.
     */
    private suspend fun readShellOutputBounded(
        manager: DormantAdbConnectionManager,
        command: String,
        timeoutMs: Long,
    ): String {
        val stream = manager.openStream("shell:$command")
        val input = stream.openInputStream()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                val available = try {
                    input.available()
                } catch (error: IOException) {
                    if (isNormalStreamClose(error)) break
                    throw error
                }
                if (available > 0) {
                    val count = try {
                        input.read(buffer, 0, minOf(buffer.size, available))
                    } catch (error: IOException) {
                        if (isNormalStreamClose(error)) break
                        throw error
                    }
                    if (count > 0) output.write(buffer, 0, count)
                } else {
                    if (stream.isClosed) break
                    delay(50L)
                }
            }
        } finally {
            closeStreamQuietly(stream)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun helperFailureMessage(diagnosticText: String): String {
        val diagnostic = diagnosticText.lowercase()
        return when {
            "apk_copy_failed" in diagnostic || "permission denied" in diagnostic || "failed to open zip" in diagnostic ->
                "Android paired successfully, but Dormant could not prepare its helper file. [HELPER-APK-01]"
            "app_process_missing" in diagnostic ->
                "Android paired successfully, but this phone's helper runtime was not available. [HELPER-RUNTIME-01]"
            "classnotfoundexception" in diagnostic || "could not find" in diagnostic && "dormantshellmain" in diagnostic ->
                "The helper file started, but Android could not find Dormant's helper class. [HELPER-CLASS-01]"
            "address already in use" in diagnostic || "bindexception" in diagnostic ->
                "An older Dormant helper is still holding the local connection. Tap Restore once more. [HELPER-PORT-01]"
            "start_sent" in diagnostic ->
                "Android started the helper command, but the helper did not answer within 10 seconds. [HELPER-TIMEOUT-01]"
            else ->
                "Android accepted pairing, but the automatic-closing helper did not answer. [HELPER-02]"
        }
    }

    private fun pairingFailureMessage(error: Throwable?): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val combined = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is ConnectException || it is SocketTimeoutException } ->
                "Android's pairing port expired before the secure connection opened. Use a fresh code. [PORT-02]"
            chain.any { it is SSLException } || "conscrypt" in combined || "tls" in combined || "exportkeyingmaterial" in combined ->
                "The secure pairing handshake failed. R4 uses a bundled TLS provider; reopen the pairing code and retry. [TLS-01]"
            "exchanging message" in combined || "pairing cipher" in combined ->
                "Android rejected the code or the code expired. Open a new pairing code and enter it once. [CODE-02]"
            "peer info" in combined ->
                "Android stopped while saving Dormant as a paired device. Open a fresh code and retry. [PAIR-02]"
            chain.any { it is IOException } ->
                "The secure pairing connection closed before Android confirmed it. Open a fresh code and retry. [PAIR-03]"
            else ->
                "Pairing stopped unexpectedly. Open a fresh code and retry. [PAIR-99]"
        }
    }

    private fun isNormalStreamClose(error: IOException): Boolean =
        error.message.orEmpty().contains("Stream closed", ignoreCase = true)

    private fun closeStreamQuietly(stream: AdbStream) {
        runCatching { stream.close() }
    }

    private fun safeDisconnect(manager: DormantAdbConnectionManager) {
        runCatching { manager.disconnect() }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "DormantWireless"
        private const val PAIRING_SOCKET_TIMEOUT_MS = 3_000
        private const val HELPER_COMMAND_TIMEOUT_MS = 6_000L
        private const val HELPER_DIAGNOSTIC_TIMEOUT_MS = 3_000L
        private const val HELPER_PING_ATTEMPTS = 20
        private const val HELPER_PING_INTERVAL_MS = 500L
        private const val HELPER_APK_FILE = "/data/local/tmp/qsd_engine.apk"
        private const val HELPER_PID_FILE = "/data/local/tmp/qsd_engine.pid"
        private const val HELPER_LAUNCH_LOG = "/data/local/tmp/qsd_engine_launcher.log"
        private const val HELPER_ENGINE_LOG = "/data/local/tmp/qsd_engine.log"
    }
}
