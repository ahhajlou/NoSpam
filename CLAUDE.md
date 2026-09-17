# CLAUDE.md — NoSpam (Android SMS app with on-device spam filtering)

Reference for anyone, human or agent, working in this repository.

> **Rewritten 2026-09-15 from the code, not from the previous version of this
> file.** Every structural claim below was checked against the repository or by
> running a command at that date. The previous file had been written once near
> the start of the project and patched occasionally, so several of its
> statements had quietly stopped being true — including its central rule about
> module dependencies, which the code had never actually followed. If you find a
> claim here that the code contradicts, the code wins; fix this file.

## 1. What this app is

A full replacement SMS/MMS messenger for Android.

- On-device spam classification. No network, no server round-trip.
- Kotlin, Jetpack Compose, Material 3.
- English first, built for more languages, with full RTL support. Persian is the
  first RTL target and the classifier ships Persian normalisation data.

## 2. Modules

18 Gradle modules. They are not all the same kind of thing, and the distinction
matters more than the count:

| Tier | Modules | Rule |
|---|---|---|
| Leaf | `core:model`, `core:common`, `core:designsystem` | No project dependencies at all. Anything may depend on them. |
| Capability | `core:database`, `core:telephony`, `core:ml`, `core:notifications`, `core:i18n` | One platform capability each. Depend only on leaf modules, **never on each other**. |
| Aggregator | `core:data` | The repository layer. Depends on leaf plus every capability module it needs. |
| Test support | `core:testing` | Fakes. Depends on leaf plus the capability modules whose interfaces it implements. Never on a production classpath. |
| Shipping feature | `feature:conversations`, `feature:thread`, `feature:settings`, `feature:onboarding` | One navigable area each. |
| Debug-only feature | `feature:export`, `feature:mldebug` | Wired with `debugImplementation`; absent from release builds. |
| App | `:app` | Shell, navigation, DI container, SMS receivers. |
| Tooling | `:baselineprofile` | Not architecture. Generates the startup profile. |

This is the authoritative list. `README.md` and `docs/TESTING.md` should point
here rather than restate a count, which is how the previous file drifted.

**The real dependency rule**, as the code actually enforces it: a capability
module never depends on another capability module. Verified — `core:database`
sees only `core:model`; `core:telephony`, `core:ml` and `core:i18n` see only
leaves. `core:data` depending on four capability modules is the point of
`core:data`, not a violation.

The previous file claimed no `core` module depended on any other `core` module,
"full stop, no exceptions". That was never true and could not have been:
`core:data` has always needed the capabilities it aggregates.

`core:testing` is an `com.android.library`, not a JVM module, and it is the one
deliberate crossing worth calling out. Its fakes have to implement
`SpamClassifier` (in `core:ml`) and `TelephonyDataSource` (in `core:telephony`),
and a pure-JVM module cannot. Before this was fixed the fakes declared no
supertype at all, satisfied no call site, and five test files each hand-rolled
their own eighteen-method copy.

**`core:model` has zero Android imports**, enforced by being a `kotlin("jvm")`
module rather than promised in prose. That is what keeps policy logic and
domain types unit-testable without Robolectric.

## 3. Rules for adding code

1. A module exists because it has a distinct reason to change. Not one per
   screen.
2. Promote shared code to `core:` on the second consumer, not the first.
3. Each screen's state holder exposes one immutable `XxxUiState` through
   `StateFlow`. Events go up as plain function calls.
4. Every module touching a platform API exposes an interface plus a real
   implementation, with a fake in `core:testing`.
5. Do not split things that share data, lifecycle and actions. Inbox, Archived
   and Spam are one feature module with different queries.
6. A new module states its tier. A debug-only module is wired with
   `debugImplementation` so its classes are absent from release, not merely
   unreachable.

## 4. Data layer

