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

- [ ] **1.1 `core:model`** (`core/model/`) — Pure Kotlin domain: `Conversation`, `Message`, `Participant`, `ThreadId`, `RawMessage`, `SpamVerdict` (label + score + calibratedProbability), `BlocklistEntry`, plus cross-module constants (direct-reply intent action/extra keys for `HeadlessSmsSendService`). No Android imports. *Verify:* `./gradlew :core:model:test` + `grep -r "android\." core/model/src` empty.
- [ ] **1.2 `core:common`** (`core/common/`) — `Result<T>`/error types, dispatcher qualifiers (`@IoDispatcher` etc.), coroutine scope helpers, `PermissionChecker` interface (folded `core:permissions`). *Verify:* unit test for `Result` mapping.
- [ ] **1.3 `core:designsystem`** (`core/designsystem/`) — Port `adaptive_messenger/DESIGN.md:3-91` tokens to `theme/Color.kt` (full `LightColors` per `CLAUDE.md:8`), generate `DarkColors` from seed via Theme Builder then hand-tune, `Type.kt` (Hanken Grotesk 500/600 → `headlineLarge/Medium`, Inter 400/500 → `bodyLarge/Medium/labelLarge` with `0.5/0.25/0.1sp` spacing), `Shape.kt` (4/8/12/16/24/full), `Theme.kt` (`NoSpamTheme` with `isSystemInDarkTheme` + `dynamicColor` false for custom). Atoms: `Avatar`, `PillButton`, `SearchBar`, `FilterChip`, `SwipeAction`. Fonts: `res/font/` provider XMLs for Inter+Hanken (Google Fonts Provider) + bundled `Vazirmatn` for `fa`. *Verify:* `@Preview` light/dark/LTR/RTL screenshots for tokens, `gradlew :core:designsystem:assembleDebug`.
- [ ] **1.4 `core:testing`** (`core/testing/`) — Fakes: `FakeTelephonyDataSource`, `FakeSpamClassifier`, `FakePermissionChecker`, `FakeBlocklistDao`. Test helpers for cursor→model mapping. *Verify:* `./gradlew :core:testing:assemble`.
- [ ] **1.5 `core:i18n`** (`core/i18n/`) — Locale switching via `AppCompatDelegate.setApplicationLocales`, DataStore persistence, date formatter (Gregorian primary; Jalali behind feature flag pending decision), `BidiFormatter.unicodeWrap` helper, RTL layout helpers. Per-module `res/values*/strings.xml` strategy documented. *Verify:* unit test locale switch + instrumented RTL layout test.

---

## Phase 2 — Platform & Data

**Gate:** `./gradlew :core:database:testDebugUnitTest :core:ml:test :core:telephony:testDebugUnitTest :core:data:testDebugUnitTest` green.

- [ ] **2.1 `core:database`** (`core/database/`) — Room: `BlocklistEntity`, `SpamVerdictOverride` (threadId + verdict + isUserCorrection), `ModelMetadata` (version, threshold), DAOs, `NoSpamDatabase`. In-memory builder for tests, migration strategy. *Verify:* DAO tests (Robolectric), `gradlew :core:database:kspDebugKotlin` no errors.
- [ ] **2.2 `core:ml`** (`core/ml/`) — Extract `app/src/main/java/com/example/nospam/ml/SpamDetector.kt:15-80` behind `interface SpamClassifier { suspend fun classify(RawMessage): SpamVerdict }`. Move `app/src/main/assets/spam_model.json:1` → `core/ml/src/main/assets/`. Replace `Gson` with `kotlinx.serialization`. `TFIDFPreprocessor` (URL→`URLTOKEN`, digits→`NUM_TOKEN`, `ي→ی`, `ك→ک`, diacritics strip, ZWNJ `\u200C` + kashida `\u0640` handling), `CharWbNgramExtractor(2..4)` (space-padded `char_wb`). Threshold calibration hook for "not spam" feedback. *Verify:* parity test vs Python `TfidfVectorizer(char_wb 2-4)+LinearSVC` on sample corpus, Persian SMS samples, `./gradlew :core:ml:test`.
- [ ] **2.3 `core:telephony`** (`core/telephony/`) — Move `receiver/SmsReceiver.kt`, `receiver/MmsReceiver.kt`, `service/HeadlessSmsSendService.kt` + own `AndroidManifest.xml` fragment (`SMS_DELIVER`/`WAP_PUSH_DELIVER`/`RESPOND_VIA_MESSAGE` + `SENDTO sms/smsto/mms/mmsto` per `app/src/main/AndroidManifest.xml:40-88`). `TelephonyDataSource` interface + `ContentResolver` impl, `SmsManager` via `SubscriptionManager` (multi-SIM). Keep query-building pure for unit tests. `MmsReceiver` stub only (logs, no parse) for v1 extensibility. *Verify:* unit tests for cursor mapping + `ContentValues` building via `core:testing` fakes; one instrumented test on emulator seeding `Telephony.Sms.CONTENT_URI`.
- [ ] **2.4 `core:notifications`** (`core/notifications/`) — Channels, `MessagingStyle`, direct-reply `PendingIntent` using `core:model` keys (no `core:telephony` dep per `CLAUDE.md:4`). Spam vs ham routing (spam skips heads-up or bundles to Spam). *Verify:* unit test for channel creation + intent building.
- [ ] **2.5 `core:data`** (`core/data/`) — Repositories: `ConversationsRepository`, `MessagesRepository`, `BlocklistRepository`, `SpamRepository`. Flow-based, single source for UI. Depends on `core:model`, `core:database`, `core:telephony`, `core:ml`. *Verify:* repo tests with fakes, `StateFlow` emissions via Turbine.

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
