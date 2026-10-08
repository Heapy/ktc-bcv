package io.heapy.ktc.plugins.bcv

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import kotlinx.validation.api.dump
import kotlinx.validation.api.extractAnnotatedPackages
import kotlinx.validation.api.filterOutAnnotated
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import kotlinx.validation.api.retainExplicitlyIncludedIfDeclared
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.bufferedWriter
import kotlin.io.path.copyTo
import kotlin.io.path.createParentDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Inspects the consumer's compiled JAR, including Kotlin visibility metadata. */
@TaskAction
public fun buildApi(
    settings: BcvSettings,
    @Input inputJar: CompilationArtifact,
    @Output outputFile: Path,
) {
    val signatures = JarFile(inputJar.artifact.toFile()).use { it.loadApiFromJvmClasses() }
    val publicAnnotations = settings.publicMarkers.map { it.replace('.', '/') }.toSet()
    val hiddenAnnotations = settings.nonPublicMarkers.map { it.replace('.', '/') }.toSet()
    // BCV 0.18.1 retains unselected classes when the marker list is empty, even when
    // package/class inclusion is requested. A semicolon cannot name a JVM annotation.
    val inclusionMarkers = if (settings.publicMarkers.isEmpty() &&
        (settings.publicPackages.isNotEmpty() || settings.publicClasses.isNotEmpty())
    ) {
        listOf(";")
    } else {
        settings.publicMarkers
    }
    val selected = signatures
        // Preserve the complete superclass map while BCV flattens inaccessible bases.
        .filterOutNonPublic(
            settings.ignoredPackages + signatures.extractAnnotatedPackages(hiddenAnnotations),
            settings.ignoredClasses,
        )
        .filterOutAnnotated(hiddenAnnotations)
        .retainExplicitlyIncludedIfDeclared(
            settings.publicPackages + signatures.extractAnnotatedPackages(publicAnnotations),
            settings.publicClasses,
            inclusionMarkers,
        )
    outputFile.createParentDirectories().bufferedWriter().use { selected.dump(it) }
}

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
