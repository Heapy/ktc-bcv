package io.heapy.ktc.plugins.bcv

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun writeIfChanged(path: Path, text: String) {
    if (!java.nio.file.Files.exists(path) || path.readText() != text) {
        path.createParentDirectories().writeText(text)
    }
}

/** A separate Toolchain project resolves reader dependencies for each compiler version. */
internal fun runAbiWorker(
    projectDir: Path,
    outputDir: Path,
    kotlinVersion: String,
    artifacts: Map<String, Path>,
    settings: BcvSettings,
): Map<String, Path> {
    require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9]+)*").matches(kotlinVersion)) {
        "Unsupported Kotlin version syntax: $kotlinVersion"
    }
    val worker = workspaceDirectory(outputDir).resolve("readers/$kotlinVersion").createDirectories()
    val outputs = artifacts.mapValues { (target, _) -> outputDir.resolve("candidates/$target.api").toAbsolutePath() }
    val request = Properties().apply {
        setProperty("targets", artifacts.keys.joinToString("\n"))
        setProperty("kotlinVersion", kotlinVersion)
        artifacts.forEach { (target, input) ->
            setProperty("input.$target", input.toAbsolutePath().toString())
            setProperty("output.$target", outputs.getValue(target).toString())
        }
        setProperty("ignoredPackages", settings.ignoredPackages.joinToString("\n"))
        setProperty("ignoredClasses", settings.ignoredClasses.joinToString("\n"))
        setProperty("nonPublicMarkers", settings.nonPublicMarkers.joinToString("\n"))
        setProperty("publicPackages", settings.publicPackages.joinToString("\n"))
        setProperty("publicClasses", settings.publicClasses.joinToString("\n"))
        setProperty("publicMarkers", settings.publicMarkers.joinToString("\n"))
    }
    val requestFile = outputDir.resolve("request.properties").toAbsolutePath()
    requestFile.outputStream().use { request.store(it, "BCV reader request") }

    val readerJar = withWorkspaceLock(worker, settings.timeoutSeconds) {
        buildAndSnapshotReader(projectDir, worker, kotlinVersion, settings.timeoutSeconds)
    }
    runReader(projectDir, readerJar, requestFile, outputDir.resolve("reader.log"), settings.timeoutSeconds)
    println("BCV reader Kotlin $kotlinVersion: ${artifacts.keys.joinToString()}")
    return outputs
}

private fun buildAndSnapshotReader(
    projectDir: Path,
    worker: Path,
    kotlinVersion: String,
    timeoutSeconds: Int,
): Path {
    writeIfChanged(worker.resolve("project.yaml"), "modules: []\n")
    writeIfChanged(worker.resolve("module.yaml"), """
        product: jvm/app
        dependencies:
          - org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1
          - org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion
          - org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion
          - org.ow2.asm:asm:9.10.1
          - org.ow2.asm:asm-tree:9.10.1
        settings:
          kotlin:
            version: $kotlinVersion
          jvm:
            release: 17
            mainClass: io.heapy.ktc.plugins.bcv.worker.MainKt
    """.trimIndent() + "\n")
    val source = requireNotNull(BcvSettings::class.java.getResourceAsStream("/worker/main.kt")) {
        "BCV worker source resource is missing"
    }.bufferedReader().use { it.readText() }
    writeIfChanged(worker.resolve("src/main.kt"), source)

    val buildDir = worker.resolve("build")
    runToolchain(projectDir, listOf(
        "task", "--project-dir", worker.toString(), "--build-dir", buildDir.toString(), ":$kotlinVersion:executableJarJvm",
    ), worker.resolve("build.log"), timeoutSeconds)

    val tasksDirectory = buildDir.resolve("tasks")
    check(Files.isDirectory(tasksDirectory)) { "BCV reader build outputs are missing: $tasksDirectory" }
    val jars = Files.walk(tasksDirectory).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path) && path.fileName.toString().endsWith("-jvm-executable.jar") &&
                path.parent.fileName.toString().contains("executableJarJvm")
        }.toList()
    }
    check(jars.size == 1) { "Expected one BCV reader executable JAR for Kotlin $kotlinVersion, found: $jars" }

    val snapshots = worker.resolve("snapshots").createDirectories()
    val temporary = Files.createTempFile(snapshots, ".reader-", ".tmp")
    try {
        Files.copy(jars.single(), temporary, REPLACE_EXISTING)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(temporary).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val snapshot = snapshots.resolve("reader-$hash.jar")
        if (!Files.exists(snapshot)) {
            try {
                Files.move(temporary, snapshot, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, snapshot)
            }
        }
        return snapshot
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun runReader(projectDir: Path, readerJar: Path, requestFile: Path, log: Path, timeoutSeconds: Int) {
    require(timeoutSeconds > 0) { "bcv.timeoutSeconds must be positive" }
    log.createParentDirectories()
    val java = Path.of(
        System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    )
    val exitCode = executeProcess(listOf(java.toString(), "-jar", readerJar.toString(), requestFile.toString()), projectDir, timeoutSeconds, log)
    check(exitCode == 0) {
        "BCV reader process failed ($log):\n" + log.readLines().takeLast(70).joinToString("\n")
    }
}
