# Run Log — 2026-02-04-mlc-opencl-device

## Context
- Goal: prefer OpenCL on supported devices (OnePlus 11) while keeping CPU fallback.
- Related logs: none.

## Related ADRs
- None.

## Changes
- Added an `MLC_DEVICE` build config to control CPU vs OpenCL, driven by Gradle properties, so GPU can be opted in without code changes per build.
- Set the runtime system property before MLCEngine init to ensure the device selection is honored.
- Fixed `installDebugWithModel` to target the actual `storeDebug` install task.

## Assumptions
- OnePlus 11 exposes OpenCL to the app namespace without linker namespace failures.
- Falling back to CPU is acceptable if OpenCL is unavailable.

## Evidence
- `./gradlew :app:installDebugWithModel` failed because `installDebug` does not exist with flavors.
- `./gradlew :app:installDebugWithModel` failed because `adb` reported no connected devices.

## Commands
- `./gradlew :app:installDebugWithModel` (failed: `installDebug` task not found).
- `./gradlew :app:tasks --all`
- `./gradlew :app:installDebugWithModel` (failed: `adb: no devices/emulators found`).

## Tests/Checks
- Not run (no local test target configured for this change).

## Risks/Rollback
- Risk: OpenCL may still be blocked on some devices; fallback should keep the app functional.
- Rollback: set `MLC_DEVICE=cpu` in `gradle.properties` and rebuild.

## Open questions
- None.

## Next steps
- Rebuild and install on the OnePlus 11, then verify logs show OpenCL device selection.

## Diff tour
- `app/build.gradle.kts`: add `MLC_DEVICE` BuildConfig field from Gradle property.
- `app/src/main/java/com/example/offlinevoice/MlcEngine.kt`: set system property before MLCEngine init.
- `gradle.properties`: default `MLC_DEVICE` to `opencl` for this build.
