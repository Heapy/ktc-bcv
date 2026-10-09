@file:OptIn(kotlinx.validation.ExperimentalBCVApi::class)

package io.heapy.ktc.plugins.bcv

import kotlinx.validation.api.klib.KlibDump
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal fun compilationTask(target: String): String = when (target) {
    "jvm" -> "jarJvm"
    "js", "wasmJs", "wasmWasi" -> "compile" + target.replaceFirstChar { it.uppercaseChar() }
    else -> "compile" + target.replaceFirstChar { it.uppercaseChar() } + "Debug"
}

internal fun artifactPath(buildDir: Path, moduleName: String, target: String): Path =
    if (target == "jvm") buildDir.resolve("tasks/_${moduleName}_jarJvm/$moduleName-jvm.jar")
    else buildDir.resolve("tasks/_${moduleName}_${compilationTask(target)}/$moduleName.klib")

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
public fun buildAbi(
    moduleName: String,
    @Input planFile: Path,
    settings: BcvSettings,
    jvm: Boolean,
    @Output outputDir: Path,
) {
    val plan = readPlan(planFile)
    val projectDir = Path.of(plan.getProperty("projectDir"))
    val targets = plan.targets().filter { (it == "jvm") == jvm }
    outputDir.createDirectories()
    val candidate = outputDir.resolve(if (jvm) "current.api" else "current.klib.api")
    if (targets.isEmpty()) {
        candidate.writeText("")
        outputDir.resolve("selected-targets.txt").writeText("")
        println("BCV ${if (jvm) "JVM" else "KLib"} skipped: no selected targets")
        return
    }
    val buildDir = outputDir.resolve("compile")
    runToolchain(projectDir, listOf("task", "--build-dir", buildDir.toString()) +
        targets.map { ":$moduleName:${compilationTask(it)}" }, outputDir.resolve("compile.log"), settings.timeoutSeconds)
    val artifacts = targets.associateWith { target ->
        artifactPath(buildDir, moduleName, target).also {
            check(it.isRegularFile()) { "KTC 0.13 output missing for $target: $it" }
        }
    }
    val outputs = runAbiWorker(projectDir, outputDir, plan.getProperty("kotlinVersion"), artifacts, settings)
    if (jvm) outputs.getValue("jvm").copyTo(candidate, overwrite = true)
    else {
        val merged = KlibDump()
        outputs.values.forEach { merged.merge(KlibDump.from(it.toFile())) }
        candidate.bufferedWriter().use { merged.saveTo(it) }
    }
    outputDir.resolve("selected-targets.txt").writeText(targets.joinToString("\n"))
}

@TaskAction
public fun checkSelectedJvmApi(moduleName: String, @Input buildDir: Path, @Input(inferTaskDependency = false) baselineFile: Path) {
    if (buildDir.resolve("selected-targets.txt").readText().isEmpty()) return
    checkApi(moduleName, buildDir.resolve("current.api"), baselineFile)
}

@TaskAction
public fun dumpSelectedJvmApi(@Input buildDir: Path, @Output baselineFile: Path) {
    if (buildDir.resolve("selected-targets.txt").readText().isEmpty()) return
    dumpApi(buildDir.resolve("current.api"), baselineFile)
}

@TaskAction
public fun checkSelectedKlibApi(@Input buildDir: Path, @Input(inferTaskDependency = false) baselineFile: Path) {
    if (buildDir.resolve("selected-targets.txt").readText().isEmpty()) return
    checkKlibApi(buildDir.resolve("current.klib.api"), baselineFile)
}

@TaskAction
public fun dumpSelectedKlibApi(@Input buildDir: Path, @Output baselineFile: Path) {
    if (buildDir.resolve("selected-targets.txt").readText().isEmpty()) return
    updateKlibApi(buildDir.resolve("current.klib.api"), baselineFile)
}
