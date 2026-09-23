# UI polish and complete UI features — working plan

Phase 1 branch: `ui/m3-polish` (from `main` at `c060f0c`), started 2026-09-17,
merged as PR #11 (`c01a7a6`).
Phase 2 branch: `feat/phase2-backend-wiring` (from `main` at `7ed77dd`), started 2026-09-22.

This file is the durable tracker for this work. If a session runs out of
context, resume from the progress log (**§6** for phase 1, **§3.6** for phase 2)
and the first unchecked box in **§3.4**. Update both as work lands. Delete or
archive the file when phase 2 merges.

## 0. Status

**P1 complete 2026-09-20**, merged in PR #11.
**P2 planned 2026-09-22** (§3). P2.1 to P2.6 done 2026-09-23. Next: P2.7, per-SIM number and the default SIM.

## 1. Constraints

- **Phase 1 (this branch) touches presentation only.** Allowed: `feature:*`
  screens and ViewModels/UiState, `core:designsystem`, `:app` shell and
  navigation, string resources, E2E flows and UI tests.
- **Not touched in phase 1:** `core:data`, `core:database`, `core:telephony`,
  `core:ml`, `core:notifications`, `core:model`, repositories, DAOs, receivers.
  No new repository methods, no schema changes.
- UI that needs a capability the backend does not have yet is listed in **§3**
  for phase 2. In phase 1 such UI is shown **disabled** (Q3).
- CLAUDE.md rules still apply: one immutable `XxxUiState` per screen,
  `start`/`end` padding, `Icons.AutoMirrored.*` for direction, per-module strings,
  a real Persian check, light and dark.

## 2. Material 3 audit of the current UI (2026-09-17)

Source review against the Material 3 skill. Ordered by user impact.

| # | Area | Finding | Where |
|---|---|---|---|
| A1 | Structure | One shell `Scaffold` + `TopAppBar` wraps every destination, so Thread, New conversation and Settings all show "NoSpam SMS" and screens cannot own their bar, contextual selection bar or overflow menu. Thread also adds `safeDrawingPadding` under an already-inset scaffold. | `NoSpamAppShell.kt:121` |
| A2 | Actions | Every long press opens an `AlertDialog` full of `TextButton`s. Not an M3 pattern for item actions; no multi-select; no icons. | `ActionsDialog.kt`, all list screens |
| A3 | Components | Search is an `OutlinedTextField` scrolled away with the list, not an M3 search bar. | `ConversationsScreen.kt:154` |
| A4 | Compose bar | `OutlinedTextField` inside a filled pill (two containers); `singleLine = true` for SMS; send active on empty draft; attachment and emoji buttons have `onClick = {}` (dead controls). | `ThreadScreen.kt:228` |
| A5 | FAB | Text-only FAB "Start chat" with a hard-coded shape. | `ConversationsScreen.kt:87` |
| A6 | Navigation | Drawer icons are semantically wrong (Menu = Inbox, Delete = Archived, Warning = Spam); plain `Text` header; top bar title never reflects the destination. | `NoSpamAppShell.kt:54-117` |
| A7 | Theming | `ConversationsScreen` re-wraps itself in `NoSpamTheme` (nested theme). Hard-coded `RoundedCornerShape(8/12/16/24.dp)` instead of `MaterialTheme.shapes`. No dynamic color, no user theme choice. | several |
| A8 | Typography | `labelLarge` is 12sp — that is M3 `labelMedium`; DESIGN.md's `label-lg` was mapped to the wrong slot. Font families are all `FontFamily.Default`; `titleLarge`, `labelMedium/Small`, `bodySmall` fall back to defaults inconsistently. | `Type.kt` |
| A9 | List rows | Hand-built rows instead of `ListItem`; unread shown as an error-red dot on the avatar (error role misused) instead of bold title + primary time/badge; avatar letter taken from the address so numbers render "+"; single avatar color. | `ConversationsScreen.kt:331` |
| A10 | Settings | Flat list with a `HorizontalDivider` under every row, custom rows instead of `ListItem`, "✓" as text, no sub-pages. | `SettingsScreen.kt` |
| A11 | Feedback | Spam screen draws a raw `Snackbar` inside a `Column` that is never dismissed; not using `SnackbarHost`. Bulk/destructive actions lack confirmation (see TODO.md "Bulk spam actions"). | `ConversationsScreen.kt:639` |
| A12 | i18n | Hard-coded English: "Not spam", "Report spam", "Add to contacts", "Call", "Today", "Yesterday", "Pinned"/"Starred"/"Muted" content descriptions, contacts permission banner. Date header ignores locale. | Thread, Conversations |
| A13 | Semantics | "Suspected spam" is an `AssistChip` with a no-op click (announces as a button). Empty-archive illustration uses a Delete icon. | `ThreadScreen.kt:130`, `:463` |
| A14 | Toolchain | Compose BOM `2024.09.00` (material3 1.3.0) predates the current search bar APIs, expressive components and `MotionScheme`. | `libs.versions.toml` |

## 3. Phase 2 — backend wiring

### 3.1 Scope and what changed since phase 1

Phase 1 collected here the backend its UI was built ahead of: per-SIM
preferences, a user-entered SIM number, theme persistence, contact name and
photo for a thread opened without a `Conversation`, bulk operations as single
calls, a manage-blocked-and-allowed-senders screen, message sounds and swipe
configuration. Phase 2 wires them.

The code moved after PR #11, and the plan is built on the current state:

- **One send path** (`bd867bd`): `SmsSender.send` in `core:telephony`, used by
  both `RealTelephonyDataSource.sendMessage` and `HeadlessSmsSendService`; rows go
  `OUTBOX` → `SENT`/`FAILED` through `SmsSentReceiver`. Its `deliveryIntent` is
  always `null`. Any per-SIM send option lands here.
- **Reply on the thread's SIM** (`bd867bd`): done for threads with history. A new
  conversation still starts on the first SIM, not the system default (TODO.md
  "Project-wide").
- **Pin-drop side effects** (`f04112a`): `archive()`, `markSenderSpam()` and
  `BlocklistRepository.block()` unpin; Archived and Spam sort newest first. Bulk
  calls must keep these.
- **Threads table read, paging by date, arrival-time timestamps** (`fbdac17`,
  `730e3ac`, `bd867bd`).
- **SPDX headers** (`09a5457`): every new file needs one; `REUSE.toml` for assets.

Found while planning, in scope because they are how a thread gets opened
without a `Conversation`:

