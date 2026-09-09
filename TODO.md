# TODO List

- [] Lists all SMS from other SMS apps before app is installed
- [] Message orders are wrong in conversations after i installed the app on a phone with old messages

## Backfill (Phase 12) follow-ups — clear fixes
- [] Progress UX: emit `Running(0, total)` when a scan starts — `statusProgress` only fires every 100 messages, so small scans / the first second of large scans show no banner
- [] Replace public `forceScanForTesting()` on `SpamBackfillUseCase` with a properly-scoped `rescan()` API (Settings currently calls `ensureStarted()`; the test-named hook stays unused in prod)
- [] Align TASKS.md 12.5 with implementation: UiState exposes `BackfillStatus` directly, not a `BackfillProgress(processed, total)` data class
- [] `insertAll` uses `CONFLICT_REPLACE`: a concurrent ingress verdict row for the same messageId gets overwritten mid-scan — skip-if-exists or accept deliberately
- [] `flush()` writes `sender_state` then `message_verdict` in two transactions; wrap in a single transaction for atomicity

## Backfill — open decisions (analyze before coding)
- [] Decide startup policy: `ensureStarted()` on every cold start re-reads all SMS + regroups senders; add a `backfill_complete` marker (invalidated on new inbound / Settings rescan) or keep the no-flag idempotent design
- [] Decide resume strategy: cancelled scans restart from the beginning (idempotent via verdict-ID skip); a per-sender resume cursor would bound cost on large histories
- [] Decide if launch-time scan should be gated on onboarding completion (currently fails fast on SecurityException before permissions are granted)

## Project-wide
- [] Fix pre-existing lint in `feature/export/ExportScreen.kt:214` (ViewModelConstructorInComposable) — blocks full `./gradlew build`
- [] perf: `SpamStateWriter.upsertAllIfNotOverridden` does one `getByAddress` per address per flush — batch `IN (...)` read under the lock
- [] Add instrumented tests for `core:telephony` provider query/write logic (off-device fake coverage is thin, per CLAUDE.md §5)
- [] MMS: extend history scan to MMS when the MMS-parsing architecture is ready (currently SMS-only in backfill)
- [] Rename `com.nospam.nospam` applicationId/package before publishing