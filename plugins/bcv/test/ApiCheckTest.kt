package io.heapy.ktc.plugins.bcv

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ApiCheckTest {
    @Test
    fun `missing baseline fails without creating it`() = withFiles { actual, baseline ->
        actual.writeText("public class Sample {}\n")
        baseline.deleteIfExists()
        val error = assertFailsWith<IllegalStateException> { checkApi("sample", actual, baseline) }
        assertContains(error.message.orEmpty(), "API baseline is missing")
        assertContains(error.message.orEmpty(), "./kotlin do apiDump")
        assertFalse(Files.exists(baseline))
    }

    @Test
    fun `changed API fails with diff and preserves baseline`() = withFiles { actual, baseline ->
        baseline.writeText("old API\n")
        actual.writeText("new API\n")
        val error = assertFailsWith<IllegalStateException> { checkApi("sample", actual, baseline) }
        assertContains(error.message.orEmpty(), "-old API")
        assertContains(error.message.orEmpty(), "+new API")
        assertEquals("old API\n", baseline.readText())
    }

    @Test
    fun `line ending differences do not fail`() = withFiles { actual, baseline ->
        baseline.writeText("public class Sample {\r\n}\r\n")
        actual.writeText("public class Sample {\n}\n")
        checkApi("sample", actual, baseline)
    }

    private fun withFiles(block: (java.nio.file.Path, java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("ktc-bcv-test-")
        val actual = directory.resolve("actual.api")
        val baseline = directory.resolve("expected.api")
        try {
            block(actual, baseline)
        } finally {
            actual.deleteIfExists()
            baseline.deleteIfExists()
            Files.delete(directory)
        }
    }
}