- Tapping a message notification does not open its thread: the content intent
  carries a `thread_id` extra that nothing in `:app` reads.
- `ACTION_SENDTO` is advertised but `MainActivity.handleSendToIntent` only logs.
- `SettingsViewModel.simById()` reads `_uiState.value` once, before the SIMs
  have loaded, so a SIM page can open with no SIM.

### 3.2 Decisions (2026-09-22)

User decisions:

- **Auto-delete spam** row: removed. It would hide more (TODO.md "Settings"), and a
  30-day auto-delete was removed once already.
- **MMS**: out of scope, its own project in TODO.md. The MMS rows (auto-download,
  roaming, group messaging) stay visible but disabled.
- **Warn about suspicious messages from contacts**: removed. The spam-routing
  rework it depends on stays a separate project.
- **Message sounds** and **configurable swipe actions**: built.
- **Tests** follow the `blackbox-unit-tests` skill. The test-writing agent runs on
  Sonnet by default and on Opus for P2.5, P2.6 and P2.8, where the contract has
  the most edge cases. Spec packets and failure triage stay with the main session.

Design decisions:

- **D1 — preferences get a capability module, `core:preferences`.** It has no
  project dependencies (the untyped store needs none) and exposes `PreferencesDataSource` (DataStore behind it),
  with a fake in `core:testing`. `core:data` adds `SettingsRepository` and
  `DraftRepository`; features and `:app` use only those. Existing file names and
  keys are kept (`settings`: `spam_protection_enabled`, `history_backfill_pending`,
  `install_id`; `drafts`: `draft_<id>`) so installed apps keep their values, and
  the old `preferencesDataStore` delegates are deleted in the same commit — two
  delegates on one file crash at runtime. New files: `ui_settings`, and
  `sim_settings` keyed `sim_<subId>_*`. `ThemeSetting` lives in `core:model`;
  `:app` maps it to the designsystem's `ThemeMode`.
- **D2 — send options reach `core:telephony` without it reading preferences.**
  `SendOptions(deliveryReport)` is a parameter of `TelephonyDataSource.sendMessage`,
  passed on to `SmsSender.send`. `HeadlessSmsSendService` cannot take a parameter,
  so `core:telephony` defines `fun interface SendOptionsProvider` and a
  `SendOptionsRegistry` that `NoSpamApplication.onCreate` fills from
  `SettingsRepository` (`Application.onCreate` always runs before a service
  starts). Telephony keeps its own manifest and never depends on another
  capability module.
- **D3 — "simple characters" dropped**, reasoning in TODO.md "Settings".
- **D4 — the drawer opens from the menu button only** on list screens
  (`gesturesEnabled = drawerState.isOpen`; dragging and the scrim still close it).
  Frees both swipe directions on rows, fixes the conflict that already exists
  with the Archived and Spam row swipes, and keeps clear of the system back
  gesture. Not a preference.
- **D5 — `allowBackup="false"`.** The database, drafts and the new SIM settings
  hold phone numbers and message text, subscription ids do not carry to a new
  device, and a restored `install_id` breaks its reset-on-uninstall promise.
  Closes ARCHITECTURE-REVIEW P-1 and REVIEW L-13. Selective backup of
  `ui_settings` can be added later if wanted. *Corrected while implementing:*
  `allowBackup="false"` does not stop device-to-device transfer on Android 12+,
  so `data_extraction_rules.xml` is rewritten to exclude every domain from both
  cloud backup and device transfer, not deleted; only `backup_rules.xml` goes.

### 3.3 Rules for every step

- One commit per step on `feat/phase2-backend-wiring`; each builds and passes on
  its own. Push and PR only when asked; split into several PRs at step
  boundaries if the branch grows too large to review.
- Unit tests through `blackbox-unit-tests`: spec packet from the step's spec
  bullets → isolated test writer that never reads the implementation → every
  failure triaged as bug, spec gap or wrong assumption.
- `./gradlew build` and `./gradlew :koverXmlReport :koverVerify` green; the
  step's device check; `tools/run-e2e.sh` stays 8/8 plus any new flows.
- SPDX header on new files, strings in en and fa, CLAUDE.md rules (one
  `XxxUiState`, start/end padding, fakes in `core:testing`, no mutable
  `PendingIntent` without an explicit component).

### 3.4 Work breakdown

- [x] P2.0 This plan, and TODO.md: MMS project, removed settings, decisions.
- [x] P2.1 **`core:preferences` foundation, backup off.** No visible change.
      New module (capability tier), `SettingsRepository`, `DraftRepository`,
      `FakePreferencesDataSource`. Callers migrated: `AppContainer`,
      `NoSpamApplication`, `NoSpamNavHost`, `DebugTools`, `SettingsPages`,
      `ThreadViewModel`. Deleted: `SpamPreferences.kt`, `DraftStore.kt`, the
      template backup XMLs.
      Spec: spam protection defaults on and backfill off; `installId` is stable,
      including under concurrent first calls; saving a blank draft removes it;
      `observeAll` follows saves and removals; a read failure returns the default.
      Device: install the previous APK, turn spam protection off and leave a
      draft, upgrade, both survive.
- [x] P2.2 **Notification tap and `ACTION_SENDTO` open the thread.** A pure
      `parseLaunchIntent(action, data, extras): LaunchTarget?` (`Thread(id)` or
      `Compose(address, body?)`); `MainActivity` exposes it as a `StateFlow`,
      including from `onNewIntent`; the NavHost consumes it once, after the
      onboarding gate. The extra's key becomes a constant shared with
      `NotificationHelper`.
      Spec: `sms:`/`smsto:`/`mms:` URIs with an optional `sms_body`; `thread_id > 0`;
      garbage gives null; `smsto:a,b` takes the first recipient; navigates once and
      not again after rotation.
      Device: `adb shell am start -a android.intent.action.SENDTO -d smsto:+15551234 --es sms_body hi`,
      notification tap from cold and warm start. *As built:* `tools/launch_intents_check.sh`
      plus three `_launch_*` flows tagged manual-only, because Maestro can send
      neither an intent nor an SMS.
- [x] P2.3 **Settings cleanup and theme.** Remove the auto-delete and
      warn-contacts rows; MMS rows disabled with "Needs MMS support"; `simById`
      becomes a flow; theme and dynamic color persist and apply (dynamic hidden
      below Android 12); `MainActivity` re-applies `enableEdgeToEdge` with the
      matching `SystemBarStyle` when the theme changes.
      Device: forced Dark on a light system, relaunch, status bar legible; Persian.
