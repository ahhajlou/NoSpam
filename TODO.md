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
- [x] Decide startup policy — RESOLVED (single-shot flag): no launch-time scan; a `history_backfill_pending` DataStore flag is set on onboarding permission grant, lets an interrupted scan resume on the next cold start exactly once, and is cleared (app-side watcher) on any terminal status (Done/Cancelled/Failed). Ordinary launches re-read the flag only.
- [] Decide resume strategy: cancelled scans restart from the beginning (idempotent via verdict-ID skip); a per-sender resume cursor would bound cost on large histories
- [x] Gate launch-time scan on onboarding — folded into the startup-policy decision above: launch re-reads the flag only, and the flag is set only after permission is granted, so there is no SecurityException path

## Voluntary corpus contribution (`:feature:export`) — designed, not built

`:feature:export` exists because volunteers should be able to contribute real
Persian SMS to the training set; there is no corpus to buy. It is currently
`debugImplementation` and out of release builds, because what exists today is
shaped like a debug tool rather than a consent flow. It comes back to release
when the items below are done, not before.

- [] **Export preprocessed text, not raw text.** `TfidfPreprocessor` already
  masks URLs, digits and Persian numerals before scoring. Running the same
  masking on export costs the model nothing and stops the corpus carrying phone
  numbers, OTP codes and account numbers. Cheapest and largest risk reduction —
  do this one first, even standalone.
- [] **Default to unknown senders only.** Contact conversations are the highest
  risk and the lowest training value for a spam model. Offer including them as
  an explicit extra choice, never the default.
- [] **Move out of the drawer, into Settings.** Something like "Help improve
  spam detection". Contribution should be discovered deliberately, not hit by a
  stray tap next to Inbox.
- [] **Consent screen at the moment of export.** State plainly what leaves the
  device, that it is their real message text, and that a submission cannot be
  recalled. Show a preview of the actual lines that will be written.
- [] **Privacy policy + Play Data Safety declaration.** Shipping this makes the
  app a data collector. The `play-policy-insights` skill audits exactly this.
- [x] **Per-install random id instead of a hardware id** — done. `installId` is
  a random UUID in DataStore, so submissions from one contributor can be grouped
  and de-duplicated without identifying the device. Replaced
  `Settings.Secure.ANDROID_ID` (`REVIEW.md` L-2).

`:feature:mldebug` is a different case and needs no such plan: it is a
classifier console for development and stays debug-only permanently.

## Project-wide
- [] Fix pre-existing lint in `feature/export/ExportScreen.kt:214` (ViewModelConstructorInComposable) — blocks full `./gradlew build`
- [] perf: `SpamStateWriter.upsertAllIfNotOverridden` does one `getByAddress` per address per flush — batch `IN (...)` read under the lock
- [] Add instrumented tests for `core:telephony` provider query/write logic (off-device fake coverage is thin, per CLAUDE.md §5)
- [] MMS: extend history scan to MMS when the MMS-parsing architecture is ready (currently SMS-only in backfill)
- [] Rename `com.nospam.nospam` applicationId/package before publishing