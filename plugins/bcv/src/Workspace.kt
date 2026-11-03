package io.heapy.ktc.plugins.bcv

import java.nio.file.Path
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.TimeUnit

// KTC 0.13 task outputs are <build>/tasks/<task>. Keep nested builds outside the
// outer build lock, but share them across modules so dependencies compile once.
internal fun workspaceDirectory(outputDir: Path): Path {
    val tasksDirectory = requireNotNull(outputDir.toAbsolutePath().parent) {
        "Unexpected KTC 0.13 task output path: $outputDir"
    }
    check(tasksDirectory.fileName.toString() == "tasks") {
        "Unexpected KTC 0.13 task output layout: expected <build>/tasks/<task>, got $outputDir"
    }
    return requireNotNull(tasksDirectory.parent) { "Unexpected KTC 0.13 task output path: $outputDir" }
        .resolve("bcv-workspace")
}

internal data class BcvEnvironment(
    val targets: List<String>?,
    val compilationDirectory: String?,
) {
    companion object {
        fun from(environment: Map<String, String> = System.getenv()): BcvEnvironment {
            val targets = environment["KTC_BCV_TARGETS"]
                ?.takeUnless { it.isBlank() }
                ?.split(Regex("[,\\s]+"))
                ?.filter { it.isNotBlank() }
            val compilationDirectory = environment["KTC_BCV_COMPILATION_DIR"]
                ?.takeUnless { it.isBlank() }
            return BcvEnvironment(targets, compilationDirectory)
        }
    }

    fun log() {
        println(
            "BCV environment: KTC_BCV_TARGETS=${targets?.joinToString(",") ?: "<unset>"}, " +
                "KTC_BCV_COMPILATION_DIR=${compilationDirectory ?: "<unset>"}",
        )
    }
}

internal fun compilationDirectory(outputDir: Path, override: String?): Path {
    val path = override?.let(Path::of) ?: workspaceDirectory(outputDir).resolve("compile")
    require(path.isAbsolute) { "KTC_BCV_COMPILATION_DIR must be absolute" }
    // Resolve existing links before '..' without creating any part of an invalid path.
    val resolvedPath = resolveExistingPath(path)
    val outerBuild = requireNotNull(outputDir.toAbsolutePath().parent?.parent)
        .toRealPath()
    require(resolvedPath != outerBuild && (!Files.exists(resolvedPath) || !Files.isSameFile(resolvedPath, outerBuild))) {
        "BCV compilation must not reuse the outer Toolchain build directory (nested build lock)"
    }
    Files.createDirectories(resolvedPath)
    return resolvedPath.toRealPath()
}

private fun resolveExistingPath(path: Path): Path {
    var resolved = requireNotNull(path.root) { "KTC_BCV_COMPILATION_DIR must be absolute" }
    var missingSegment = false
    for (segment in path) {
        when (segment.toString()) {
            "." -> Unit
            ".." -> {
                require(!missingSegment) {
                    "KTC_BCV_COMPILATION_DIR must not traverse '..' after a missing path segment"
                }
                resolved = resolved.parent ?: resolved
            }
            else -> {
                val candidate = resolved.resolve(segment)
                if (!missingSegment && Files.exists(candidate)) {
                    resolved = candidate.toRealPath()
                } else {
                    missingSegment = true
                    resolved = candidate
                }
            }
        }
    }
    return resolved
}

/** Task actions have separate class loaders in KTC 0.13; JVM monitors cannot coordinate them. */
internal fun <T> withWorkspaceLock(directory: Path, timeoutSeconds: Int, action: () -> T): T {
    require(timeoutSeconds > 0) { "bcv.timeoutSeconds must be positive" }
    Files.createDirectories(directory)
    val ownerFile = directory.resolve(".bcv.lock.owner")
    var observedOwner: String? = null
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
    FileChannel.open(directory.resolve(".bcv.lock"), CREATE, WRITE).use { channel ->
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("BCV workspace lock interrupted")
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock != null) return lock.use {
                writeOwnerToken(directory, ownerFile)
                action()
            }
            val owner = if (Files.exists(ownerFile)) Files.readString(ownerFile) else null
            if (owner != observedOwner) {
                observedOwner = owner
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
            }
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

private fun writeOwnerToken(directory: Path, ownerFile: Path) {
    val temporary = Files.createTempFile(directory, ".bcv.lock.owner-", ".tmp")
    try {
        Files.writeString(temporary, UUID.randomUUID().toString())
        try {
            Files.move(temporary, ownerFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, ownerFile, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