- [x] P2.4 **Contact name and photo** in the thread title and inbox rows.
      `TelephonyDataSource.loadContactPhoto(uri, sizePx)`, `Avatar(image: ImageBitmap?)`,
      `ThreadUiState.contact: Participant?`, a small LRU.
      Spec: a thread opened with only an address shows name and photo; no contact
      shows the number and the initial; a failed photo load falls back to the initial.
- [x] P2.5 **Bulk operations as single transactional calls.** Test writer: Opus.
      `NoSpamDatabase.transaction {}`, `*All(ids)` DAO methods, repository
      `archive/unarchive/setRead/setStarred/setPinned/setMuted/delete(ids)`,
      `blockAll`, `markSendersNotSpam`; telephony `deleteConversations` and
      `setRead` as one `thread_id IN (…)` call per 500 ids. Loops removed from
      `ConversationsViewModel` and the NavHost callbacks.
      Spec: archive and block unpin; delete keeps `sender_state`; an empty
      collection does nothing; a failure part-way leaves no partial flags;
      Archived and Spam ordering unchanged.
- [x] P2.6 **Manage blocked and allowed senders.** Test writer: Opus.
      `SenderStateDao.observeUserOverrides()`; `SpamRepository.removeAllow` clears
      the override and re-derives the state through `ThreadSpamPolicy`, keeping the
      counts; `TelephonyDataSource.getSystemBlockedNumbers()`; screen, ViewModel
      and route in `feature:settings`.
      Spec: the list is app blocks plus system blocks, deduplicated by normalised
      address; unblock removes from both; rules survive thread deletion; removing
      a rule never deletes a thread. New flow `manage_senders.yaml`.
