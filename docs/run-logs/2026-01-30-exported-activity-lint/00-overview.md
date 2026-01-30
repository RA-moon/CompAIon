# Run Log — 2026-01-30-exported-activity-lint

## Context
- Goal: suppress exported-activity security warnings for required launcher activities using explicit lint ignores.
- Repo note: `docs/project-overview.md` was not found in this repository.

## Related ADRs
- None.

## Changes
- Added `tools:ignore="ExportedActivity"` to launcher activities in the main app manifest and bundled example manifests.
- Introduced the tools namespace in manifests that needed it to support the ignore annotation.

## Assumptions
- The code-quality scanner respects Android Lint suppressions for `ExportedActivity`.

## Evidence
- Manual manifest review.

## Commands (state-changing only)
- `mkdir -p docs/run-logs/2026-01-30-exported-activity-lint` → created run-log directory.

## Tests/Checks
- Not run (manifest-only change; no local Android lint/build configured here).

## Risks/Rollback
- Risk: if the scanner ignores lint suppressions, the finding may persist.
- Rollback: remove the `tools:ignore` attributes and tools namespace additions.

## Open questions
- If the warning persists, should we remove or relocate the embedded example manifests from the scan scope?

## Next steps
- Re-run the code quality scan to confirm the vulnerability is cleared.

## Diff tour
- `app/src/main/AndroidManifest.xml`: added tools namespace and `tools:ignore` on the launcher activity.
- `app/src/main/cpp/whisper.cpp/examples/whisper.android/app/src/main/AndroidManifest.xml`: added `tools:ignore` on launcher activity.
- `app/src/main/cpp/whisper.cpp/examples/whisper.android.java/app/src/main/AndroidManifest.xml`: added tools namespace and `tools:ignore` on launcher activity.
- `docs/run-logs/2026-01-30-exported-activity-lint/00-overview.md`: run log for the manifest change.
