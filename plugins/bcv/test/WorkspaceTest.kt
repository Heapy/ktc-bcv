package io.heapy.ktc.plugins.bcv

import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class WorkspaceTest {
    private fun temporary(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("bcv-workspace-")
        try { action(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun rejectsRelativeAndOuterCompilationDirectoriesButAllowsNewNestedDirectories() = temporary { root ->
        val output = Files.createDirectories(root.resolve("build/tasks/_module_apiBuild"))
        assertFailsWith<IllegalArgumentException> { compilationDirectory(output, "relative") }
        for (path in listOf(root.resolve("build"), root.resolve("build/tasks/.."))) {
            assertFailsWith<IllegalArgumentException> { compilationDirectory(output, path.toString()) }
        }
        val invalidTail = root.resolve("build/not-created/../compile")
        assertFailsWith<IllegalArgumentException> { compilationDirectory(output, invalidTail.toString()) }
        assertFalse(Files.exists(root.resolve("build/not-created")))
        assertEquals(root.toRealPath().resolve("build/bcv-workspace/compile"), compilationDirectory(output, null))
        assertEquals(root.toRealPath().resolve("precompiled"), compilationDirectory(output, root.resolve("precompiled").toString()))
    }

    @Test
    fun workspaceDirectoryRejectsUnexpectedToolchainOutputLayout() = temporary { root ->
        assertFailsWith<IllegalStateException> { workspaceDirectory(root.resolve("build/output/task")) }
    }

    @Test
    fun emptyEnvironmentOverridesAreTreatedAsUnset() {
        assertEquals(
            BcvEnvironment(null, null),
            BcvEnvironment.from(mapOf("KTC_BCV_TARGETS" to "  ", "KTC_BCV_COMPILATION_DIR" to "")),
        )
        assertEquals(
            BcvEnvironment(listOf("jvm", "linuxX64"), "/tmp/bcv-compile"),
            BcvEnvironment.from(mapOf("KTC_BCV_TARGETS" to "jvm, linuxX64", "KTC_BCV_COMPILATION_DIR" to "/tmp/bcv-compile")),
        )
    }

    @Test
    fun rejectsSymlinkAliasesIncludingParentTraversal() = temporary { root ->
        // Windows symlink creation needs privileges not granted to ordinary users.
        if (System.getProperty("os.name").startsWith("Windows")) return@temporary
        val output = Files.createDirectories(root.resolve("build/tasks/_module_apiBuild"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), root.resolve("build"))
        val childAlias = Files.createSymbolicLink(root.resolve("child-alias"), root.resolve("build/tasks"))
        for (path in listOf(alias, alias.resolve("tasks/.."), childAlias.resolve(".."))) {
            assertFailsWith<IllegalArgumentException> { compilationDirectory(output, path.toString()) }
        }
    }

    @Test
    fun serializesConcurrentRequestsAndReleasesAfterFailure() = temporary { root ->
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            val future = withWorkspaceLock(root, 5) {
                executor.submit {
                    started.countDown()
                    withWorkspaceLock(root, 5) { entered.countDown() }
                }.also {
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    assertFalse(entered.await(200, TimeUnit.MILLISECONDS))
                }
            }
            future.get(5, TimeUnit.SECONDS)
            assertEquals(0L, entered.count)
            assertFailsWith<IllegalArgumentException> { withWorkspaceLock(root, 5) { errorForTest() } }
            assertEquals("released", withWorkspaceLock(root, 5) { "released" })
        } finally { executor.shutdownNow() }
    }

    private fun errorForTest(): Nothing = throw IllegalArgumentException("reader failed")

    @Test
    fun waitingRequestsTimeOutOrCanBeInterrupted() = temporary { root ->
        withWorkspaceLock(root, 5) {
            assertContains(assertFailsWith<IllegalStateException> { withWorkspaceLock(root, 1) {} }.message.orEmpty(), "timed out")
            val started = CountDownLatch(1)
            val interrupted = AtomicBoolean()
            val entered = AtomicBoolean()
            val thread = Thread {
                started.countDown()
                try { withWorkspaceLock(root, 5) { entered.set(true) } }
                catch (_: InterruptedException) { interrupted.set(Thread.currentThread().isInterrupted) }
            }
            thread.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            thread.interrupt()
            thread.join(5_000)
            assertFalse(thread.isAlive)
            assertTrue(interrupted.get())
            assertFalse(entered.get())
        }
    }

    @Test
    fun waitingTimeoutRestartsWhenLockOwnerChanges() = temporary { root ->
        val ownerFile = root.resolve(".bcv.lock.owner")
        Files.writeString(ownerFile, "first-owner")
        FileChannel.open(root.resolve(".bcv.lock"), CREATE, WRITE).use { channel ->
            val heldLock = channel.lock()
            val executor = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            val entered = CountDownLatch(1)
            try {
                val future = executor.submit {
                    started.countDown()
                    withWorkspaceLock(root, 1) { entered.countDown() }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                Thread.sleep(650)
                Files.writeString(ownerFile, "second-owner")
                Thread.sleep(650)
                heldLock.release()
                future.get(5, TimeUnit.SECONDS)
                assertEquals(0L, entered.count)
            } finally {
                if (heldLock.isValid) heldLock.release()
                executor.shutdownNow()
            }
        }
    }
}
