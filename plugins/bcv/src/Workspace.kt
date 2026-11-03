package io.heapy.ktc.plugins.bcv

import java.nio.file.Path
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit

// KTC 0.13 task outputs are <build>/tasks/<task>. Keep nested builds outside the
// outer build lock, but share them across modules so dependencies compile once.
internal fun workspaceDirectory(outputDir: Path): Path =
    outputDir.toAbsolutePath().parent.parent.resolve("bcv-workspace")

internal fun compilationDirectory(outputDir: Path, override: String? = System.getenv("KTC_BCV_COMPILATION_DIR")): Path {
    val path = override?.let(Path::of) ?: workspaceDirectory(outputDir).resolve("compile")
    require(path.isAbsolute) { "KTC_BCV_COMPILATION_DIR must be absolute" }
    // Resolve links before '..': lexical normalization can hide an alias of the outer lock.
    val realPath = Files.createDirectories(path).toRealPath()
    require(!Files.isSameFile(realPath, outputDir.toAbsolutePath().parent.parent)) {
        "BCV compilation must not reuse the outer Toolchain build directory (nested build lock)"
    }
    return realPath
}

/** Task actions have separate class loaders in KTC 0.13; JVM monitors cannot coordinate them. */
internal fun <T> withWorkspaceLock(directory: Path, timeoutSeconds: Int, action: () -> T): T {
    require(timeoutSeconds > 0) { "bcv.timeoutSeconds must be positive" }
    Files.createDirectories(directory)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
    FileChannel.open(directory.resolve(".bcv.lock"), CREATE, WRITE).use { channel ->
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("BCV workspace lock interrupted")
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock != null) return lock.use { action() }
            check(System.nanoTime() < deadline) { "BCV workspace lock timed out: $directory" }
            try {
                Thread.sleep(50)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw exception
            }
        }
    }
}
