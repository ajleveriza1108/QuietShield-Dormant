package com.ajcoder.quietshield.dormant.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

data class EngineRuntimeSnapshot(
    val runningPackages: Set<String> = emptySet(),
    val activeServicePackages: Set<String> = emptySet(),
    val mediaPackages: Set<String> = emptySet(),
    val disabledPackages: Set<String> = emptySet(),
)

/**
 * Main automatic-closing command client.
 *
 * Wireless setup now uses DirectAdbShellEngine and does not need a detached
 * app_process helper. The authenticated localhost helper protocol is retained
 * only as a compatibility fallback for the optional USB activation script.
 */
class DormantEngineClient(private val context: Context) {
    private val directEngine = DirectAdbShellEngine(context.applicationContext)

    companion object {
        const val PORT = 47531
        private const val TOKEN_FILE = "engine_token"
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        directEngine.ping() || localSingleResponse("PING").startsWith("OK")
    }

    suspend fun forceStop(packageName: String): Boolean = withContext(Dispatchers.IO) {
        directEngine.forceStop(packageName) ||
            localSingleResponse("FORCE_STOP $packageName").startsWith("OK")
    }

    suspend fun placeInStandby(packageName: String): Boolean = withContext(Dispatchers.IO) {
        directEngine.placeInStandby(packageName) ||
            localSingleResponse("STANDBY $packageName").startsWith("OK")
    }

    suspend fun markActive(packageName: String): Boolean = withContext(Dispatchers.IO) {
        directEngine.markActive(packageName) ||
            localSingleResponse("WAKE $packageName").startsWith("OK")
    }

    suspend fun disableApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        directEngine.disableApp(packageName) ||
            localSingleResponse("DISABLE $packageName").startsWith("OK")
    }

    suspend fun enableApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        directEngine.enableApp(packageName) ||
            localSingleResponse("ENABLE $packageName").startsWith("OK")
    }

    suspend fun runtimeSnapshot(): EngineRuntimeSnapshot = withContext(Dispatchers.IO) {
        directEngine.runtimeSnapshot() ?: localRuntimeSnapshot()
    }

    suspend fun runningPackages(): Set<String> = runtimeSnapshot().runningPackages

    private fun localRuntimeSnapshot(): EngineRuntimeSnapshot {
        val lines = localMultiResponse("RUNTIME")
        if (lines.isEmpty()) return EngineRuntimeSnapshot()
        val running = mutableSetOf<String>()
        val services = mutableSetOf<String>()
        val media = mutableSetOf<String>()
        val disabled = mutableSetOf<String>()
        lines.forEach { line ->
            val kind = line.substringBefore('\t')
            val packageName = line.substringAfter('\t', "").trim()
            if (packageName.isBlank()) return@forEach
            when (kind) {
                "RUNNING" -> running += packageName
                "SERVICE" -> services += packageName
                "MEDIA" -> media += packageName
                "DISABLED" -> disabled += packageName
            }
        }
        return EngineRuntimeSnapshot(running, services, media, disabled)
    }

    private fun localMultiResponse(command: String): List<String> {
        val token = readToken() ?: return emptyList()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 1_500)
                socket.soTimeout = 15_000
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.println(token)
                writer.println(command)
                if (reader.readLine() != "BEGIN") return@use emptyList<String>()
                buildList {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line == "END") break
                        if (line.isNotBlank()) add(line)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun localSingleResponse(command: String): String {
        val token = readToken() ?: return "ERROR Setup needed"
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 1_500)
                socket.soTimeout = 5_000
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.println(token)
                writer.println(command)
                reader.readLine().orEmpty()
            }
        }.getOrDefault("ERROR Not available")
    }

    private fun readToken(): String? {
        val file = context.filesDir.resolve(TOKEN_FILE)
        return runCatching { file.readText().trim() }
            .getOrNull()
            ?.takeIf { it.length >= 24 }
    }
}
