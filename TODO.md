# TODO List

> Re-checked item by item against `main` at `c060f0c` on 2026-09-17. Items
> ticked or rewritten in that pass say so inline; everything else was confirmed
> still open in the code.

- [] Lists all SMS from other SMS apps before app is installed
- [] Message orders are wrong in conversations after i installed the app on a phone with old messages

  **Both root-caused on 2026-09-14 to one mechanism** — the thread pagination
  cursor. See "Thread pagination strands older messages on imported history"
  below. They are not two bugs; fixing the cursor should close both. Verify
  against a real imported history before ticking either.

## Backfill (Phase 12) follow-ups — clear fixes
- [x] Progress UX: emit `Running(0, total)` when a scan starts — done: `SpamBackfillUseCase.run()` announces `Running(0, total)` before the first batch, `statusProgress` adapts its step to history size, and `SpamBackfillProgressTest` asserts the start tick
- [] Delete the unused `forceScanForTesting()` on `SpamBackfillUseCase`. The properly-scoped API it was waiting for exists: `rescanAll()`, which Settings' "Re-check all messages" already calls via `NoSpamNavHost`. Nothing in `src/` or tests calls the test-named hook any more
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

## Spam routing — agreed model (2026-09-15), supersedes the ratio rule

Decided after working through the mixed-sender case (a shop sending both OTPs
and promos from one number). Replaces `GRADUATION_MIN_SPAM` /
`GRADUATION_MIN_SPAM_RATIO`, which are to be removed.

**Why the ratio rule goes.** It fires only on senders that have demonstrably
sent ham, since that is the only way to reach MIXED in the first place. It also
makes the outcome depend on arrival order: the same shop ends up hidden whether
its first message was the OTP or the promo. And the two errors are not
symmetric — a promo in the inbox costs a glance, a hidden OTP can lock someone
out of an account — so thresholding on a symmetric frequency is the wrong tool.

**Routing. Evaluate top to bottom, first match wins.**

| # | Condition | Folder | Notification |
|---|---|---|---|
| 1 | User explicitly blocked the sender | Spam | none |
| 2 | User explicitly marked the sender not-spam | Inbox | normal |
| 3 | Sender is a contact, or the user has ever sent to them | Inbox | normal for ham; silent + labelled for spam |
| 4 | Sender has ever sent at least one ham message | Inbox | normal for ham; silent + labelled for spam |
| 5 | Only ever spam, exactly one message so far (probation) | Inbox | silent, labelled "Suspected spam" |
| 6 | Only ever spam, two or more | Spam | none |
| 7 | No spam at all | Inbox | normal |

Rows 3 and 4 are the existing MIXED behaviour and are already correct. Nothing
in this table hides a conversation that has ever produced a legitimate message.

**Saved contacts bypass the classifier entirely.** No inference at ingress, no
label, no routing effect. A contact's conversation moves only on an explicit
user block or mute.

This follows from row 1 and 2 of the table rather than being a separate policy:
saving someone to contacts *is* user intent, expressed deliberately before any
message arrives. Letting a heuristic second-guess it would mean "user intent
outranks the classifier" is not actually the top rule. Apple enforces the same
bypass at the platform level — a filter extension is never shown a message from
a number in your contacts — which suggests it is not a close call in practice.

The error costs are lopsided the same way as everywhere else in this model.
Wrongly flagging a message from someone you chose to save is both incorrect and
slightly insulting, and it teaches the user to distrust the label everywhere
else; genuine spam from saved contacts is rare precisely because the user chose
them. Bypass is also the reversible choice: adding an opt-in label later costs
nothing, whereas withdrawing labels you have already shown people about their
friends does not win the trust back.

Bypass does not mean never classifiable. If the user reports a contact's
message themselves, classify it then, on demand.

**A saved contact and a replied-to sender are not the same signal.** Saving is
deliberate; replying can be a single "STOP" to a spammer. The current code
lumps them into one `protectFromSpam` flag. Split them:

