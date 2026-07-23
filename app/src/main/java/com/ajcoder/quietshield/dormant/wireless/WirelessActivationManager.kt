package com.ajcoder.quietshield.dormant.wireless

import android.content.Context
import android.os.Build
import com.ajcoder.quietshield.dormant.engine.DormantEngineClient
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.SecureRandom

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
                "Dormant could not find Android's current pairing screen.",
            )
        }
        if (!pairingCode.matches(Regex("\\d{6}"))) {
            return@withContext WirelessActivationResult.Failure(
                "Enter the six-digit pairing code shown by Android.",
            )
        }

        val manager = runCatching {
            DormantAdbConnectionManager.getInstance(context)
        }.getOrElse {
            return@withContext WirelessActivationResult.Failure(
                "QuietShield Dormant could not prepare its private pairing identity.",
            )
        }

        val paired = runCatching {
            manager.pair(host, port, pairingCode)
        }.getOrDefault(false)
        if (!paired) {
            safeDisconnect(manager)
            return@withContext WirelessActivationResult.Failure(
                "Pairing did not complete. Keep Android's pairing-code screen open and try again.",
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
            } catch (_: Throwable) {
                false
            }
            if (connected) return@repeat
            if (attempt < 2) delay(1_000L)
        }
        if (!connected) {
            safeDisconnect(manager)
            return WirelessActivationResult.Failure(
                "Dormant could not connect. Keep Wireless Debugging on, return to its main screen, and tap Restore again.",
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
        }.getOrDefault(false)
        safeDisconnect(manager)
        if (!commandStarted) {
            return WirelessActivationResult.Failure(
                "The phone accepted the wireless connection, but the automatic-closing helper did not start.",
            )
        }

        repeat(12) {
            delay(500L)
            if (engineClient.ping()) return WirelessActivationResult.Success
        }
        return WirelessActivationResult.Failure(
            "The automatic-closing helper did not respond. Toggle Wireless Debugging off and on, then try Restore.",
        )
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
}
