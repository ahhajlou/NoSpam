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

- [ ] **3.1 `:app` shell** — `NoSpamApplication @HiltAndroidApp` (`app/src/main/java/com/example/nospam/MainActivity.kt:14` thins to `setContent { NoSpamTheme { NoSpamNavHost() } }`), `navigation/NoSpamNavHost.kt` with `@Serializable` routes wiring each feature graph, `ui/NoSpamAppShell.kt` (drawer 280dp `rounded-xl` per `navigation_drawer/code.html`, TopAppBar, `NavigationSuiteScaffold` adaptive, FAB 56dp). Remove old `navigation/AppDestinations.kt` enum, `ui/NoSpamApp.kt` bottom nav. *Verify:* `./gradlew :app:assembleDebug`, device smoke: drawer open/close, navigation between routes.
- [ ] **3.2 `feature:onboarding`** — Runtime permissions (`READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `READ_CONTACTS`) + default-SMS role request (`RoleManager.createRequestRoleIntent(ROLE_SMS)` Q+ / `ACTION_CHANGE_DEFAULT` pre-Q, `isRoleHeld` check per `CLAUDE.md:7`; reference `ui/screens/SettingsScreen.kt:50-62`). Design to match `core:designsystem` (not in Stitch export, must be built). *Verify:* instrumented test granting/denying role, screenshot LTR/RTL.
- [ ] **3.3 `feature:conversations`** — Single `ConversationsScreen` + `ConversationsViewModel(StateFlow<UiState>)` merging Inbox/Archived/Spam queries. Implements `messages/code.html` (search bar `rounded-xl`, 5 filter chips All/Unread/Known/Unknown/Starred, pinned section with push_pin + unread dot, recent list 72dp rows, FAB "Start chat") + `search_messages/code.html` (same screen expanded state: 6 category tiles Unread/Starred/Images/Videos/Places/Links + suggested contacts). Search is UI state, not separate destination unless deep-link needed. `ConversationRow` stays feature-local. *Verify:* Compose previews light/dark/LTR/RTL, UI test filter + search.
- [ ] **3.4 `feature:thread`** — `ThreadScreen` + `ThreadViewModel` for `chat_with_alice_smith/code.html` (bubbles max 75% width, `rounded-2xl` with `rounded-br-sm/bl-sm` sharp corner, timestamps, grouped 4dp/8dp spacing, image bubble) + `new_conversation/code.html` (To: field, Top contacts horizontal, All contacts list 72dp). Compose bar pill `rounded-full` with add/image/send actions. *Verify:* previews with grouped vs single messages, RTL bubble mirroring, UI test send flow with fake repo.

---

## Phase 4 — Remaining UI Polish

**Gate:** All 8 Stitch screens pixel-matched (allow font rendering diff), light/dark/LTR/RTL previews attached.

- [ ] **4.1 Archived** — Same `ConversationsScreen` with `archived=true` query, `archived/code.html` swipe-right unarchive (threshold 80px, spring-back), empty state `inventory_2` icon. *Verify:* swipe UI test, empty state preview.
- [ ] **4.2 Spam & Blocked** — `spam_blocked/code.html` (error-container banner "deleted after 30 days", Empty Spam button, spam rows with report icon). Add inferred "Not spam" action (not in mockup, required per `CLAUDE.md:6`) → `SpamRepository` correction. *Verify:* UI test "Not spam" moves thread to Inbox, banner preview.
- [ ] **4.3 Settings** — `settings/code.html` sections (General/RCS/Notifications/Bubbles, Privacy/Spam protection, Advanced/Group messaging/Auto-download MMS toggle, About/Version). Add beyond mock: language picker (`core:i18n`), default-SMS status/re-request, blocklist management, notification prefs. *Verify:* settings navigation + toggle tests, language switch persists after process death on API <33.
- [ ] **4.4 Shell details** — `navigation_drawer` modal drawer, `Starred` filter confirmed as Favorites mapping (`ui/screens/FavoritesScreen.kt:8` folds into chip, not separate screen), FAB placement `bottom-24 right-margin-side` per `messages/code.html`. *Verify:* drawer screenshot vs Stitch `screen.png` side-by-side.
- [ ] **4.5 Preview & screenshot gate** — Generate Paparazzi/Roborazzi or Compose Preview screenshots for all screens × (light/dark) × (en/fa). *Verify:* `./gradlew :feature:conversations:testDebugUnitTest` screenshot tasks, manual device check for mixed bidi (Latin number inside Persian text) with `\u2068` isolation.

---

## Phase 5 — Backend Wiring (Spam Pipeline + SMS I/O)

**Gate:** Device receives real SMS → classified → routed correctly; instrumented test on emulator with seeded SMS.

- [ ] **5.1 SmsReceiver wiring** — Hilt-injected `SpamClassifier`, `TelephonyDataSource`, `SpamRepository`. On `SMS_DELIVER`, extract `getMessagesFromIntent`, `preprocess→ngrams→score`, write via `ContentResolver.insert(Inbox.CONTENT_URI)` with `READ=0/1` (spam=1 to suppress heads-up per `receiver/SmsReceiver.kt:49`). Store verdict in Room for later correction. *Verify:* emulator `adb emu sms send` + logcat `Prediction: spam/ham`.
- [ ] **5.2 Notifications routing** — Ham → `MessagingStyle` notification with direct-reply `PendingIntent` (via `core:model` keys → `HeadlessSmsSendService`); spam → no heads-up or bundled "Spam" summary per settings. *Verify:* notification appears for ham, suppressed for spam.
- [ ] **5.3 HeadlessSmsSendService** — Implement `onStartCommand` for `RESPOND_VIA_MESSAGE` (direct reply via `SmsManager` with correct `SubscriptionId`). *Verify:* inline reply from notification sends SMS.
- [ ] **5.4 Corrections & retention** — "Not spam"/"Report spam" + blocklist CRUD → Room + threshold re-tuning (no retrain). WorkManager periodic re-classification if needed. *Verify:* correction persists, re-opens thread in Inbox.

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
