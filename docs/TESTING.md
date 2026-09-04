# NoSpam — Android SMS app with on-device spam filtering

A full replacement SMS messenger for Android with on-device ML spam/scam
classification (TF-IDF + linear model, no server round-trip).
Kotlin + Jetpack Compose + Material 3, English first with full RTL support
(Persian is the first RTL target).

Architecture source of truth: [`../CLAUDE.md`](../CLAUDE.md).
Execution tracker: [`../TASKS.md`](../TASKS.md) (all 6 phases complete).

## Module graph

```
:app
 ├─ feature:conversations   (Inbox / Archived / Spam & Blocked / Search)
 ├─ feature:thread          (message thread + new conversation)
 ├─ feature:settings
 └─ feature:onboarding      (permissions + default-SMS-app request)

feature:* → core:designsystem, core:model, core:data, core:i18n
core:data → core:model, core:database, core:telephony, core:ml
core:* → only core:common + core:model (never each other)
core:common, core:model, core:testing → leaves
```

- `:app → feature:* → core:*`, one-way, enforced by Gradle.
- `core:model` is a plain Kotlin/JVM module — zero Android imports.
- SMS content lives in Android's Telephony provider; only `core:telephony`
  touches it. Room holds app-owned data only (blocklist, verdicts, metadata).
- No Hilt/Koin: a manual `AppContainer` in `:app` wires the four singletons
  (receivers can't use constructor injection anyway).

## Prerequisites

- JDK 25 (Gradle daemon + `kotlin.jvmToolchain(25)` for JVM modules;
  app bytecode target stays 17 for dex compatibility).
- Android SDK with platform 36 (compile/target) and minSdk 26.
- An emulator or device for install, UI tests, and instrumented tests.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew build          # all 15 modules: assemble + lint + unit tests
```

Install and smoke-test:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep AppSmsReceiver   # Prediction: ham/spam + Score
```

## Tests

### 1. JVM unit tests — fast, run everywhere (53 tests, 0 failures)

Pure logic with JUnit + fakes from `core:testing`, no device needed:

```bash
# everything at once
./gradlew :core:model:test :core:common:test :core:testing:test \
  :core:database:testDebugUnitTest :core:data:testDebugUnitTest \
  :core:ml:testDebugUnitTest :core:telephony:testDebugUnitTest \
  :core:notifications:testDebugUnitTest :core:i18n:testDebugUnitTest \
  :core:designsystem:testDebugUnitTest \
  :feature:conversations:testDebugUnitTest :feature:thread:testDebugUnitTest
```

| Module | What's covered |
|---|---|
| `core:model` | Domain types, `TelephonyConstants` |
| `core:common` | `Result` map/fold, dispatchers, permission constants |
| `core:testing` | All fakes (classifier counts, telephony filters) |
| `core:database` | In-memory DAOs: blocklist, verdict override, retention prune |
| `core:data` | Repos + `SmsIngressUseCase` (spam→READ=1, ham→unread, insert failure, override-preserving prune) |
| `core:ml` | Preprocess (URL/NUM_TOKEN, Persian normalization, ZWNJ), `char_wb` n-grams, classifier parity |
| `core:telephony` | Constants, reply-text/subscription pure helpers |
| `core:i18n` | RTL detection, bidi isolates, date formatting |
| `core:designsystem` | Stitch seed colors, typography, shapes |
| `feature:*` | `ConversationsViewModel` (incl. duplicate-key crash regression), `ThreadViewModel` send/draft |

### 2. Compose UI tests — need an emulator

`createComposeRule` suites under each feature's `src/androidTest`
(conversations list/filter/search/click, spam not-spam + empty-spam,
thread send flow, settings sections/switch/language dialog):

```bash
./gradlew :feature:conversations:connectedDebugAndroidTest \
  :feature:thread:connectedDebugAndroidTest \
  :feature:settings:connectedDebugAndroidTest
```

Compile/package check without a device:

```bash
./gradlew :feature:conversations:assembleDebugAndroidTest \
  :feature:thread:assembleDebugAndroidTest \
  :feature:settings:assembleDebugAndroidTest
```

> Naming rule: test function names must use underscores
> (`` `not_spam_removes_row` ``). Spaces in backtick names break D8
> dexing pre-dex-040.

### 3. Instrumented tests — need an emulator with NoSpam as default SMS app

- `core/telephony/.../TelephonyInstrumentedTest` — SMS insert/query
  round-trip (skips itself via `Assume` unless the app holds the
  default-SMS role) + notification reply-action assertion.
- `core/telephony/.../TelephonyMapperDeviceTest` — real `ContentValues`
  mapping (impossible on JVM: `android.content` is stubbed).
- `core/designsystem/.../AtomsTest` — Avatar/chip/search composables.

```bash
# grant the role first (or set it in Settings), then:
./gradlew :core:telephony:connectedDebugAndroidTest
```

Compile/package check without a device:

```bash
./gradlew :core:telephony:assembleDebugAndroidTest \
  :core:designsystem:assembleDebugAndroidTest
```

## Coverage (Kover)

Merged report over all 15 modules (Kover `0.9.9`, root is the merging
module via `kover(...)` deps):

```bash
./gradlew :koverXmlReport    # build/reports/kover/report.xml + html/
./gradlew :koverVerify       # ratchet gate (also runs in CI)
```

Current merged JVM line coverage: **32.8% (484/1476)**.
Module highs: `core:ml` 89%, `core:database/dao` 83%, `core:model` 78%,
`core:data` 68%, `core:testing` 94%, design tokens 94%.

Two deliberate policies, both documented in the root `build.gradle.kts`:

1. **Dead legacy tree excluded** (`app/.../ui|navigation|receiver|service|ml`,
   deprecated telephony shim) — unregistered from the manifest and
   unreferenced, kept only until end-of-project cleanup.
2. **Ratchet, not the 80% goal**: `total { verify { minBound(30) } }`
   fails the build on regression ("never lower this bound"). 80% honestly
   requires the emulator suites above plus working Robolectric (currently
   broken on JDK 25 — `RoboCookieManager` NoClassDefFound — so
   `NotificationCompat` builders and `ContentValues` mapping stay
   device-tested). Raise the bound as those suites land.

## CI

`.github/workflows/android.yml` (JDK 25 + Android SDK): `build` →
test-APK assembly → `:koverXmlReport :koverVerify` → report upload.
Connected tests are intentionally excluded — they need an emulator with
the default-SMS role; run them locally per §3 above before release.

## Manual verification (spam pipeline)

```bash
adb emu sms send +989121234567 "see you tomorrow"     # ham → notification with Reply
adb emu sms send 1000 "You won! Click here to claim"  # spam → silent, verdict stored
```

Reply inline from the ham notification, then check the thread shows the
reply. "Not spam" on a spam row persists an override that survives the
30-day auto-spam prune.

## Known limitations / next steps

- Inbox/Spam/Thread read live provider data via `AppContainer` repos
  (fake fallback only in previews/tests). Archived is truthfully empty —
  the provider has no archived flag, so an app-owned archive store is
  still future work. Contact-name resolution is also future work
  (rows show the raw address until then).
- Room is an in-memory stand-in (KSP + AGP 9.0/Kotlin 2.2 incompat);
  swap in the real `RoomDatabase` behind the same DAO interfaces.
- Fonts are `FontFamily.Default` placeholders; wire Inter/Hanken via the
  Google Fonts provider + bundled Vazirmatn for Persian.
- `com.example.nospam` rename + legacy `app/src/main/java` deletion at
  end of project (then drop its Kover exclusion).
