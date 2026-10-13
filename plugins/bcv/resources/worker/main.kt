@file:OptIn(kotlinx.validation.ExperimentalBCVApi::class)

package io.heapy.ktc.plugins.bcv.worker

import kotlinx.validation.api.dump
import kotlinx.validation.api.extractAnnotatedPackages
import kotlinx.validation.api.filterOutAnnotated
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import kotlinx.validation.api.retainExplicitlyIncludedIfDeclared
import kotlinx.validation.api.klib.KlibDump
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarFile
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createParentDirectories
import kotlin.io.path.inputStream

private class Request(val properties: Properties) {
    fun required(key: String): String = requireNotNull(properties.getProperty(key)) { "Missing worker property: $key" }
    fun names(key: String): List<String> = properties.getProperty(key, "").lineSequence().filter { it.isNotEmpty() }.toList()
    val publicMarkers get() = names("publicMarkers")
    val nonPublicMarkers get() = names("nonPublicMarkers")
    val publicPackages get() = names("publicPackages")
    val publicClasses get() = names("publicClasses")
    val ignoredPackages get() = names("ignoredPackages")
    val ignoredClasses get() = names("ignoredClasses")
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected one worker request file" }
    val request = Request(Properties().apply { Path.of(args.single()).inputStream().use { load(it) } })
    val compilerVersion = KotlinCompilerVersion.VERSION
    check(compilerVersion == request.required("kotlinVersion")) {
        "BCV reader compiler mismatch: requested ${request.required("kotlinVersion")}, loaded $compilerVersion"
    }
    for (target in request.names("targets")) {
        val input = Path.of(request.required("input.$target"))
        val output = Path.of(request.required("output.$target"))
        if (target == "jvm") {
            dumpJvm(request, input, output)
        } else {
            output.createParentDirectories().bufferedWriter().use { writer ->
                KlibDump.fromKlib(input.toFile(), configurableTargetName = target).saveTo(writer)
            }
        }
        println("BCV reader Kotlin $compilerVersion: $target -> $output")
    }
}

private fun dumpJvm(request: Request, input: Path, output: Path) {
    val signatures = JarFile(input.toFile()).use { it.loadApiFromJvmClasses() }
    val publicAnnotations = request.publicMarkers.map { it.replace('.', '/') }.toSet()
    val hiddenAnnotations = request.nonPublicMarkers.map { it.replace('.', '/') }.toSet()
    // BCV 0.18.1 retains unselected classes when the marker list is empty, even when
    // package/class inclusion is requested. A semicolon cannot name a JVM annotation.
    val inclusionMarkers = if (request.publicMarkers.isEmpty() &&
        (request.publicPackages.isNotEmpty() || request.publicClasses.isNotEmpty())
    ) {
        listOf(";")
    } else {
        request.publicMarkers
    }
    val selected = signatures
        // Preserve the complete superclass map while BCV flattens inaccessible bases.
        .filterOutNonPublic(
            request.ignoredPackages + signatures.extractAnnotatedPackages(hiddenAnnotations),
            request.ignoredClasses,
        )
        .filterOutAnnotated(hiddenAnnotations)
        .retainExplicitlyIncludedIfDeclared(
            request.publicPackages + signatures.extractAnnotatedPackages(publicAnnotations),
            request.publicClasses,
            inclusionMarkers,
        )
    output.createParentDirectories().bufferedWriter().use { selected.dump(it) }
}
