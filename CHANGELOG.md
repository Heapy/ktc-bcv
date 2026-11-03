# Changelog

## 0.3.1 — 2026-11-03

- Snapshot compiled artifacts while holding the shared compilation lock.
- Build executable ABI readers per Kotlin version, preserve content-addressed snapshots, and run readers concurrently outside the build lock.
- Restart the workspace lock wait timeout when its owner changes, while preserving interruption support.
- Treat blank environment overrides as unset and log their effective values.
- Validate the Toolchain output layout and reject missing-path parent traversal before creating compilation directories.

## 0.3.0 — 2026-11-03

- Share compiler and reader build workspaces across modules with per-workspace file locking.
- Add explicit target partitioning and validate shared compilation directory overrides.
- Verify workspace locking and path handling on macOS and Windows.

## 0.2.1 — 2026-10-21

- Forward nested Kotlin Toolchain stdout and stderr through the parent task logger.
- Drain both streams concurrently and preserve final output, failures and timeouts.
- Keep subprocess log files for model parsing and failure diagnostics.
