# UI polish and complete UI features — working plan

Branch: `ui/m3-polish` (from `main` at `c060f0c`). Started 2026-09-17.

This file is the durable tracker for this work. If a session runs out of
context, resume from **§6 Progress log** and the first unchecked box in **§4**.
Update both as work lands. Delete or archive the file when the branch merges.

## 0. Status

**P1.7 done 2026-09-20.** Next: P1.8 (strings), P1.9 (tests), P1.10 (visual check).

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

## 3. Phase 2 — backend wiring (deferred, collected as we go)

Items the phase 1 UI will need but the backend does not provide. Add to this
list whenever a UI element is built ahead of its backend.

- [ ] Per-SIM preferences storage (delivery reports, auto-download MMS, roaming
      MMS, simple characters) and applying them in `core:telephony` send paths.
- [ ] User-entered phone number per SIM when `SubscriptionManager` returns none.
- [ ] Theme preference (system / light / dark, dynamic color) persistence read by `:app`.
- [ ] Contact display-name + photo lookup for a thread opened without a
      `Conversation` in hand (notification / `ACTION_SENDTO` entry).
- [ ] Bulk operations as single repository calls (today: loop of per-id calls).
- [ ] Manage blocked and allowed senders screen data source (TODO.md "Settings").
- [ ] Message sounds, swipe-action configuration, and any other settings the
      Settings redesign surfaces without storage.

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
- [ ] P1.8 Strings: remove hard-coded English, add Persian translations.
- [ ] P1.9 Tests: update Compose UI tests and `.maestro/flows` (they anchor on
      "NoSpam SMS" and dialog labels), run unit tests + coverage gate.
- [ ] P1.10 Visual check light / dark / Persian RTL on device.

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