**Messages live in Android's Telephony provider**, not in this app. Only the
current default SMS app may write there. `core:telephony` is the only module
permitted to touch `ContentResolver` for those URIs, `SmsManager`, or
`SubscriptionManager`. Do not mirror the message store into an app database;
that creates a second source of truth.

**`core:database` holds only what the provider has no concept of**: the manual
blocklist, per-message spam verdicts, per-sender spam state and user overrides,
starred/pinned/muted/archived flags, and model metadata. Persistent
`nospam.db`, accessed through `SqliteNoSpamOpenHelper` and `Sqlite*Dao` behind
`*Dao` interfaces. `NoSpamDatabase.persistent(context)` ships;
`inMemory()` is test-only.

DAOs never query in `<init>`. They expose a `MutableStateFlow` initialised
lazily via `onStart { withContext(IO) { … } }` behind an `AtomicBoolean`, so
building the DI container does not touch disk on the main thread.

**Ingress ordering.** `SmsIngressUseCase` inserts the incoming message into the
provider with `READ=0` *before* classifying, then updates read state and verdict
afterwards. Classification is wrapped in `withTimeout(8_000)` so a slow model
degrades to "unread, no verdict" rather than losing the message.

**Address normalisation is the join key everywhere.** E.164 via
`PhoneNumberUtils`, falling back to the trimmed upper-cased raw value for
alphanumeric sender IDs, which are common on Iranian networks. Comparing raw
`+98912…` against `0912…` is a bug, not an edge case.

**Blocklist writes target `BlockedNumberContract.BlockedNumbers`** when the app
holds the default-SMS role, so blocks apply system-wide including to calls and
survive uninstall. The app's own table is the mirror and the fallback.

## 5. Spam routing — changing

The shipped model grades a sender to SPAM on a ratio threshold
(`GRADUATION_MIN_SPAM`, `GRADUATION_MIN_SPAM_RATIO` in `ThreadSpamPolicy`).
**That rule has been decided against and is being replaced.** It fires only on
senders that have demonstrably sent legitimate messages, and it makes the
outcome depend on arrival order.

The agreed replacement, the reasoning behind it, and the contact-bypass decision
live in `TODO.md` under "Spam routing — agreed model". Read that before touching
`ThreadSpamPolicy`. Do not treat the current constants as settled design.

What is stable and should survive the change: two levels, immutable per-message
`MessageVerdict` evidence plus a derived per-sender `SenderState`; keying on
normalised address rather than `threadId`, because provider thread ids are
recycled; user overrides outranking the classifier; and spam inside a legitimate
conversation being silenced and labelled rather than hidden.

## 6. Default-SMS-app plumbing

To be selectable as the default SMS app the manifest needs all of this, and
`core:telephony` owns its own manifest fragment so the module stays
self-contained:

- A receiver for `SMS_DELIVER_ACTION` requiring `BROADCAST_SMS`. Not
  `SMS_RECEIVED_ACTION` — that is the broadcast every app with `READ_SMS` gets,
  and it cannot write to the provider.
- A receiver for `WAP_PUSH_DELIVER_ACTION` requiring `BROADCAST_WAP_PUSH`.
- `HeadlessSmsSendService` for `ACTION_RESPOND_VIA_MESSAGE`, requiring
  `SEND_RESPOND_VIA_MESSAGE`. Also the direct-reply target.
- An activity handling `ACTION_SENDTO` for `sms:`/`smsto:`/`mms:`/`mmsto:`.
  **Advertised but not implemented** — see `TODO.md`.
- Role request on API 29+ through `roleManager.createRequestRoleIntent(ROLE_SMS)`
  launched via the Activity Result API. There is no public intent action to
  build by hand. Pre-Q, fall back to `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT`
  with `EXTRA_PACKAGE_NAME`.
- Multi-SIM: resolve `SmsManager` through `SubscriptionManager`.

Note for tests and scripts: the role constant is `android.app.role.SMS`,
uppercase. Lowercase silently fails with "Unknown role".

## 7. i18n and RTL

