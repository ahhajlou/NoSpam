# NoSpam — architecture review

Reviewed 2026-09-13 against `hardening-phase`, branched from `main` at `0bc813d`.
Scope: the module graph, build configuration, release surface and documentation
contract in `CLAUDE.md` §3, §4, §11. Thread-safety and performance were reviewed
separately in `REVIEW.md` (2026-09-11) and are not revisited here.

Companion to that document, not a replacement. Where the two overlap, this one
is newer.

**On `CLAUDE.md`'s authority.** It was written once near the start of the project
and updated only occasionally, so it is not a reliable statement of the current
architecture. Findings below are judged against what the code is evidently trying
to do and against ordinary Android practice. Where the code and the document
disagree, that is treated as evidence about the document at least as often as
evidence about the code, and it is called out either way. Section references to
`CLAUDE.md` are there to locate the claim, not to appeal to it.

---

## 1. Verdict

The production module graph is clean. No `core:*` module depends on another
`core:*` module, nothing depends on `:app`, and `core:model` is a `kotlin("jvm")`
module with no Android imports, so §3.4 is compiler-enforced rather than
promised. That part of the architecture is real.

The problems are not in the graph. They are in what the build ships and what the
documentation claims.

| Check | Result |
|---|---|
| `./gradlew build` | exit 0 |
| `./gradlew :app:assembleDebug :app:assembleRelease` | exit 0 |
| Unit suites | all pass |
| Merged line coverage | 31.1% (1457 / 4678) |

The single most serious finding is that release builds shipped two developer
tools in the main navigation drawer, one of which wrote a persistent device
identifier into a file of the user's private SMS. That is fixed here.

**What could not be verified.** No device or emulator was attached, so the seven
instrumented suites did not run. The release APK was inspected as an artifact,
not executed.

---

## 2. Fixed in this pass

### A-1 — Two unfinished tools shipped in release builds  *(was: highest severity)*

`:feature:export` and `:feature:mldebug` were plain `implementation` in
`app/build.gradle.kts`, registered as unconditional `composable<>` destinations
in `NoSpamNavHost.kt`, and given permanent `NavigationDrawerItem` entries in
`NoSpamAppShell.kt`. There was no `BuildConfig.DEBUG` guard anywhere. Any user of
a release build could open both from the main drawer.

Gating on `BuildConfig.DEBUG` alone would not have been enough — the classes
would still be linked into the release APK and reachable by other means. Both
modules are now `debugImplementation`, and the drawer entries and nav
destinations come from a variant-specific seam:

| File | Role |
|---|---|
| `app/src/main/.../navigation/DebugTool.kt` | The shared descriptor type. |
| `app/src/debug/.../navigation/DebugTools.kt` | The real tools, plus the `ExportRoute` / `MlDebugRoute` declarations. |
| `app/src/release/.../navigation/DebugTools.kt` | Empty list, no-op destinations. |

`NoSpamNavHost` now calls `debugToolDestinations(container, context)` and knows
nothing about either feature. `NoSpamAppShell` iterates `debugTools`. The
`drawer_export` and `drawer_mldebug` strings moved to `app/src/debug/res`, both
locales, so release carries no orphan resources.

**The two are not the same case, and the gating means different things.**

`:feature:mldebug` is a classifier console: normalized text, n-gram count, score.
It has no user-facing purpose and stays debug-only permanently.

`:feature:export` is an unfinished *product* feature, not a debug tool. Its
purpose is to let volunteers contribute real Persian SMS to the training corpus,
which is data that cannot be bought. That is legitimate and worth shipping
eventually. What it cannot ship as is: it sits one tap from the inbox with no
consent step and writes the raw address and raw body of every message on the
device. Gating it is a hold, not a verdict. The requirements to bring it back —
export preprocessed rather than raw text, default to unknown senders, move under
Settings behind a consent screen, and declare the collection to Play — are
recorded in `TODO.md`.

Verified at the artifact level, which matters more than a compile check:

| APK | Distinct `com/nospam/nospam/feature/(export|mldebug)` class strings in dex |
|---|---|
| debug | 78 |
| release | 0 |

### A-2 — The export wrote a hardware identifier into private SMS

`ExportViewModel` stamped `Settings.Secure.ANDROID_ID` onto every exported line
as `hwid`, and `ExportScreen` displayed it in a "Hardware ID" card. A persistent
device identifier inside a file containing the user's messages is a materially
worse artifact than either piece alone. This is `REVIEW.md` L-13's sibling and
was reported there as L-2.

