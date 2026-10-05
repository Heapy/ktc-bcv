# ktc-bcv

A local Kotlin Toolchain **0.13.0** plugin for reviewing changes to a library's public JVM API.
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

```yaml
plugins:
  bcv:
    enabled: true
    apiDirectory: api
    ignoredPackages: [com.example.internal]
    ignoredClasses: [com.example.GeneratedMetadata]
    nonPublicMarkers: [com.example.InternalApi]
    publicPackages: []
    publicClasses: []
    publicMarkers: []
```

The baseline is `<module>/<apiDirectory>/<module-name>.api`. The default directory is `api`;
all filtering lists default to empty. Names are fully qualified, in dotted notation. Nested
classes use their JVM name, for example `com.example.Outer$Nested`. Public inclusion lists
restrict the snapshot when any is populated, and exclusions take precedence. Ordinary Kotlin `internal`
and private declarations are excluded using metadata; `@PublishedApi` declarations follow BCV's
effective visibility rules.

The `apiBuild` task writes a candidate inside `build/tasks/`. `apiCheck` only reads the baseline:
its `@Input(inferTaskDependency = false)` prevents automatic scheduling of `apiDump`.
Missing baselines fail and remain missing. CRLF versus LF differences are ignored.

## Supported scope and versions

- JVM main compilation through `${module.jar}`: Kotlin and Java libraries.
- Tested with `jvm/lib`, Kotlin 2.4.20, and JVM release 17 on Kotlin Toolchain 0.13.0.
- BCV **0.18.1**, Kotlin metadata **2.4.20**, ASM **9.10.1**, java-diff-utils **4.12**.
  Explicit metadata and ASM pins support the Toolchain's newer compiler and bytecode.
- No Native/KLib, JavaScript, Android variant, Swift, TypeScript, or behavioral API checks.
  Multiplatform product bindings have not been tested.
- BCV is in maintenance mode upstream. The adapter deliberately uses its JVM API and does not
  depend on the Kotlin Gradle plugin's separate ABI-validation feature.

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
python3 scripts/integration.py
```

The integration script installs the plugin into a temporary consumer under `build/` and verifies
a missing baseline failure, initial dump, unchanged API success, real signature change failure
without baseline mutation, explicit update, package/class/marker inclusion, and public members inherited
from a package-private superclass. Unit tests cover missing files, unified diagnostics,
baseline preservation, and checkout line endings. CI runs these checks on Linux. Keep dependency
pins reviewed together when updating Kotlin, and run integration tests after every engine upgrade.

Upstream references: [BCV API source](https://github.com/Kotlin/binary-compatibility-validator/blob/0.18.1/src/main/kotlin/api/KotlinSignaturesLoading.kt),
[Toolchain plugin API](https://github.com/JetBrains/kotlin-toolchain/tree/v0.13.0/build-sources/binary-compatibility-validator).

Licensed under [Apache-2.0](LICENSE).
