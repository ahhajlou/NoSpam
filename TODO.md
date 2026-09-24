# TODO List

> Re-checked item by item against `main` at `c060f0c` on 2026-09-17. Items
> ticked or rewritten in that pass say so inline; everything else was confirmed
> still open in the code.

- [] Lists all SMS from other SMS apps before app is installed — cursor fix done 2026-09-20 and verified on the seeded emulator fixture; not yet checked against a real imported history
- [] Message orders are wrong in conversations after i installed the app on a phone with old messages — cursor fix done 2026-09-20 and verified on the seeded emulator fixture; not yet checked against a real imported history

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
- [] "Not spam" and "Report spam" overwrite the sender's counts. `markSendersNotSpam` writes a fresh `sender_state` (spamCount and hamCount 0) and `markSendersSpam` writes spamCount 1, hamCount 0, so the sender's history is lost. Seen 2026-09-23 through the new senders page: removing an allow can then only return the sender to CLEAN, never MIXED. Safe (it can only hide less) but it throws evidence away; keep the counts and change only state and override
- [] Re-derive graduated SPAM rows on upgrade.

### Settings: what to expose, and the rule for deciding

Every toggle multiplies the number of states that have to be reasoned about and
tested, and in a spam filter the failure mode is a hidden message. So:

**A setting may only ever make the filter hide less. Never more.** Any toggle
that can cause a conversation to be hidden that otherwise would not be, is a new
way to lose an OTP.

Worth having:
- [x] **Done 2026-09-23 (phase 2, P2.6):** Settings → Spam protection → Blocked and allowed senders lists this app's blocks, Android's system block list and "Not spam" senders, each undoable. Original entry: **Manage blocked and allowed senders** — a real gap, not a preference.
  Sticky rules are keyed to the sender and survive thread deletion, so today a
  user can block a number, delete the thread, and have no way to find or undo
  that rule. There is no such screen in `SettingsScreen.kt` today.
  **Scheduled: phase 2 step 6** (`docs/UI-POLISH-PLAN.md` §3).
- [] **"Warn about suspicious messages from contacts"** (default OFF, and only
  if users ask for it). Contacts bypass the classifier by default, so this
  toggle turns labelling on. Safe under the rule above because it can only add
  a warning, never hide a message. Not worth building speculatively.
  **Decided 2026-09-22:** the disabled placeholder row phase 1 added is removed
  in phase 2 step 3. It comes back, if ever, with the routing rework above,
  because until contacts actually bypass the classifier it has nothing to turn on.
- Master spam protection on/off — already exists (`SettingsRepository.spamProtection`).

Deliberately not offering: sensitivity sliders or aggressive/balanced/relaxed
presets. Users cannot reason about a threshold they cannot see the effect of,
and each preset needs its own correctness argument and test matrix.

Also deliberately not offering (decided 2026-09-22):
- **"Auto-delete spam after 30 days."** Phase 1 added it as a disabled row; phase 2
  step 3 removes it. It would hide (in fact delete) more, which the rule above
  forbids, and a 30-day auto-delete was already removed once as contradictory
  (see "Bulk spam actions are irreversible and unconfirmed" below). Deleted SMS
  cannot be recovered. `SpamRepository.pruneOldSpam` is unrelated: it drops old
  automatic verdict rows and never touches messages.
- **"Use simple characters"** (strip accents so a message fits GSM-7). The usual
  implementation (NFD, then drop combining marks) also strips Persian harakat,
  and Persian is sent as UCS-2 whatever we do, so for the first audience it
  would damage text and save nothing. Revisit only with a GSM-7-aware
  transliteration table that leaves non-Latin scripts alone.

## MMS — its own project, not started (recorded 2026-09-22)

MMS today is a stub: `core/telephony/.../receiver/MmsReceiver.kt` answers
`WAP_PUSH_DELIVER` by inserting a fake SMS row from "MMS" reading "Media message
not supported yet". There is no PDU parsing, no `downloadMultimediaMessage`, no
MMS sending, no attachments. It was kept out of phase 2 on purpose: it needs its
own architecture and a device on a carrier with a working MMSC to verify.

