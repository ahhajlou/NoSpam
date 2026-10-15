# Testing NoSpam

How to run the tests, what each layer is for, and what is deliberately not
covered.

> **Rewritten 2026-09-20 from the code.** The previous version was written on
> 2026-09-04 for Phase 6 and patched occasionally after, so most of its numbers
> and several of its structural claims had stopped being true: it described
> Room (never used), an eleven-module graph (there are 18), a dependency rule
> the code has never followed, `platform 36` (compileSdk is 37), a Robolectric
> failure that was fixed in Wave 2A, a Kover exclusion that no longer exists,
> and a `.maestro/smoke_test.yaml` that had been replaced by twelve flows. If a
> claim here contradicts the code, the code wins; fix this file.

**Architecture lives in [`../CLAUDE.md`](../CLAUDE.md)** — modules and their
tiers in §2, the testing rules in §9. This file does not restate them, which is
how the previous version drifted into contradicting them.

## Layers

| Layer | Where | Needs a device | State 2026-09-20 |
|---|---|---|---|
| Unit, including every Compose screen | `src/test` in 17 modules | no | 339 tests |
| Instrumented, storage | `core/database/src/androidTest` | yes | 44 tests in 10 files |
| Instrumented, telephony | `core/telephony/src/androidTest` | yes | 13 tests, all run (2026-09-24) |
| End-to-end | `.maestro/flows` | yes | 12 flows, 8 run by default |
| Manual checks | `tools/*.sh` | yes | permission gate, block/unblock persistence |

Tests are written against behavior, not implementation. The fakes in
`core:testing` are the substitution point — do not hand-roll a local fake.
Turbine for Flow assertions.

## Prerequisites

- **JDK 25.** The Gradle daemon is pinned to a JetBrains toolchain through
  `gradle/gradle-daemon-jvm.properties` so the CLI and the IDE share one
  daemon. Module bytecode targets Java 17.
- **Android SDK platform `android-37.0`** (compileSdk 37, targetSdk 36,
  minSdk 26).
- **Rust and the NDK** for `core:ml`'s native library, which the JVM tests load
  too: rustup, `cargo install cargo-ndk --locked`, the NDK version in
  `core/ml/build.gradle.kts`, and the submodule checked out
  (`git submodule update --init`). See the README's "Build".
- **A device or emulator** for the instrumented suites, the Maestro flows and
  the manual scripts. The Compose UI tests do not need one.
- **Maestro CLI** for the flows: `curl -Ls "https://get.maestro.mobile.dev" | bash`.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew build          # every module: assemble + lint + unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep AppSmsReceiver   # "Prediction: ham/spam (Score: …) state=… notif=…"
```

## 1. Unit tests — no device, run everywhere

```bash
./gradlew test                              # all 18 modules
./gradlew :feature:thread:testDebugUnitTest # one module
```

| Module | Tests | What is covered |
|---|---|---|
| `core:model` | 24 | Domain types, `ThreadSpamPolicy`, `TelephonyConstants` |
| `core:common` | 8 | `Result` map/fold, dispatchers, permission constants |
| `core:testing` | 10 | The fakes themselves (classifier counts, telephony filters) |
| `core:database` | 31 | DAO logic against `NoSpamDatabase.inMemory()` |
| `core:data` | 205 | Repositories and `SmsIngressUseCase` (ingress ordering, override-preserving prune); `SettingsRepository`/`DraftRepository` defaults, stored key names and failure fallbacks |
| `core:ml` | 13 | Parity with training on the host build of the Rust core (512 fixture cases: every stage, the n-grams, the score), the model's pipeline check, TF-IDF scoring, deferred loading |
| `core:telephony` | 62 | Address normalization, default-SMS detection, `SmsManager` resolution |
| `core:preferences` | 12 | The DataStore source against real files: type round-trips, removal, serialized edits, unsupported types |
| `core:notifications` | 9 | Channel ids, reply-extra constants and the incoming-alert rule (notify / in-app sound / nothing) — see Known gaps |
| `core:i18n` | 3 | RTL detection, date formatting |
| `core:designsystem` | 36 | Color roles, type scale, shapes, avatar palette, top-bar action partition, bidi isolation, avatar semantics and contact photos |
| `feature:conversations` | 50 | `ConversationsViewModel` + the inbox/spam screens |
| `feature:thread` | 113 | `ThreadViewModel`, SMS segment counting, emoji insertion + the thread and new-conversation screens |
| `feature:settings` | 106 | `SettingsViewModel`, `SpamSettingsViewModel`, `GeneralSettingsViewModel` + the settings pages and dialogs |
| `feature:onboarding` | 12 | The permission list and the onboarding screen |
| `feature:export`, `feature:mldebug` | 22 | Developer tools in the drawer |
| `:app` | 69 | `AppContainer` wiring, `AppSmsReceiver`, and launch-intent parsing (`SENDTO`, notification taps) |

**Compose screens are tested here, not on a device.** Suites use
`createComposeRule` under Robolectric:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
```

