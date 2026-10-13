package io.heapy.ktc.plugins.bcv

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readText

internal val supportedTargets = setOf(
    "jvm", "js", "wasmJs", "wasmWasi", "macosX64", "macosArm64", "iosSimulatorArm64", "iosX64", "iosArm64",
    "linuxX64", "linuxArm64", "watchosSimulatorArm64", "watchosArm32", "watchosArm64",
    "tvosSimulatorArm64", "tvosX64", "tvosArm64", "mingwX64",
    "androidNativeArm32", "androidNativeArm64", "androidNativeX86", "androidNativeX64",
)

/** KTC 0.13 renders resolved fragment platform sets, including template propagation. */
internal fun declaredTargets(effectiveSettings: String): List<String> {
    val headers = Regex("^settings@([A-Za-z0-9+]+):\\s*$", RegexOption.MULTILINE)
        .findAll(effectiveSettings).map { it.groupValues[1] }.toList()
    check(headers.isNotEmpty()) { "Cannot discover targets from KTC 0.13 show settings output" }
    return headers.flatMap { it.split('+') }.distinct().sorted()
}

internal fun hostTargets(targets: List<String>, os: String): List<String> = targets.filter { target ->
    target in setOf("jvm", "js", "wasmJs", "wasmWasi") || target.startsWith("androidNative") || when {
        os.startsWith("Mac", ignoreCase = true) -> listOf("macos", "ios", "tvos", "watchos").any { target.startsWith(it) }
        os.startsWith("Windows", ignoreCase = true) -> target.startsWith("mingw")
        os.startsWith("Linux", ignoreCase = true) -> target.startsWith("linux")
        else -> false
    }
}

internal fun selectedTargets(declared: List<String>, settings: BcvSettings, os: String): List<String> {
    require(settings.excludedTargets.distinct().size == settings.excludedTargets.size) { "Duplicate bcv.excludedTargets" }
    require((supportedTargets + declared).containsAll(settings.excludedTargets)) {
        "Unknown bcv.excludedTargets: ${settings.excludedTargets - supportedTargets - declared.toSet()}"
    }
    val enabled = declared - settings.excludedTargets.toSet()
    require(supportedTargets.containsAll(enabled)) {
        "BCV does not support these targets yet: ${enabled - supportedTargets}. Exclude them explicitly if intentional."
    }
    return if (settings.includeCrossTargets) enabled else hostTargets(enabled, os)
}

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
public fun planAbi(
    @Input(inferTaskDependency = false) projectDir: Path,
    moduleName: String,
    kotlinVersion: String,
    settings: BcvSettings,
    @Output outputDir: Path,
) {
    outputDir.createDirectories()
    val model = outputDir.resolve("settings.log")
    runToolchain(projectDir, listOf("show", "settings", "--build-dir", outputDir.resolve("model").toString(), "-m", moduleName), model, settings.timeoutSeconds)
    val declared = declaredTargets(model.readText())
    val selected = selectedTargets(declared, settings, System.getProperty("os.name"))
    val unavailable = declared - settings.excludedTargets.toSet() - selected.toSet()
    if (settings.excludedTargets.isNotEmpty()) println("BCV excluded: ${settings.excludedTargets.joinToString()}")
    if (unavailable.isNotEmpty()) println("BCV skipped on this host: ${unavailable.joinToString()}; validate on the corresponding CI host")
    println("BCV selected: ${selected.joinToString().ifEmpty { "none" }}; Kotlin $kotlinVersion")
    Properties().apply {
        setProperty("projectDir", projectDir.toString())
        setProperty("kotlinVersion", kotlinVersion)
        setProperty("targets", selected.joinToString("\n"))
    }.also { properties -> outputDir.resolve("plan.properties").outputStream().use { properties.store(it, "BCV target selection") } }
}

internal fun readPlan(path: Path): Properties = Properties().apply { path.inputStream().use { load(it) } }
internal fun Properties.targets(): List<String> = getProperty("targets", "").lines().filter { it.isNotEmpty() }
