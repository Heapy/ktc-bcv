@file:OptIn(kotlinx.validation.ExperimentalBCVApi::class)

package io.heapy.ktc.plugins.bcv

import kotlinx.validation.api.klib.KlibDump
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KlibApiTest {
    private val settings = object : BcvSettings {
        override val klibTargets = listOf("macosArm64", "linuxX64")
    }

    private fun dump(
        dir: Path,
        name: String,
        target: String,
        type: String = "kotlin/String",
    ): Path =
        dir.resolve("$name.api").also {
            it.writeText(
                """
                // Klib ABI Dump
                // Targets: [$target]
                // Rendering settings:
                // - Signature version: 2
                // - Show manifest properties: true
                // - Show declarations: true

                // Library unique name: <sample>
                final fun sample/value(): $type // sample/value|value(){{}}[0]
                """.trimIndent() + "\n",
            )
        }

    @Test
    fun disabledKlibLeavesJvmOnlyConsumersAndBaselinesUntouched() {
        val root = Files.createTempDirectory("bcv-jvm-only-test")
        try {
            val defaults = object : BcvSettings {}
            val output = root.resolve("output")
            val baseline = root.resolve("baseline.api")
            baseline.writeText("existing baseline")
            buildKlibApi(root, "sample", defaults, output)
            val candidate = output.resolve("current.klib.api")
            assertEquals("", Files.readString(candidate))
            checkKlibApi(defaults, candidate, baseline)
            updateKlibApi(defaults, candidate, baseline)
            assertEquals("existing baseline", Files.readString(baseline))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsUnknownAndDuplicateTargetsBeforeHostFiltering() {
        assertFailsWith<IllegalArgumentException> { validateKlibTargets(listOf("macosArm46")) }
        assertFailsWith<IllegalArgumentException> { validateKlibTargets(listOf("js", "js")) }
        validateKlibTargets(settings.klibTargets)
    }

    @Test
    fun hostSelectionKeepsWebAndOnlyHostNativeTargets() {
        val targets = listOf("js", "wasmJs", "macosArm64", "iosArm64", "linuxX64", "mingwX64")
        assertEquals(listOf("js", "wasmJs", "macosArm64", "iosArm64"), hostTargets(targets, "Mac OS X"))
        assertEquals(listOf("js", "wasmJs", "linuxX64"), hostTargets(targets, "Linux"))
        assertEquals(listOf("js", "wasmJs", "mingwX64"), hostTargets(targets, "Windows 11"))
    }

    @Test
    fun subsetCheckAndUpdatePreserveOtherPlatforms() {
        val root = Files.createTempDirectory("klib-abi-test")
        try {
            val linux = KlibDump.from(dump(root, "linux", "linuxX64", "kotlin/Int").toFile())
            val macFile = dump(root, "mac", "macosArm64")
            val mac = KlibDump.from(macFile.toFile())
            val combined = linux.copy().apply { merge(mac) }
            val baseline = root.resolve("baseline.api").also { it.writeText(render(combined)) }
            checkKlibApi(settings, macFile, baseline)
            val before = Files.readString(baseline)
            val changed = dump(root, "changed", "macosArm64", "kotlin/Boolean")
            assertFailsWith<IllegalStateException> { checkKlibApi(settings, changed, baseline) }
            assertEquals(before, Files.readString(baseline))
            updateKlibApi(settings, changed, baseline)
            val updated = KlibDump.from(baseline.toFile())
            assertEquals(expectedSubset(combined, linux), expectedSubset(updated, linux))
            assertNotEquals(expectedSubset(combined, mac), expectedSubset(updated, mac))
            checkKlibApi(settings, changed, baseline)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingBaselineAndEmptyOrNewTargets() {
        val root = Files.createTempDirectory("klib-abi-test")
        try {
            val file = dump(root, "mac", "macosArm64")
            assertFailsWith<IllegalStateException> { checkKlibApi(settings, file, root.resolve("missing.api")) }
            val mac = KlibDump.from(file.toFile())
            assertFailsWith<IllegalStateException> { expectedSubset(mac, KlibDump()) }
            val linux = KlibDump.from(dump(root, "linux", "linuxX64").toFile())
            assertFailsWith<IllegalStateException> { expectedSubset(mac, linux) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
