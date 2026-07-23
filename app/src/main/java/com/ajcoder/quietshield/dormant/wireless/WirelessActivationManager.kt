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

    suspend fun pairAndStart(pairingAddress: String, pairingCode: String): WirelessActivationResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@withContext WirelessActivationResult.Failure(
                    "Wireless setup requires Android 11 or newer.",
                )
            }
            val endpoint = parsePairingAddress(pairingAddress)
                ?: return@withContext WirelessActivationResult.Failure(
                    "Enter the address and port shown by Android, such as 192.168.1.20:37123.",
                )
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
                manager.pair(endpoint.first, endpoint.second, pairingCode)
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
        val connected = try {
            manager.autoConnect(context, 12_000)
        } catch (_: AdbPairingRequiredException) {
            false
        } catch (_: Throwable) {
            false
        }
        if (!connected) {
            safeDisconnect(manager)
            return WirelessActivationResult.Failure(
                "QuietShield Dormant could not connect. Keep Wireless Debugging on and open its main screen, then try Restore.",
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

        repeat(8) {
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


    private fun parsePairingAddress(value: String): Pair<String, Int>? {
        val trimmed = value.trim()
        val separator = trimmed.lastIndexOf(':')
        if (separator <= 0 || separator == trimmed.lastIndex) return null
        val host = trimmed.substring(0, separator)
            .removePrefix("[")
            .removeSuffix("]")
            .trim()
        val port = trimmed.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
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