- [ ] P2.7 **Per-SIM: the user's own number, and the default SIM.**
      `SettingsRepository.simPreferences(subId)`, `setSimNumber`,
      `TelephonyDataSource.getDefaultSmsSubscriptionId()`. A new conversation
      starts on the system default SMS SIM (closes that half of TODO.md's SIM item).
      Spec: an entered number overrides the carrier's; clearing it falls back;
      values are per SIM.
- [ ] P2.8 **Delivery reports.** Test writer: Opus. `deliveryIntent` to a new
      explicit, immutable, non-exported `SmsDeliveredReceiver`; `STATUS` pending on
      insert; `Message.deliveryStatus`; "Delivered" under the newest delivered
      outgoing message; the headless path through D2.
      Spec: status mapping (0–31 complete, 32–63 pending, 64+ failed); a failed
      part stays failed across a multipart message; the receiver ignores foreign
      URIs and ids ≤ 0; reports off sends no delivery intent.
      Device: send to the emulator's own number; then a real SIM.
- [ ] P2.9 **Swipe actions, and the drawer gesture (D4).**
      `SwipeAction {NONE, ARCHIVE, DELETE, TOGGLE_READ}` per direction, default
      ARCHIVE both ways as in Google Messages; DELETE confirms; ARCHIVE offers undo;
      directions follow the layout (RTL). Archived and Spam keep their fixed actions.
- [ ] P2.10 **Message sounds.** `MessageSoundPlayer` (SoundPool) in
      `core:notifications`, silent when the ringer is silent or on vibrate; bundled
      sounds with licences in `REUSE.toml`; foreground only (`ProcessLifecycleOwner`),
      background stays with the notification channel; the sent sound plays when a
      send is queued.
- [ ] P2.11 **Close-out.** Raise the coverage ratchet to the measured value, run
      the full E2E suite, update CLAUDE.md (§2 module count and tier table, §4
      preferences, bulk and delivery, §6 `ACTION_SENDTO` implemented) and TODO.md.

### 3.5 Open questions

None at the moment. Record new ones here with the answer when given.

### 3.6 Phase 2 progress log

- 2026-09-22 — Research: docs, `git log c01a7a6..HEAD`, settings/theme/send paths,
  blocklist/bulk/contacts/tests. Four scope questions answered (§3.2). Branch
  created. Plan written; TODO.md updated. Next: P2.1.
- 2026-09-23 — P2.1 done. `core:preferences` (capability tier: `PreferencesDataSource`
  + DataStore implementation, one delegate per file), `SettingsRepository` and
  `DraftRepository` in `core:data`, `FakePreferencesDataSource` in `core:testing`.
  `SpamPreferences` and `DraftStore` deleted; the spam page got
  `SpamSettingsViewModel`, and `ThreadViewModel` takes a `DraftRepository`. With no
  process-wide DataStore left in `feature:settings`, its `forkEvery = 1` went too.
  Backup and device transfer off (D5, corrected above).
  Tests: 44 black-box tests written from the spec by an isolated agent (Sonnet),
  all passing first time against the implementation; its one change was a harness
  fix (real time, not virtual, around real file I/O). Build, lint, 390 unit tests,
  coverage 60.89% (ratchet stays 60), all green.
  Device (emulator-5554): installed `main`'s build, turned spam protection off and
  left a draft, upgraded in place to this build: both survived. Package flags no
  longer include `ALLOW_BACKUP`. E2E after a clean install: 7 pass, 1 fail
  (`archived_unarchive`, which passes on rerun). *Corrected in P2.2:* the cause
  is not the database (its rows were there) but the Archived page showing
  "Archive is empty" while it loads, on a slow first launch after install.
  Pre-existing; recorded in TODO.md.
- 2026-09-23 — P2.2 done. `parseLaunchIntent` (pure, `:app` navigation) turns
  `SENDTO`/`VIEW` with `sms:`/`smsto:`/`mms:`/`mmsto:` or our notification's
  `thread_id` into a `LaunchTarget`; `MainActivity` holds it in a `StateFlow`
  (fresh launches and `onNewIntent` only, so rotation cannot replay it);
  `NoSpamNavHost` opens it once, after the onboarding gate, on top of the current
  screen. Found on the way: `MainActivity` had the default launch mode, so a
  notification tap with the app open would have stacked a second copy of the
  app; now `singleTop`. The notification uses the shared `EXTRA_THREAD_ID` /
  `EXTRA_SUBSCRIPTION_ID` constants instead of string literals.
  Tests: 42 black-box tests (Sonnet), all passing first time. Build, lint,
  432 unit tests, coverage 60.97%.
  Device (emulator-5554): `tools/launch_intents_check.sh` passes all four cases:
  cold SENDTO plus rotation, warm SENDTO, notification tap with the app in the
  background, and after the process was killed. Two script issues fixed on the
  way: Maestro needs `LANDSCAPE_LEFT`, and the emulator console strips a sender
  to its digits (`NSTEST_NOTIF1` arrived as `1`), so the check uses a numeric
  sender. The same console behaviour probably undermines
  `tools/persistence_check.sh`; recorded in TODO.md. E2E: 7 pass, and
  `archived_unarchive` failed first again. Diagnosed this time: the Archived page
  shows "Archive is empty" while loading, and the first load after an install
  is slow. Pre-existing, recorded in TODO.md, not fixed here.
- 2026-09-23 — Archived/Spam loading fix (own commit, at the user's request,
  between P2.2 and P2.3). Both ViewModels start at `null` rather than an empty
  list, and both pages show the inbox's skeleton rows until the first load, so
  neither claims to be empty while loading. Spam had the same bug. Tests
  rewritten from the new contract by an isolated agent (one stalled and was
  relaunched).
- 2026-09-23 — P2.3 done. Removed: the auto-delete spam row (it was still there,
  as phase 1's disabled placeholder; the earlier removal was the Spam screen's
  30-day banner) and the warn-about-contacts row, with their strings. Group
  messaging, auto-download MMS and roaming MMS are disabled with "Needs MMS
  support"; the group-messaging dialog, whose choice was never saved, is gone.
  The SIM page now collects the SIM list, so one opened before it loads fills
  in. Theme and dynamic color persist in the new `ui_settings` file and apply as
  `AppCompatDelegate` night mode, set in `NoSpamApplication` before the first
  activity; the designsystem's unused `ThemeMode` went, `ThemeSetting` is in
  `core:model`. The blocking startup read measured 21-38ms on the debug
  emulator, so it only happens when `ui_settings` exists (new
  `PreferencesDataSource.exists`, 1ms).
  Tests: 38 + 1 + 9 black-box tests (Sonnet). The SIM load race needed a seam,
  so `FakeTelephonyDataSource.subscriptionsGate` was added and the test written
  after. 486 unit tests, coverage 63.56%, ratchet 60 -> 63.
  Device (emulator-5554): Dark on a light system and Light on a dark system both
  apply, including status bar icons and the AppCompat emoji picker, and survive
  a relaunch; wallpaper colors apply; the SIM page is correct in Persian (RTL,
  disabled MMS rows, isolated number). Note: `./gradlew build` ran out of daemon
  heap once after several parallel agent builds; `--max-workers=2` passes, as in
  phase 1.
- 2026-09-23 — P2.4 done. Photo URIs already reached the models (`Participant`,
  `Conversation`, `ContactEntry`); what was missing was loading and drawing.
  `TelephonyDataSource.loadContactPhoto` reads the bytes, contacts-provider URIs
  only, capped at 2 MB, bounded by hand because `readNBytes` is API 33+.
  `core:designsystem` gets a `ContactPhotoLoader` interface and
  `LocalContactPhotoLoader`; `Avatar(photoUri = …)` loads through it and falls
  back to the letter while loading, with no loader, or on failure. So the inbox
  rows, the thread header and the new-conversation contact list all show photos
  with no per-feature wiring. `:app`'s `ContactPhotoCache` decodes at the
  avatar's pixel size (`sampleSizeFor`) and remembers hits and misses for the
  process. `ThreadUiState.contactPhotoUri` sits beside `contactName`. As built,
  the plan's `ThreadUiState.contact: Participant?` became that one field.
  Tests: 31 + 1 black-box tests (Sonnet); the fake gained `contactPhotos`,
  `loadedContactPhotos` and `contactPhotoError`. Under this Robolectric setup
  `ImageBitmap(w, h)` throws; tests use `Bitmap.createBitmap(...).asImageBitmap()`.
  Device (emulator-5554): the photo shows in the inbox row and in the header of
  a thread opened by SENDTO with only the number. To reproduce: `adb root`, then
  in `contacts2.db` insert a `data` row (mimetype `vnd.android.cursor.item/photo`,
  `data15` = a small PNG) for the contact's raw contact and set `contacts.photo_id`
  to it, then kill `android.process.acore`. `content read` cannot fetch the photo
  (the provider serves it through `openAssetFile`, not `openFile`); the app can.
- 2026-09-23 — Found while measuring P2.5, fixed on its own: **a deleted
  conversation stayed in the inbox until the app restarted.** The provider does
  notify, and the inbox does re-query, but `tryThreadsQuery` decided whether its
  cache was stale by comparing only the threads still present, so a removal
  never counted and the old list came back. Reproduced with an external `content
  delete` (an external insert showed up within 3s, the delete never did); after
  the fix the row leaves the open inbox. The decision is now two small
  functions, `changedThreadIds` and `inboxChanged`, with 14 black-box tests.
- 2026-09-23 — P2.5 done, measured before and after on the emulator with 50
  conversations and temporary logging (not committed):

  | 50 selected | before | after |
  |---|---|---|
  | Archive | 37 intermediate inbox states over 507ms | 1 state at +138ms |
  | Delete  | 50 concurrent provider deletes + 50 notifications; 1 state at +401ms (with the stale-inbox fix) | 1 provider delete, 1 state at +340ms |

  As built: the four flag DAOs gained `…All(ids)` (one transaction, one publish;
  deletes chunked at 500). Telephony gained `setThreadsRead` and
  `deleteConversations` (`thread_id IN (…)` per 500); the single forms delegate.
  `ConversationsRepository`, `SpamRepository.markSendersSpam/NotSpam` (sender
  states in one `upsertAll`) and `BlocklistRepository.block/unblock(addresses)`
  take whole selections, and the Inbox, Archived and Spam screens hand them over
  in one call. Deviations from the plan, deliberately: atomic per flag table,
  not across tables (archive touches pinned then archived: two publishes, not
  37); no `NoSpamDatabase.transaction {}`; block and unblock still go sender by
  sender inside the call, because Android's block list has no bulk form (they
  used to run as 50 concurrent coroutines). Found and fixed on the way: deleting
  a conversation cleared its archive flag but not pin, star or mute, and the
  provider recycles thread ids.
  Tests: 32 black-box tests written by Opus (6 on the device against real
  SQLite, 26 JVM), none failing. Its "one publish per batch" device test was
  checked by mutation: putting the per-id loop back in `archiveAll` fails exactly
  that test, listing the intermediate states.
