package com.ajcoder.quietshield.dormant.wireless

import android.content.Context
import android.os.Build
import android.util.Log
import com.ajcoder.quietshield.dormant.engine.DormantEngineClient
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
        val apkPath = context.applicationInfo.sourceDir
        val className = "com.ajcoder.quietshield.dormant.engine.DormantShellMain"
        val startCommand = buildString {
            append("pkill -f ")
            append(shellQuote(className))
            append(" >/dev/null 2>&1 || true; ")
            append("rm -f /data/local/tmp/qsd_engine.log; ")
            append("nohup env CLASSPATH=")
            append(shellQuote(apkPath))
            append(" app_process /system/bin ")
            append(className)
            append(" --port 47531 --token ")
            append(shellQuote(token))
            append(" >/data/local/tmp/qsd_engine.log 2>&1 </dev/null &")
        }

        val commandStarted = runCatching {
            executeShell(manager, startCommand)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to start Dormant shell helper", error)
            false
        }
        safeDisconnect(manager)
        if (!commandStarted) {
            return WirelessActivationResult.Failure(
                "Android accepted pairing, but the automatic-closing helper did not start. [HELPER-01]",
            )
        }

        repeat(12) {
            delay(500L)
            if (engineClient.ping()) return WirelessActivationResult.Success
        }
        return WirelessActivationResult.Failure(
            "The automatic-closing helper did not respond. Toggle Wireless Debugging off and on, then tap Restore. [HELPER-02]",
        )
    }

    private fun pairingFailureMessage(error: Throwable?): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val combined = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is ConnectException || it is SocketTimeoutException } ->
                "Android's pairing port expired before the secure connection opened. Use a fresh code. [PORT-02]"
            chain.any { it is SSLException } || "conscrypt" in combined || "tls" in combined || "exportkeyingmaterial" in combined ->
                "The secure pairing handshake failed. R3 uses a bundled TLS provider; reopen the pairing code and retry. [TLS-01]"
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

    private fun executeShell(manager: DormantAdbConnectionManager, command: String): String {
        val stream = manager.openStream("shell:$command")
        return stream.use {
            it.openInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
        }
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
    }
}
