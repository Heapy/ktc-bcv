package io.heapy.ktc.plugins.bcv

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream
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
    val worker = outputDir.resolve("readers/$kotlinVersion").createDirectories()
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
    val requestFile = worker.resolve("request.properties")
    requestFile.outputStream().use { request.store(it, "BCV reader request") }
    runToolchain(projectDir, listOf(
        "run", "--project-dir", worker.toString(), "--build-dir", worker.resolve("build").toString(),
        "--", requestFile.toString(),
    ), worker.resolve("reader.log"), settings.timeoutSeconds)
    println("BCV reader Kotlin $kotlinVersion: ${artifacts.keys.joinToString()}")
    return outputs
}