- 2026-09-23 — P2.6 done. Settings → Spam protection → Blocked and allowed
  senders: a "Blocked" section (this app's blocklist plus Android's own block
  list, so numbers blocked from the dialer show too, one entry per normalised
  address) and a "Marked not spam" section, each row with the contact's name and
  photo where known and an Unblock / Remove button, no confirmation (both are
  one action to redo). `TelephonyDataSource.getSystemBlockedNumbers`,
  `BlocklistRepository.observeBlockedSenders`, `SpamRepository.observeAllowedSenders`
  and `removeAllow` (TRUSTED override → MIXED with spam history, else CLEAN; counts
  kept). `SettingsItem` gained a `leadingContent` slot for the avatar. As built,
  the plan's `SenderStateDao.observeUserOverrides()` was not needed: the
  repository filters `observeAll`.
  Device: blocked NSTEST_STAR1, deleted its conversation, found the rule on the
  page and unblocked it; marked NSTEST_SPAM1 not spam, found it, removed it.
  Now the permanent flow `manage_senders.yaml`. Found on the way (TODO.md): Not
  spam / Report spam reset the sender's counts, so Remove can only return CLEAN.
  Tests: 41 black-box tests (Opus). One failed, and the test was right: the
  in-memory blocklist DAO kept insertion order while SQLite sorts newest first,
  so the fake now sorts like SQLite. feature:settings tests gained a
  core:database test dependency.

## 4. Phase 1 work breakdown

Order chosen so each step leaves the app building and usable.

- [x] P1.0 Toolchain: Compose BOM `2024.09.00` → `2026.06.01` (material3 1.3.0 → 1.4.0, Compose UI 1.11.4).
      Then, after the user bumped AGP 9.4.0 / Gradle 9.6.0 / KSP 2.3.6: compileSdk 36 → 37
      (platform `android-37.0` installed) and BOM → `2026.09.00` (Compose UI 1.12.1,
      material3 still 1.4.0). targetSdk stays 36.
      Fixes: `feature:thread` and `feature:settings` now declare
      `material-icons-extended` (material3 1.4 no longer brings icons transitively);
      two `context.getString` calls in composables replaced (new lint
      `LocalContextGetResourceValueCall`). Verified: `./gradlew build` green, 246 unit
      tests pass (85 UI-module tests force re-run), kover gate green,
      `assembleDebugAndroidTest` compiles. Instrumented tests not run (no device).
      Carried into P1.3: `rememberSwipeToDismissBoxState(confirmValueChange=…)` is now
      deprecated (Archived and Spam rows).
- [x] P1.1 Design system (`core:designsystem` only, plus 14 mechanical `labelLarge`→`labelMedium` call sites):
      - `Type.kt`: all 15 roles explicit; DESIGN `label-lg` moved to `labelMedium`, `labelLarge` = 14/20 (buttons).
      - `Color.kt`: dark surface/background/surfaceDim tone 10 → 6, surfaceBright 24, inverseOnSurface 20.
      - `Theme.kt`: `NoSpamTheme(darkTheme, dynamicColor = false)`, `ThemeMode { SYSTEM, LIGHT, DARK }` + `isDark()`,
        `isDynamicColorSupported`. Nothing persists these yet (§3).
      - Components: `NoSpamTopAppBar` (+ `TopBarNavigation` None/Menu/Back), `SelectionTopAppBar`,
        `TopBarAction` + `TopBarActions` (≤2 inline + overflow, all 3 inline if no overflow), `TooltipIconButton`,
        `Avatar(name, colorKey, size, selected)` (letter or person icon, stable 6-color palette, check when selected,
        decorative semantics), `SettingsSectionHeader` / `SettingsGroup` / `SettingsItem` / `SettingsSwitchItem`
        (ListItem, grouped rounded card, `enabled=false` for unbacked settings), `ConfirmationDialog`.
      - Strings in `core/designsystem/src/main/res` (en + fa).
      - Visible now: button labels 12sp → 14sp (spec); darker dark-mode page. Everything else unchanged until screens adopt.
      - `ActionMenuDialog`, `PillChip`, `SearchBarPlaceholder` still exist; remove when P1.3/P1.4 stop using them
        (the last two are already unused).
      - Carry to P1.2: when a forced LIGHT/DARK `ThemeMode` lands, `enableEdgeToEdge` must get matching
        system-bar styles, or status-bar icons follow the system instead of the app.
- [x] P1.2 Shell: screens own their `Scaffold`/top bar; drawer stays in shell and
      is opened through a callback; drawer icons and header fixed; top-level
      titles follow the active drawer item.
      - Shell: no Scaffold. Drawer: "Messages" header, filled/outlined icon pairs (Inbox, Archive,
        Report, Settings), debug tools grouped, Settings pinned bottom; gestures only on drawer
        destinations; typed `hasRoute` matching instead of route substrings.
      - Inbox/Settings: own Scaffold + `NoSpamTopAppBar` (Menu, drawer label title, pinned scroll).
        Archived/Spam/debug tools: `DrawerDestinationScaffold` (designsystem, 2 consumers).
        Thread: Back + address placeholder title, Scaffold + consumeWindowInsets + imePadding
        (verified with keyboard open). New conversation: Back + "New conversation", recipient
        field autofocuses (found via E2E: without focus, hardware Enter activated the up button).
        Onboarding: safeDrawingPadding, no bar, no drawer.
      - Screen APIs: `title` (required) + `onOpenDrawer` on Conversations/Archived/Spam/Settings;
        `onNavigateUp` on Thread/NewConversation. Nested NoSpamTheme removed (Conversations, MlDebug).
      - E2E: see progress log. `spam_notspam_and_bulk` left failing on purpose (asserts removed
        bulk UI; rewritten with Spam selection mode in P1.3).
