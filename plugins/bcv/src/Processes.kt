package io.heapy.ktc.plugins.bcv

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readLines

internal fun wrapperCommand(projectDir: Path, arguments: List<String>): List<String> =
    if (System.getProperty("os.name").startsWith("Windows")) {
        listOf("cmd", "/c", projectDir.resolve("kotlin.bat").toString()) + arguments
    } else {
        listOf(projectDir.resolve("kotlin").toString()) + arguments
    }

internal fun runToolchain(projectDir: Path, arguments: List<String>, log: Path, timeoutSeconds: Int) {
    require(timeoutSeconds > 0) { "bcv.timeoutSeconds must be positive" }
    log.createParentDirectories()
    val process = ProcessBuilder(wrapperCommand(projectDir, arguments))
        .directory(projectDir.toFile())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start()
    try {
        check(process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) { "BCV subprocess timed out: $log" }
        check(process.exitValue() == 0) {
            "BCV subprocess failed ($log):\n" + log.readLines().takeLast(70).joinToString("\n")
        }
    } finally {
        if (process.isAlive) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor()
        }
    }
}
