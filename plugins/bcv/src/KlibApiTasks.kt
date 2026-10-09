@file:OptIn(kotlinx.validation.ExperimentalBCVApi::class)

package io.heapy.ktc.plugins.bcv

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import kotlinx.validation.api.klib.KlibDump
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText

internal fun validateKlibTargets(targets: List<String>) {
    val supported = setOf(
        "js", "wasmJs", "macosX64", "macosArm64", "iosSimulatorArm64", "iosX64", "iosArm64",
        "linuxX64", "linuxArm64", "watchosSimulatorArm64", "watchosArm32", "watchosArm64",
        "tvosSimulatorArm64", "tvosX64", "tvosArm64", "mingwX64",
    )
    require(targets.distinct().size == targets.size) { "Duplicate bcv.klibTargets: $targets" }
    require(targets.all { it in supported }) { "Unsupported bcv.klibTargets: ${targets - supported}" }
}

internal fun hostTargets(
    targets: List<String>,
    os: String,
): List<String> =
    targets.filter { target ->
        target == "js" || target == "wasmJs" ||
            when {
                os.startsWith("Mac", ignoreCase = true) -> target.startsWith("macos") || target.startsWith("ios") || target.startsWith("tvos") || target.startsWith("watchos")
                os.startsWith("Windows", ignoreCase = true) -> target.startsWith("mingw")
                else -> target.startsWith("linux")
            }
    }

// KTC 0.13 exposes only JVM artifacts to plugins. Keep this adapter versioned
// with the wrapper and use a separate build directory to avoid recursive locks.
internal fun klibPath(
    buildDir: Path,
    moduleName: String,
    target: String,
): Path {
    val suffix = if (target == "js" || target == "wasmJs") "" else "Debug"
    val task = "compile" + target.replaceFirstChar { it.uppercaseChar() } + suffix
    return buildDir.resolve("tasks/_${moduleName}_$task/$moduleName.klib")
}

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun buildKlibApi(
    @Input(inferTaskDependency = false) projectDir: Path,
    moduleName: String,
    settings: BcvSettings,
    @Output outputDir: Path,
) {
    outputDir.createDirectories()
    if (settings.klibTargets.isEmpty()) {
        Files.writeString(outputDir.resolve("current.klib.api"), "")
        println("KLib ABI disabled: bcv.klibTargets is empty")
        return
    }
    validateKlibTargets(settings.klibTargets)
    val targets = if (settings.klibIncludeCrossTargets) settings.klibTargets else hostTargets(settings.klibTargets, System.getProperty("os.name"))
    require(targets.isNotEmpty()) { "No KLib ABI targets selected for this host" }
    require(settings.klibTimeoutSeconds > 0)
    outputDir.createDirectories()
    val buildDir = outputDir.resolve("compile")
    val command =
        buildList {
            if (System.getProperty("os.name").startsWith("Windows")) {
                addAll(listOf("cmd", "/c", projectDir.resolve("kotlin.bat").toString()))
            } else {
                add(projectDir.resolve("kotlin").toString())
            }
            addAll(listOf("task", "--build-dir", buildDir.toString()))
            targets.forEach { target ->
                val suffix = if (target == "js" || target == "wasmJs") "" else "Debug"
                add(":$moduleName:compile${target.replaceFirstChar { it.uppercaseChar() }}$suffix")
            }
        }
    val log = outputDir.resolve("compile.log")
    val process =
        ProcessBuilder(command)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
    try {
        check(process.waitFor(settings.klibTimeoutSeconds.toLong(), TimeUnit.SECONDS)) { "KLib compilation timed out: $log" }
        check(process.exitValue() == 0) { "KLib compilation failed:\n" + log.readLines().takeLast(70).joinToString("\n") }
    } finally {
        if (process.isAlive) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor()
        }
    }
    val actual = KlibDump()
    targets.forEach { target ->
        val file = klibPath(buildDir, moduleName, target)
        check(file.isRegularFile()) { "KTC 0.13 KLib output missing for $target: $file" }
        actual.merge(KlibDump.fromKlib(file.toFile(), configurableTargetName = target))
    }
    outputDir.resolve("current.klib.api").bufferedWriter().use { actual.saveTo(it) }
    println("KLib ABI compiled for ${targets.joinToString()}")
}

internal fun render(dump: KlibDump): String = dump.saveTo(StringBuilder()).toString()

internal fun expectedSubset(
    baseline: KlibDump,
    actual: KlibDump,
): String {
    check(actual.targets.isNotEmpty()) { "Refusing to validate an empty KLib target set" }
    check(baseline.targets.containsAll(actual.targets)) { "New KLib targets need an explicitly reviewed baseline: ${actual.targets - baseline.targets}" }
    return render(baseline.copy().apply { retain(actual.targets) })
}

@TaskAction
fun checkKlibApi(
    settings: BcvSettings,
    @Input actualFile: Path,
    @Input(inferTaskDependency = false) baselineFile: Path,
) {
    if (settings.klibTargets.isEmpty()) return
    check(baselineFile.isRegularFile()) { "Missing KLib baseline: $baselineFile. Run klibApiDump explicitly and review it." }
    val actual = KlibDump.from(actualFile.toFile())
    val expected = expectedSubset(KlibDump.from(baselineFile.toFile()), actual).lines()
    val generated = render(actual).lines()
    if (expected != generated) {
        val diff = UnifiedDiffUtils.generateUnifiedDiff(baselineFile.toString(), actualFile.toString(), expected, DiffUtils.diff(expected, generated), 3)
        error("KLib API changed:\n${diff.joinToString("\n")}\nRun ./kotlin do klibApiDump only after reviewing the change.")
    }
    println("KLib ABI matches for ${actual.targets.joinToString()}")
}

// Only replace the targets actually compiled on this host. Never infer or erase
// another platform's ABI: its own CI host must verify/update that part.
@TaskAction
fun updateKlibApi(
    settings: BcvSettings,
    @Input actualFile: Path,
    @Output baselineFile: Path,
) {
    if (settings.klibTargets.isEmpty()) return
    val actual = KlibDump.from(actualFile.toFile())
    check(actual.targets.isNotEmpty()) { "Refusing to save an empty KLib target set" }
    val baseline = if (baselineFile.isRegularFile()) KlibDump.from(baselineFile.toFile()) else KlibDump()
    baseline.replace(actual)
    val text = render(baseline)
    baselineFile.createParentDirectories()
    val temporary = Files.createTempFile(baselineFile.parent, ".bcv-klib-", ".tmp")
    try {
        Files.writeString(temporary, text)
        Files.move(temporary, baselineFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
    println("Updated KLib ABI for ${actual.targets.joinToString()}; other target baselines preserved")
}