- [x] P1.3 Inbox/Archived/Spam: M3 list items, avatars, unread styling, search
      bar, icon-only FAB, long-press selection mode, contextual app bar, confirmations.
      **Selection mode applies to all three lists** (user, 2026-09-17), not just Inbox:
      Archived (Unarchive, Delete) and Spam & blocked (Not spam, Block/Unblock, Delete),
      each with actions that adapt to the selection, replacing their ActionMenuDialogs.
      **Selection bar shows 3 icons + ⋮** (user, 2026-09-17, matching Google Messages): change
      `SelectionTopAppBar` to pass `maxInline = 3` (normal top bars keep 2) and update
      `ComponentLogicTest`. No floating/centered action menu anywhere; the only menu is the ⋮
      dropdown anchored in the top bar. Delete/Block confirm with a count.
      Done 2026-09-17:
      - Files: `ConversationsScreen.kt` (Inbox only), `ArchivedScreen.kt`, `SpamScreen.kt`,
        `ConversationList.kt` (row, swipe row, skeleton, empty state, `ConversationListScaffold`,
        confirm dialogs, dial/add-contact), `ConversationSelection.kt` (saveable selection, pruning,
        pure `summarize`/`addressesOf`).
      - Row: Avatar 48dp (letter/person icon, per-sender color, check when selected), bold name and
        primary timestamp when unread (no red dot), Mixed/Blocked badges, pin/star/mute icons with
        translated descriptions, secondaryContainer when selected, `semantics.selected`.
      - Inbox bar: Pin/Unpin, Archive, Delete (confirm) + ⋮ Mark read/unread, Star, Mute, Add to
        contacts and Call (single only), Report spam, Block (confirm) / Unblock. Archived: Unarchive,
        Delete. Spam: Not spam, Block/Unblock, Delete. Swipe kept (Unarchive / Not spam), disabled
        while selecting, migrated off deprecated `confirmValueChange` to `onDismiss`.
      - Back clears selection (BackHandler; `activity-compose` added to the module).
      - Search: `SearchBarDefaults.InputField` in a pill with a clear button, still filters inline.
      - FAB: icon-only `AddComment`, content description "Start chat"; hidden while selecting.
      - Empty states for Inbox, Archived (Archive icon, was Delete) and Spam (new).
      - VM: `toggleStar/Pin/Mute` replaced by `setStarred/setPinned/setMuted(ids, value)` over the
        existing `setStar/setPin/setMute`.
      - Fixed on the way: "Unblock" in the old menus called `block()`; now `unblock()`.
      - Removed: the preview-only "Empty Spam" button, the Spam row's inline "Not spam" button
        (swipe + selection replace it), the ActionMenuDialog use on these three screens.
- [x] P1.4 Thread: top app bar with contact name + call + overflow menu, message
      long-press selection mode (same bar: 3 icons + ⋮, e.g. Copy/Forward/Delete, Share in ⋮),
      Done 2026-09-17:
      - Top bar: avatar + contact name (resolved through the existing `lookupContact`; number
        underneath when the name comes from contacts, else the address), Call inline, ⋮ with
        Add to contacts (unknown senders), Archive, Block, Delete conversation. Block and
        Delete conversation confirm; Archive/Block/Delete navigate up afterwards.
      - Messages: long-press selects (Copy, Forward, Delete inline; Share in ⋮; Forward/Share
        single-selection only), tap reveals the time, selected rows highlight full width.
      - Compose bar (`ComposeBar.kt`): multi-line (5), Send disabled while blank, emoji panel
        (`emoji2-emojipicker` 1.6.0) that replaces the keyboard and inserts at the cursor;
        the dead attachment button is gone (MMS attachments are phase 2).
      - Date headers localized (`date_today`/`date_yesterday` + DateUtils), "Suspected spam" is
        a label rather than a clickable chip, its two buttons now use string resources.
      - `NewConversationScreen` split into its own file; `SelectionState`/`PruneSelection`
        promoted to `core:designsystem` (messages are the second consumer).
      - Selection bar shows exactly `maxInline` icons now: the old "one more icon when nothing
        overflows" rule produced 4 icons and no ⋮.
      - Fixed on the way: the app's XML theme was `Theme.AppCompat` (always dark), so the
        emoji picker rendered white-on-white in light mode — now `Theme.AppCompat.DayNight`.
      - Compose UI tests now run on the JVM under Robolectric (conversations + thread), which
        works around the API 37 instrumented failure: merged coverage 38.22% -> 49.69%,
        ratchet raised 37 -> 49. multi-line compose bar, emoji picker (`emoji2-emojipicker`), localized dates.
- [x] P1.5 New conversation screen (2026-09-17): shared `Avatar` for top contacts and the
      contact list, M3 `ListItem` rows, contacts-permission prompt restyled and its two
      hardcoded English strings translated.
- [x] P1.6 Settings (2026-09-17): landing page of sections, each on its own route/page.
      - General: Default SMS app, Notifications, Bubbles, Language + an Appearance group
        (theme, dynamic color, message sounds) shown disabled until phase 2 storage exists.
      - One page per active SIM (`SettingsViewModel` reads `getActiveSubscriptions`): group
        messaging, auto-download MMS, roaming MMS, delivery reports (disabled), and the SIM's
        number with "Not provided by this SIM" when the carrier withholds it.
      - Spam protection: master switch (live), blocked/allowed senders and the contacts-warning
        toggle disabled pending phase 2. Advanced: re-check, auto-delete spam (disabled), data
        notice. About: version, terms.
      - Rows use the designsystem `SettingsGroup`/`SettingsItem`/`SettingsSwitchItem`.
- [x] P1.6b Compose-bar SMS counter (user request, 2026-09-17): `smsLength()` computes GSM-7 vs
      UCS-2 segmentation in pure Kotlin (160/153 vs 70/67, extension chars double); the counter
      shows "remaining/parts" above Send once a second part is near, with a spoken description.
- [x] P1.7 Onboarding (2026-09-20). It is a gate now, not just a first-run screen, so it
      explains itself: app mark, headline, and two steps (permissions, default SMS app) that
      each say what they are for, show a check when satisfied, and carry their own action.
      - Handles the dead end: after two denials Android stops showing the dialog, so the grant
        button did nothing. It now detects that (`shouldShowRequestPermissionRationale` false
        for every missing permission), says Android will not ask again, and opens app settings.
      - States the privacy promise on the screen that asks for access: no internet permission,
        nothing leaves the phone.
      - Scrolls, so two steps plus explanations survive small screens and large font sizes.
        Continue is disabled until both steps are done, rather than hidden.
      - Persian throughout; Robolectric UI tests for the steps, the privacy line, the disabled
        Continue, the granted state, and completion.
- [x] P1.8 Strings and RTL (2026-09-20). The sweep found little hardcoded English left —
      earlier steps translated what they touched — but three real gaps:
      - `core:notifications` had no Persian at all: channel names and the backfill progress
        notification were English on a Persian phone.
      - Relative times were built as `"${'$'}{diff / 60_000}m"`, which is English either way and
        always writes Latin digits. Now plurals, so the digits follow the locale too.
      - The clipboard entry's label (shown by the system clipboard UI) was the literal "sms".
      RTL, found by looking at the app in Persian rather than by grepping:
      - English snippets and message bodies took the layout's direction, which moved their
        full stop to the front (".Meeting moved to 3pm"). Text whose language varies now takes
        its direction from its own content (`TextDirection.Content`): snippets, message bodies,
        names, thread title.
      - Phone numbers are isolated (`isolateIfPhoneNumber`), so a Persian layout cannot move a
        leading "+" to the other end; alphanumeric sender ids are deliberately left alone,
        because isolate characters are still characters and UI tests and flows match ids exactly.
        First cut isolated every address and broke four tests, which is how that was caught.