- Per-app language through `AppCompatDelegate.setApplicationLocales`, backed by
  `res/xml/locales_config.xml` and `android:localeConfig`. On API below 33 the
  AppCompat backport also needs `AppLocalesMetadataHolderService` registered
  with `autoStoreLocales`, or the choice silently fails to persist.
- Each module ships its own strings. `:app` owns almost none.
- Always `padding(start=, end=)`, never `left`/`right`. Use
  `Icons.AutoMirrored.*` for directional icons.
- `android:supportsRtl="true"`. Compose needs no extra flag.
- Test with real Persian strings, not placeholder text. Latin numbers inside
  Persian text need bidi isolation in places.
- Gregorian versus Jalali dates is still undecided in fact: `DateFormatter`
  comments mention a flag that does not exist, and the behaviour is Gregorian
  always. That is a silent default, which the i18n notes explicitly wanted to
  avoid.

## 8. Design tokens (source of truth: `adaptive_messenger/DESIGN.md` in the Stitch export)

The export already speaks Material 3's expanded color-role vocabulary almost 1:1
(the `surfaceContainer*`/`surfaceDim`/`surfaceBright` roles below require a
reasonably current Compose Material3 version — they were added alongside M3's
tonal-elevation model). Map it directly instead of re-deriving a palette:

```kotlin
// core/designsystem/.../theme/Color.kt

val md_light_primary = Color(0xFF005BBF)
val md_light_onPrimary = Color(0xFFFFFFFF)
val md_light_primaryContainer = Color(0xFF1A73E8)
val md_light_onPrimaryContainer = Color(0xFFFFFFFF)
val md_light_secondary = Color(0xFF575F6B)
val md_light_onSecondary = Color(0xFFFFFFFF)
val md_light_secondaryContainer = Color(0xFFDBE3F1)
val md_light_onSecondaryContainer = Color(0xFF5D6571)
val md_light_tertiary = Color(0xFF195ABC)
val md_light_onTertiary = Color(0xFFFFFFFF)
val md_light_tertiaryContainer = Color(0xFF3D74D7)
val md_light_onTertiaryContainer = Color(0xFF000414)
val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF93000A)
val md_light_background = Color(0xFFF8F9FA)
val md_light_onBackground = Color(0xFF191C1D)
val md_light_surface = Color(0xFFF8F9FA)
val md_light_onSurface = Color(0xFF191C1D)
val md_light_surfaceVariant = Color(0xFFE1E3E4)
val md_light_onSurfaceVariant = Color(0xFF414754)
val md_light_outline = Color(0xFF727785)
val md_light_outlineVariant = Color(0xFFC1C6D6)
val md_light_inverseSurface = Color(0xFF2E3132)
val md_light_inverseOnSurface = Color(0xFFF0F1F2)
val md_light_inversePrimary = Color(0xFFADC7FF)
val md_light_surfaceDim = Color(0xFFD9DADB)
val md_light_surfaceBright = Color(0xFFF8F9FA)
val md_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_light_surfaceContainerLow = Color(0xFFF3F4F5)
val md_light_surfaceContainer = Color(0xFFEDEEEF)
val md_light_surfaceContainerHigh = Color(0xFFE7E8E9)
val md_light_surfaceContainerHighest = Color(0xFFE1E3E4)

val LightColors = lightColorScheme(
    primary = md_light_primary, onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer, onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary, onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer, onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary = md_light_tertiary, onTertiary = md_light_onTertiary,
    tertiaryContainer = md_light_tertiaryContainer, onTertiaryContainer = md_light_onTertiaryContainer,
    error = md_light_error, onError = md_light_onError,
    errorContainer = md_light_errorContainer, onErrorContainer = md_light_onErrorContainer,
    background = md_light_background, onBackground = md_light_onBackground,
    surface = md_light_surface, onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant, onSurfaceVariant = md_light_onSurfaceVariant,
    outline = md_light_outline, outlineVariant = md_light_outlineVariant,
    inverseSurface = md_light_inverseSurface, inverseOnSurface = md_light_inverseOnSurface,
    inversePrimary = md_light_inversePrimary,
    surfaceDim = md_light_surfaceDim, surfaceBright = md_light_surfaceBright,
    surfaceContainerLowest = md_light_surfaceContainerLowest,
    surfaceContainerLow = md_light_surfaceContainerLow,
    surfaceContainer = md_light_surfaceContainer,
    surfaceContainerHigh = md_light_surfaceContainerHigh,
    surfaceContainerHighest = md_light_surfaceContainerHighest,
)
```