Replaced with a random per-install UUID minted on first use and stored in the
existing DataStore (`SpamPreferences.installId`). Exported corpora stay groupable
without carrying anything that identifies the device. The JSON field is now
`installId`, not `hwid`.

### A-3 — `feature:export` bypassed the repository layer

It was the only feature module depending on `core:database` and `core:telephony`
directly rather than going through `core:data`. Introduced
`core/data/.../ExportRepository.kt` with the two reads it actually needed, wired
through `AppContainer.exportRepository`. `feature:export` now depends on
`core:data` like every other feature.

### A-4 — The shared test fakes could not implement the interfaces they faked

`core:testing` was `kotlin("jvm")`, but `SpamClassifier` and
`TelephonyDataSource` live in Android library modules. A pure-JVM module cannot
implement them, so `FakeSpamClassifier` and `FakeTelephonyDataSource` declared no
supertype at all:

```kotlin
class FakeTelephonyDataSource {      // no ": TelephonyDataSource"
class FakeSpamClassifier(            // no ": SpamClassifier"
```

They were lookalikes that satisfied no call site. That is why five separate test
files each hand-rolled their own copy of an eighteen-method fake, and why
`core:data`'s `testImplementation(project(":core:testing"))` was dead.

`core:testing` is now an `android.library` depending on `core:ml` and
`core:telephony`, and both fakes implement the real interfaces. `FakesTest` was
rewritten: it previously asserted against the lookalike's invented API (`seed`,
`fakeConversations`, `returnsSpam`), which is precisely why it never caught the
problem.

This is a deliberate, documented exception to §3.4. `core:testing` is test
support, never on a production classpath, and a fake is only useful if it
implements the real interface. The exception is recorded in the module's build
file rather than left for a reader to infer.

An alternative was tried first and reverted: moving both interfaces into
`core:model`. It compiled, but it touched fourteen files, added a coroutines
dependency to a module documented as holding pure domain types, and separated
each port from the module that owns the capability. The `core:testing` change is
two files and keeps interfaces with their modules.

### A-5 — The Compose plugin on six modules with no Compose

`kotlin.compose` plus `buildFeatures { compose = true }` plus the Compose BOM and
`androidx.compose.ui` were applied to `core:data`, `core:database`,
`core:telephony`, `core:notifications`, `core:ml` and `core:i18n`. None contains
a single `@Composable` or `androidx.compose` import. `core:database`'s build file
even carried the comment "compose runtime required for kotlin.compose plugin even
if not using compose", which is circular: the runtime was there only to satisfy a
plugin that was not needed. All six are now plain Android libraries.

### A-6 — The coverage gate measured the wrong code

The root `build.gradle.kts` Kover filter excluded five packages:

| Excluded pattern | Reality |
|---|---|
| `com.nospam.nospam.receiver.*` | Package no longer exists |
| `com.nospam.nospam.service.*` | Package no longer exists |
| `com.nospam.nospam.ml.*` | Package no longer exists |
| `com.nospam.nospam.ui.*` | **Live**: `NoSpamAppShell` |
| `com.nospam.nospam.navigation.*` | **Live**: `NoSpamNavHost` |

The justifying comment described dead code under `app/src/main/java`, a directory
that has since been deleted. The filter was hiding shipped code from the gate.
Removed entirely. Merged coverage now reads 31.1%, still above the `minBound(29)`
ratchet, so the bound was left alone — a later wave raises it once tests land.

### A-7 — Dead dependencies

Removed `implementation(libs.gson)` from `app/build.gradle.kts` (zero Gson
references repo-wide; the code uses `kotlinx.serialization`), the `gson` entries
from the version catalog, and two commented-out PyTorch dependency lines left
over from an abandoned model approach.

---

## 3. Room and KSP: the documented reason is wrong

`CLAUDE.md` §13 states Room is blocked by an "`AGP 9.0.0 + Kotlin 2.2.10` KSP
incompatibility (`builtInKotlin` cast error)". The catalog actually pins AGP
9.0.1, and the failure is not a cast error. Tested directly by applying KSP and
Room to `core:database` and reverting afterwards:

1. **KSP alone** fails at configuration:
   `Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.`
2. **KSP plus `kotlin.android`** fails harder:
   `Cannot add extension with name 'kotlin', as there is an extension already registered with that name.`

The real mechanism: AGP 9 ships built-in Kotlin support and registers the
`kotlin` extension itself. KSP requires the standalone Kotlin Gradle Plugin,
which tries to register the same extension. They cannot coexist.