- [x] P1.9 Tests: one home per screen test, and the dead code they were keeping alive.
      - Every Compose `androidTest` suite was a second copy of a JVM suite, so the
        assertions that existed only on the device side were ported into the Robolectric
        suites and the instrumented copies deleted: conversations (inbox rows + FAB,
        filter chip, search input, thread-id click), thread (send a typed message, tap a
        message for its time, recipient IME action, contact filtering), spam (rows and the
        Blocked label, not-spam from selection, block confirmation), designsystem (avatar
        is decorative). `feature:conversations`, `feature:thread`, `feature:settings` and
        `core:designsystem` now have no `androidTest` source set, and their
        `androidTestImplementation` lines went with it.
      - Instrumented tests kept where the JVM cannot tell the truth: `core:database`
        against real SQLite (44) and `core:telephony` against a real `ContentResolver`
        and `SubscriptionManager` (4).
      - Deleted with their last consumer: `ActionMenuDialog`/`ActionMenuItem` (the
        tap-menu model dropped in Q1), `PillChip` and `SearchBarPlaceholder` (replaced by
        `FilterChip` and `SearchBarDefaults.InputField` at the call sites). Each was
        referenced only by its own test.
      - 339 unit tests, 60.39% line coverage (3470/5746); ratchet 54 → 60. Flows were
        already brought up to date in P1.2–P1.8; re-run to confirm.
- [x] P1.10 Visual check light / dark / Persian RTL on device. Thirty screenshots
      (inbox, selection mode, drawer, archived, spam, thread, new conversation, settings
      landing / general / about) in light-EN, dark-EN and light-FA, driven by a Maestro
      tour kept outside `.maestro/flows` so the E2E runner never picks it up. Four things
      were wrong; everything else matched the design.
      - **The selected drawer item was the least legible row in the drawer.** M3's default
        selected content is `onSecondaryContainer`, which in the Stitch light palette is a
        mid grey: 4.56:1 on the selected pill against 8.84:1 for every unselected row. Now
        `onSurface` (13.3:1), keeping the pill. Dark mode was already right.
      - **A disabled Send looked enabled.** The icon hard-set its tint to
        `onSurfaceVariant`, which overrode the button's disabled color with a
        full-opacity mid-dark arrow. The tint now comes from the button.
      - **New conversation showed "Top contacts", a divider and "All contacts" over
        empty space** on a device with no contacts. Each section is hidden when empty,
        with "No contacts yet" / "No contacts match that" instead.
      - **Its two section headers did not match each other** (one primary, one
        onSurfaceVariant, different padding) or the inbox's. Both now use the inbox
        treatment.
      - Checked and found correct: RTL mirroring throughout (drawer, chips, bubbles,
        FAB, AutoMirrored icons), English text inside a Persian layout keeping its own
        direction, the dark scheme's tonal surfaces, selection mode, the 88dp FAB
        clearance (what looked like an overlap is the FAB floating over mid-scroll
        content, which is standard), and the bubbles' mirrored corner.
      - Fell out of the check, in `tools/seed.sh` rather than the app: the QA contact was
        never created. `content insert` prints nothing on success, so the raw_contact id
        was parsed from an empty string, the function returned before writing the name and
        number rows, and each run leaked one nameless raw_contact (187 by 2026-09-20).
        Contact-name resolution and the "Known" filter were therefore never exercised on
        device. Fixed; `inbox_filters_and_search` now asserts the display name, and its
        "Known" assertion is no longer `optional`.

## 5. Open questions (answers recorded here)

| # | Question | Recommendation | Answer |
|---|---|---|---|
| Q1 | Actions model: option 1 (tap → contextual menu) vs option 2 (long-press → selection mode everywhere) | Option 2 | **Option 2** |
| Q2 | Scope of "don't touch backend": may phase 1 call **existing** repository methods for new UI (pin, mute, archive, block, delete from the thread overflow, bulk via loops)? | Yes — no backend code changes, only calls | **Yes** — may call existing repository APIs; no backend code changes |
| Q3 | UI whose backend does not exist yet: hide, show disabled, or show working-looking but inert? | Build it, hide behind one internal flag until phase 2 | **Show disabled** until phase 2 |
| Q4 | Emoji picker: `androidx.emoji2:emoji2-emojipicker` (new dependency) vs hand-rolled | Official library in a panel that swaps with the keyboard | **emoji2-emojipicker** |
| Q5 | Settings top level: only General / SIMs / Advanced, or also Spam protection and About | Add Spam protection and About | **Add both** Spam protection and About |
| Q6 | Dynamic color (Material You) on Android 12+, plus a theme picker | Keep brand scheme by default, offer dynamic color as a toggle | **Brand default + dynamic toggle**, System/Light/Dark picker (disabled until persistence lands, per Q3) |
| Q7 | Bump Compose BOM to current stable | Yes, first and alone | **Yes**, first and alone |
| Q8 | FAB: icon-only, or extended "Start chat" that collapses to icon on scroll | Extended → collapses on scroll | **Icon-only FAB** |

## 6. Progress log

- 2026-09-17 — Branch created. Read CLAUDE.md, TODO.md, ARCHITECTURE-REVIEW.md
  and all UI sources; audit in §2; questions in §5. No code changes yet.
- 2026-09-17 — Re-checked TODO.md against `main` (`c060f0c`) and updated it:
  backfill start tick ticked, `forceScanForTesting` item rewritten (only deletion
  left), bulk spam actions marked resolved by removal in `6898a35` and kept as the
  confirmation rule for multi-select, stale Maestro spam flow added, telephony
  device-test item made precise. Relevant here: P1.9 must also fix
  `spam_notspam_and_bulk.yaml`.
- 2026-09-17 — All eight questions answered (§5). Plan items updated to match.
- 2026-09-17 — P1.0 done (see §4). Note: `assembleDebugAndroidTest` with default
  parallelism hit Gradle daemon GC thrashing at `-Xmx2048m` (dexing icons-extended
  into several test APKs); `--max-workers=2` passes. CI does not build test APKs.
- 2026-09-17 — Toolchain finished: user's AGP/Gradle/KSP bump committed, compileSdk 37 +
  BOM 2026.09.00. Build, 246 unit tests (85 UI re-run), kover, test APKs all green.
  Next: P1.1.
