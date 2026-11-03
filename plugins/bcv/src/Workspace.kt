package io.heapy.ktc.plugins.bcv

import java.nio.file.Path

// KTC 0.13 task outputs are <build>/tasks/<task>. Keep nested builds outside the
// outer build lock, but share them across modules so dependencies compile once.
internal fun workspaceDirectory(outputDir: Path): Path =
    outputDir.toAbsolutePath().parent.parent.resolve("bcv-workspace")

internal fun compilationDirectory(outputDir: Path): Path {
    val override = System.getenv("KTC_BCV_COMPILATION_DIR") ?: return workspaceDirectory(outputDir).resolve("compile")
    val path = Path.of(override).normalize()
    require(path.isAbsolute) { "KTC_BCV_COMPILATION_DIR must be absolute" }
    require(path != outputDir.toAbsolutePath().parent.parent.normalize()) {
        "BCV compilation must not reuse the outer Toolchain build directory (nested build lock)"
    }
    return path
}
