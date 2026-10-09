#!/usr/bin/env kotlinr
// Run with Kotlin 2.4.21+ and JDK 25: kotlinr scripts/integration.main.kts

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

val repo = __FILE__.canonicalFile.parentFile.parentFile
val windows = System.getProperty("os.name").startsWith("Windows")

fun temporary(prefix: String, block: (File) -> Unit) {
    val directory = Files.createTempDirectory(prefix).toFile()
    try { block(directory) } finally { directory.deleteRecursively() }
}

fun write(root: File, path: String, text: String) {
    root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
}

fun copy(source: File, destination: File) {
    check(source.copyRecursively(destination, overwrite = true)) { "Could not copy $source to $destination" }
    if (!windows) {
        source.walkTopDown().filter { it.isFile && it.canExecute() }.forEach { file ->
            val target = if (source.isDirectory) destination.resolve(file.relativeTo(source)) else destination
            check(target.setExecutable(true, false)) { "Could not preserve executable permission: $target" }
        }
    }
}

data class CommandResult(val exitCode: Int, val output: String)
fun command(directory: File, arguments: List<String>, environment: Map<String, String?> = emptyMap(), timeout: Long = 600): CommandResult {
    val log = Files.createTempFile("ktc-command-", ".log").toFile()
    try {
        val process = ProcessBuilder(arguments).directory(directory).redirectErrorStream(true).redirectOutput(log).apply {
            environment.forEach { (key, value) -> if (value == null) environment().remove(key) else environment()[key] = value }
        }.start()
        try {
            check(process.waitFor(timeout, TimeUnit.SECONDS)) { "Timed out: $arguments\n${log.readText()}" }
            return CommandResult(process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly().waitFor()
            }
        }
    } finally { log.delete() }
}

fun toolchain(project: File, vararg arguments: String, succeeds: Boolean = true, diagnostic: String? = null): String {
    val wrapper = project.resolve(if (windows) "kotlin.bat" else "kotlin").absolutePath
    val invocation = if (windows) listOf("cmd.exe", "/c", wrapper) else listOf("sh", wrapper)
    val result = command(project, invocation + arguments)
    check((result.exitCode == 0) == succeeds) { "Unexpected exit ${result.exitCode}: ${arguments.toList()}\n${result.output}" }
    check(diagnostic == null || diagnostic in result.output) { "Missing diagnostic $diagnostic:\n${result.output}" }
    println("PASS: ${arguments.joinToString(" ")} (${if (succeeds) "success" else "expected failure"})")
    return result.output
}

