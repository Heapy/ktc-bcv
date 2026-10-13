package io.heapy.ktc.plugins.bcv

import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetPlanTest {
    private fun settings(excluded: List<String> = emptyList(), cross: Boolean = false) = object : BcvSettings {
        override val excludedTargets = excluded
        override val includeCrossTargets = cross
    }

    @Test
    fun discoversPlatformsFromMergedFragmentsAndIgnoresTestSettings() {
        val model = """
            settings@js+wasmJs:
              kotlin:
                version: 2.3.20 # template
            settings@js:
              kotlin: {}
            settings@wasmJs:
              kotlin: {}
            settings@jvm:
              kotlin: {}
            test-settings@linuxX64:
              kotlin: {}
        """.trimIndent()
        assertEquals(listOf("js", "jvm", "wasmJs"), declaredTargets(model))
        assertFailsWith<IllegalStateException> { declaredTargets("unexpected CLI output") }
    }

    @Test
    fun defaultsDiscoverAllHostTargetsAndAllowSymmetricExclusions() {
        val declared = listOf("jvm", "js", "wasmJs", "macosArm64", "linuxX64", "mingwX64")
        assertEquals(listOf("jvm", "js", "wasmJs", "linuxX64"), selectedTargets(declared, settings(), "Linux"))
        assertEquals(listOf("js", "wasmJs", "linuxX64"), selectedTargets(declared, settings(listOf("jvm")), "Linux"))
        assertEquals(listOf("jvm"), selectedTargets(declared, settings(declared - "jvm"), "Linux"))
        assertEquals(emptyList(), selectedTargets(declared, settings(declared), "Linux"))
        assertEquals(declared, selectedTargets(declared, settings(cross = true), "Mac OS X"))
        assertEquals(emptyList(), selectedTargets(listOf("iosArm64"), settings(), "Linux"))
        assertEquals(listOf("macosArm64"), selectedTargets(listOf("macosArm64"), settings(listOf("jvm")), "Mac OS X"))
    }

    @Test
    fun wasiAndAndroidNativeAreSelectedOnEverySupportedHost() {
        val declared = listOf("wasmWasi", "androidNativeArm32", "androidNativeArm64", "androidNativeX86", "androidNativeX64")
        for (os in listOf("Mac OS X", "Linux", "Windows 11")) {
            assertEquals(declared, selectedTargets(declared, settings(), os))
        }
        assertEquals("compileWasmWasi", compilationTask("wasmWasi"))
        assertEquals("compileAndroidNativeArm64Debug", compilationTask("androidNativeArm64"))
    }

    @Test
    fun unsupportedTargetsAndInvalidExclusionsAreNotSilentlyIgnored() {
        assertFailsWith<IllegalArgumentException> { selectedTargets(listOf("jvm"), settings(listOf("jmv")), "Linux") }
        assertFailsWith<IllegalArgumentException> { selectedTargets(listOf("jvm"), settings(listOf("jvm", "jvm")), "Linux") }
        assertFailsWith<IllegalArgumentException> { selectedTargets(listOf("android"), settings(), "Linux") }
        assertEquals(emptyList(), selectedTargets(listOf("android"), settings(listOf("android")), "Linux"))
    }

    @Test
    fun skippedTasksNeverReadOrOverwriteBaselinesOrLaunchCompilation() {
        val root = Files.createTempDirectory("bcv-skipped-test")
        try {
            val plan = root.resolve("plan.properties")
            Properties().apply {
                setProperty("projectDir", root.resolve("no-wrapper").toString())
                setProperty("kotlinVersion", "2.3.20")
                setProperty("targets", "")
            }.also { p -> plan.outputStream().use { p.store(it, "test") } }
            val baseline = root.resolve("baseline.api").also { it.writeText("retained") }
            for (jvm in listOf(true, false)) {
                val output = root.resolve("build-$jvm")
                buildAbi("sample", plan, settings(), jvm, output)
                if (jvm) {
                    checkSelectedJvmApi("sample", output, root.resolve("missing.api"))
                    dumpSelectedJvmApi(output, baseline)
                } else {
                    checkSelectedKlibApi(output, root.resolve("missing.api"))
                    dumpSelectedKlibApi(output, baseline)
                }
                assertEquals("retained", baseline.readText())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