Settings that depend on it stay **visible but disabled**, labelled as needing MMS
support, until this lands: auto-download MMS, auto-download while roaming, and
group messaging (which is sent as MMS). Scope when it starts:
- [] Parse the WAP push notification indication, download through
      `SmsManager.downloadMultimediaMessage` (per subscription), write to the
      provider's `mms` tables, notify like SMS. Respect the auto-download and
      roaming settings.
- [] Classify MMS text parts through the same ingress and spam policy as SMS.
- [] Render MMS in the thread (text + image parts first), and read MMS rows in
      the inbox and the history backfill (see "Project-wide" below).
- [] Send MMS: attachment picker in the compose bar (the dead attachment button
      was removed in phase 1), `sendMultimediaMessage`, group conversations.
- [] Persist the group-messaging choice per SIM (today `rememberSaveable` only).

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

- [x] **Fixed 2026-09-20; verified on the emulator.** Replaying the app's two queries on the seeded thread 80 stranded 10 of its 210 messages; after the fix, scrolling to the top of that thread shows all 10 "OLD HISTORY" rows. `getMessages` now takes the oldest loaded `Message` as its cursor and pages by `(date, _id)`; the fake mirrors it, and `ThreadViewModel` trims its sliding window by id rather than position. Original plan: make the cursor composite — `(date, _id)` with `_id` as tiebreaker —
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

### Compose instrumented tests cannot run on API 37  — tooling — **resolved 2026-09-20**

Compose UI tests failed with
`NoSuchMethodException: android.hardware.input.InputManager.getInstance`.
Espresso's UI controller reflectively calls a method that no longer exists on
API 37. Not app logic — the emulator is newer than the test libraries.

Resolved by removing the need rather than the failure. Every Compose
`androidTest` suite duplicated a JVM suite, so in P1.9 the device-only
assertions were ported into the Robolectric suites and the instrumented copies
deleted; `feature:conversations`, `feature:thread`, `feature:settings` and
`core:designsystem` no longer have an `androidTest` source set. Nothing is
waiting on an Espresso bump or a second AVD, and screen coverage is gated on
every build instead of on an emulator.

The storage suites (nine `Sqlite*Dao` plus `SqliteNoSpamOpenHelper`, 44/44) and
`core:telephony`'s were never affected and stay on the device — see CLAUDE.md §9
for when a new instrumented test is the right call.

## Removed in the cleanup pass (2026-09-15) — implement properly if wanted

- [] **"Mark all as read"** — the drawer item was removed. Its `onClick` only
  closed the drawer; it had never done anything. A menu item that silently does
  nothing is worse than no menu item, so it is gone rather than left lying.
  Re-add it when it is actually implemented. Note it is a bulk action over every
  conversation with no natural undo, so it should follow the bulk-action
  confirmation rule recorded under "Bulk spam actions" above.