There is an escape hatch, and it is worse than it sounds.
`android.builtInKotlin=false` in `gradle.properties` does disable the built-in
support, but AGP warns it is deprecated and removed in AGP 10, and it
immediately breaks every module's `kotlin { compilerOptions { } }` block, which
is the built-in DSL rather than KGP's. Taking it would mean migrating all
sixteen module build files onto a path that disappears in the next major AGP.

**Conclusion: Room stays blocked, and the block is real.** The correct thing to
wait for is a KSP release that supports AGP's built-in Kotlin, not a newer Room.
`CLAUDE.md` §13 should say this instead of what it currently says.

---

## 4. Proposed, not done

### P-1 — `allowBackup="true"` with template backup rules  *(carried from `REVIEW.md` L-13)*

`app/src/main/AndroidManifest.xml:23` enables backup. Both referenced rule
files, `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`, are
the untouched Android Studio templates with every rule commented out. The
effective policy is therefore "back everything up", which includes `nospam.db` —
spam verdicts and sender state keyed by phone number — and the DataStore
holding the new install id.

Decide deliberately: either exclude the database and DataStore, or set
`allowBackup="false"`. Do not leave it as a template default on this data.

### P-2 — R8 is off in release

`isMinifyEnabled = false`, and `app/proguard-rules.pro` contains nothing but the
generated comments. The release APK is 14.5 MB unminified. Enabling R8 is the
single largest size win available, and it also removes the reachability argument
behind A-1 entirely. It needs a keep-rule pass for the `@Serializable` route
objects and reflective Compose entry points, so it is a change with real risk,
not a flag flip. Worth doing before publishing, not during this phase.

### P-3 — `build-logic` convention plugins

Sixteen Android-library build files repeat the same `compileSdk 36` / `minSdk 26`
/ `jvmTarget 17` block near-verbatim; only the namespace and a handful of
dependencies differ. `CLAUDE.md` §13 sets its own threshold at "8+ files" and
defers the work below it. The project is at double that. Three convention
plugins would cover it: an Android library base, a Compose-enabled variant, and a
JVM library base. Deferred here because it touches every module at once and
would collide with the test work running in this same phase.

### P-4 — An undocumented test-scope core-to-core edge

`core/telephony/build.gradle.kts` declares
`androidTestImplementation(project(":core:notifications"))`, used by
`TelephonyInstrumentedTest`. §4 of `CLAUDE.md` specifically celebrates having
designed this exact edge out and claims "principle 3 now has zero exceptions".
The production edge is indeed gone; a test-scope one exists. Either drop it, or
say so in the doc. Given A-4 establishes that test-support code legitimately
needs to see the modules it exercises, documenting it is the honest option.

### P-5 — Two incomplete features presented as working

Neither is dead code exactly; both advertise something the app does not do.

- `NoSpamAppShell`'s "Mark all as read" drawer item closes the drawer and does
  nothing else.
- `MainActivity.handleSendToIntent()` normalizes the incoming address and then
  only logs it, under a comment claiming NavHost deep-linking is wired. The
  manifest advertises `sms:`, `smsto:`, `mms:` and `mmsto:` to other apps, so
  this is a broken advertised entry point rather than an unused private helper.
  Another app handing off "compose SMS to X" gets a cold inbox.

---

## 5. Not findings

Checked and deliberately left alone.

- **Production `core` to `core` edges.** None exist. Verified across all
  eighteen modules.
- **Anything depending on `:app`.** Nothing does; the only `project(":app")`
  reference is the Kover rollup.
- **`core:model` purity.** No Android imports. It is `kotlin("jvm")`, so this
  cannot silently regress.
- **`feature:*` depending on `core:telephony`.** All four shipping features do,
  since `9526646` routed default-SMS checks through one helper. This is correct;
  `CLAUDE.md` §4's dependency list is what is wrong, and that is a documentation
  fix, not a code one.

---

## 6. How to verify any of this

```bash
./gradlew build
./gradlew :app:assembleRelease :app:assembleDebug
./gradlew :koverXmlReport :koverVerify
```

For A-1 specifically, confirm the release APK carries none of the debug tooling:

```bash
apk=app/build/outputs/apk/release/*-release-unsigned.apk
for d in $(unzip -Z1 $apk | grep '^classes.*dex$'); do unzip -p $apk $d | strings; done \
  | grep -cE "com/nospam/nospam/feature/(export|mldebug)"
```

Expect `0` for release and a non-zero count for the debug APK.