**Open item:** the export only defines a light scheme. Either hand-tune a dark
scheme with the same role semantics, or generate one from the seed color
(`dynamicColorScheme` / Material color-scheme builder) and eyeball it — don't ship
an accidental default dark theme.

Typography → `androidx.compose.material3.Typography`:

| Token | fontFamily | size/line-height/weight | Compose slot |
|---|---|---|---|
| `headline-lg` | Hanken Grotesk | 28/36, 600 | `headlineLarge` |
| `headline-md` | Hanken Grotesk | 22/28, 500 | `headlineMedium` |
| `headline-lg-mobile` | Hanken Grotesk | 24/32, 600 | `headlineLarge` (compact window class) |
| `body-lg` | Inter | 16/24, 400, 0.5sp | `bodyLarge` |
| `body-md` | Inter | 14/20, 400, 0.25sp | `bodyMedium` |
| `label-lg` | Inter | 12/16, 500, 0.1sp | `labelMedium` (M3 `labelLarge` is the 14/20 button role) |

Roles DESIGN.md does not name use the M3 baseline scale, but are still defined
explicitly in `Type.kt` so a font swap reaches every role.

Shapes → `androidx.compose.material3.Shapes`: `sm`=4dp, default=8dp, `md`=12dp,
`lg`=16dp (message bubbles, sharp corner on the sender-side per the export), `xl`=24dp
(search fields, drawer leading edge), `full`=`CircleShape` (FAB, avatars, pill buttons).


## 9. Testing

| Layer | Where | State as of 2026-09-15 |
|---|---|---|
| Unit | `src/test` across 16 modules | 238 tests, 38.63% line coverage |
| Instrumented, storage | `core/database/src/androidTest` | 44 tests, all passing on a device |
| Instrumented, Compose UI | 5 modules | **cannot run on API 37** — see below |
| End-to-end | `.maestro/flows` | 12 flows; 8 run by default (debug, destructive and manual-only tags are skipped), 7 passing on 2026-09-17 |

Tests are written against behaviour, not implementation. The shared fakes in
`core:testing` are the substitution point; do not hand-roll a local fake.
Turbine for Flow assertions. Coverage gate is a ratchet in the root
`build.gradle.kts`; raise it, never lower it.

**Compose instrumented tests fail on API 37** with
`NoSuchMethodException: android.hardware.input.InputManager.getInstance`.
Espresso reflects into a platform method that no longer exists. Not app logic.
Either bump the test artifacts or keep an older AVD for those suites.

**End-to-end runs are local only**; CI has no emulator. `tools/run-e2e.sh`
reseeds before every flow, because flows mutate shared fixtures and otherwise
break each other in ways that look like flakes. `tools/seed.sh` is idempotent —
it resets the addresses it is about to write before writing them.

**If you start an emulator by hand, use `-qt-hide-window`, never `-no-window`.**
The latter selects `qemu-system-x86_64-headless`, which segfaults during startup
on Android 16+ images on Linux. Same emulator build, different binary. This is
not a graphics problem however much the log looks like one.

## 10. Build and toolchain

Gradle 9.6.0, AGP 9.4.0, KSP 2.3.6, Kotlin 2.2.10, Compose BOM 2026.09.00
(Compose UI 1.12.1, Material 3 1.4.0). compileSdk 37 (SDK platform `android-37.0`,
required by Compose 1.12), targetSdk 36. The Gradle
daemon is pinned to a JDK 25 JetBrains toolchain through
`gradle/gradle-daemon-jvm.properties`, so the CLI and the IDE share one daemon.

