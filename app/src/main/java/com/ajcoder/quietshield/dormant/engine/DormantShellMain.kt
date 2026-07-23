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
    private val componentPattern = Regex("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+")
    private val packageFieldPattern = Regex("(?:package|packageName)=([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)")

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
                val shouldContinue = runCatching { server.accept().use(::handleClient) }
                    .getOrDefault(true)
                if (!shouldContinue) break
            }
        }
    }

    private fun handleClient(socket: Socket): Boolean {
        socket.soTimeout = 20_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        val expectedToken = System.getProperty("qsd.token")
        val suppliedToken = reader.readLine().orEmpty()
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
            "VERSION" -> writer.println("OK 2")
            "RUNNING" -> writeSimpleSet(writer, runningPackages())
            "RUNTIME" -> writeRuntimeSnapshot(writer)
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
            "DISABLE" -> writeCommandResult(writer, packageName) {
                runShell("pm disable-user --user 0 $packageName")
            }
            "ENABLE" -> writeCommandResult(writer, packageName) {
                runShell("pm enable $packageName")
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

    private fun writeSimpleSet(writer: PrintWriter, packages: Set<String>) {
        writer.println("BEGIN")
        packages.sorted().forEach(writer::println)
        writer.println("END")
    }

    private fun writeRuntimeSnapshot(writer: PrintWriter) {
        writer.println("BEGIN")
        runningPackages().sorted().forEach { writer.println("RUNNING\t$it") }
        activeServicePackages().sorted().forEach { writer.println("SERVICE\t$it") }
        activeMediaPackages().sorted().forEach { writer.println("MEDIA\t$it") }
        disabledPackages().sorted().forEach { writer.println("DISABLED\t$it") }
        writer.println("END")
    }

    private fun runningPackages(): Set<String> {
        val output = runShellForOutput("ps -A -o NAME")
        return output.lineSequence()
            .map(String::trim)
            .filter { it.contains('.') }
            .map { it.substringBefore(':') }
            .filter(packagePattern::matches)
            .toSet()
    }

    private fun activeServicePackages(): Set<String> {
        val output = runShellForOutput("dumpsys activity services")
        return buildSet {
            output.lineSequence().forEach { line ->
                componentPattern.findAll(line).forEach { match -> add(match.groupValues[1]) }
                packageFieldPattern.findAll(line).forEach { match -> add(match.groupValues[1]) }
            }
        }
    }

    private fun activeMediaPackages(): Set<String> {
        val output = runShellForOutput("dumpsys media_session")
        return buildSet {
            output.lineSequence().forEach { line ->
                packageFieldPattern.findAll(line).forEach { match -> add(match.groupValues[1]) }
                componentPattern.findAll(line).forEach { match -> add(match.groupValues[1]) }
            }
        }
    }

    private fun disabledPackages(): Set<String> {
        return runShellForOutput("pm list packages -d")
            .lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter(packagePattern::matches)
            .toSet()
    }

    private fun runShell(command: String): Boolean {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
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
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ""
            } else output
        }.getOrDefault("")
    }

    private fun List<String>.valueAfter(name: String): String? {
        val index = indexOf(name)
        return if (index >= 0 && index + 1 < size) this[index + 1] else null
    }
}
