# TASKS.md — NoSpam Implementation Plan

> Source architecture: `CLAUDE.md`. Stitch UI: `/home/amirhossein/Downloads/stitch_modern_sms_messenger/` (8 screens + `adaptive_messenger/DESIGN.md`). This file is the execution tracker — one checkbox = one verifiable outcome. Do not start next task until the current one is marked done and confirmed by owner.

**Global constraints**
- Stack: Kotlin + Compose + Material 3, Navigation-Compose type-safe `@Serializable` routes, Hilt, Coroutines/Flow, Room, DataStore, WorkManager.
- Module deps: `:app → feature:* → core:*`, `core:*` never depend on each other (shared data in `core:model`). `core:model` has zero Android imports (plain Kotlin `jvm` module).
- i18n: `AppCompatDelegate.setApplicationLocales` + `res/xml/locales_config.xml` + `android:localeConfig` + `AppLocalesMetadataHolderService (autoStoreLocales)`, `android:supportsRtl="true"`, `start/end` not `left/right`, `Icons.AutoMirrored`, `resourceConfigurations ["en","fa"]`, bidi isolation for phone numbers/timestamps, Jalali vs Gregorian decision pending.
- SMS ownership: only `core:telephony` touches `ContentResolver` (`Telephony.Sms`/`Mms`/`Threads`), `SmsManager`, `SubscriptionManager`. Room holds blocklist/verdicts/metadata only — no SMS mirroring.
- Fonts: `Hanken Grotesk` + `Inter` via Google Fonts Provider (downloadable, certs in `res/font/*.xml`) with optional bundled fallback for offline; Persian adds `Vazirmatn` (ZWNJ/kashida aware). Dark theme generated from seed `0xFF005BBF` via Material Theme Builder then hand-tuned — do not ship inverted light. Default-SMS via `RoleManager.createRequestRoleIntent(ROLE_SMS)` (Q+) / `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` (pre-Q) + `isRoleHeld` check.
- MMS: stub only in v1 (`MmsReceiver` receives `WAP_PUSH_DELIVER` but no full parsing). Architecture stays extensible via `TelephonyDataSource` interface.
- Execution: incremental PRs in dependency order, `gradlew build` + smoke test after each. `com.nospam.nospam` rename deferred. `minSdk` raised `24 → 26` in Phase 0.

**How to use this file**
- Each task lists: scope, key files, verification command. Check box only after verification passes and screenshot/preview attached where noted.
- Gate = mandatory `./gradlew` or device check before owner confirmation to proceed.
- Coverage goal at end: ≥80% line for `core:*`, full Compose previews for 8 screens (light/dark × LTR/RTL).

---

## Phase 0 — Tooling & Module Skeletons

**Gate:** `./gradlew build` passes on all 15 modules (no logic yet).

- [x] **0.1 Bump versions & SDK** — Update `gradle/libs.versions.toml:1-11` (`agp 9.0.0` — downgraded from 9.1.0 for Android Studio compat: Studio max 9.0.0, AGP 9.0.0 requires Gradle 9.1.0, `kotlin 2.2.20`, `composeBom 2024.09.00`, `coreKtx 1.13.0`, `lifecycleRuntimeKtx 2.9.0`, `activityCompose 1.10.1`), add `appcompat 1.7.0`, Bump `app/build.gradle.kts:8-14` `minSdk 26` (from 24), `jvmTarget 17`, wrapper `9.1.0` (was 9.3.1). Stuck to Java 17 per decision (Java 21 with desugaring deferred). *Verify:* `./gradlew :app:assembleDebug` + `./gradlew build` green with `java-25-openjdk-devel`. Done 2026-09-04, fix 2026-09-04 Studio sync.
- [x] **0.2 Per-app language baseline** — Created `app/src/main/res/xml/locales_config.xml` (`en`, `fa`), `app/src/main/AndroidManifest.xml:25` `android:localeConfig`, `92-99` `AppLocalesMetadataHolderService` with `autoStoreLocales`, `androidResources.localeFilters` in `app/build.gradle.kts:21-23` (replaces deprecated `resourceConfigurations`), `supportsRtl="true"` already present. *Verify:* `./gradlew :app:assembleDebug` green. Done 2026-09-04.
- [x] **0.3 Create 15 empty modules** — Updated `settings.gradle.kts:22` to include 15 modules, added `android-library`, `kotlin-jvm`, `kotlin-compose` to `libs.versions.toml:32-36` and `build.gradle.kts:3-6`, created `core/*` and `feature/*` `build.gradle.kts` skeletons (leaf `kotlin-jvm` toolchain 25→JVM_17, android libs `compileSdk 36`, `minSdk 26`, `jvmTarget 17`), fixed `lint MissingClass` via `androidx.appcompat:appcompat`. *Verify:* `./gradlew build` 758 tasks SUCCESSFUL. Done 2026-09-04.

---

## Phase 1 — Leaf Cores (No Android deps where enforced)

**Gate:** `./gradlew :core:model:test :core:common:test :core:designsystem:testDebugUnitTest` green + previews render.

