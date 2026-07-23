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

class DormantEngineClient(private val context: Context) {
    companion object {
        const val PORT = 47531
        private const val TOKEN_FILE = "engine_token"
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        singleResponse("PING").startsWith("OK")
    }

    suspend fun forceStop(packageName: String): Boolean = withContext(Dispatchers.IO) {
        singleResponse("FORCE_STOP $packageName").startsWith("OK")
    }

    suspend fun placeInStandby(packageName: String): Boolean = withContext(Dispatchers.IO) {
        singleResponse("STANDBY $packageName").startsWith("OK")
    }

    suspend fun markActive(packageName: String): Boolean = withContext(Dispatchers.IO) {
        singleResponse("WAKE $packageName").startsWith("OK")
    }

    suspend fun runningPackages(): Set<String> = withContext(Dispatchers.IO) {
        val token = readToken() ?: return@withContext emptySet()
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 1_500)
                socket.soTimeout = 5_000
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.println(token)
                writer.println("RUNNING")
                if (reader.readLine() != "BEGIN") return@use emptySet<String>()
                buildSet {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line == "END") break
                        if (line.isNotBlank()) add(line)
                    }
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun singleResponse(command: String): String {
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