- [x] **Done 2026-09-23 (phase 2, P2.2):** `SENDTO` opens a conversation with the
  recipient and any `sms_body`, cold or warm; a notification tap opens its
  thread too, which it never did. Checked by `tools/launch_intents_check.sh`.
  Original entry: **`ACTION_SENDTO` handling is advertised but not implemented.** The
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
or a persisted draft is found via `DraftRepository.load(id)` on the live
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
`""` up front, and whether doing so can race the async `DraftRepository.load` (it
runs in `viewModelScope.launch`, so an eager synchronous reset plus a later
async overwrite needs to be ordered correctly, not just patched to "clear
first").

- [x] **Not reproducible through navigation (checked 2026-09-20 on the emulator).** Typing an unsent draft in one thread, going back and opening another leaves the second compose box empty: `composable<ThreadRoute>` creates its `ThreadViewModel` per back-stack entry, so no instance is ever reused across threads. The "one VM can serve successive routes" comment in `ThreadViewModel` is out of date. The code path described above still exists if a VM were ever shared; original item: draft carries over between threads when neither
  `forwardBody` nor a persisted `DraftRepository` entry exists for the
  newly-opened thread. `feature/thread/src/main/kotlin/com/nospam/nospam/feature/thread/ThreadViewModel.kt`, `loadThread()`.

## Drafts in the inbox — agreed model (2026-09-18)

Today a draft is invisible outside its own thread: drafts are stored per thread
id (`DraftRepository`, `core:data`, since 2026-09-23), the inbox shows no sign of it, and
`Conversation.hasDraft` exists but is never set or rendered.

**Verified on the emulator (Google Messages 20260331, as default SMS app):**
typing a draft and leaving the thread moves that conversation to the **top** of
the inbox, above the newest received message, and its preview reads
`You: <draft text>` with a **"Draft"** label. Notably, `content://sms` has **no
`type=3` (draft) rows** afterwards — Messages keeps drafts in its own database,
not the system store. So the ordering and the label are the app's own doing, and
we can match the behaviour without writing to the provider.

Agreed for a later phase:
- [] Show a "Draft" marker and the draft text as the preview in the inbox row.
- [] Sort a conversation with a draft by when the draft was saved, so it rises
  to the top like Messages does. A half-written message is the conversation the
  user is most likely to return to.
- [] Set `Conversation.hasDraft` from the draft store rather than leaving the
  field unused.
- [x] Needs a home the inbox can read. **Done 2026-09-23 (phase 2, P2.1):**
  `DraftRepository` in `core:data`, same DataStore file and `draft_<id>` keys,
  with `observeAll()` for the inbox. The old `DraftStore` in `feature:thread` is
  gone; the draft-carryover bug below still has to be fixed against the new
  class.
- [] Decide then whether to also write drafts to the provider as `type=3`. It
  would make drafts visible to other SMS apps and survive a reinstall, which
  Messages does not bother with; weigh that against a second source of truth,
  which CLAUDE.md §4 warns about.

## Project-wide
- [~] Reply on the conversation's own SIM. **Done 2026-09-20 for threads with history:** `Message.subscriptionId` is now read from the provider and `ThreadViewModel` defaults the SIM picker to the SIM the thread last used (a manual pick sticks). **Done 2026-09-23 (phase 2, P2.7):** a thread with no SIM history now starts on the system default SMS SIM when it is active (unit-tested; the emulator has one SIM, so not seen on a device). **Still open:** when the SIM list is empty (phone permission missing, or single SIM) `sendMessage` still passes no subscription and `resolveSmsManager` falls back to the system default. Not tested on a dual-SIM device — the emulator has one SIM; the selection logic is covered by `ThreadViewModelContextTest` only
- [] Re-verify the Room/KSP constraint in CLAUDE.md §11 on the current toolchain (AGP 9.4.0, KSP 2.3.6). It was verified on AGP 9.0.1 / KSP 2.3.2; the recorded condition for revisiting is "a KSP release supporting AGP built-in Kotlin". Not checked yet — do not assume either way
- [] perf: `SpamStateWriter.upsertAllIfNotOverridden` does one `getByAddress` per address per flush — batch `IN (...)` read under the lock
- [] Add instrumented tests for `core:telephony` provider query/write logic. Two device suites exist (`TelephonyInstrumentedTest`: one SMS insert/query round trip plus a notification build; `TelephonyMapperDeviceTest`: two `ContentValues` mappers) but nothing covers pagination, delete, mark-read or the SIM path. The thread pagination cursor bug above is exactly the kind this would have caught
- [] MMS: extend history scan to MMS when the MMS-parsing architecture is ready (currently SMS-only in backfill)
- [x] **Fixed 2026-09-23:** both ViewModels now start at `null` ("loading"), and both pages show the inbox's skeleton rows until the first load, then the list or the empty state. Spam had the same bug. Original entry: **Archived (and probably Spam) says "Archive is empty" while it is still loading.** `ArchivedViewModel` starts its `stateIn` from `emptyList()` (`feature/conversations/.../SpamViewModel.kt:25`) and `ArchivedScreen` shows the empty state for any empty list, so there is no loading state. Found 2026-09-23 because `archived_unarchive`, the first flow `tools/run-e2e.sh` runs after installing a new build, failed twice with an empty Archived page; the first load after an install is slow enough to outlast Maestro's wait. Rerun alone, and with the runner's exact install/role/grant/reseed sequence, it passes. (An earlier guess, that seeding ran before the database existed, was wrong: the rows were there.) Fix: a nullable or `Loading` initial state and the list skeleton the inbox already has; check `SpamViewModel` for the same
- [] `tools/persistence_check.sh` sends its test SMS as `NSTEST_UNBLOCK1` through `adb emu sms send`, but the emulator console keeps only a sender's digits (verified 2026-09-23: `NSTEST_NOTIF1` arrived as address `1`), so the script's address checks are probably not testing what they say. It also taps `"Menu"`, which phase 1 renamed to "Open navigation menu". Re-check it before relying on it; `tools/launch_intents_check.sh` uses a numeric sender for this reason
- [] Rename `com.nospam.nospam` applicationId/package before publishing
- [] Onboarding does not react to permissions granted outside the app. Fresh install → onboarding shows → user grants the permissions from system Settings (App info → Permissions) instead of the in-app dialog → returns to the app: onboarding still shows the old state and the inbox is never reached until the app is force-closed and reopened. Check first: `NoSpamNavHost`'s resume check (CLAUDE.md §6) may only route *to* onboarding when permissions are missing and never route *away* from it once they are all granted, and the onboarding screen may compute its granted state once instead of re-reading on `ON_RESUME`. Expected: on resume, re-evaluate `requiredPermissions()` and continue to the inbox (or to the next step, the default-SMS role) without a restart. Not covered today — `tools/permission_gate_check.sh` only tests the revoke → resume direction; add the grant → resume direction there and a Robolectric test on the onboarding screen. Searched TODO.md, TASKS.md, REVIEW.md and docs/ on 2026-09-20: no existing report of this
- [] Sideloaded installs hit Android's "restricted settings" block, with no in-app explanation. Verified 2026-09-20 on a Galaxy A26: the release APK downloaded from GitHub and installed from Samsung My Files (not a store, so Android restricts SMS-related permissions until the user allows it) showed "App was denied access to be default SMS app… restricted permissions", and the permission requests were declined twice so onboarding read "Android will not ask again". Play Protect's "This app looks safe" is a separate malware scan and does not lift it. The app cannot remove the restriction; only a store installer (Play, F-Droid, possibly Galaxy Store) or adb avoids it. Two pieces to build, both for the GitHub-APK route:
  - [] Onboarding hint: when a required permission is permanently denied, or the default-SMS role request comes back denied, show the steps — Settings → Apps → NoSpam → ⋮ → **Allow restricted settings**, then grant the permissions and retry. The user may need to attempt a grant once before the ⋮ entry appears. Add English and Persian strings (each module ships its own, `feature:onboarding`), and test with real Persian text. It can only be shown as advice: there is no API to detect the restricted state, so key it off "denied twice" / role denied.
  - [] README and release notes: put the same steps next to the GitHub download link so people see them before installing. Point `docs/` and the release workflow's notes text at one copy rather than restating it.
  - [] Later, not decided: F-Droid (main repo, or a self-hosted repo on GitHub Pages) is the cheapest way to remove the step altogether. Try a self-hosted repo install on a phone before promising it works. Google Play needs the default-SMS declaration form and a developer account; check that it is practical for the Iranian audience first.
- [x] Failed sends: **fixed 2026-09-20; verified on the emulator** (an outgoing message is written as OUTBOX, sent with a sent-result callback, and moves to SENT or FAILED; a failed one shows "Not sent · Tap to retry"). Reproduced first on the emulator: with the radio off the old code stored the message as SENT, never delivered it and never retried, while Google Messages kept it queued ("Waiting to connect") and re-sent it by itself once the radio returned.
- [] Failed sends, still open: we do not retry automatically, only on tap. Also unverified: an OUTBOX row whose result never arrives (process killed and the radio never reports) would show "Sending…" forever
- [x] **Fixed 2026-09-21; verified on the emulator.** The Threads fast path failed on both the API 37 emulator and the Samsung A26 (`no such column: message_count`, logged as "Threads query failed, falling back to message scan" on every launch), so every load took the uncached fallback, which reads only the 3000 newest SMS rows. Past that, older conversations vanished from the inbox: with 3564 messages the inbox showed 2 conversations; the control at 463 showed all. Fix: `tryThreadsQuery` reads `content://mms-sms/conversations?simple=true` (the threads table itself; all four columns present on both devices), and its "stop once every thread has its newest message" scan now counts only threads with `message_count > 0` (13 of 282 on the A26 are empty, which kept the scan from ever stopping early). **Kept deliberately:** the newest body/date still come from the `sms` table, not the Threads snippet/date. `8e32889` moved off those because the table can be stale, and it is truncated to whole seconds (`…342000` against the message's `…342899`). **Speed, measured on the emulator with a debug build, 4 cold launches each:** 462 messages, baseline 309-475ms (median ~372) against fixed 361-414ms (~390), equal within noise; 3564 messages, baseline 380-404ms showing 2 conversations against fixed 382-417ms showing all 13. The earlier 4-10s load was a different problem (Perfetto: number-normaliser binder calls, main-thread work, per-conversation contact lookups, `3b4f178`), untouched by this. Not yet measured on the Samsung with a release build (baseline there: 110-174ms, median ~132ms, fallback path). Repeatable check: `tools/seed.sh capflood` (needs root and NoSpam as default SMS app; the live provider DB is `/data/user/0/...`, `/data/user_de/0/...` is an unused stub) then the inbox must list `NSTEST_CAPOLD1`. **Still true:** a thread holding only MMS never matches the early-exit count, so it still costs a full walk of the SMS table; the fallback keeps its 3000-row cap for real provider failures
- [x] **Not reproduced, no change made (2026-09-21).** `getOrCreateThreadId` returning `-1` (then `ThreadRoute(-1, …)` and a shared `draft_-1` key) could not be reproduced: the provider accepts even nonsense recipients (Google Messages' log shows it mapping one to a thread). It can only happen if the provider call itself throws. Left alone rather than guarded speculatively
- [x] **Dual-SIM test report, fixed 2026-09-20** (`730e3ac`): a SIM 1 -> SIM 2 message made two conversations (correct: the provider keys threads by address, as Google Messages does), and (a) the received message sorted before its own sent row because incoming messages stored the carrier's whole-second timestamp instead of arrival time; (b) the sent text came back as a draft because sending never cleared the saved draft; (c) a thread holding only outgoing messages could not be replied to, because the other party was read only from incoming rows. Evidence: sent row `…890202` against received row `…890000`; afterwards the phone's provider held received rows with un-rounded arrival timestamps (`…647312`), which is the only after-fix observation on the phone. Not reproduced before the fix: (b), which rests on reading the code plus the after-fix check
- [x] **Archived and Spam & blocked ordering and pins, matched to Google Messages (2026-09-21).** Checked on the emulator: Messages orders both pages newest message first, whatever order conversations were archived or blocked in (archived MIXED1, FA1, STAR1 newest-first, then PIN1 last: page read MIXED1, FA1, PIN1, STAR1; blocked SPAM1, BULK1, SPAM3: page read BULK1, UNBLOCK1, SPAM1, SPAM3), and it drops a conversation's pin both when archiving it and when blocking it (a pinned, then blocked, then unblocked conversation came back unpinned, and a pinned one sorted last on Spam & blocked). Ours had no sort of its own on either page and kept pins. Now `observeSpam()` and `observeArchived()` sort newest first themselves, `archive()` unpins, `markSenderSpam()` (the user's Report spam) unpins, and `BlocklistRepository.block()` unpins the sender's conversations (it looks them up through `TelephonyDataSource`, now passed in by `AppContainer`). Deliberately not done: automatic spam classification does not unpin, since Messages has no such path and a pin should not vanish from an automated decision; unblocking and un-archiving do not re-pin. Checked in our app on the emulator: a pinned conversation archived then un-archived returns in Recent with no pin. The block and Report spam paths are covered by unit tests only, not driven through our UI