temporary("bcv-integration-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "project.yaml", "plugins", "templates", "example/src", "example/module.yaml")) {
        copy(repo.resolve(name), project.resolve(name))
    }
    val baseline = project.resolve("example/api/example.api")
    toolchain(project, "check", "apiCheck", "-m", "example", succeeds = false, diagnostic = "API baseline is missing")
    check(!baseline.exists()) { "Check must never create a missing baseline" }
    toolchain(project, "do", "apiDump", "-m", "example")
    val original = baseline.readText()
    check("example/Greeter" in original && "greet" in original)
    check("implementationDetail" !in original) { "Kotlin internal API leaked into dump" }
    toolchain(project, "check", "apiCheck", "-m", "example")
    toolchain(project, "check", "klibApiCheck", "-m", "example")
    toolchain(project, "do", "klibApiDump", "-m", "example")
    check(!project.resolve("example/api/example.klib.api").exists()) {
        "JVM-only consumers must not create a KLib baseline"
    }
    val source = project.resolve("example/src/Greeter.kt")
    source.writeText(source.readText().replace("fun greet(", "fun welcome("))
    toolchain(project, "check", "apiCheck", "-m", "example", succeeds = false, diagnostic = "Public JVM API changed")
    check(baseline.readText() == original) { "Check overwrote the baseline" }
    toolchain(project, "do", "apiDump", "-m", "example")
    check("welcome" in baseline.readText() && baseline.readText() != original)
    toolchain(project, "check", "apiCheck", "-m", "example")
    write(project, "example/src/Other.kt", """
        package unrelated
        @Target(AnnotationTarget.CLASS)
        @Retention(AnnotationRetention.BINARY)
        public annotation class PublicApi
        @PublicApi public class Marked
        public class Other
    """.trimIndent() + "\n")
    val module = project.resolve("example/module.yaml")
    val originalModule = module.readText()
    module.writeText(originalModule + "\nplugins:\n  bcv:\n    publicPackages: [example]\n")
    toolchain(project, "do", "apiDump", "-m", "example")
    check("example/Greeter" in baseline.readText())
    check("unrelated/Other" !in baseline.readText()) { "Package inclusion was ignored" }
    check("unrelated/Marked" !in baseline.readText())
    module.writeText(originalModule + "\nplugins:\n  bcv:\n    publicPackages: [example]\n    publicMarkers: [unrelated.PublicApi]\n")
    toolchain(project, "do", "apiDump", "-m", "example")
    check("example/Greeter" in baseline.readText() && "unrelated/Marked" in baseline.readText())
    check("unrelated/Other" !in baseline.readText()) { "Mixed inclusion leaked unmarked API" }
    write(project, "example/src/Visible.java", """
        package inherited;
        public class Visible extends Hidden {}
        class Hidden { public static String inheritedMethod() { return "inherited"; } }
    """.trimIndent() + "\n")
    module.writeText(originalModule + "\nplugins:\n  bcv:\n    publicClasses: [inherited.Visible]\n")
    toolchain(project, "do", "apiDump", "-m", "example")
    check("inherited/Visible" in baseline.readText())
    check("inheritedMethod" in baseline.readText()) { "Inclusion lost inherited public members" }
    check("example/Greeter" !in baseline.readText()) { "Class inclusion was ignored" }
}
temporary("bcv-klib-integration-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "plugins/bcv")) {
        copy(repo.resolve(name), project.resolve(name))
    }
    val nativeTarget = when {
        windows -> "mingwX64"
        System.getProperty("os.name").startsWith("Mac") -> if (System.getProperty("os.arch") == "aarch64") "macosArm64" else "macosX64"
        else -> if (System.getProperty("os.arch") == "aarch64") "linuxArm64" else "linuxX64"
    }
    write(project, "project.yaml", """
        modules: [library, web, native, plugins/bcv]
        plugins: [//plugins/bcv]
    """.trimIndent() + "\n")
    write(project, "shared.module-template.yaml", """
        settings:
          kotlin:
            version: 2.3.20
        plugins:
          bcv:
            enabled: true
            apiDirectory: snapshots
    """.trimIndent() + "\n")
    write(project, "library/module.yaml", """
        product:
          type: kmp/lib
          platforms: [jvm, js, wasmJs]
        apply: [//shared.module-template.yaml]
    """.trimIndent() + "\n")
    for ((name, target) in listOf("web" to "js, wasmWasi", "native" to "$nativeTarget, androidNativeArm32, androidNativeArm64, androidNativeX86, androidNativeX64")) {
        write(project, "$name/module.yaml", """
            product:
              type: kmp/lib
              platforms: [$target]
            settings:
              kotlin:
                version: 2.4.20
            plugins:
              bcv: enabled
        """.trimIndent() + "\n")
    }
    for (name in listOf("library", "web", "native")) {
        write(project, "$name/src/Value.kt", """
            package example
            public fun value(): String = "hello"
        """.trimIndent() + "\n")
    }
    val baseline = project.resolve("library/snapshots/library.klib.api")
    toolchain(project, "check", "klibApiCheck", "-m", "library", succeeds = false, diagnostic = "Missing KLib baseline")
    check(!baseline.exists()) { "Check must not create the KLib baseline" }
    toolchain(project, "do", "apiDump", "-m", "library", diagnostic = "BCV reader Kotlin 2.3.20: jvm")
    val dumpOutput = toolchain(project, "do", "klibApiDump", "-m", "library", "-m", "web", "-m", "native")
    check("BCV reader Kotlin 2.3.20:" in dumpOutput && "BCV reader Kotlin 2.4.20:" in dumpOutput) {
        "Reader versions must follow each module's effective Kotlin version"
    }
    val original = baseline.readText()
    check("// Targets: [js, wasmJs]" in original && "example/value" in original)
    check("// Targets: [js, wasmWasi]" in project.resolve("web/api/web.klib.api").readText())
    check("// Targets: [androidNativeArm32, androidNativeArm64, androidNativeX64, androidNativeX86, $nativeTarget]" in project.resolve("native/api/native.klib.api").readText())
    toolchain(project, "check", "apiCheck", "klibApiCheck", "-m", "library", "-m", "web", "-m", "native")
    toolchain(project, "do", "apiDump", "-m", "web", "-m", "native")
    check(!project.resolve("web/api/web.api").exists() && !project.resolve("native/api/native.api").exists()) {
        "Modules without JVM must not require or create a JVM baseline"
    }
    val source = project.resolve("library/src/Value.kt")
    source.writeText(source.readText().replace("fun value()", "fun changedValue()"))
    toolchain(project, "check", "klibApiCheck", "-m", "library", succeeds = false, diagnostic = "KLib API changed")
    check(baseline.readText() == original) { "Check overwrote the KLib baseline" }
    toolchain(project, "do", "klibApiDump", "-m", "library")
    check("example/changedValue" in baseline.readText() && baseline.readText() != original)
    toolchain(project, "check", "klibApiCheck", "-m", "library")
    check(!project.resolve("library/api/library.klib.api").exists()) { "Ignored apiDirectory" }

    val module = project.resolve("library/module.yaml")
    val originalModule = module.readText()
    val jvmBaseline = project.resolve("library/snapshots/library.api")
    check(jvmBaseline.delete())
    module.writeText(originalModule + "\nplugins:\n  bcv:\n    excludedTargets: [jvm, wasmJs]\n")
    toolchain(project, "check", "apiCheck", "klibApiCheck", "-m", "library", diagnostic = "BCV selected: js;")
    toolchain(project, "do", "apiDump", "-m", "library")
    check(!jvmBaseline.exists()) { "Excluded JVM target must not write a baseline" }
    val klibBefore = baseline.readText()
    module.writeText(originalModule + "\nplugins:\n  bcv:\n    excludedTargets: [js, wasmJs]\n")
    toolchain(project, "do", "apiDump", "-m", "library")
    toolchain(project, "check", "apiCheck", "klibApiCheck", "-m", "library", diagnostic = "BCV selected: jvm;")
    toolchain(project, "do", "klibApiDump", "-m", "library")
    check(baseline.readText() == klibBefore) { "Excluded KLib targets must preserve their baseline" }
}
println("BCV integration passed: JVM filters, automatic targets, pure Native/web, exclusions, mixed Kotlin versions, and non-mutating checks.")