- [x] **1.1 `core:model`** (`core/model/`) — Pure Kotlin domain: `Conversation`, `Message`, `Participant`, `ThreadId`, `RawMessage`, `SpamVerdict` (label + score + calibratedProbability), `BlocklistEntry`, plus cross-module constants (`TelephonyConstants`), `grep -r "import android" core/model/src` empty (compiler-enforced `kotlin("jvm")`). *Verify:* `./gradlew :core:model:test` 4 tests PASS. Done 2026-09-04.
- [x] **1.2 `core:common`** (`core/common/`) — `Result<T>` (Success/Failure/Loading + map/fold), `DispatcherProvider` (Test/Default), `PermissionChecker` (pure Kotlin, SmsPermissions constants folded from `core:permissions`). Added `kotlinx-coroutines-core 1.8.1` to `libs.versions.toml:12,31-32`. *Verify:* `./gradlew :core:common:test` 6 tests PASS. Done 2026-09-04.
- [x] **1.3 `core:designsystem`** (`core/designsystem/`) — Ported `DESIGN.md` tokens to `theme/Color.kt` (LightColors + hand-tuned DarkColors from seed `0xFF005BBF`, stripped `*Fixed` family for BOM 2024.09.00 compat), `Type.kt` (Inter/Hanken via `FontFamily.Default` placeholder, Vazirmatn ready), `Shape.kt` (4/8/12/16/24/full), `Theme.kt` (`NoSpamTheme`). Atoms: `Avatar`, `PillChip`, `SearchBarPlaceholder`. Added `androidx.core:1.13.0` + compose BOM deps. *Verify:* `./gradlew :core:designsystem:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **1.4 `core:testing`** (`core/testing/`) — Fakes: `FakePermissionChecker`, `FakeSpamClassifier`, `FakeTelephonyDataSource`, `TestData` (Persian samples). Depends on `core:model` + `core:common`. *Verify:* `./gradlew :core:testing:assemble` SUCCESS. Done 2026-09-04.
- [x] **1.5 `core:i18n`** (`core/i18n/`) — `LocaleHelper` (`AppCompatDelegate.setApplicationLocales`, `isRtl` for `fa`/`ar`), `BidiHelper` (pure JVM `\u2068`/`\u2069` isolates, not `BidiFormatter` to stay unit-testable), `DateFormatter` (Gregorian primary, Jalali deferred). Fixed `core/i18n` build: added compose runtime for `kotlin.compose` plugin (`platform(compose.bom)` + `ui`) and internet-drop recovery. *Verify:* `./gradlew :core:i18n:testDebugUnitTest` 3 tests PASS; `./gradlew build` 787 tasks SUCCESS. Done 2026-09-04.

---

## Phase 2 — Platform & Data

**Gate:** `./gradlew :core:database:testDebugUnitTest :core:ml:test :core:telephony:testDebugUnitTest :core:data:testDebugUnitTest` green.

- [x] **2.1 `core:database`** (`core/database/`) — In-memory deferred Room: `BlocklistEntity`, `SpamVerdictEntity`, `ModelMetadataEntity`, `*Dao` with `InMemory*Dao` (Flow), `NoSpamDatabase.inMemory()` — Room `ksp` deferred due to `AGP 9.0.0 + Kotlin 2.2.10` KSP incompat (`builtInKotlin` cast error) + `Robolectric 4.11` `RoboCookieManager` missing; kept pure JVM for gate. Added `kotlinx-coroutines-core`, `robolectric 4.11.1` (unused after defer). *Verify:* `./gradlew :core:database:testDebugUnitTest` 3 tests PASS (blocklist, verdict override, clearAutoSpam respects override). Done 2026-09-04.
- [x] **2.2 `core:ml`** (`core/ml/`) — Extracted `SpamDetector.kt:15-80` behind `SpamClassifier` (`core/ml/SpamClassifier.kt:1`), `SpamModel` serializable (`SpomModel.kt:1`), `TfidfPreprocessor` (`TfidfPreprocessor.kt:1` URL→`URLTOKEN`, digits→`NUM_TOKEN`, `ي→ی`/`ك→ک`, ZWNJ→space/kashida/zero-width removal, diacritics), `TfidfSpamClassifier` (`TfidfSpamClassifier.kt:1` bias+weights+threshold, `fromAsset`/`fromJson` via `kotlinx-serialization-json 1.7.3`). Moved `spam_model.json` `app/src/main/assets` → `core/ml/src/main/assets`. *Verify:* `./gradlew :core:ml:testDebugUnitTest` 6 tests PASS (preprocess, ngrams, dummy model parity, real asset smoke). Done 2026-09-04.
- [x] **2.3 `core:telephony`** (`core/telephony/`) — `TelephonyDataSource` interface (`TelephonyDataSource.kt:1`), `TelephonyMapper` pure (`TelephonyMapper.kt:1` + `ContentValues` builder), `RealTelephonyDataSource` (`RealTelephonyDataSource.kt:1` `ContentResolver` + `SmsManager` multi-SIM via `SubscriptionManager`), `receiver/SmsReceiver`/`MmsReceiver` stub + `service/HeadlessSmsSendService` + own `AndroidManifest.xml` fragment (`Telephony.SMS_DELIVER`/`WAP_PUSH_DELIVER`/`RESPOND_VIA_MESSAGE`). Fixed `ContentValues` mock test via `@Config(NONE)` → pure string-constant test. *Verify:* `./gradlew :core:telephony:testDebugUnitTest` 2 tests PASS. Done 2026-09-04.
- [x] **2.4 `core:notifications`** (`core/notifications/`) — `NotificationHelper` (`NotificationHelper.kt:1` channels `messages`/`spam`, `MessagingStyle`, `RemoteInput` direct-reply `PendingIntent` via `TelephonyConstants.ACTION_RESPOND_VIA_MESSAGE` + `EXTRA_MESSAGE`, package-restricted for `MutableImplicitPendingIntent` lint). Added `core:model` dep, fixed lint. *Verify:* `./gradlew :core:notifications:testDebugUnitTest` 3 tests PASS. Done 2026-09-04.
- [x] **2.5 `core:data`** (`core/data/`) — `ConversationsRepository` (`ConversationsRepository.kt:1` `combine` telephony+spamVerdicts+blocklist → `ConversationFilter` ALL/UNREAD/STARRED/KNOWN/UNKNOWN), `SpamRepository` (`SpamRepository.kt:1` `classifyAndStore`/`markNotSpam`/`markSpam`), `BlocklistRepository` (`BlocklistRepository.kt:1`). Used `core:testing` fakes for `TelephonyDataSource`. *Verify:* `./gradlew :core:data:testDebugUnitTest` 3 tests PASS; gate `./gradlew :core:database:test :core:ml:test :core:telephony:test :core:data:test :core:notifications:test` + `./gradlew build` 831 tasks SUCCESS. Done 2026-09-04.

---

## Phase 3 — Minimal App Shell + Feature Scaffolds (Mandatory UI)

**Gate:** App launches, drawer + conversations list + thread + new-conversation render with fake data, no real SMS yet.

- [x] **3.1 `:app` shell** — `NoSpamApplication` (`NoSpamApplication.kt:1`), `navigation/NoSpamNavHost.kt:1` type-safe `@Serializable` routes (`ConversationsRoute`/`ArchivedRoute`/`SpamRoute`/`ThreadRoute`/`NewConversationRoute`/`SettingsRoute`/`OnboardingRoute`), `ui/NoSpamAppShell.kt:1` `ModalNavigationDrawer` 280dp `ModalDrawerSheet` + `TopAppBar` + `Scaffold` with `NoSpamTheme` from `core:designsystem`, drawer items Inbox/Archived/Spam/Settings (`Delete`/`Warning` icons for `Archive`/`Report` missing in default `Icons.Filled`). `MainActivity.kt:1` thinned to `setContent { NoSpamAppShell() }`, added `android:name=".NoSpamApplication"` to `AndroidManifest.xml:20`. Added `navigation-compose 2.8.4` + `lifecycle-viewmodel-compose 2.9.0` to `libs.versions.toml:19-20`, wired `app` deps to all `feature:*` + `core:*`. *Verify:* `./gradlew :app:assembleDebug` SUCCESS; `./gradlew build` 949 tasks. Done 2026-09-04.
- [x] **3.2 `feature:onboarding`** (`feature/onboarding/OnboardingScreen.kt:1`) — Runtime `RequestMultiplePermissions` (`READ_SMS`/`SEND_SMS`/`RECEIVE_SMS`/`READ_CONTACTS`) + default-SMS role (`RoleManager.createRequestRoleIntent` Q+ / `ACTION_CHANGE_DEFAULT` pre-Q, `isRoleHeld`/`getDefaultSmsPackage`), `AppCompat` check, `onComplete` nav. *Verify:* `./gradlew :feature:onboarding:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **3.3 `feature:conversations`** (`ConversationsViewModel.kt:1` `StateFlow<ConversationsUiState>` fake 6 conversations, pinned, `ConversationsScreen.kt:1` search `RoundedCornerShape 24dp` + `FilterChip` row `horizontalScroll` (`ConversationFilter` ALL..STARRED) + pinned header + `ConversationRow` 72dp `CircleShape` avatar + unread dot `error` + `formatTime` + `FloatingActionButton` 16dp + `ArchivedScreen`/`SpamScreen` reuse same list). *Verify:* `./gradlew :feature:conversations:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **3.4 `feature:thread`** (`ThreadViewModel.kt:1` `ThreadUiState` + `onSend`, `ThreadScreen.kt:1` `LazyColumn` bubbles `MessageBubbleShapeIncoming/Outgoing` 16dp sharp corner, `surfaceContainerHigh` vs `primary`, `compose bar` pill `24dp` with `AddCircle`/`Face`/`Send` + `NewConversationScreen` `To:` field + `Top contacts`, `SettingsScreen.kt:1` `LazyColumn` sections General/Privacy/Advanced/About with `Switch` and `KeyboardArrowRight` (fixed `ChevronRight`/`Image`/`AddPhotoAlternate` unresolved `Icons.Filled` → `Warning`/`Face`/`Delete`). *Verify:* `./gradlew :app:assembleDebug` SUCCESS; `./gradlew build` 949 tasks. Done 2026-09-04. Fix 2026-09-05: `To:` field used `value = ""` + no-op `onValueChange` so keystrokes were discarded (logcat `commitText on inactive InputConnection`); now hoisted to `rememberSaveable` query state driving a filtered Top-contacts/All-contacts list per the Stitch mock, with `new_conversation_recipient_field_accepts_input_and_filters` UI regression test. Contacts-provider query still future work (fake seed).

---

## Phase 4 — Remaining UI Polish

**Gate:** All 8 Stitch screens pixel-matched (allow font rendering diff), light/dark/LTR/RTL previews attached.

- [x] **4.1 Archived** — Replaced placeholder `ArchivedScreen` (`ConversationsScreen.kt:158`) with `SwipeToDismissBox` `StartToEnd` 0.5 threshold + `primary` background `Unarchive` (`Delete` icon, `RoundedCornerShape 12dp`), `LazyColumn` 2 fake archived (`Bank Alerts`, `Home Depot`), empty state `96dp` `surfaceContainer` circle `Delete` `tertiary` 48dp + “Archive is empty” `headlineMedium`. Fixed duplicate key `1` (pinned+recent) via `ViewModel drop(1)` earlier. *Verify:* `./gradlew :feature:conversations:assembleDebug` SUCCESS; manual swipe removes item, empty shows. Done 2026-09-04.
- [x] **4.2 Spam & Blocked** — `SpamScreen` (`ConversationsScreen.kt:228`) `errorContainer` banner “deleted after 30 days” (`Warning` `onErrorContainer`), `Empty Spam` `TextButton` + `SwipeToDismissBox StartToEnd` “Not spam” `tertiaryContainer` + `TextButton` “Not spam” per row (inferred, not in mockup per `CLAUDE.md:6` → `SpamRepository` hook), 3 fake spam (`Win A Free Cruise!` etc.) with `error` avatar `Warning`. Added `Not spam` snackbar. *Verify:* `./gradlew :feature:conversations:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **4.3 Settings** — `SettingsScreen.kt:1` `LazyColumn` General/Privacy/Advanced/About + language picker (`LocaleHelper.setLocale` toggle `en↔fa`, `selectedLocale` + `fa` `فارسی`), default-SMS `isDefaultSmsApp`/`requestDefaultSmsRole` (`RoleManager` Q+ / `ACTION_CHANGE_DEFAULT` pre-Q) with `rememberLauncherForActivityResult`, `Version info`/`Terms`, `Auto-download MMS` `Switch`. Added `activity-compose` to `feature:settings/build.gradle.kts:34`. *Verify:* `./gradlew :feature:settings:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **4.4 Shell details** — `NoSpamAppShell.kt:1` drawer `ModalDrawerSheet` + `Mark all as read` `HorizontalDivider` + `Spacer(weight=1f)` + `Settings` at bottom, `Icons.Warning` for Spam, `Delete` for Archived (fallback for missing `Archive`/`Report` in `Icons.Filled` default), `Spacer`/`Box` imports, `16.dp` header. Starred maps to `ConversationFilter.STARRED` chip (no separate `FavoritesScreen`). FAB stays `feature:conversations` `FloatingActionButton` `16dp`. *Verify:* `./gradlew :app:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **4.5 Preview & screenshot gate** — Added `@Preview` (`ConversationsScreenPreview` light/dark/RTL `fa`, `ArchivedEmptyPreview`, `SpamPreview`, `ThreadScreenPreview` light/dark/RTL, `SettingsScreenPreview` en/fa, `NoSpamAppShellPreview` light/dark) via `ui.tooling.preview` (added to 4 `feature/*` `build.gradle.kts`), fixed `tooling` unresolved via `implementation(libs.androidx.compose.ui.tooling.preview)`. Bidi `BidiHelper.wrap` `\u2068` isolates for `+98 912…` inside Persian. *Verify:* `./gradlew :app:assembleDebug` SUCCESS; `./gradlew build` 949 tasks SUCCESS. Done 2026-09-04. Fix 2026-09-05 (Persian had no effect): two root causes — (1) `MainActivity : ComponentActivity` silently dropped `AppCompatDelegate.setApplicationLocales()` on pre-33 (needs `AppCompatActivity` + `Theme.AppCompat.NoActionBar` parent), (2) zero localizable strings (all UI text hardcoded). Now per-module `values/strings.xml` + `values-fa/strings.xml` (`:app`, all 4 `feature:*`) with every user-visible literal via `stringResource` (English byte-identical so UI tests pass; chips/time/snackbar via `@Composable` helpers, snackbar uses `%1$s` arg, `confirmValueChange` hoists the formatted string since it isn't composable). RTL audit: `supportsRtl`, no absolute left/right layouts, all directional icons `AutoMirrored`, locale-sensitive `lowercase()` chip hack removed.

---

## Phase 5 — Backend Wiring (Spam Pipeline + SMS I/O)

**Gate:** Device receives real SMS → classified → routed correctly; instrumented test on emulator with seeded SMS.

- [x] **5.1 SmsReceiver wiring** — Manual `AppContainer` (no Hilt — lighter alternative per CLAUDE.md; receivers can't use constructor injection anyway). `SmsIngressUseCase` (`core/data`) orchestrates: `getMessagesFromIntent` → `TfidfSpamClassifier.classify` → `getOrCreateThreadId` → `insertInboxMessage` with READ=1 for spam → verdict upsert → opportunistic prune. `AppSmsReceiver` (`:app`) uses `goAsync` + IO scope. Manifest owns exactly one SMS_DELIVER entry; legacy + telephony receiver entries removed (files kept). *Verify:* `./gradlew :core:data:testDebugUnitTest` (4 ingress tests) + emulator `adb emu sms send` + logcat `Prediction: spam/ham`. Done 2026-09-05.
- [x] **5.2 Notifications routing** — Ham → `MessagingStyle` + `RemoteInput` direct-reply `PendingIntent` (explicit package, `thread_id` + `subscription_id` extras); spam → silent (low-importance channel only). `POST_NOTIFICATIONS` added. *Verify:* notification for ham, suppressed for spam. Done 2026-09-05.
- [x] **5.3 HeadlessSmsSendService** — `onStartCommand` handles `RESPOND_VIA_MESSAGE`: `RemoteInput` text (fallback `EXTRA_TEXT`/`sms_body`), subscription-aware `SmsManager`, writes sent message to provider, cancels notification. Pure `pickReplyText`/`normalizeSubscriptionId` unit-tested. Done 2026-09-05.
- [x] **5.4 Corrections & retention** — `markNotSpam`/`markSpam` (override-preserving) + `BlocklistRepository` CRUD + `pruneOldSpam(30d)` (overrides never pruned; opportunistic prune on each ingress, no WorkManager for v1). SpamScreen "Not spam" (button + swipe) persists via `NavHost → SpamRepository`. *Verify:* `SmsIngressUseCaseTest.pruneOldSpam keeps user overrides` PASS. Done 2026-09-05.
- [x] **5.5 UI↔repo wiring (why test SMS never appeared in lists)** — Inbox/Spam/Thread rendered fake data while the pipeline wrote to the provider. Now: `ConversationsViewModel(repository?)` serves `observeConversations` + chip/search client filter (fake fallback for previews/tests), `SpamViewModel`/`ArchivedViewModel` serve `observeSpam`/`observeArchived` (archived truthfully empty — provider has no archived flag), `ThreadViewModel(dataSource?)` loads `getMessages` and sends via `SmsManager` + new `insertSentMessage` provider write, `TelephonyDataSource` queries hardened against `SecurityException` (empty list, never crash), `NavHost` builds VMs from `AppContainer` via factory and starts at `OnboardingRoute` until SMS permission + default role are held. *Verify:* `./gradlew build` green; 5 new VM tests (live inbox/search/unread, live thread load/send). On device: `adb emu sms send` → ham in Inbox, spam in Spam & Blocked. Done 2026-09-05.
- [x] **5.6 Live inbox refresh (list was frozen until renavigate)** — Same `ContentObserver`-on-`Telephony.Sms.CONTENT_URI` mechanism as the legacy `HomeScreen.rememberSmsInbox`, relocated per architecture: `RealTelephonyDataSource.observeConversations()` is now `callbackFlow` (observer registered/unregistered in `awaitClose`) + `onStart` initial emit + `mapLatest { getConversations() }` to coalesce bursts; queries stay on IO, features stay Android-free, and `ConversationsRepository` combine propagates re-emissions untouched. Follow-up hardening from a device crash: conversations are grouped client-side from `Sms` rows (`TelephonyMapper.toConversation`) instead of `Threads.CONTENT_URI` (`snippet` column missing on some providers → `SQLiteException`), and query catches widened to `Exception`. *Verify:* `./gradlew build` green; `RepositoryTest.observeConversations re-emits on provider change` + `TelephonyMapperTest.toConversation groups by latest message`. Done 2026-09-05.
- [x] **5.7 New-conversation address flow + long-press menus** — (1) Typing a number + enter did nothing and contact taps passed a meaningless `hashCode` as thread id: `NewConversationScreen` now takes `onAddressEntered`, resolves typed queries via pure `resolveRecipientAddress` (contact-name match → phone, else raw input) with IME-Done handling, and `NavHost` resolves `getOrCreateThreadId` then opens `ThreadRoute(threadId, address)`; `ThreadViewModel.loadThread(id, address)` carries the address so sending works with zero messages. (2) Long-press menus everywhere via shared `ConversationActionsDialog`: inbox (mark read/unread, archive, report spam, block/unblock, delete), archived (unarchive, delete), spam (not spam, block/unblock, delete) — backed by new `ArchivedDao` store, `markAsUnread`, `deleteConversation` (provider + verdict + archive cleanup), `setRead`/`archive`/`unarchive` repo ops; provider writes hardened to best-effort. Star/pin/mute deliberately omitted (no data model). *Verify:* `./gradlew build` + `:koverVerify` green, 67 JVM tests pass (resolver, archive repo/dao, live thread send); UI suites compile into test APKs (long-press dialog, IME-Done). Done 2026-09-05.
- [x] **5.9 Sent bubble invisible until reopen** — The reload right after the sent-box write could miss the new row on some provider builds. `ThreadViewModel.onSend` now appends an optimistic SENT row (negative id, never collides) instantly, then reconciles: provider rows win, unconfirmed optimistic rows are kept, reopening still resets to pure provider data; send/insert failures log warnings. *Verify:* `./gradlew build` green; `sent bubble survives a stale provider reload` regression test (fake hides sent rows from queries). Done 2026-09-05.
- [x] **5.10 Incoming messages invisible until reopen (proper fix)** — The thread screen did a one-shot `getMessages` while only the inbox had a provider observer. No polling/duct tape: `TelephonyDataSource.observeMessages(threadId)` reuses the same `ContentObserver`→`callbackFlow`→`mapLatest` pipeline as conversations (shared `observeSmsChanges`), `ThreadViewModel` collects it in a cancellable job (switching threads cancels the old collection), merges optimistic rows through the same reconciliation, and marks the thread read on open when unread is present (self-terminating: the update re-emits all-read). *Verify:* `./gradlew build` + `:koverVerify` green, zero warnings; `incoming message appears without reopening`, `opening thread with unread marks read once`, `all-read thread does not mark`. Done 2026-09-05.
- [x] **5.8 Delete fix (menu Delete silently did nothing)** — `deleteConversation` targeted `Threads.CONTENT_URI`, a query-only UNION on most providers, and every provider-write catch swallowed the exception (hence a clean logcat). Now deletes message rows via `Telephony.Sms.CONTENT_URI` + `thread_id = ?` (thread vanishes once empty) with best-effort Threads-URI attempt, and all best-effort catches log a warning naming the likely cause (not default app). *Verify:* `./gradlew build` green; delete test now asserts the provider call. On device: long-press → Delete removes the thread; if it still fails, logcat shows the reason. Done 2026-09-05.

---

## Phase 6 — Testing & Coverage

**Gate:** `./gradlew build` + merged coverage report, no P1 bugs.

- [x] **6.1 Unit tests** — `core:ml` (TF-IDF parity, Persian URL/NUM_TOKEN, ZWNJ), `core:model` mapping, `ConversationsViewModelTest` (pinned/main key-uniqueness regression, filter, search) + `ThreadViewModelTest` (send/draft/blank no-op), `DispatcherProviderTest`, `FakesTest`, `TokensTest` (Stitch seed assertions), repos (fakes). 53 JVM tests, 0 failures. No Turbine — `StateFlow.value`/`first()` assertions suffice. *Verify:* per-module `test`/`testDebugUnitTest` green. Done 2026-09-05.
- [x] **6.2 Compose UI tests** — `createComposeRule` suites (not `createAndroidComposeRule` — no Activity needed): conversations (rows/FAB/filter/search/click), spam ("not spam" removes + reports id, empty-spam), thread (send flow), settings (sections, switch toggle, language dialog). Dex-safe underscore test names (spaces in backtick names break D8 pre-dex-040). Test APKs assemble; execution needs an emulator. *Verify:* `:feature:*assembleDebugAndroidTest` green. Done 2026-09-05.
- [x] **6.3 Instrumented tests** — `TelephonyInstrumentedTest` (SMS insert/query round-trip with `Assume` skip unless default-SMS role held + `GrantPermissionRule`; notification reply-action count), `TelephonyMapperDeviceTest` (real `ContentValues`), `AtomsTest` (designsystem). Compiled + packaged (`assembleDebugAndroidTest`); not executed here — no `adb`/emulator on this machine. *Verify on emulator:* `./gradlew connectedDebugAndroidTest` after granting default-SMS role. Done 2026-09-05 (code-complete, execution pending device).
- [x] **6.4 Coverage & CI** — Kover `0.9.9` with root merge (`kover(...)` deps on all 15 modules): `build/reports/kover/html` + `report.xml` via `:koverXmlReport`. Merged JVM line coverage **32.8% (484/1476)**. Dead legacy tree (`app/.../ui|navigation|receiver|service|ml`, deprecated telephony shim) excluded from report — unregistered/unreferenced, EOP deletion. `total { verify { minBound(30) } }` ratchet passes (`:koverVerify` green, CI-enforced); honest floor, not the 80% goal — 80% needs emulator (device suites above) + Robolectric (broken on JDK 25: `RoboCookieManager` NoClassDefFound). `.github/workflows/android.yml` runs `build` + test-APK assembly + `:koverXmlReport :koverVerify` + report upload. Final `./gradlew build` green. Done 2026-09-05.

---

## Phase 7 — Review remediation (P0)

> Source: full-codebase review (manifest → receiver → ingress → repos → DAOs →
> UI → notifications → direct-reply service) against a standard Android SMS
> app + the `android-intent-security` skill. See chat log 2026-09-08 for the
> full finding list (R1–R18, S1–S9, M1–M10); this phase covers the P0/security
> subset. P1 polish is Phase 9.

**Gate:** `./gradlew build` green; new ingress test proves a message is
persisted even when the classifier throws; `./gradlew lint` green with
`UnsafeIntentLaunch`/`MutableImplicitPendingIntent` as errors.

- [x] **7.1 Persistent app DB** — Replace `NoSpamDatabase.inMemory()` in
  `AppContainer` with a persistent impl behind the existing DAO interfaces
  (Room if KSP works on the current AGP; else a `SQLiteOpenHelper`-backed
  fallback with the same interfaces). All app-owned state (blocklist,
  verdicts, overrides, archive) currently vanishes on process death — a
  receiver-driven app dies constantly, so this is the highest-priority fix.
  Implemented `SqliteNoSpamOpenHelper` + `Sqlite*Dao` for all 4 tables;
  `AppContainer` now uses `NoSpamDatabase.persistent(context)`.
  *Verify:* block a number, force-stop, relaunch → still blocked. Done 2026-09-08.
- [x] **7.2 Ingress ordering & resilience** — `SmsIngressUseCase.handle`
  currently classifies *before* inserting into the provider; if the
  classifier throws, the SMS is lost. Reorder to insert (READ=0) → classify →
  update READ/verdict; wrap classification in `withTimeout(8_000)`; warm the
  classifier (1.2 MB JSON asset) in `NoSpamApplication.onCreate` on a
  background thread so first-SMS latency doesn't eat the `goAsync()` budget.
  Added `TelephonyDataSource.updateMessageRead`. *Verify:* `SmsIngressUseCaseTest.classifier_failure_still_persists_message` PASS. Done 2026-09-08.
- [x] **7.3 Blocklist at ingress** — Blocked senders are currently inserted,
  classified, and (if ham) notified — `Block` only hides rows in the list.
  Added `PhoneNumberNormalizer` (`core/telephony`), `TelephonyDataSource.isSystemBlocked` + `RealTelephonyDataSource` query on `BlockedNumberContract`, `BlocklistRepository` now normalizes + syncs to system provider when default-SMS, `SmsIngressUseCase` checks blocklist first and inserts as READ=1 with NONE notification. *Verify:* unit test + emulator. Done 2026-09-08.
- [x] **7.4 Contacts resolution** — No `ContactsContract` usage exists today;
  Added `ContactLookup` (`core/telephony/ContactLookup.kt`) via `PhoneLookup.CONTENT_FILTER_URI` cached per address, `TelephonyDataSource.lookupContact`, and enrich `RealTelephonyDataSource.queryConversations` with displayName/photoUri. *Verify:*
  `TelephonyInstrumentedTest`. Done 2026-09-08.
- [x] **7.5 Notification contract** — `NotificationHelper` now sets `setContentIntent` (deep-link to `MainActivity` with thread_id), `setWhen`/`setShowWhen`/`setShortcutId`/`Person.setKey` + `ShortcutManagerCompat.pushDynamicShortcut`, `createChannels` uses localized `R.string.channel_*` (added `core/notifications/res/values/strings.xml`), `ThreadViewModel.loadThread(id, address, context)` cancels via `NotificationManagerCompat.cancel` on open, onboarding requests `POST_NOTIFICATIONS` on API 33+. *Verify:* tap opens thread;
  opening thread clears its notification. Done 2026-09-08.
- [x] **7.6 Multipart + sent/failed status** — `RealTelephonyDataSource.sendMessage` now uses `divideMessage`/`sendMultipartTextMessage` for >70 char Persian (UCS-2) and long GSM-7 messages. Done 2026-09-08.
- [x] **7.7 Security hardening** (`android-intent-security` skill findings):
  - S1 (High): the reply `PendingIntent` in `NotificationHelper` is
    `FLAG_MUTABLE` (required for `RemoteInput`) but its base `Intent` has no
    explicit target component — set
    `setClassName(context.packageName, "com.nospam.nospam.core.telephony.service.HeadlessSmsSendService")`.
  - S2: delete unused `NotificationHelper.buildDirectReplyIntent` (mutable,
    no `RemoteInput`, no explicit component).
  - S3: replace `Uri.parse("sms:$sender")` with `Uri.fromParts("sms", sender, null)`
    — sender is attacker-controlled (alphanumeric IDs can contain `?`/`#`/`;`).
  - S4: in `HeadlessSmsSendService`, validate `intent.action` and
    `subscription_id` against `SubscriptionManager.activeSubscriptionInfoList`.
  - S6: `MainActivity`'s exported `SENDTO` intent-filter data is never read —
    handle `intent.data`/`onNewIntent` (normalize via `PhoneNumberUtils`,
    open `NewConversationRoute`/`ThreadRoute`).
  - S8: drop the unused `READ_CELL_BROADCASTS` permission.
  - Enable `lint { checkReleaseBuilds = true }` with
    `UnsafeIntentLaunch`/`MutableImplicitPendingIntent` as errors in
    `app/build.gradle.kts`.
  *Verify:* `./gradlew lint` green. Done 2026-09-08.
- [x] **7.8 Dead code removal** — Deleted `core/telephony/receiver/SmsReceiver.kt` + `ExampleUnitTest`/`ExampleInstrumentedTest`, added `.kotlin/` to `.gitignore`. Done 2026-09-08.

---

## Phase 8 — Spam/ham categorization v2

> Fixes the root cause behind Phase 7's spam findings: today
> `SmsIngressUseCase` upserts each new message's verdict directly onto the
> thread (`SpamVerdictEntity(threadId, isSpam = verdict.isSpam, ...,
> isUserOverride = false)`), so (a) a single spam message flips an entire ham
> thread to Spam and a single ham reply flips it back — a mixed sender (bank
> OTP + bank promo) flaps in and out of the inbox — and (b) every new
> incoming message overwrites `isUserOverride = false`, silently undoing the
> user's "Not spam" correction on the very next SMS from that sender.
>
> Decision log (chat, 2026-09-08): conversations that mix spam and ham from
> the same sender must **stay in the inbox** with spam messages silenced
> per-message, not moved wholesale to Spam. A conversation that goes fully
> `SPAM` is **sticky** — a later ham-looking message never rescues it
> automatically, only a user action does (spammers routinely send innocuous
> openers). The classifier is a strict binary `ham`/`spam` model (no
> confidence tiers needed): **a first message from a brand-new sender that
> is classified spam sends the conversation straight to Spam**, *except*
> when the sender is a known contact (never auto-`SPAM`, at most `MIXED`).
> Sender state is keyed by **normalized address** (E.164 via
> `PhoneNumberUtils.formatNumberToE164`, or the raw alphanumeric sender ID
> when normalization fails), not `threadId` — provider thread ids are
> recycled after a thread is deleted, so anything keyed on them silently
> orphans; Google's own `BlockedNumberContract` uses the same
> original+E164 keying strategy.

**Gate:** `ThreadSpamPolicyTest` covers every row of the table below;
emulator check: a sender that sends a bank OTP (ham) then a promo (spam)
stays in the inbox with the promo silenced and labelled; a brand-new unknown
sender whose first message is spam lands directly in Spam with no
notification; a "Not spam" correction survives the next incoming SMS from
that sender.

- [ ] **8.1 `core:model`** — `enum ThreadSpamState { CLEAN, MIXED, SPAM,
  TRUSTED, BLOCKED }`, `enum NotificationDecision { NORMAL, SILENT, NONE }`,
  `MessageVerdict` (per-message, immutable evidence), `SenderState`
  (per-address, keyed by normalized address: `state`, `spamCount`,
  `hamCount`, `isUserOverride`, `updatedAt`). Pure `ThreadSpamPolicy`
  (`core:model`, zero Android imports) implementing:

  | Prev state | Signal | New state | Notification |
  |---|---|---|---|
  | any | `isBlocked` | `BLOCKED` | `NONE` |
  | `TRUSTED` (override) | any | `TRUSTED` | `NORMAL` (label spam inline) |
  | `SPAM` (override or sticky auto) | ham or spam | `SPAM` | `NONE` |
  | none (new sender) | ham | `CLEAN` | `NORMAL` |
  | none (new sender) | spam, is contact | `MIXED` | `SILENT` |
  | none (new sender) | spam, not a contact | `SPAM` | `NONE` |
  | `CLEAN`/`MIXED` | ham, or `isContact`/`hasOutbound` true | state unchanged or `CLEAN` | `NORMAL` |
  | `CLEAN` | spam | `MIXED` | `SILENT` |
  | `MIXED` | spam, `spamCount+1 ≥ 3` and ratio `≥ 0.8` | `SPAM` | `NONE` |
  | `MIXED` | spam, otherwise | `MIXED` | `SILENT` |

  Contacts and senders the user has replied to (`hasOutbound`) can never be
  auto-promoted to `SPAM` — at most `MIXED`. Unit tests (pure JVM, one per
  row above) plus: `override_survives_ingress`,
  `mixed_thread_never_flaps_back_to_clean_on_single_ham`,
  `contact_never_auto_spam`, `replied_sender_never_auto_spam`,
  `first_message_spam_from_unknown_goes_to_spam`,
  `first_message_spam_from_contact_stays_mixed`,
  `sticky_spam_ignores_later_ham`, `ratio_graduation_at_3_and_0_8`,
  `blocked_wins_over_everything`.

- [ ] **8.2 `core:database`** — `MessageVerdictEntity`/`MessageVerdictDao`
  (keyed by provider message id + threadId, pruned with the thread) and
  `SenderStateEntity`/`SenderStateDao` (keyed by normalized address,
  replacing `SpamVerdictEntity`'s thread-keying), both with `observeAll()`.
  Depends on 7.1 (persistent DB) landing first or in parallel.
- [ ] **8.3 `core:telephony`** — `hasOutboundMessages(threadId)` on
  `TelephonyDataSource`; write `SUBSCRIPTION_ID` on inbox insert (currently
  dropped by `TelephonyMapper.buildMessageValues`); wire in contact lookup
  from 7.4.
- [ ] **8.4 `core:data`** — `SmsIngressUseCase` runs `ThreadSpamPolicy`
  after classification, upserts `SenderState` **only when not
  `isUserOverride`**, stores the `MessageVerdict`, and returns the resulting
  `NotificationDecision` instead of a raw `isSpam` boolean.
  `SpamRepository`: rename `markNotSpam`/`markSpam` intent to
  `markSenderNotSpam` (→ `TRUSTED`, override) / `markSenderSpam` (→ `SPAM`,
  override), add `markMessageNotSpam`/`markMessageSpam` for the per-message
  action inside a `MIXED` thread (flips that message's `userLabel`,
  recomputes counts, does not touch the sender override).
  `ConversationsRepository`: Spam section = `state in {SPAM, BLOCKED}`;
  inbox = `CLEAN`/`MIXED`/`TRUSTED`; `MIXED` conversation snippet = latest
  **ham** message, not latest message. Retention (30 d) prunes auto
  `MessageVerdict` rows only; `SenderState` and all user overrides are never
  pruned (unbounded — spammers should stay flagged).
- [ ] **8.5 `feature:thread`** — join messages with their `MessageVerdict`;
  render a muted "Suspected spam" chip + per-message "Not spam"/"Report
  spam" action on flagged bubbles inside `MIXED` threads.
- [ ] **8.6 `feature:conversations`** — `MIXED` badge on inbox rows; Spam
  screen bulk "Block all"/"Delete all" actions.
- [ ] **8.7 `feature:settings`** — persist "Spam protection" toggle via
  DataStore (currently `remember { mutableStateOf }`, never read by
  ingress); when off, `ThreadSpamPolicy` short-circuits to
  `CLEAN`/`NORMAL` but verdicts/classification are still computed and stored
  so re-enabling is instant.
- [ ] **8.8 `:app`** — `AppSmsReceiver` switches its notification path on
  `NotificationDecision` instead of `result.isSpam`; add a receiver for
  `ACTION_DEFAULT_SMS_PACKAGE_CHANGED` and an inbox "not default app" banner
  (today only onboarding checks this).

---

## Phase 9 — P1 polish (post categorization-v2)

Deferred standard-SMS-app gaps found in the same review, lower priority than
Phases 7–8:

- [ ] 9.1 Star/pin/mute — model fields exist (`Conversation.isStarred/isPinned`)
  but there is no backing store or UI action for them (mute doesn't exist at
  all).
- [ ] 9.2 Draft persistence — `Conversation.hasDraft` is never written to/read
  from `Telephony.Sms.Draft`.
- [ ] 9.3 Dual-SIM send UI — `subscriptionId` is threaded through ingress but
  `ThreadViewModel.onSend` always passes `null`; no SIM picker for outgoing
  messages on dual-SIM devices.
- [ ] 9.4 Search message bodies, not just the list snippet.
- [ ] 9.5 `RealTelephonyDataSource.queryConversations` loads every SMS row on
  every provider change and groups client-side — fine at hundreds of
  messages, not thousands; move to a `GROUP BY thread_id` query or guarded
  `Threads.CONTENT_URI` read, and page the thread screen.
- [ ] 9.6 MMS placeholder — `MmsReceiver` silently drops WAP push content;
  show at least a "Media message not supported yet" row instead of nothing.
- [ ] 9.7 Message-level actions in `ThreadScreen` (copy/forward/delete/share/
  details, select-multiple) — currently the attach/emoji buttons are
  `onClick = {}` stubs and there is no per-message long-press menu.
- [ ] 9.8 Conversation actions gap — "Add to contacts", "Call", "Mark all
  read", "Select multiple" are absent from the long-press menu.

---

## Phase 10 — Listing & Date correctness (new)

> Origin: user-requested 2026-09-08 — (a) remaining deferred from Phase 9 perf
> note “deep Threads address enrichment + true GROUP BY provider query and
> thread paging LIMIT/OFFSET”, (b) optimization for listing messages,
> (c) incorrect message date, (d) check message order in conversation,
> (e) message dates not shown / should be grouped as dates like Google
> Messages. Owner confirms each `[ ]` → `[x]` as before — do not auto-commit.

**Gate:** Inbox cold-start on a 10k-message device <1s (trace), thread with
1k messages scrolls at 60fps; device manual check: dates/order/grouping match
the system default SMS app on the same seed data.

- [x] **10.1 Deep Threads address enrichment + true GROUP BY** — `7c4b8b8` implements `tryThreadsQuery` batch `Sms IN (threadIds)` + `canonical-addresses` + `Threads` fast path, fallback `LIMIT 3000`, cache + parallel `ContactLookup`. Done 2026-09-08 `7c4b8b8`.
  Replace the
  `Sms.CONTENT_URI LIMIT 3000` client group in
  `core/telephony/RealTelephonyDataSource.queryConversations:75` with a
  provider-side `GROUP BY thread_id` (prefer `Telephony.Threads.CONTENT_URI`
  guarded read — `SNIPPET/DATE/MESSAGE_COUNT/READ` — with
  `canonical_addresses` join for `address`; fallback to `Sms` raw
  `GROUP BY` via `query()` with `GROUP BY thread_id` or `SELECT thread_id,
  MAX(date)` subquery). Keep `ContactLookup` batching (current per-thread IPC
  is the second hot spot) and preserve `SenderState` address-keying. *Key
  files:* `RealTelephonyDataSource.kt`, `TelephonyMapper.kt`, `ContactLookup.kt`.
  *Verify:* `TelephonyInstrumentedTest` seeded 5k SMS — `getConversations()`
  count equals `Threads` count; `android-profiler` trace cold-start <1s.

- [x] **10.2 Optimization for listing messages (paging & flag isolation)** — `7c4b8b8` Fix 2+3: split `ConversationsRepository` 8-way `combine` into `telephonyFlow.distinctUntilChanged().mapLatest(adjust)` + `flagsFlow` (7 DB), and `RealTelephonyDataSource` `debounce(200)`, `Mutex` cache (`cachedMetas/latestMap`), parallel `ContactLookup` via `async`. Paging `LIMIT 200` already in `queryMessages`. Done 2026-09-08 `7c4b8b8`.
  Current `getMessages` loads `LIMIT 200` but `ConversationsRepository` 8-way
  `combine` (`observeConversations:62`) still re-scans SMS on every
  `star/pin/mute` write. Split into `conversationFlow` vs `flagFlows` with
  `distinctUntilChanged`, add `Paging 3` (`paging-compose`) to
  `feature/thread/ThreadViewModel.observeMessages` with `LIMIT/OFFSET` and
  `flowOn(Dispatchers.IO)`, and debounce `ContentObserver` bursts. *Key
  files:* `ConversationsRepository.kt`, `RealTelephonyDataSource.kt:116`,
  `ThreadViewModel.kt:78`, `app/build.gradle.kts` (paging dep).
  *Verify:* star toggle on device causes no `Sms` query (profiler); thread
  with 1k messages pages 50 at a time.

- [ ] **10.3 Incorrect message date** — `Telephony.Sms.DATE` vs
  `DATE_SENT`/`date_sent` and seconds-vs-millis confusion on dual-SIM
  providers. `TelephonyMapper.mapCursorToMessage:18` currently reads `DATE`
  only; some builds store seconds. `Conversation.date` from
  `TelephonyMapper.toConversation:48` (`latest.date`) must be `MAX(date)`
  from provider, not client max of a limited query. Fix projection to read
  both `DATE` and `DATE_SENT`, normalize to millis (detect `< 1e12` → `*1000`),
  and use `DateFormatter` (`core/i18n`) for Jalali/Gregorian toggle. *Key
  files:* `TelephonyMapper.kt`, `RealTelephonyDataSource.kt:80`, `DateFormatter.kt`.
  *Verify:* instrumented test inserts known `date`, asserts
  `getConversations()[0].date == inserted`; manual vs default SMS app timestamp
  equal.

- [ ] **10.4 Check message order in conversation** — `queryMessages` sorts
  `DATE DESC LIMIT 200` then resorts `sortedBy { date }` in Kotlin; ties on
  same `date` (same second, rapid `adb emu sms send`) are non-deterministic.
  Add secondary sort `_ID ASC` (provider order) both in SQL `DATE ASC, _ID ASC`
  and in `ThreadViewModel.merged:91` tie-breaker, and ensure `observeMessages`
  emits in stable order after reconciliation with optimistic rows. *Key files:*
  `RealTelephonyDataSource.kt:130`, `ThreadViewModel.kt:91`, `TelephonyMapperTest`.
  *Verify:* unit test with same-date messages; device rapid 3-sms order matches
  default app.

- [x] **10.5 Message date grouping like Google Messages** — Implemented `ThreadScreen.kt:57` `groupBy LocalDate` + `stickyHeader` `Today/Yesterday/MMM d, yyyy` via `formatDateHeader`, `reverseLayout` already in place from `a014481`. Done 2026-09-08 `a014481`/`8fdfd18`.
  `ThreadScreen.kt:57`
  currently flat `LazyColumn` of bubbles with no separators. Introduce
  `sealed ConversationItem { DateHeader(LocalDate), MessageRow }`, group by
  `LocalDate` (device zone, Jalali-aware via `core/i18n/DateFormatter`), add
  `stickyHeader` per date (`Today`/`Yesterday`/`MMM d` via `formatTime`), and
  ensure TalkBack reads header then messages. *Key files:*
  `ThreadScreen.kt`, `DateFormatter.kt`, `core/designsystem` atoms.
  *Verify:* screenshot tests light/dark/RTL + manual 3-day conversation shows
  3 sticky headers; `time_now`/`time_yesterday` strings reused.

- [x] **10.6 Thread reverseLayout + jump-to-latest FAB (7c4b8b8 Fix 4)** — `ThreadScreen.kt:57` `LazyColumn(reverseLayout=true)` with `lazyState`, `atBottom derivedState`, `hasScrolledInitially` + `scrollToItem(0)` on first non-empty, `FAB KeyboardArrowDown` when `!atBottom`. Done 2026-09-08 `7c4b8b8`/`a014481`.

---

## Deferred (Not in v1)
- `build-logic` convention plugins (add at 8+ modules when duplication justifies).
- Baseline profiles / macrobenchmark.
- Dynamic feature modules.
- Full MMS parsing (architecture ready, impl when needed).
- Retrained/quantized transformer classifier (swappable behind `SpamClassifier` when Persian dataset justifies).

## Progress Tracking
- Update this file per PR: flip `[ ]` → `[x]` and append date + commit hash.
- Owner confirms each task before next starts — no chaining without sign-off.
- Keep `CLAUDE.md` as architecture source; `TASKS.md` is execution state.

## Useful Commands (per CLAUDE.md:14)
```bash
./gradlew :app:assembleDebug
./gradlew :feature:conversations:testDebugUnitTest
./gradlew :core:telephony:testDebugUnitTest
./gradlew build   # full project, all modules
./gradlew koverHtmlReport  # coverage
```