| Signal | Classified at ingress | Can be hidden |
|---|---|---|
| Saved contact | no | only by explicit user action |
| Replied-to sender | yes | no — label only |

Revisit only on evidence, not argument: if real users report wanting messages
from saved contacts flagged, add the opt-in label toggle, which is safe because
it can only add a warning and never hide anything.

**What clears what.**

- Explicit block or allow: cleared only by an explicit user action. Survives
  thread deletion.
- Automatic spam state: cleared by any ham message, by the user replying, or by
  adding the sender to contacts.
- **An implicit action must never undo an explicit one.** Replying "STOP" to a
  spammer is common, so a reply must not clear a user's own block. Saving a
  blocked number to contacts must not unblock them either.

**Two codebase-specific notes.**

- `ConversationsRepository.deleteConversation` already leaves `sender_state`
  intact while deleting the thread's verdict rows. That is the correct
  behaviour and must stay.
- Because of that, the "has ever sent ham" test must read
  `sender_state.hamCount`, which survives deletion, and never derive from
  `message_verdict` rows, which are deleted with the thread. Getting this
  backwards silently disarms the protection when a user tidies up.

**Migration.** Existing `sender_state` rows that reached SPAM through
graduation must be re-derived on upgrade, or those users keep a hidden
conversation the new rules would never have hidden.

- [] Remove the graduation constants and the ratio branch from `ThreadSpamPolicy`.
- [] Add the probation state (row 5) — a first-ever message that classifies as
  spam stays in the inbox, silent and labelled, instead of being hidden.
- [] Make SPAM non-sticky against ham: a ham message from a SPAM sender moves it
  back to the inbox as MIXED.
- [] Add the "reply clears automatic spam state, never an explicit block" rule.
- [] Re-derive graduated SPAM rows on upgrade.

### Settings: what to expose, and the rule for deciding

Every toggle multiplies the number of states that have to be reasoned about and
tested, and in a spam filter the failure mode is a hidden message. So:

**A setting may only ever make the filter hide less. Never more.** Any toggle
that can cause a conversation to be hidden that otherwise would not be, is a new
way to lose an OTP.

Worth having:
- [] **Manage blocked and allowed senders** — a real gap, not a preference.
  Sticky rules are keyed to the sender and survive thread deletion, so today a
  user can block a number, delete the thread, and have no way to find or undo
  that rule. There is no such screen in `SettingsScreen.kt` today.
- [] **"Warn about suspicious messages from contacts"** (default OFF, and only
  if users ask for it). Contacts bypass the classifier by default, so this
  toggle turns labelling on. Safe under the rule above because it can only add
  a warning, never hide a message. Not worth building speculatively.
- Master spam protection on/off — already exists (`SpamPreferences.isEnabled`).

Deliberately not offering: sensitivity sliders or aggressive/balanced/relaxed
presets. Users cannot reason about a threshold they cannot see the effect of,
and each preset needs its own correctness argument and test matrix.

## Found during the E2E wave (2026-09-14) — verified, not yet fixed

### Thread pagination strands older messages on imported history  — user-visible

This is the single mechanism behind both long-standing bugs at the top of this
file: messages predating install not listed, and wrong ordering with existing
history.

`RealTelephonyDataSource.queryMessages` orders by `DATE DESC` but filters
`_ID < beforeId`, and `ThreadViewModel.loadOlder` passes `min(_id)` of the
loaded messages as that cursor. Row ids follow provider insertion order; dates
follow when a message was sent. On organic traffic the two agree, which is why
this never shows up on a fresh phone. The moment history is imported, restored
or backfilled out of order, they diverge, and any message that is older but was
inserted later carries a higher row id and is excluded permanently. It does not
load slowly — it is unreachable.

Reproduced on-device with a fixture of 200 recent messages inserted first and 10
genuinely older ones inserted after: scrolling back stops dead, no spinner, no
error.