plus `testOptions.unitTests.isIncludeAndroidResources = true` in the module's
build file. **The qualifiers are not optional**: Robolectric's default window is
320x470px, too small to compose a single list row, and every query then fails
with "could not find any node".

## 2. Instrumented tests — need an emulator

Only three modules have an `androidTest` source set, and the rule for adding
another is in CLAUDE.md §9: an instrumented test is for what the JVM cannot tell
the truth about. Until 2026-09-20 every Compose screen also had a device copy
asserting the same things; those were folded into the JVM suites and deleted.

- `core:database` — nine `Sqlite*Dao` suites against real SQLite, plus
  `SqliteNoSpamOpenHelperTest` (every table is created, the version is 4, data
  survives a reopen).
- `core:telephony` — `TelephonyInstrumentedTest` (the notification reply
  action, and an SMS insert/query round trip), `TelephonyMapperDeviceTest` (real
  `ContentValues` mapping, which JVM stubs cannot do) and
  `DeliveryReportDeviceTest` (real 3GPP status-report PDUs recorded on real
  rows). 13 tests, all run.

  Two things about this suite are easy to trip over. It is **self-instrumenting**:
  the test APK is `com.nospam.nospam.core.telephony.test`, and `:app`'s manifest
  is not part of it, so `core/telephony/src/androidTest/AndroidManifest.xml`
  declares the SMS permissions itself — without it `GrantPermissionRule` fails
  before any assertion with "Failed to grant permissions, see logcat for
  details". And a test that writes the provider needs the *test* package to
  hold the default-SMS role. The role requires an SMS_DELIVER receiver and a
  SENDTO activity, which live in `:app`, so the androidTest manifest declares
  two inert stubs, and `SmsRoleRule` takes the role through the shell for the
  test and hands it back. Until 2026-09-24 the round trip had no way to get the
  role and always skipped. While a test holds the role a real incoming SMS goes
  to the stub and is lost: use an emulator or a spare device.
- `core:ml` — `CoreParityDeviceTest`: the fixture check of the JVM's
  `CoreParityTest` (shared code in `src/sharedTest`), against the phone's build
  of the Rust core. Run it before each release. It is self-instrumenting
  (`com.nospam.nospam.core.ml.test`), so it never touches the installed app and
  needs no role or permission.

  `connectedDebugAndroidTest` runs only the device's main ABI. On a phone that
  also runs 32-bit apps (the A26 does), check armeabi-v7a by hand:

  ```bash
  ./gradlew :core:ml:assembleDebugAndroidTest
  adb install -r -t --abi armeabi-v7a core/ml/build/outputs/apk/androidTest/debug/ml-debug-androidTest.apk
  adb shell am instrument -w com.nospam.nospam.core.ml.test/androidx.test.runner.AndroidJUnitRunner
  adb uninstall com.nospam.nospam.core.ml.test
  ```

  For x86_64, run `connectedDebugAndroidTest` on an x86_64 emulator. The API
  37.1 `google_apis_ps16k` image also checks 16 KB pages.

```bash
# grant the role first (tools/run-e2e.sh does it, or set it in system settings)
adb shell cmd role add-role-holder android.app.role.SMS com.nospam.nospam
./gradlew :core:database:connectedDebugAndroidTest :core:telephony:connectedDebugAndroidTest
./gradlew :core:ml:connectedDebugAndroidTest

# compile-only check, no device (this is what CI runs):
./gradlew :core:database:assembleDebugAndroidTest :core:telephony:assembleDebugAndroidTest \
  :core:ml:assembleDebugAndroidTest
```

> Naming rule for these suites: test function names must use underscores
> (`` `insert_round_trip` ``). Spaces in backtick names break D8 dexing before
> dex-040. JVM suites are free to use spaces.

## 3. End-to-end — Maestro, local only

```bash
tools/run-e2e.sh          # installs, grants the role, reseeds, runs every untagged flow
maestro test .maestro/flows/onboarding.yaml   # one flow, if the fixtures are already seeded
```

`tools/run-e2e.sh` reseeds with `tools/seed.sh core` **before every flow**.
The flows share one fixture pool in the Telephony provider and `nospam.db`, and
several mutate it (block, archive, star, mark-not-spam), so without reseeding a
flow silently breaks the next one's preconditions and the failure looks like a
flake. `tools/seed.sh` is idempotent and only touches addresses prefixed
`NSTEST_`, so teardown removes exactly what it created.

Eight of the twelve flows run by default. The runner skips three tags:

