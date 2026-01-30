# Run Log — 2026-01-30-exported-activity-guard

## Context
- Goal: tighten the exported launcher activity so only launcher-style intents are accepted before forwarding into `MainActivity`.
- Repo note: `docs/project-overview.md` was not found in this repository.

## Related ADRs
- None.

## Changes
- Added launcher-intent gating in `LauncherActivity` to ignore non-launcher starts before forwarding.
- Updated the class comment to reflect the new intent validation behavior.

## Assumptions
- Launcher and task-resume flows deliver `ACTION_MAIN` with `CATEGORY_LAUNCHER` and no data URI.

## Evidence
- Manual review of `LauncherActivity` behavior.

## Commands (state-changing only)
- `mkdir -p docs/run-logs/2026-01-30-exported-activity-guard` → created run-log directory.

## Tests/Checks
- Not run (UI-only change; no local Android build environment configured here).

## Risks/Rollback
- Risk: a non-standard launcher using a different intent shape could be blocked.
- Rollback: remove the `isLauncherIntent` guard and restore the previous `onCreate` flow.

## Open questions
- Should we also harden the exported activity via manifest-level constraints (if acceptable for launcher compatibility)?

## Next steps
- Confirm the security scan now recognizes the launcher activity as guarded.

## Diff tour
- `app/src/main/java/com/example/offlinevoice/LauncherActivity.kt`: reject non-launcher intents before forwarding.
- `docs/run-logs/2026-01-30-exported-activity-guard/00-overview.md`: run log for the change.
