package io.heapy.ktc.plugins.bcv

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createParentDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Compares snapshots without scheduling the baseline-writing task. */
@TaskAction
public fun checkApi(
    moduleName: String,
    @Input actualFile: Path,
    @Input(inferTaskDependency = false) baselineFile: Path,
) {
    val instruction = "Run ./kotlin do apiDump -m '$moduleName', review the diff, and commit the baseline."
    check(baselineFile.isRegularFile()) {
        "API baseline is missing: $baselineFile\n$instruction"
    }
    // Ignore the checkout platform's newline convention, but preserve meaningful whitespace.
    val expected = baselineFile.readText().replace("\r\n", "\n").lines()
    val actual = actualFile.readText().replace("\r\n", "\n").lines()
    if (expected == actual) return
    val patch = DiffUtils.diff(expected, actual)
    val diff = UnifiedDiffUtils.generateUnifiedDiff(
        baselineFile.toString(), actualFile.toString(), expected, patch, 3,
    ).joinToString("\n")
    error("Public JVM API changed in $moduleName:\n$diff\n\n$instruction")
}

/** Writes the reviewed API candidate only when the user explicitly requests apiDump. */
@TaskAction
public fun dumpApi(
    @Input actualFile: Path,
    @Output baselineFile: Path,
) {
    actualFile.copyTo(baselineFile.createParentDirectories(), overwrite = true)
    println("Updated API baseline: $baselineFile")
}
