@file:OptIn(kotlinx.validation.ExperimentalBCVApi::class)

package io.heapy.ktc.plugins.bcv

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import kotlinx.validation.api.klib.KlibDump
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.isRegularFile

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
    @Input actualFile: Path,
    @Input(inferTaskDependency = false) baselineFile: Path,
) {
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
    @Input actualFile: Path,
    @Output baselineFile: Path,
) {
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
