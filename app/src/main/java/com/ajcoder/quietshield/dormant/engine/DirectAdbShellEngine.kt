package com.ajcoder.quietshield.dormant.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ajcoder.quietshield.dormant.domain.CorePackageRules
import com.ajcoder.quietshield.dormant.wireless.DormantAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Executes Dormant operations directly through the phone's authenticated
 * Wireless Debugging connection.
 *
 * Earlier beta builds started a second app_process helper and then connected
 * to a localhost TCP port. Some Android builds accepted the helper command but
 * terminated the detached process before it could answer. Direct mode removes
 * that fragile process-launch layer: the foreground Dormant service keeps the
 * encrypted ADB session and opens a short shell stream only when an action is
 * required.
 */
class DirectAdbShellEngine(private val context: Context) {
    private val appContext = context.applicationContext

    suspend fun verifyConnected(manager: DormantAdbConnectionManager): Boolean =
        commandMutex.withLock {
            connectionHint = true
            val result = executeOnManager(
                manager = manager,
                command = "printf 'QSD_DIRECT_READY\\n'",
                timeoutMs = SHORT_TIMEOUT_MS,
                maxBytes = SMALL_OUTPUT_LIMIT,
            )
            val ready = result.exitCode == 0 && "QSD_DIRECT_READY" in result.output
            if (!ready) connectionHint = false
            ready
        }

    suspend fun ping(): Boolean = commandMutex.withLock {
        val result = executeWithRecoveryLocked(
            command = "printf 'QSD_DIRECT_READY\\n'",
            timeoutMs = SHORT_TIMEOUT_MS,
            maxBytes = SMALL_OUTPUT_LIMIT,
        )
        result?.exitCode == 0 && "QSD_DIRECT_READY" in result.output
    }

    suspend fun forceStop(packageName: String): Boolean =
        executePackageAction(packageName, "am force-stop $packageName")

    suspend fun placeInStandby(packageName: String): Boolean = executePackageAction(
        packageName,
        "am set-standby-bucket $packageName rare >/dev/null 2>&1 && " +
            "am set-inactive $packageName true >/dev/null 2>&1",
    )

    suspend fun markActive(packageName: String): Boolean = executePackageAction(
        packageName,
        "am set-inactive $packageName false >/dev/null 2>&1 && " +
            "am set-standby-bucket $packageName active >/dev/null 2>&1",
    )

    suspend fun disableApp(packageName: String): Boolean =
        executePackageAction(packageName, "pm disable-user --user 0 $packageName >/dev/null 2>&1")

    suspend fun enableApp(packageName: String): Boolean =
        executePackageAction(packageName, "pm enable $packageName >/dev/null 2>&1")

    suspend fun runtimeSnapshot(): EngineRuntimeSnapshot? = commandMutex.withLock {
        val command = buildString {
            append("printf '$RUNNING_MARKER\n'; ")
            append("ps -A -o NAME 2>/dev/null || ps -A 2>/dev/null; ")
            append("printf '$SERVICES_MARKER\n'; ")
            append("dumpsys activity services 2>/dev/null; ")
            append("printf '$MEDIA_MARKER\n'; ")
            append("dumpsys media_session 2>/dev/null; ")
            append("printf '$DISABLED_MARKER\n'; ")
            append("pm list packages -d 2>/dev/null; ")
            append("printf '$SNAPSHOT_END_MARKER\n'")
        }
        val result = executeWithRecoveryLocked(
            command = command,
            timeoutMs = RUNTIME_TIMEOUT_MS,
            maxBytes = SNAPSHOT_OUTPUT_LIMIT,
        ) ?: return@withLock null
        if (result.exitCode != 0 || SNAPSHOT_END_MARKER !in result.output) {
            return@withLock null
        }

        val runningOutput = sectionBetween(result.output, RUNNING_MARKER, SERVICES_MARKER)
        val servicesOutput = sectionBetween(result.output, SERVICES_MARKER, MEDIA_MARKER)
        val mediaOutput = sectionBetween(result.output, MEDIA_MARKER, DISABLED_MARKER)
        val disabledOutput = sectionBetween(result.output, DISABLED_MARKER, SNAPSHOT_END_MARKER)

        EngineRuntimeSnapshot(
            runningPackages = parseRunningPackages(runningOutput),
            activeServicePackages = parseComponentPackages(servicesOutput),
            mediaPackages = parseComponentPackages(mediaOutput),
            disabledPackages = disabledOutput.lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter(packagePattern::matches)
                .toSet(),
        )
    }

    private suspend fun executePackageAction(packageName: String, command: String): Boolean {
        if (!isEligiblePackage(packageName)) return false
        return commandMutex.withLock {
            executeWithRecoveryLocked(
                command = command,
                timeoutMs = ACTION_TIMEOUT_MS,
                maxBytes = SMALL_OUTPUT_LIMIT,
            )?.exitCode == 0
        }
    }

    private fun isEligiblePackage(packageName: String): Boolean {
        if (!packagePattern.matches(packageName)) return false
        if (packageName.startsWith("com.ajcoder.quietshield.dormant")) return false
        return !CorePackageRules.isKnownCore(packageName)
    }

