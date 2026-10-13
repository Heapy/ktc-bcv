# ktc-bcv

A local Kotlin Toolchain **0.13.0** plugin for reviewing changes to a library's public JVM and KLib API.
Uses the published [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator/tree/0.18.1)
API, without invoking Gradle.

```sh
./kotlin do apiDump -m example    # explicitly create/update example/api/example.api
./kotlin check apiCheck -m example
./kotlin check                   # tests plus API checks for enabled modules
```

Commit the initial `.api` file. Each subsequent check compares the compiled JAR with that file and
fails with a unified diff on any API change, including compatible additions. Review changes before
running `apiDump` and committing the updated baseline. This is an API snapshot review gate, not a
semantic classifier of source or binary compatibility.

## Install in another project

From your consumer project, use the [ktc-plugins installer](https://github.com/Heapy/ktc-plugins):

```sh
./ktc-plugins add Heapy/ktc-bcv --branch main --enable-in library
```

Replace `library` with your consumer module path. The root [`ktc-plugin.yaml`](ktc-plugin.yaml)
declares selector `bcv`, module `plugins/bcv`, and `LICENSE`; the installer registers
and enables the plugin automatically. Commit the generated manifest, lockfile, and vendored
sources. The lockfile pins the resolved commit; use `--commit <full-40-character-SHA>`
instead of `--branch main` to select a specific revision. Templates are copied separately.

For manual installation:

Copy `plugins/bcv/` into your project's `plugins/bcv/`, preserving this repository's `LICENSE`.
The plugin has no repository-local module, version-catalog, or template dependencies. Register it:

```yaml
# project.yaml
modules:
  - library
  - plugins/bcv
plugins:
  - //plugins/bcv
```

```yaml
# library/module.yaml
product: jvm/lib
plugins:
  bcv: enabled
```

The root `ktc-plugin.yaml` exposes selector `bcv` for compatible local-plugin installers. The
optional `templates/bcv.module-template.yaml` enables the plugin across consumer modules;
copy it separately if you want to use it. Plugin registration still belongs in `project.yaml`.

## Configuration

A single `bcv: enabled` checks every supported target declared by the consumer. No target
list or Kotlin reader version is required. Optional settings are:

```yaml
plugins:
  bcv:
    enabled: true
    apiDirectory: api
    excludedTargets: []
    includeCrossTargets: false
    timeoutSeconds: 1800
    ignoredPackages: [com.example.internal]
    ignoredClasses: [com.example.GeneratedMetadata]
    nonPublicMarkers: [com.example.InternalApi]
    publicPackages: []
    publicClasses: []
    publicMarkers: []
```

`excludedTargets` accepts platform names. Known targets absent from a module are allowed,
so one shared template can exclude JVM across both JVM and pure Native/web modules. Excluding
`jvm` disables JVM validation; excluding every KLib target leaves JVM validation enabled.
Modules without JVM automatically skip the JVM tasks. Excluded targets and targets unavailable
on the current host are printed explicitly; skipped checks/dumps never read or write a baseline.
Unknown/duplicate exclusions fail. An unsupported target must be explicitly excluded rather
than silently receiving no validation.

The JVM baseline is `<module>/<apiDirectory>/<module-name>.api`; the KLib baseline is
`<module>/<apiDirectory>/<module-name>.klib.api`. The default directory is `api` for both.
JVM filter names are fully qualified and use dotted notation. Nested classes use their JVM
name, for example `com.example.Outer$Nested`. Public inclusion lists restrict the JVM snapshot
when any is populated, and exclusions take precedence. Kotlin `internal` and private declarations
are excluded using metadata; `@PublishedApi` follows BCV's effective visibility rules. These
filters do not apply to KLib snapshots, which contain the complete ABI.

## Checks and explicit updates

```sh
./kotlin check apiCheck klibApiCheck -m library
./kotlin do apiDump -m library
./kotlin do klibApiDump -m library
```

Checks never schedule baseline updates. A missing baseline fails with update instructions.
JVM checks ignore CRLF/LF differences. KLib checks compare only the compiled targets; explicit
KLib updates replace those targets while preserving all others. No foreign platform's ABI is
inferred. New targets, missing artifacts, empty candidates and changed snapshots fail.

JS, Wasm-JS, Wasm-WASI and Android Native compile on every supported host. Native target selection follows the host family: Apple
on macOS, Linux on Linux, and MinGW on Windows. Run checks on the appropriate CI hosts for full
coverage. `includeCrossTargets: true` requests all non-excluded targets and propagates compilation
failures; use it only when the host can compile them. A module with no selected targets is
explicitly reported as skipped, including a Native-only module on another operating system.

## Automatic Kotlin readers and implementation boundaries

The plugin obtains the effective Kotlin compiler version from KTC's
`${module.settings.kotlin.version}` reference after template resolution. KTC 0.13 permits one
compiler version per module. Different modules may use different versions in the same build.

ABI reading runs in a separate JVM application with `kotlin-compiler-embeddable` and
`kotlin-metadata-jvm` matching that module's compiler. The reader checks the loaded compiler
version before running. These dependencies never enter the consumer library's dependencies;
users do not add or configure them. BCV **0.18.1**, ASM/ASM-tree **9.10.1** and diff-utils **4.12**
are adapter-owned pins. `jvm.release: 17` targets the plugin/reader's own bytecode, independently
of the consumer's JVM target. Verification currently runs on JDK 25.

KTC 0.13 does not expose platforms or KLib artifacts through the source-plugin API. The adapter
uses `show settings` to discover the resolved fragment platform sets and invokes main compilation
tasks in isolated directories. It does not parse source templates itself, recursively run checks,
or link Native device tests. JVM compilation is invoked only when JVM is actually selected;
there is no unconditional `${module.jar}` binding.

The reader is a generated, isolated KTC project using the consumer's wrapper for dependency
resolution and execution. Its source is shipped in the plugin's resources. Build outputs, resolved
settings, reader configuration and compiler/reader logs stay under the task output directory.
Task names, artifact paths and the `show settings` format are pinned to KTC **0.13.0**; revalidate
these when upgrading. Unrecognized settings output fails rather than disabling validation.

Supported platforms: JVM, JS, Wasm-JS, Wasm-WASI, Android Native, macOS, iOS, tvOS,
watchOS (except `watchosX64`), Linux and MinGW as provided by KTC 0.13. Pure Native and web modules are supported. Android variants,
Swift, TypeScript and behavioral API checks are outside the adapter's scope. Tested consumer
Kotlin versions are **2.3.20** and **2.4.20**; automatic version selection is not a promise that
BCV supports every future compiler format/API.

Local plugins are source modules in this Toolchain release; there is no Maven plugin publication.

## Real-project trial: Kotgent (2026-10-05)

Validated in an isolated worktree of Kotgent at `ac1f35a21af210c0579b3536f326da96dace0ebb`,
using Kotlin Toolchain 0.13.0 and its original `plugins/build-info` and
`plugins/sqldelight-gen` JVM build-plugin modules. BCV can be enabled on
`jvm/amper-plugin` consumers. No extracted or synthetic consumer was needed.

After copying `plugins/bcv`, registering it in `project.yaml`, and enabling `bcv` in both
consumer modules, these commands ran through Kotgent's shared build mutex:

```sh
kotgent mutex run kotlin-build -- ./kotlin check apiCheck -m build-info
# Expected exit 1: missing baseline; no baseline was created.
kotgent mutex run kotlin-build -- ./kotlin do apiDump -m build-info -m sqldelight-gen
# Exit 0: 20-line build-info and 42-line sqldelight-gen API baselines.
kotgent mutex run kotlin-build -- ./kotlin check apiCheck -m build-info -m sqldelight-gen
# Exit 0: unchanged API.
```

Temporarily changing Kotgent's real `kotlinStringLiteral` parameter from `String` to
`CharSequence`, then repeating `check apiCheck -m build-info`, failed with a unified
signature diff. SHA-256 hashes proved both baseline files stayed unchanged. Restoring
the source and running the aggregate module check passed, including all 10 existing
build-info tests:

```sh
kotgent mutex run kotlin-build -- ./kotlin check -m build-info -m sqldelight-gen
```

No BCV implementation defect was found in this trial. Kotgent's application modules are
Native-only, so this validates its JVM build tooling, not the application's Native API.
macOS sandboxed execution initially failed in Toolchain's `sysctl` process cleanup;
the serialized checks succeeded outside that sandbox. Retained local evidence is in
`ktc-plugin-trials/2026-10-05/kotgent-bcv/result.json` and
`bcv-trial-logs/` in that worktree. These are maintainer-local artifacts, not files
in this repository.

## Verify and maintain

```sh
./kotlin build
./kotlin check
kotlinr scripts/integration.main.kts
```

The integration script installs the plugin into temporary consumer projects and verifies:

- JVM baseline creation, unchanged API, a real API change, and non-mutating failures;
- package/class/marker filtering and inherited members of a hidden superclass;
- automatic JVM/JS/Wasm-JS/Wasm-WASI, Android Native and host Native discovery, including pure web/Native modules;
- Kotlin 2.3.20 from a template alongside Kotlin 2.4.20 modules in one build;
- JVM and KLib exclusions, skipped dumps, and custom baseline directories.

Unit tests cover target discovery/selection, invalid exclusions, skipped tasks, missing files,
unified diagnostics, baseline preservation, line endings, and KLib updates preserving other
platforms. CI runs the integration on Linux. Keep engine pins reviewed together and re-run
integration tests after compiler/Toolchain upgrades.

Upstream references: [BCV API source](https://github.com/Kotlin/binary-compatibility-validator/blob/0.18.1/src/main/kotlin/api/KotlinSignaturesLoading.kt),
[Toolchain plugin API](https://github.com/JetBrains/kotlin-toolchain/tree/v0.13.0/build-sources/binary-compatibility-validator).

Licensed under [Apache-2.0](LICENSE).

## Running verification scripts

The `.main.kts` scripts require JDK 25 and Kotlin 2.4.21+ (`kotlinr` on `PATH`).
Run them with `kotlinr scripts/<name>.main.kts` from the repository root.
The Kotlin Toolchain `./kotlin` command is a separate executable. CI installs the script runner
through [Heapy/setup-main-kts](https://github.com/Heapy/setup-main-kts), pinned to v1.0.1's
commit SHA. The action caches the compiler, Maven dependencies, and compiled scripts between
eligible CI runs. The first script run compiles the script and resolves any pinned Maven
dependencies; later runs reuse the script cache.