```bash
./gradlew build                          # every module: assemble, lint, unit tests
./gradlew :koverXmlReport :koverVerify   # merged coverage plus the ratchet
./gradlew connectedDebugAndroidTest      # needs a device
tools/run-e2e.sh                         # end-to-end, needs a device
```

Performance work is measured on a **release** build, not debug: the same commit
is roughly 1.75s debuggable and 0.91s release, because a debuggable APK JITs far
more and runs StrictMode. Discard the first launch after install and take the
median of three.

## 11. Where this project diverges from common Android practice

Recorded with the *kind* of divergence, because the previous file listed choices
without saying whether they were decisions, constraints or accidents, and a
reader could not tell which were safe to change.

| Divergence | Kind | Notes |
|---|---|---|
| Manual `AppContainer` instead of Hilt | **Deliberate, still holds** | A handful of singletons, and a `BroadcastReceiver` cannot use constructor injection anyway. Repositories take collaborators as parameters, so fakes still substitute. Hilt would add KSP cost per module for little gain. |
| `SQLiteOpenHelper` instead of Room | **Forced by a constraint** | Verified 2026-09-15: AGP 9 registers the `kotlin` Gradle extension itself, and KSP requires the standalone Kotlin plugin, which then cannot be applied. `android.builtInKotlin=false` works but is deprecated, disappears in AGP 10, and breaks every module's `compilerOptions` block. Wait for a KSP release supporting AGP built-in Kotlin, not for a newer Room. |
| `core:testing` is an Android library, not JVM | **Deliberate** | Its fakes must implement interfaces that live in Android modules. See §2. |
| No `build-logic` convention plugins | **Accidental drift** | 16 near-identical build files repeat the same `compileSdk`/`minSdk`/`jvmTarget` block. The threshold for doing this was passed long ago. |
| R8 disabled in release | **Accidental drift** | `isMinifyEnabled = false` and an empty keep-rules file. The largest available size win, and it needs a keep-rule pass for the `@Serializable` routes. |
| `allowBackup="true"` with template rules | **Accidental drift** | Both backup XML files are untouched Android Studio templates with everything commented out, so the effective policy is "back up everything", including a verdict database keyed by phone number. |
| Spam graduation ratio | **Accidental, decided against** | See §5 and `TODO.md`. |

## 12. Intent and PendingIntent rules

Standing rules, not a one-time fix list.

- **Never create a mutable `PendingIntent` without an explicit target
  component.** `RemoteInput` replies require `FLAG_MUTABLE`, so set
  `Intent.setClassName(packageName, "<fully.qualified.Receiver>")`. Every other
  `PendingIntent` is `FLAG_IMMUTABLE` unless it demonstrably needs mutation.
- **Never build an `sms:` URI with string interpolation.** Sender strings are
  attacker-controlled and can contain `?`, `#` or `;`. Use
  `Uri.fromParts("sms", address, null)`.
- **Validate every extra an exported component reads**, even behind a
  signature-level permission. Check `intent.action` explicitly and treat
  malformed extras as "ignore", never crash.
- **`android:exported="true"` only paired with a permission** restricting the
  caller to the system or a signature-matched app.
- Prefer `androidx.core.content.IntentSanitizer` if the app ever forwards an
  incoming `Intent`.

## 13. Companion documents

- `TODO.md` — open work, and the agreed spam routing model. Read before
  touching `ThreadSpamPolicy`.
- `docs/ARCHITECTURE-REVIEW.md` — 2026-09-13 audit, current.
- `REVIEW.md` — 2026-09-11 thread-safety and performance review. Mostly fixed;
  check its status table before acting on anything in it.
- `docs/TESTING.md`, `docs/SPAM_PROTECTION.md`, `TASKS.md` — phase history.