    private suspend fun executeWithRecoveryLocked(
        command: String,
        timeoutMs: Long,
        maxBytes: Int,
    ): ShellResult? {
        if (!DormantAdbConnectionManager.hasSavedIdentity(appContext)) return null
        val manager = runCatching { DormantAdbConnectionManager.getInstance(appContext) }
            .getOrElse { error ->
                Log.e(TAG, "Unable to open the saved wireless identity", error)
                connectionHint = false
                return null
            }

        if (!connectionHint && !connectLocked(manager)) return null

        val firstAttempt = runCatching {
            executeOnManager(manager, command, timeoutMs, maxBytes)
        }
        if (firstAttempt.isSuccess) return firstAttempt.getOrNull()

        Log.w(TAG, "Wireless shell stream closed; reconnecting once", firstAttempt.exceptionOrNull())
        connectionHint = false
        runCatching { manager.disconnect() }
        if (!connectLocked(manager)) return null

        return runCatching {
            executeOnManager(manager, command, timeoutMs, maxBytes)
        }.onFailure { error ->
            connectionHint = false
            Log.e(TAG, "Wireless shell command failed after reconnect", error)
        }.getOrNull()
    }

    private suspend fun connectLocked(manager: DormantAdbConnectionManager): Boolean {
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val connected = try {
                manager.autoConnect(appContext, CONNECT_TIMEOUT_MS)
            } catch (_: AdbPairingRequiredException) {
                false
            } catch (error: Throwable) {
                Log.w(TAG, "Wireless Debugging connection attempt failed", error)
                false
            }
            if (connected) {
                connectionHint = true
                return true
            }
            if (attempt + 1 < CONNECT_ATTEMPTS) delay(CONNECT_RETRY_DELAY_MS)
        }
        connectionHint = false
        return false
    }

    private suspend fun executeOnManager(
        manager: DormantAdbConnectionManager,
        command: String,
        timeoutMs: Long,
        maxBytes: Int,
    ): ShellResult {
        val wrappedCommand =
            "{ $command; }; qsd_rc=\$?; printf '\\n$RESULT_MARKER:%s\\n' \"\$qsd_rc\""
        val stream = manager.openStream("shell:$wrappedCommand")
        val input = stream.openInputStream()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var markerFound = false
        var markerWindow = ""

        try {
            while (SystemClock.elapsedRealtime() < deadline && output.size() < maxBytes) {
                val available = try {
                    input.available()
                } catch (error: IOException) {
                    if (isNormalStreamClose(error)) break
                    throw error
                }

                if (available > 0) {
                    val allowed = minOf(buffer.size, available, maxBytes - output.size())
                    val count = try {
                        input.read(buffer, 0, allowed)
                    } catch (error: IOException) {
                        if (isNormalStreamClose(error)) break
                        throw error
                    }
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        val chunk = String(buffer, 0, count, Charsets.ISO_8859_1)
                        markerWindow = (markerWindow + chunk)
                            .takeLast(RESULT_MARKER.length + 32)
                        if (RESULT_MARKER in markerWindow) {
                            markerFound = true
                            break
                        }
                    }
                } else {
                    if (stream.isClosed) break
                    delay(READ_POLL_DELAY_MS)
                }
            }
        } finally {
            closeStreamQuietly(stream)
        }

        val text = output.toString(Charsets.UTF_8.name())
        val markerIndex = text.lastIndexOf(RESULT_MARKER)
        if (!markerFound && markerIndex < 0) {
            throw IOException("Wireless shell result marker was not received.")
        }
        val resultLine = text.substring(markerIndex)
            .lineSequence()
            .firstOrNull()
            .orEmpty()
        val exitCode = resultLine.substringAfter(':', "").trim().toIntOrNull()
        return ShellResult(
            output = text.substring(0, markerIndex).trimEnd(),
            exitCode = exitCode,
        )
    }

    private fun sectionBetween(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        if (start < 0) return ""
        val contentStart = start + startMarker.length
        val end = text.indexOf(endMarker, contentStart)
        if (end < 0) return ""
        return text.substring(contentStart, end).trim()
    }

    private fun parseRunningPackages(output: String): Set<String> = output.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.equals("NAME", ignoreCase = true) }
        .map { line -> line.substringAfterLast(' ').substringBefore(':') }
        .filter(packagePattern::matches)
        .toSet()

    private fun parseComponentPackages(output: String): Set<String> = buildSet {
        output.lineSequence().forEach { line ->
            componentPattern.findAll(line).forEach { match -> add(match.groupValues[1]) }
            packageFieldPattern.findAll(line).forEach { match -> add(match.groupValues[1]) }
        }
    }

    private fun isNormalStreamClose(error: IOException): Boolean =
        error.message.orEmpty().contains("Stream closed", ignoreCase = true)

    private fun closeStreamQuietly(stream: AdbStream) {
        runCatching { stream.close() }
    }

    private data class ShellResult(
        val output: String,
        val exitCode: Int?,
    )

    companion object {
        private const val TAG = "DormantDirectAdb"
        private const val RESULT_MARKER = "__QSD_RESULT__"
        private const val RUNNING_MARKER = "__QSD_RUNNING__"
        private const val SERVICES_MARKER = "__QSD_SERVICES__"
        private const val MEDIA_MARKER = "__QSD_MEDIA__"
        private const val DISABLED_MARKER = "__QSD_DISABLED__"
        private const val SNAPSHOT_END_MARKER = "__QSD_SNAPSHOT_END__"
        private const val CONNECT_ATTEMPTS = 2
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val CONNECT_RETRY_DELAY_MS = 750L
        private const val READ_POLL_DELAY_MS = 40L
        private const val SHORT_TIMEOUT_MS = 5_000L
        private const val ACTION_TIMEOUT_MS = 12_000L
        private const val RUNTIME_TIMEOUT_MS = 25_000L
        private const val SMALL_OUTPUT_LIMIT = 32 * 1_024
        private const val SNAPSHOT_OUTPUT_LIMIT = 4 * 1_024 * 1_024

        private val commandMutex = Mutex()

        @Volatile
        private var connectionHint = false

        fun markDisconnected() {
            connectionHint = false
        }

        private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val componentPattern =
            Regex("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+")
        private val packageFieldPattern =
            Regex("(?:package|packageName)=([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)")
    }
}