- [] Fix: make the cursor composite — `(date, _id)` with `_id` as tiebreaker —
  so the pagination key matches the sort key. Touches `TelephonyDataSource`
  (interface + KDoc), `RealTelephonyDataSource`, `ThreadViewModel`,
  `FakeTelephonyDataSource` in `core:testing` (it mirrors the same `_id` contract
  and would silently diverge from an impl-only fix), and their tests.

### Bulk spam actions are irreversible and unconfirmed  — resolved by removal

**Resolved 2026-09-15 in `6898a35` (PR #8):** the "Block all" / "Delete all"
row and the "deleted automatically after 30 days" banner were removed from
`SpamScreen`, so the unconfirmed hard delete no longer exists. Per-conversation
Not spam / Block / Delete remain on swipe and long-press. The analysis below is
kept because it is the rule any future bulk action must meet — including
multi-select in the UI polish work (`docs/UI-POLISH-PLAN.md`).

- [x] **`.maestro/flows/spam_notspam_and_bulk.yaml` was stale** — rewritten
  2026-09-17 with Spam selection mode (`docs/UI-POLISH-PLAN.md` P1.3): it now
  asserts the bulk row is gone, a confirmed Delete, swipe to Not spam and a
  two-row Not spam. Passing. `settings_dialogs_and_switches.yaml` still carries
  a comment mentioning the removed button; harmless.
- The confirmation rule below is now implemented for multi-select Delete and
  Block in Inbox, Archived and Spam & blocked (count in the title; Block states
  that it reaches the system blocked-numbers list and calls).
- The "Empty Spam" button left in `SpamScreen` renders only in preview/fake mode
  (`!isLive`) and deletes nothing real. Not a finding, noted so it is not
  mistaken for a surviving bulk action.

Original finding, for the record — `ConversationsScreen`'s Spam & Blocked bulk row ran
`spamList.forEach { onDelete(it.threadId.value) }`, which reaches
`telephony.deleteConversation` — a hard delete from the system SMS provider. No
confirmation, no undo, no tombstone.

Three things make this worse than an ordinary delete button:
- The banner directly above it promises spam is deleted automatically after 30
  days. The screen contradicts its own stated retention policy.
- The list is machine-generated, not user-curated. "Re-check all messages"
  re-runs the classifier over all history and readily flags repetitive templated
  text, so the set being deleted was never reviewed by anyone.
- "Block all" writes to `BlockedNumberContract` while the app holds the default
  SMS role, so it is system-wide, applies to calls, and survives uninstall.

Observed for real: running a re-check and then a bulk action in one session
destroyed most of the seeded test fixtures.

The fixes proposed here no longer have a button to attach to. They stand as the
rule for any bulk action that is reintroduced (multi-select Delete / Block):

- Confirmation dialog naming the count before a bulk delete.
- Confirmation for a bulk block that states it also blocks calls and persists
  after uninstall.
- Consider an undo snackbar for Block, which is reversible. Delete is not, so
  confirmation is the only guard available there.

### Compose instrumented tests cannot run on API 37  — tooling

All 20 Compose UI tests fail with
`NoSuchMethodException: android.hardware.input.InputManager.getInstance`.
Espresso's UI controller reflectively calls a method that no longer exists on
API 37. Not app logic — the emulator is newer than the test libraries.

The nine `Sqlite*Dao` suites and `SqliteNoSpamOpenHelper` are unaffected and
pass 44/44 on the same device, so the storage layer is now verified.

- [] Either bump Espresso and the Compose test artifacts, or keep a second AVD
  on an older API for UI tests. Decide before writing the E2E runner script,
  since the runner has to target whichever combination works.

## Removed in the cleanup pass (2026-09-15) — implement properly if wanted

- [] **"Mark all as read"** — the drawer item was removed. Its `onClick` only
  closed the drawer; it had never done anything. A menu item that silently does
  nothing is worse than no menu item, so it is gone rather than left lying.
  Re-add it when it is actually implemented. Note it is a bulk action over every
  conversation with no natural undo, so it should follow the bulk-action
  confirmation rule recorded under "Bulk spam actions" above.

- [] **`ACTION_SENDTO` handling is advertised but not implemented.** The
  manifest claims `sms:`, `smsto:`, `mms:` and `mmsto:` so other apps can hand
  off "compose SMS to X", and `MainActivity.handleSendToIntent()` normalizes the
  address and then only logs it, under a comment claiming NavHost deep-linking
  is wired. It is not. Left in place deliberately: removing the intent filter
  would drop a real capability, and wiring the navigation is feature work rather
  than cleanup. Until it is done, another app handing off to NoSpam gets a cold
  inbox.

## Thread draft carryover — analyze before coding (2026-09-15)

Found while black-box unit testing the new long-press message actions
(Copy/Delete/Share/Forward, `ThreadScreen.kt`/`ThreadViewModel.kt`). A related
but distinct bug in the same area — `forwardBody` being dropped entirely on
`ThreadViewModel`'s no-`dataSource` path — was already fixed. This one is
left open deliberately.

`ThreadViewModel.loadThread(id, address, context, forwardBody)` only ever
overwrites `uiState.draft` in two cases: `forwardBody != null` (synchronous),
or a persisted draft is found via `DraftStore.load(context, id)` on the live
path (`dataSource != null`), and only if that thread actually has a saved
draft. If neither applies — the common case of a plain reload with
`forwardBody == null` and no persisted draft for the *target* thread — nothing
in `loadThread` clears `draft` at all. The live-path reset
(`_uiState.value.copy(threadId = id, messages = emptyList(), ...)`) uses
`.copy()`, which carries the previous value of `draft` forward untouched.

Concretely: open thread A, type (or forward) text into the compose bar
without sending, navigate to thread B, which has never had a saved draft —
thread B's compose bar shows thread A's leftover text, and nothing on this
path ever resets it to `""`.

This predates the Copy/Delete/Share/Forward work — it is pre-existing
`ThreadViewModel` behavior — but Forward makes it easier to trigger, since it
deliberately populates a draft that the user may then navigate away from
without sending.

**Analyze again before touching `ThreadViewModel` code here.** In particular:
whether a fresh thread with no persisted draft should explicitly reset to
`""` up front, and whether doing so can race the async `DraftStore.load` (it
runs in `viewModelScope.launch`, so an eager synchronous reset plus a later
async overwrite needs to be ordered correctly, not just patched to "clear
first").

- [] Investigate and fix: draft carries over between threads when neither
  `forwardBody` nor a persisted `DraftStore` entry exists for the
  newly-opened thread. `feature/thread/src/main/kotlin/com/nospam/nospam/feature/thread/ThreadViewModel.kt`, `loadThread()`.

## Project-wide
- [] Re-verify the Room/KSP constraint in CLAUDE.md §11 on the current toolchain (AGP 9.4.0, KSP 2.3.6). It was verified on AGP 9.0.1 / KSP 2.3.2; the recorded condition for revisiting is "a KSP release supporting AGP built-in Kotlin". Not checked yet — do not assume either way
- [] perf: `SpamStateWriter.upsertAllIfNotOverridden` does one `getByAddress` per address per flush — batch `IN (...)` read under the lock
- [] Add instrumented tests for `core:telephony` provider query/write logic. Two device suites exist (`TelephonyInstrumentedTest`: one SMS insert/query round trip plus a notification build; `TelephonyMapperDeviceTest`: two `ContentValues` mappers) but nothing covers pagination, delete, mark-read or the SIM path. The thread pagination cursor bug above is exactly the kind this would have caught
- [] MMS: extend history scan to MMS when the MMS-parsing architecture is ready (currently SMS-only in backfill)
- [] Rename `com.nospam.nospam` applicationId/package before publishing