| Tag | Flows | Why it is opt-in |
|---|---|---|
| `destructive` | `settings_recheck` | Rewrites global classifier state |
| `manual-only` | `_unblock_part1_block`, `_unblock_part2_deliver_and_unblock` | Driven by `tools/persistence_check.sh`, which injects an SMS between the parts; reseeding between them guarantees part 2 fails |

Selectors are text and content descriptions only — there is no `testTag`
wiring in the app. Two consequences worth knowing before editing a flow:
isolate characters around phone numbers mean a number assertion needs `.*`
around it, and a flow cannot assert anything while the keyboard is up, because
Maestro's view hierarchy then contains only IME nodes.

## 4. Manual checks — things neither harness can reach

```bash
tools/permission_gate_check.sh   # onboarding gate on revoke + resume
tools/persistence_check.sh       # block → inbound SMS → unblock, across process death
```

`permission_gate_check.sh` exists because **Maestro cannot deny these
permissions**: `launchApp: permissions: {phone: deny}` leaves `READ_PHONE_STATE`
granted, and holding the default-SMS role makes Android re-grant contacts and
phone (`GRANTED_BY_ROLE`). Only an explicit `pm revoke` takes them away.

Spam pipeline, by hand:

```bash
adb emu sms send +989121234567 "see you tomorrow"     # ham → notification with Reply
adb emu sms send 1000 "You won! Click here to claim"  # spam → silent, verdict stored
```

Reply inline from the ham notification and check the thread shows it. "Not
spam" on a spam row persists an override that survives the 30-day auto-spam
prune.

## Coverage (Kover 0.9.9)

```bash
./gradlew :koverXmlReport :koverVerify   # total variant: build/reports/kover/
./gradlew :koverXmlReportCi :koverVerifyCi   # debug-only variant, what CI runs
```

Merged line coverage **60.39% (3470/5746)**. The ratchet in the root
`build.gradle.kts` is `minBound(60)` — raise it, never lower it. Its comment
block carries the history of every raise and why.

**The merged number counts JVM runs only.** Device suites do not feed it, which
is why `core/database/dao` reads 35.5% here despite 44 instrumented tests
against it, and `core:telephony` reads 22.3%. Treat those two as better covered
than the report says; treat everything else as measured.

The root project is the merging module (`kover(project(...))` per module). The
`ci` variant merges debug variants only; `total` also merges release, which
compiles the release graph a second time.

## CI

`.github/workflows/android.yml` — JDK 25 + Android SDK, then the Rust core's
toolchain (rustup installs what the submodule's `rust-toolchain.toml` pins, the
NDK named in `core/ml/build.gradle.kts`, cargo-ndk; crates and the core's build
directory are cached), then one Gradle invocation:
debug assemble + `testDebugUnitTest` + `lintDebug`, the two JVM modules' `test`,
`:app:compileReleaseKotlin` and `:baselineprofile:compileNonMinifiedReleaseKotlin`
as a release-path compile check, the three instrumented modules'
`assembleDebugAndroidTest` as a compile check, then `:koverXmlReportCi
:koverHtmlReportCi :koverVerifyCi` and the report upload.

CI deliberately does **not** run `build`: that also packages release,
benchmarkRelease and nonMinifiedRelease APKs that nothing consumes, and it made
up about 43% of the executed tasks. It also does not run connected tests or the
flows — no emulator. Run those locally before a release.

## Known gaps

Honest list of what has no test, from the merged report:

- `:app` `navigation` (218 lines) and `ui` (64) — `NoSpamNavHost` and the app
  shell, including the permission gate. The gate is covered end-to-end by
  `tools/permission_gate_check.sh` instead.
- `core:notifications` (78 lines, 0%) — the three unit tests assert channel ids
  and reply-extra constants, not the `NotificationCompat` builders. Those are
  exercised only through `TelephonyInstrumentedTest`'s reply-action assertion.
- `core:telephony` `service` and `receiver` (57 lines combined) —
  `HeadlessSmsSendService` and the WAP-push receiver.
- `SqliteNoSpamOpenHelper.onUpgrade`: nothing exercises an upgrade from an
  older schema. The suite covers a fresh create at version 4 only, so a
  migration bug would ship silently.
- `feature:export` and `feature:mldebug` — developer tools, covered only by
  their 22 unit tests and the `debug_tools` flow.
- No 32-bit x86 build of the Rust core, so the APK ships no x86 folder at all
  (`abiFilters` in `:app`): a 32-bit x86 device cannot install the app. Such
  devices with Android 8+ (minSdk 26) are close to nonexistent.
- Fonts are `FontFamily.Default` placeholders (`Type.kt`), so nothing asserts
  the real Inter / Hanken Grotesk / Vazirmatn metrics yet.
