package com.ajcoder.quietshield.dormant.wireless

import android.content.Context
import android.os.Build
import android.util.Log
import com.ajcoder.quietshield.dormant.engine.DirectAdbShellEngine
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

sealed interface WirelessActivationResult {
    data object Success : WirelessActivationResult
    data class Failure(
        val message: String,
        val pairingAccepted: Boolean = false,
    ) : WirelessActivationResult
}

/**
 * Pairs QuietShield Dormant with this phone and verifies a direct encrypted ADB
 * shell session. R5 deliberately does not launch a detached app_process helper:
 * the Dormant foreground service keeps the authenticated Wireless Debugging
 * connection and opens short shell streams only when an action is due.
 */
class WirelessActivationManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val directEngine = DirectAdbShellEngine(appContext)

    fun hasSavedPairing(): Boolean =
        DormantAdbConnectionManager.hasSavedIdentity(appContext)

    fun forgetPairing(): Boolean {
        runCatching { appContext.filesDir.resolve("engine_token").delete() }
        DirectAdbShellEngine.markDisconnected()
        return DormantAdbConnectionManager.forgetIdentity(appContext)
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
            DormantAdbConnectionManager.getInstance(appContext)
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

        connectDirectEngine(manager, pairingAccepted = true)
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
            DormantAdbConnectionManager.getInstance(appContext)
        }.getOrElse { error ->
            Log.e(TAG, "Unable to open saved wireless identity", error)
            return@withContext WirelessActivationResult.Failure(
                "The saved wireless setup could not be opened. [IDENTITY-02]",
                pairingAccepted = true,
            )
        }
        connectDirectEngine(manager, pairingAccepted = true)
    }

    private suspend fun connectDirectEngine(
        manager: DormantAdbConnectionManager,
        pairingAccepted: Boolean,
    ): WirelessActivationResult {
        var connected = false
        repeat(CONNECT_ATTEMPTS) { attempt ->
            connected = try {
                manager.autoConnect(appContext, CONNECT_TIMEOUT_MS)
            } catch (_: AdbPairingRequiredException) {
                false
            } catch (error: Throwable) {
                Log.w(TAG, "Wireless ADB connection attempt failed", error)
                false
            }
            if (connected) return@repeat
            if (attempt + 1 < CONNECT_ATTEMPTS) delay(CONNECT_RETRY_DELAY_MS)
        }
        if (!connected) {
            DirectAdbShellEngine.markDisconnected()
            safeDisconnect(manager)
            return WirelessActivationResult.Failure(
                "Pairing is saved, but Dormant could not find Android's active connection. Return to the main Wireless Debugging screen and tap Restore. [CONNECT-01]",
                pairingAccepted = pairingAccepted,
            )
        }

        val directReady = runCatching {
            directEngine.verifyConnected(manager)
        }.getOrElse { error ->
            Log.e(TAG, "Direct Wireless Debugging verification failed", error)
            false
        }
        if (!directReady) {
            DirectAdbShellEngine.markDisconnected()
            safeDisconnect(manager)
            return WirelessActivationResult.Failure(
                "Pairing is saved, but Android did not complete the automatic-closing command test. Keep Wireless Debugging on and tap Restore. [ENGINE-SHELL-01]",
                pairingAccepted = pairingAccepted,
            )
        }

        // Keep the encrypted connection alive. DormantMonitorService reuses the
        // same manager and reconnects once if Android rotates the connection.
        return WirelessActivationResult.Success
    }

    private fun pairingFailureMessage(error: Throwable?): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val combined = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is ConnectException || it is SocketTimeoutException } ->
                "Android's pairing port expired before the secure connection opened. Use a fresh code. [PORT-02]"
            chain.any { it is SSLException } ||
                "conscrypt" in combined ||
                "tls" in combined ||
                "exportkeyingmaterial" in combined ->
                "The secure pairing handshake failed. Reopen the pairing code and retry. [TLS-01]"
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

    private fun safeDisconnect(manager: DormantAdbConnectionManager) {
        runCatching { manager.disconnect() }
    }

    companion object {
        private const val TAG = "DormantWireless"
        private const val PAIRING_SOCKET_TIMEOUT_MS = 3_000
        private const val CONNECT_ATTEMPTS = 3
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val CONNECT_RETRY_DELAY_MS = 1_000L
    }
}
