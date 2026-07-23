package com.ajcoder.quietshield.dormant.engine

import com.ajcoder.quietshield.dormant.domain.CorePackageRules
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

object DormantShellMain {
    private const val DEFAULT_PORT = 47531
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    @JvmStatic
    fun main(args: Array<String>) {
        val arguments = args.toList()
        val port = arguments.valueAfter("--port")?.toIntOrNull() ?: DEFAULT_PORT
        val token = arguments.valueAfter("--token").orEmpty()
        if (token.length < 24) {
            System.err.println("QuietShield Dormant setup key is missing.")
            return
        }
        System.setProperty("qsd.token", token)

        ServerSocket(port, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            server.reuseAddress = true
            println("QSD_ENGINE_READY:$port")
            System.out.flush()
            while (true) {
                val shouldContinue = runCatching {
                    server.accept().use(::handleClient)
                }.getOrDefault(true)
                if (!shouldContinue) break
            }
        }
    }

    private fun handleClient(socket: Socket): Boolean {
        socket.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        val expectedToken = System.getProperty("qsd.token")
        val suppliedToken = reader.readLine().orEmpty()

        // The launcher passes the token in the first client line and in the process arguments.
        // The process argument is copied to this property before accepting commands.
        if (expectedToken != null && suppliedToken != expectedToken) {
            writer.println("ERROR Not allowed")
            return true
        }

        val commandLine = reader.readLine().orEmpty().trim()
        if (commandLine.isBlank()) {
            writer.println("ERROR Empty request")
            return true
        }

        val command = commandLine.substringBefore(' ').uppercase()
        val packageName = commandLine.substringAfter(' ', "").trim()
        when (command) {
            "PING" -> writer.println("OK PONG")
            "VERSION" -> writer.println("OK 1")
            "RUNNING" -> writeRunningPackages(writer)
            "STANDBY" -> writeCommandResult(writer, packageName) {
                runShell("am set-standby-bucket $packageName rare") &&
                    runShell("am set-inactive $packageName true")
            }
            "WAKE" -> writeCommandResult(writer, packageName) {
                runShell("am set-inactive $packageName false") &&
                    runShell("am set-standby-bucket $packageName active")
            }
            "FORCE_STOP" -> writeCommandResult(writer, packageName) {
                runShell("am force-stop $packageName")
            }
            "EXIT" -> {
                writer.println("OK BYE")
                return false
            }
            else -> writer.println("ERROR Unknown request")
        }
        return true
    }

    private fun writeCommandResult(
        writer: PrintWriter,
        packageName: String,
        operation: () -> Boolean,
    ) {
        if (!isEligiblePackage(packageName)) {
            writer.println("ERROR Protected app")
            return
        }
        writer.println(if (operation()) "OK" else "ERROR Action failed")
    }

    private fun isEligiblePackage(packageName: String): Boolean {
        if (!packagePattern.matches(packageName)) return false
        if (packageName.startsWith("com.ajcoder.quietshield.dormant")) return false
        return !CorePackageRules.isKnownCore(packageName)
    }

    private fun writeRunningPackages(writer: PrintWriter) {
        val output = runShellForOutput("ps -A -o NAME")
        val packages = output.lineSequence()
            .map(String::trim)
            .filter { it.contains('.') }
            .map { it.substringBefore(':') }
            .filter(packagePattern::matches)
            .distinct()
            .sorted()
            .toList()

        writer.println("BEGIN")
        packages.forEach(writer::println)
        writer.println("END")
    }

    private fun runShell(command: String): Boolean {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

    private fun runShellForOutput(command: String): String {
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ""
            } else {
                output
            }
        }.getOrDefault("")
    }

    private fun List<String>.valueAfter(name: String): String? {
        val index = indexOf(name)
        return if (index >= 0 && index + 1 < size) this[index + 1] else null
    }

}