- 2026-09-17 — P1.1 done. Build, lint, kover, test APKs green; 257 unit tests (+11:
  avatar initial/palette, top-bar action partition, full type scale, dark tone order).
  Not visually checked on a device yet — do that at the start of P1.2.
- 2026-09-17 — P1.2 in progress. Visual check of P1.1 on emulator-5556 (Medium Phone API 37.1):
  light and dark inbox render correctly; only buttons (14sp) and the darker dark page changed.
  Code done, not yet built/verified: shell has no Scaffold; drawer header + outlined/filled icons,
  gestures only on drawer destinations; each screen owns its bar (Inbox/Archived/Spam/Settings: Menu +
  drawer label as title; Thread: Back + address placeholder; New conversation: Back + title; debug tools
  via `DrawerDestinationScaffold`, promoted to designsystem on its 2nd consumer); onboarding gets
  safeDrawingPadding; nested NoSpamTheme removed from Conversations and MlDebug. Maestro anchors:
  "NoSpam SMS" → "Inbox", "Menu" → "Open navigation menu". E2E baseline running on HEAD c833fed
  from a worktree in the scratchpad before verifying.
- 2026-09-17 — Baseline `persistence` failure diagnosed: flow asserted NSTEST_ARCHIVE2 in the inbox
  without scrolling; the fixture is 10 days old and sorts below the fold. Flow fixed (scroll before
  the positive check; search + unique snippet for the absence check, which was vacuous). Goes in the
  P1.2 commit. User added: selection mode also in Archived and Spam & blocked (recorded in P1.3).
- 2026-09-17 — P1.2 verified on emulator-5556: screenshots of every destination light/dark, keyboard
  in thread. E2E baseline (c833fed) 6 pass / 4 fail, all four flow or runner defects:
  persistence (no scroll to a 10-day-old fixture), thread_send (asserted dialog title removed in
  656146c), spam_notspam_and_bulk (asserts UI removed in 6898a35), _unblock_part2 (manual-only
  flow run by run-e2e.sh with a reseed between parts). Fixed in P1.2: persistence, thread_send
  (+ Navigate up instead of back while IME open), settings_dialogs (Terms row now below fold;
  final anchor is "Settings"), runner skips `manual-only`.
- 2026-09-17 — P1.2 final E2E: 7 pass / 1 fail (spam_notspam_and_bulk, expected) vs baseline 6/4.
  Build, 257 unit tests, kover, test APKs green.
- 2026-09-17 — P1.3 verified: build, unit tests, kover, test APKs green; screenshots of selection mode
  (copied to ~/Desktop/__CLAUDE__/images/p13/ at the user's request); E2E 8 pass / 0 fail. Flows
  updated: long_press_actions (selection bar, ⋮, multi-select, cancelled delete, back exits),
  spam_notspam_and_bulk (rewritten), persistence (star/mute via ⋮, Clear selection),
  _unblock_part1 (⋮ Block + confirm).
- 2026-09-17 — P1.4 verified: build, 294 unit tests (incl. the new JVM Compose suites), kover
  (49.69%, ratchet raised to 49), test APKs, E2E 8/0. Screenshots in the scratchpad
  (p14-*.png). Two defects found by the screenshots: 4 icons in the selection bar (partition
  rule) and the emoji picker unreadable in light mode (app XML theme was the dark AppCompat).
- 2026-09-17 — P1.5/P1.6/counter: build green, 311 unit tests, coverage 54.88% (ratchet 49 → 54),
  settings flows rewritten for the sub-pages. The per-SIM pages first showed nothing on-device:
  the emulator does have a SIM (T-Mobile, LOADED), but the app never declared READ_PHONE_STATE,
  so `getActiveSubscriptionInfoList` threw SecurityException and the telephony source swallowed
  it. Same root cause as the dual-SIM send picker never appearing — pre-existing, not new.
- 2026-09-17 — E2E after the settings restructure: 8 pass / 0 fail.
- 2026-09-17 — Phone permissions added at the user's direction (privacy stance unchanged: the
  data never leaves the device). READ_PHONE_STATE + READ_PHONE_NUMBERS in the manifest and in
  onboarding's request, but NOT in the list that gates onboarding: denying them leaves a working
  single-SIM app. Verified on-device: Settings now lists "T-Mobile / +15551234567" and its page.
- 2026-09-18 — Permission gate settled with the user after testing Google Messages on the
  emulator one permission at a time: it gates on SMS, contacts and phone individually (an
  earlier "it tolerates denial" reading was wrong — its preinstalled GRANTED_BY_DEFAULT flag
  silently re-granted phone on request). So: phone moved into `requiredPermissions()`,
  notifications moved out into `optionalPermissions()` (still requested), and `needsOnboarding`
  now checks the whole required list on cold start and on resume. Contacts stays required
  because the contact bypass in the spam policy depends on it. Phase 2: reply on the
  conversation's own subscription id (TODO.md, Project-wide).
- 2026-09-20 — P1.7: onboarding rebuilt as an explained gate. Verified on emulator-5554 in light
  and dark. Labels tightened after the screenshot pass: the done states read "Granted" and
  "Set as default" rather than a bare "Set", which looked like a button.
- 2026-09-20 — P1.7 E2E: 8 pass / 0 fail (onboarding flow updated for the new step strings).
- 2026-09-20 — P1.8: verified by running the app in Persian on emulator-5554 (`adb shell cmd
  locale set-app-locales com.nospam.nospam --locales fa`), which is how the snippet punctuation
  bug surfaced. 327 unit tests, coverage 55.84%.
- 2026-09-20 — P1.8 E2E: 8 pass / 0 fail. inbox_filters_and_search needed `.*` around the phone number it asserts, for the same isolate-character reason.
- 2026-09-20 — P1.9: the four Compose `androidTest` suites folded into their Robolectric
  counterparts and deleted, along with three components no production code still called.
  339 unit tests, 60.39% coverage (ratchet 54 → 60). CLAUDE.md §9 rewritten: instrumented
  tests are now for storage and telephony only, and the rule for adding one is stated.
- 2026-09-20 — P1.9 E2E: 8 pass / 0 fail, unchanged flows.
- 2026-09-20 — P1.10: 30 screenshots in light-EN / dark-EN / light-FA. Four UI fixes (drawer
  selected-item contrast, disabled Send, empty contact sections, header styling) plus the
  seed.sh contact bug they uncovered. 339 unit tests, 60.49% coverage, E2E 8 pass / 0 fail.
  P1 complete.
