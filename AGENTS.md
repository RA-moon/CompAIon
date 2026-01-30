# AGENTS.md — Codex Defaults for astralpirates.com

These defaults are meant to be light but consistent. Use them **when relevant**.

## Read-first (repo signals)
- Before running commands or changing code, consult `docs/project-overview.md` and the relevant README (`frontend/README.md` or `cms/README.md`).
- Prefer existing `pnpm` scripts or repo helpers (`scripts/*.sh`, `pnpm docker:*`) over ad‑hoc workflows.

## Work style
- Short plan (3–6 bullets) → execute → close with results + next steps.
- Scope gate: 1–2 bullets of “not in scope” when a task is larger than the change.
- No refactor while fixing: keep changes minimal unless explicitly requested.
- For non-trivial changes, include a brief “why” (1–2 sentences) alongside the change notes.
- Capture up to 3 assumptions when relevant and list open questions at the end.
- Avoid drive-by formatting/renames outside the scope; call out if unavoidable.
- Remove debug logs or temporary flags before final output unless explicitly requested to keep them.

## Architecture decisions (ADR)
- For architectural decisions (contracts, DB model, auth/permissions, caching/indexing), add or update an ADR in `docs/adr/NNN-<slug>.md` using `docs/adr/000-template.md`.
- Use a zero-padded 3-digit sequence starting at 001 (000 reserved for the template); keep slugs kebab-case and reference the ADR in the run log.
- See `docs/adr/README.md` for naming and lifecycle notes.

## Run logs (default knowledge capture)
- Any change should be logged in a run log: `docs/run-logs/YYYY-MM-DD-<slug>/00-overview.md`.
- Use kebab-case slugs, keep them short, and align the slug with the change topic.
- Include concise sections:
  - Context (goal, pointers to related logs)
  - Related ADRs (if any)
  - Changes (what + why, file‑level)
  - Assumptions (if any; keep to 1–3)
  - Evidence (logs, screenshots, links)
  - Commands (state‑changing commands only) + result
  - Tests/Checks (run or reason skipped)
  - Risks/Rollback (1–3 lines)
  - Open questions (if any) + Next steps
  - Diff tour (3–6 bullets) when changes span multiple areas
- Never include secrets/PII in logs.
- When running Docker/test commands, store the resulting logs under the current run‑log folder.

## Quality gates (run when applicable; otherwise explain)
- `pnpm lint`
- `pnpm test`
- `pnpm build`
- Prefer package scope when only one workspace changes:
  - Frontend: `pnpm --dir frontend lint|test|generate`
  - CMS: `pnpm --dir cms lint|test|build`
  - Shared: `pnpm --filter "@astralpirates/shared" lint|test|build`
- `pnpm --dir frontend generate` needs `ASTRAL_API_BASE` and `NUXT_PUBLIC_ASTRAL_API_BASE` set for production-like output; if missing, skip with a note.
- Prefer `pnpm docker:test` / `pnpm docker:e2e` for integration runs; attach the log output in the run‑log folder.

## Runtime verification (when touching API routes/middleware/hooks)
- Make at least one real request (curl/browser) and note: endpoint, input, expected vs actual, result/status.

## Env surface changes
- For new/changed env vars: name, purpose, default, and where read (Nuxt/Next/Payload).
- Update the relevant README or add a doc‑TODO in the run log.

## Contracts & shared types
- If `shared/api-contracts.ts` or shared contracts change:
  - State breaking vs non‑breaking.
  - Verify both CMS + frontend compile (or explain why not).
  - Provide a semantic hint: patch/minor/major.

## Planning docs & changelog
- If you edit `docs/planning/**`, run `pnpm plan:status` and note it in the run log.
- Avoid manual edits to `CHANGELOG.md` unless explicitly required; it is generated via `pnpm plan:status`.

## Design tokens / ESLint plugin
- If `shared/theme` or token scripts change, run `pnpm tokens:check` (or `pnpm tokens:generate` if updating).
- If `eslint-plugin-astral-design` changes, note rule intent and verification (test or manual check).

## Data / safety guardrails
- No destructive DB/Compose actions (e.g., `docker compose down -v`) without explicit instruction.
- Always include a rollback note for data/schema/ops changes.

## Additional checks (when relevant)
- Dependency changes: note why + risk and mention lockfile changes in the run log.
- Frontend UI changes: note SSR/CSR sanity, empty/error state checks, and a basic keyboard/focus pass (or explain if skipped).
- Logging/telemetry changes: include a short PII-handling note (what is logged and what is explicitly excluded).
- For opt-in deeper validation, see `docs/agent-extended-checks.md`.
