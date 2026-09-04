# TASKS.md — NoSpam Implementation Plan

> Source architecture: `CLAUDE.md`. Stitch UI: `/home/amirhossein/Downloads/stitch_modern_sms_messenger/` (8 screens + `adaptive_messenger/DESIGN.md`). This file is the execution tracker — one checkbox = one verifiable outcome. Do not start next task until the current one is marked done and confirmed by owner.

**Global constraints**
- Stack: Kotlin + Compose + Material 3, Navigation-Compose type-safe `@Serializable` routes, Hilt, Coroutines/Flow, Room, DataStore, WorkManager.
- Module deps: `:app → feature:* → core:*`, `core:*` never depend on each other (shared data in `core:model`). `core:model` has zero Android imports (plain Kotlin `jvm` module).
- i18n: `AppCompatDelegate.setApplicationLocales` + `res/xml/locales_config.xml` + `android:localeConfig` + `AppLocalesMetadataHolderService (autoStoreLocales)`, `android:supportsRtl="true"`, `start/end` not `left/right`, `Icons.AutoMirrored`, `resourceConfigurations ["en","fa"]`, bidi isolation for phone numbers/timestamps, Jalali vs Gregorian decision pending.
- SMS ownership: only `core:telephony` touches `ContentResolver` (`Telephony.Sms`/`Mms`/`Threads`), `SmsManager`, `SubscriptionManager`. Room holds blocklist/verdicts/metadata only — no SMS mirroring.
- Fonts: `Hanken Grotesk` + `Inter` via Google Fonts Provider (downloadable, certs in `res/font/*.xml`) with optional bundled fallback for offline; Persian adds `Vazirmatn` (ZWNJ/kashida aware). Dark theme generated from seed `0xFF005BBF` via Material Theme Builder then hand-tuned — do not ship inverted light. Default-SMS via `RoleManager.createRequestRoleIntent(ROLE_SMS)` (Q+) / `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` (pre-Q) + `isRoleHeld` check.
- MMS: stub only in v1 (`MmsReceiver` receives `WAP_PUSH_DELIVER` but no full parsing). Architecture stays extensible via `TelephonyDataSource` interface.
- Execution: incremental PRs in dependency order, `gradlew build` + smoke test after each. `com.example.nospam` rename deferred. `minSdk` raised `24 → 26` in Phase 0.

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
- [x] **3.4 `feature:thread`** (`ThreadViewModel.kt:1` `ThreadUiState` + `onSend`, `ThreadScreen.kt:1` `LazyColumn` bubbles `MessageBubbleShapeIncoming/Outgoing` 16dp sharp corner, `surfaceContainerHigh` vs `primary`, `compose bar` pill `24dp` with `AddCircle`/`Face`/`Send` + `NewConversationScreen` `To:` field + `Top contacts`, `SettingsScreen.kt:1` `LazyColumn` sections General/Privacy/Advanced/About with `Switch` and `KeyboardArrowRight` (fixed `ChevronRight`/`Image`/`AddPhotoAlternate` unresolved `Icons.Filled` → `Warning`/`Face`/`Delete`). *Verify:* `./gradlew :app:assembleDebug` SUCCESS; `./gradlew build` 949 tasks. Done 2026-09-04.

---

## Phase 4 — Remaining UI Polish

**Gate:** All 8 Stitch screens pixel-matched (allow font rendering diff), light/dark/LTR/RTL previews attached.

- [x] **4.1 Archived** — Replaced placeholder `ArchivedScreen` (`ConversationsScreen.kt:158`) with `SwipeToDismissBox` `StartToEnd` 0.5 threshold + `primary` background `Unarchive` (`Delete` icon, `RoundedCornerShape 12dp`), `LazyColumn` 2 fake archived (`Bank Alerts`, `Home Depot`), empty state `96dp` `surfaceContainer` circle `Delete` `tertiary` 48dp + “Archive is empty” `headlineMedium`. Fixed duplicate key `1` (pinned+recent) via `ViewModel drop(1)` earlier. *Verify:* `./gradlew :feature:conversations:assembleDebug` SUCCESS; manual swipe removes item, empty shows. Done 2026-09-04.
- [x] **4.2 Spam & Blocked** — `SpamScreen` (`ConversationsScreen.kt:228`) `errorContainer` banner “deleted after 30 days” (`Warning` `onErrorContainer`), `Empty Spam` `TextButton` + `SwipeToDismissBox StartToEnd` “Not spam” `tertiaryContainer` + `TextButton` “Not spam” per row (inferred, not in mockup per `CLAUDE.md:6` → `SpamRepository` hook), 3 fake spam (`Win A Free Cruise!` etc.) with `error` avatar `Warning`. Added `Not spam` snackbar. *Verify:* `./gradlew :feature:conversations:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **4.3 Settings** — `SettingsScreen.kt:1` `LazyColumn` General/Privacy/Advanced/About + language picker (`LocaleHelper.setLocale` toggle `en↔fa`, `selectedLocale` + `fa` `فارسی`), default-SMS `isDefaultSmsApp`/`requestDefaultSmsRole` (`RoleManager` Q+ / `ACTION_CHANGE_DEFAULT` pre-Q) with `rememberLauncherForActivityResult`, `Version info`/`Terms`, `Auto-download MMS` `Switch`. Added `activity-compose` to `feature:settings/build.gradle.kts:34`. *Verify:* `./gradlew :feature:settings:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **4.4 Shell details** — `NoSpamAppShell.kt:1` drawer `ModalDrawerSheet` + `Mark all as read` `HorizontalDivider` + `Spacer(weight=1f)` + `Settings` at bottom, `Icons.Warning` for Spam, `Delete` for Archived (fallback for missing `Archive`/`Report` in `Icons.Filled` default), `Spacer`/`Box` imports, `16.dp` header. Starred maps to `ConversationFilter.STARRED` chip (no separate `FavoritesScreen`). FAB stays `feature:conversations` `FloatingActionButton` `16dp`. *Verify:* `./gradlew :app:assembleDebug` SUCCESS. Done 2026-09-04.
- [x] **4.5 Preview & screenshot gate** — Added `@Preview` (`ConversationsScreenPreview` light/dark/RTL `fa`, `ArchivedEmptyPreview`, `SpamPreview`, `ThreadScreenPreview` light/dark/RTL, `SettingsScreenPreview` en/fa, `NoSpamAppShellPreview` light/dark) via `ui.tooling.preview` (added to 4 `feature/*` `build.gradle.kts`), fixed `tooling` unresolved via `implementation(libs.androidx.compose.ui.tooling.preview)`. Bidi `BidiHelper.wrap` `\u2068` isolates for `+98 912…` inside Persian. *Verify:* `./gradlew :app:assembleDebug` SUCCESS; `./gradlew build` 949 tasks SUCCESS. Done 2026-09-04.

---

## Phase 5 — Backend Wiring (Spam Pipeline + SMS I/O)

**Gate:** Device receives real SMS → classified → routed correctly; instrumented test on emulator with seeded SMS.

- [x] **5.1 SmsReceiver wiring** — Manual `AppContainer` (no Hilt — lighter alternative per CLAUDE.md; receivers can't use constructor injection anyway). `SmsIngressUseCase` (`core/data`) orchestrates: `getMessagesFromIntent` → `TfidfSpamClassifier.classify` → `getOrCreateThreadId` → `insertInboxMessage` with READ=1 for spam → verdict upsert → opportunistic prune. `AppSmsReceiver` (`:app`) uses `goAsync` + IO scope. Manifest owns exactly one SMS_DELIVER entry; legacy + telephony receiver entries removed (files kept). *Verify:* `./gradlew :core:data:testDebugUnitTest` (4 ingress tests) + emulator `adb emu sms send` + logcat `Prediction: spam/ham`. Done 2026-09-05.
- [x] **5.2 Notifications routing** — Ham → `MessagingStyle` + `RemoteInput` direct-reply `PendingIntent` (explicit package, `thread_id` + `subscription_id` extras); spam → silent (low-importance channel only). `POST_NOTIFICATIONS` added. *Verify:* notification for ham, suppressed for spam. Done 2026-09-05.
- [x] **5.3 HeadlessSmsSendService** — `onStartCommand` handles `RESPOND_VIA_MESSAGE`: `RemoteInput` text (fallback `EXTRA_TEXT`/`sms_body`), subscription-aware `SmsManager`, writes sent message to provider, cancels notification. Pure `pickReplyText`/`normalizeSubscriptionId` unit-tested. Done 2026-09-05.
- [x] **5.4 Corrections & retention** — `markNotSpam`/`markSpam` (override-preserving) + `BlocklistRepository` CRUD + `pruneOldSpam(30d)` (overrides never pruned; opportunistic prune on each ingress, no WorkManager for v1). SpamScreen "Not spam" (button + swipe) persists via `NavHost → SpamRepository`. *Verify:* `SmsIngressUseCaseTest.pruneOldSpam keeps user overrides` PASS. Done 2026-09-05.

---

## Phase 6 — Testing & Coverage

**Gate:** `./gradlew build` + merged coverage report, no P1 bugs.

- [ ] **6.1 Unit tests** — `core:ml` (TF-IDF parity, Persian URL/NUM_TOKEN, ZWNJ), `core:model` mapping, ViewModels (Turbine `StateFlow<UiState>`), repos (fakes). *Verify:* `./gradlew :core:ml:test :core:data:testDebugUnitTest` with coverage.
- [ ] **6.2 Compose UI tests** — `createAndroidComposeRule` for conversations/thread/settings (filtering, RTL mirroring, empty states, "not spam"). *Verify:* `./gradlew :feature:conversations:testDebugUnitTest :feature:thread:testDebugUnitTest`.
- [ ] **6.3 Instrumented tests** — `core:telephony` query/insert, `core:database` migration, permission/role flow, notification PendingIntent on API 26+ emulator. *Verify:* `./gradlew connectedDebugAndroidTest` (keep query-building pure so most coverage is fast unit tests per `CLAUDE.md:5`).
- [ ] **6.4 Coverage & CI** — Kover/Jacoco merged report, enforce ≥80% for `core:*`, screenshot diff in CI, final `./gradlew build` all modules. *Verify:* `build/reports/kover/html` shows target, no `com.example.nospam` remaining references beyond deferred rename.

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
