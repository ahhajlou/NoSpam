# CLAUDE.md — NoSpam (Android SMS app with on-device spam filtering)

This file is the persistent reference for any AI agent (or human) working in this
repository. Read it before making structural changes. Keep it up to date when the
architecture changes — a stale CLAUDE.md is worse than none.

> Revised after a self-review pass: two Android API references were corrected
> (§7), one dependency edge was removed rather than documented as an exception
> (§4), and two modules were cut for being under-justified at this project's
> current size (§4, §11). See the chat response this came with for the reasoning.

## 1. What this app is

A full replacement SMS/MMS messenger for Android:
- On-device ML spam/scam classification (no server round-trip).
- Kotlin + Jetpack Compose + Material 3.
- Ships with English first; designed from day one for additional languages, with
  full RTL support (Persian is the first RTL target).
- UI is inspired by a Google Stitch export (`stitch_modern_sms_messenger.zip`,
  see §6) but that export is a **starting reference, not a spec** — it's HTML/Tailwind,
  not Compose, and several required Android SMS-app screens (onboarding/default-app
  request) aren't in it at all. Some UX details below (e.g. a "not spam" control)
  are inferred requirements, not things visible in the static mockup — flagged
  where that's the case.

## 2. Tech stack

| Concern | Choice | Why |
|---|---|---|
| UI | Jetpack Compose, Material 3 | Matches the Stitch design tokens directly (M3 roles) |
| Navigation | Navigation-Compose, type-safe routes (`@Serializable` route objects) | Compile-time-checked args, no string routes |
| DI | Hilt | Standard for multi-module Android, integrates with WorkManager/ViewModel. Trade-off: KSP adds real build-time cost per module — another reason to keep the module count down (§4). Koin or manual constructor injection are legitimate lighter alternatives if build time becomes a pain point. |
| Async | Kotlin Coroutines + Flow | `StateFlow<UiState>` per screen, unidirectional data flow |
| Local storage | Room (NOT the SMS store itself — see §5) | App-owned data: blocklist, spam labels, model metadata |
| Build | Gradle Kotlin DSL + version catalog (`libs.versions.toml`, already in place) | Keep using it; add one entry per new module dependency |
| Background work | WorkManager | Periodic re-classification / model updates, if needed |

## 3. Architectural principles (read this before adding a module or a dependency)

1. **Two kinds of modules.** `core:*` = one platform capability or cross-cutting
   concern. `feature:*` = one coherent, navigable area of UI. A module exists because
   it has a distinct reason to change — don't create a module per screen, and don't
   create a module for something that isn't shared by at least two consumers yet
   (see the `core:ui` discussion in §4 for a case where this was violated and cut).
2. **Unidirectional data flow.** Each screen's ViewModel exposes one immutable
   `XxxUiState` via `StateFlow`. Events go up as plain function calls (`onAction()`),
   never two-way bindings.
3. **Dependency direction is one-way and enforced by Gradle, not convention:**
   `:app` → `feature:*` → `core:*`. Nothing depends on `:app`. `core:*` modules do
   **not** depend on each other — full stop, no documented exceptions. If you find
   yourself wanting one core module to see another, the shared thing is almost
   always plain data that belongs in `core:model` instead (see §4 for a concrete
   example where this happened).
4. **`core:model` has zero Android imports.** It's a plain Kotlin module (not
   `com.android.library`) so this is a compiler-enforced fact, not a promise. Keeping
   domain types framework-free is what makes ViewModels and repositories trivially
   unit-testable without Robolectric.
5. **Every module that touches a platform API exposes an interface + real impl,**
   with a fake impl in `core:testing` when something depends on it for tests
   (`TelephonyDataSource`, `SpamClassifier`, `PermissionChecker`, etc.). Callers only
   ever see the interface.
6. **Don't split what shares data, lifecycle, and actions.** Inbox, Archived, and
   Spam & Blocked are the same list UI with different queries/actions — they're one
   feature module, not three. Search is a UI state of the same top bar, not a
   separate destination, unless you deliberately want deep-linkable results later.
7. **A module needs two consumers before it exists**, not one. If only one feature
   uses a composable/helper today, it lives in that feature module. Promote it to
   `core:` the moment a second consumer needs it — don't pre-build the shared layer
   speculatively.

## 4. Module graph

```
:app
 ├─ feature:conversations   (Inbox / Archived / Spam & Blocked / Search)
 ├─ feature:thread          (message thread, incl. "new conversation" state)
 ├─ feature:settings
 └─ feature:onboarding      (permissions + default-SMS-app request — not in the
                              Stitch export, but mandatory, see §7)

feature:* depend on
 ├─ core:designsystem
 ├─ core:model
 ├─ core:data
 └─ core:i18n   (wherever locale/RTL-aware behavior is needed)

core:data depends on
 ├─ core:model
 ├─ core:database
 ├─ core:telephony
 └─ core:ml

core:notifications, core:telephony, core:ml, core:database, core:designsystem,
core:i18n → depend only on core:common and core:model. They never depend on
core:data or on each other.

core:common, core:model, core:testing → leaf modules, no internal deps.
```

**Two things were cut from the first pass of this doc, on review, and it's worth
recording why — the reasoning matters more than the diagram:**

- **`core:ui` is gone.** The original plan split "pure design-system atoms"
  (`core:designsystem`) from "composite composables that combine model + design
  system" (`core:ui`), following the Now-in-Android pattern. But once the list
  screens (Inbox/Archived/Spam&Blocked) were merged into one `feature:conversations`
  module (principle 6), `ConversationRow` is only ever used inside that one module —
  and `MessageBubble` is only ever used inside `feature:thread`. Neither composable
  has a second consumer, so per principle 7 there's nothing to share yet. Keep them
  as feature-local composables for now. If a genuinely cross-feature composite
  composable shows up later (e.g. a contact avatar reused by both conversations and
  onboarding), that's when `core:ui` earns its place — not before.
- **`core:permissions` is folded into `core:common`.** A runtime-permission-check
  helper is the same kind of small, generic utility as the dispatcher/Result types
  already in `core:common` — it doesn't yet justify its own `build.gradle.kts` and
  module boundary. Split it back out if it grows into something with real surface
  area (a full permission-request orchestration flow, say).
- **`core:notifications` no longer depends on `core:telephony`.** The first draft
  had notifications reaching into telephony just to reference the intent
  action/extra-key that `HeadlessSmsSendService` responds to, so it could build a
  direct-reply `PendingIntent` — and called that out as "the one deliberate
  core-to-core exception." On review, that's not actually necessary: those are
  plain `String` constants with no Android dependency, so they belong in
  `core:model` instead. Both modules depend on `core:model`; neither depends on
  the other. Principle 3 now has zero exceptions instead of one.

## 5. Data layer — what lives where (read this before writing any query)

- **SMS/MMS content itself** lives in Android's own `Telephony` content provider
  (`Telephony.Sms`, `Telephony.Mms`, `Telephony.Threads`). Only the current default
  SMS app can write to it. `core:telephony` is the **only** module allowed to touch
  `ContentResolver` for these URIs, `SmsManager`, and `SubscriptionManager` (multi-SIM).
  Do not mirror the message store into Room — you'll create a second source of truth.
- **Room (`core:database`)** holds app-owned data that the Telephony provider has no
  concept of: manual blocklist entries, per-thread spam verdicts/overrides ("not
  spam" corrections), spam-model metadata/version, and any app-specific cache
  (e.g. resolved contact photo cache).
- **DataStore** (add to `core:common` or a thin `core:preferences` if it grows) for
  user settings: selected app language, notification prefs, default-app onboarding
  state.
- **Testing gap worth naming honestly:** `ContentResolver` queries against
  `Telephony.Sms`/`Telephony.Mms` don't have great off-device fakes — Robolectric's
  shadow support for the Telephony provider specifically is thin, so
  `core:telephony`'s actual query/write logic will likely need instrumented tests
  on a real device or emulator (seed rows via `Telephony.Sms.CONTENT_URI` insert,
  then assert). Keep the query-building/parsing logic that *can* be pure (mapping
  cursor rows to `core:model` types, building `ContentValues`) separated from the
  `ContentResolver` calls themselves, so at least that part gets fast unit-test
  coverage via `core:testing` fakes, and reserve instrumented tests for the thin
  I/O layer.

## 6. Stitch screen → module mapping

The export at `stitch_modern_sms_messenger.zip` contains 8 real screens plus one
flow diagram. Design tokens are in `adaptive_messenger/DESIGN.md` (see §8).

| Stitch folder | Destination | Notes |
|---|---|---|
| `messages` | `feature:conversations` | Inbox route: drawer, merged search/menu top bar, filter chips (All/Unread/Known/Unknown/Starred), pinned section, unread-dot avatars. |
| `search_messages` | `feature:conversations` | Same screen's search bar in an expanded/focused state — implement as UI state unless you want a dedicated deep-linkable results route. |
| `chat_with_alice_smith` | `feature:thread` | Message thread: bubbles, compose bar. |
| `new_conversation` | `feature:thread` | Recipient-picker state of the same feature, before a thread exists. |
| `archived` | `feature:conversations` | Same list composable as Inbox, `archived = true` query + "unarchive" swipe action. |
| `spam_blocked` | `feature:conversations` | Same list composable, sourced from ML verdicts + manual blocklist. The mockup itself only shows `report`/`info`/`edit` affordances and example flagged senders — it does **not** show a correction control. Any spam filter needs one regardless, so plan a "not spam" action here that calls back into `core:data`/`core:ml`; this is a requirement being added on top of the mockup, not something ported from it. |
| `navigation_drawer` | `:app` | App shell chrome (drawer + top bar), not a feature — build once in the top-level `Scaffold` using `core:designsystem` components. |
| `settings` | `feature:settings` | Add beyond the mock: language picker, default-SMS-app status/re-request entry point, blocking-rules management, notification prefs. |
| `messaging_app_flow` | — | Flow/sitemap reference only (no `screen.png`) — not a screen to implement. |
| *(not in export)* | `feature:onboarding` | Runtime permissions + `RoleManager.ROLE_SMS` request. Required for any real default-SMS app; design it to match the rest of the visual language. |

"Favorites" in the current codebase isn't in the Stitch design — it most likely
maps to the **Starred** filter chip on the conversations list, not a separate
screen. Confirm before porting `FavoritesScreen.kt` as-is.

## 7. Standard SMS-app plumbing (already partly present, moves into `core:telephony`)

To be a selectable default SMS app, the manifest needs (move these, and their own
`AndroidManifest.xml` fragment, into `core:telephony` so the module is self-contained
— library manifests merge into `:app`'s):

- `SmsReceiver` on `Telephony.Sms.Intents.SMS_DELIVER_ACTION`, `<receiver>` declaring
  `android.permission.BROADCAST_SMS` as a required permission — delivered only to
  the default app. Don't use `SMS_RECEIVED_ACTION`; that's the broadcast every app
  with `READ_SMS` gets, and it can't write to the provider.
- `MmsReceiver` on `Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION` (MIME type
  `application/vnd.wap.mms-message`), `<receiver>` requiring
  `android.permission.BROADCAST_WAP_PUSH`.
- `HeadlessSmsSendService` implementing `TelephonyManager.ACTION_RESPOND_VIA_MESSAGE`
  (already present) — the `<service>` must require
  `android.permission.SEND_RESPOND_VIA_MESSAGE`. Also the target for notification
  direct-reply.
- An activity handling `Intent.ACTION_SENDTO` for the `sms:`/`smsto:`/`mms:`/`mmsto:`
  schemes, so other apps can hand off "compose SMS to X" to this app.
- Runtime request of the default-app role, API 29+: call
  `roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)` and launch it via the
  Activity Result API. There is **no public `ACTION_REQUEST_ROLE` intent action to
  construct by hand** — `createRequestRoleIntent()` is the only supported entry
  point; an earlier draft of this doc implied otherwise. Pre-Q, fall back to
  `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` (note it's nested under `.Intents`,
  not directly on `Telephony.Sms` — also wrong in an earlier draft of this doc),
  passing `EXTRA_PACKAGE_NAME`. Check current state either way with
  `RoleManager.isRoleHeld(ROLE_SMS)` (Q+) or
  `Telephony.Sms.getDefaultSmsPackage(context)` (pre-Q).
- Multi-SIM: resolve the right `SmsManager` via `SubscriptionManager` rather than
  assuming a single SIM.

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
| `label-lg` | Inter | 12/16, 500, 0.1sp | `labelLarge` |

Shapes → `androidx.compose.material3.Shapes`: `sm`=4dp, default=8dp, `md`=12dp,
`lg`=16dp (message bubbles, sharp corner on the sender-side per the export), `xl`=24dp
(search fields, drawer leading edge), `full`=`CircleShape` (FAB, avatars, pill buttons).

## 9. `core:ml` — keep the classifier swappable

The existing `assets/spam_model.json` + `SpamDetector.kt` move here behind an
interface:

```kotlin
interface SpamClassifier {
    suspend fun classify(message: RawMessage): SpamVerdict
}
```

Reasons this boundary matters concretely for this project:
- The "Not spam" action on the `spam_blocked` screen must be able to record a
  correction (`core:data`) without knowing or caring how the classifier is
  implemented today.
- A hand-rolled JSON-weights model is a reasonable v1; if this ever grows into a
  heavier model (e.g. a distilled/quantized transformer for Persian text), that's a
  drop-in replacement behind the same interface, not a rewrite of feature code.
- Persian text needs its own normalization step (ZWNJ handling, script-aware
  tokenization) before it hits whatever the current feature extractor is — don't
  assume the existing tokenizer generalizes past English/Latin script without
  checking it against real Persian SMS spam samples.

## 10. i18n & RTL checklist

- Use the AndroidX per-app language APIs (`AppCompatDelegate.setApplicationLocales`,
  backed by `res/xml/locales_config.xml` + `android:localeConfig` in the manifest)
  instead of manually recreating activities or mutating `Locale.setDefault`. For the
  AppCompat backport to persist the chosen locale across process death on API <33,
  also register `androidx.appcompat.app.AppLocalesMetadataHolderService` in the
  manifest with an `autoStoreLocales` meta-data value — easy to skip and the
  language selection silently won't stick on older devices if you do.
- Each module ships its own `res/values/strings.xml` (and `values-fa/`, etc.) —
  `:app` should own almost no translatable strings itself.
- Compose layout: always `Modifier.padding(start = ..., end = ...)`, never
  `left`/`right`; rely on `Arrangement`/`Alignment`, which already respect
  `LocalLayoutDirection`.
- Directional icons (back arrow, send, chevron, reply) — use
  `Icons.AutoMirrored.Filled.*` / `Icons.AutoMirrored.Outlined.*` so they flip
  automatically under RTL instead of shipping mirrored assets by hand.
- `android:supportsRtl="true"` in the manifest; Compose itself needs no extra flag.
- Mixed-direction content (Latin phone numbers/timestamps inside Persian text) can
  need explicit bidi isolation in edge cases — test with real Persian strings, not
  placeholder text.
- Decide explicitly whether Persian users see Gregorian or Jalali (Shamsi) dates —
  many Persian-locale users expect Jalali. Don't let this default silently.

## 11. Directory layout

```
NoSpam/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/example/nospam/
│       │   ├── NoSpamApplication.kt        # @HiltAndroidApp
│       │   ├── MainActivity.kt
│       │   ├── navigation/NoSpamNavHost.kt # composes each feature's nav graph
│       │   └── ui/NoSpamAppShell.kt        # top bar + drawer ("navigation_drawer")
│       └── res/...
│
├── build-logic/                # OPTIONAL — add convention plugins once
│   └── convention/             # per-module build.gradle.kts duplication justifies it
│
├── core/
│   ├── common/                  # dispatchers, Result/error types, coroutine scope
│   │   │                        # utils, runtime-permission-check helpers
│   ├── model/                   # pure Kotlin domain types (kotlin("jvm"), no Android);
│   │   │                        # also owns cross-module constants like the
│   │   │                        # direct-reply intent action/extra keys
│   ├── designsystem/            # theme, tokens, atoms (Avatar, Pill, SwipeAction)
│   ├── database/                # Room: blocklist, spam verdicts/overrides, model metadata
│   ├── telephony/                # Telephony provider access, SmsManager, SmsReceiver,
│   │   │                        # MmsReceiver, HeadlessSmsSendService, default-role request
│   │   ├── src/main/AndroidManifest.xml   # owns its receiver/service declarations
│   │   └── src/main/kotlin/.../telephony/
│   ├── ml/                      # SpamClassifier interface + current JSON-model impl
│   │   └── src/main/assets/spam_model.json
│   ├── notifications/           # channels, MessagingStyle, direct-reply PendingIntents
│   ├── i18n/                    # locale switching, RTL helpers, date formatting
│   ├── data/                    # repositories: Conversations/Messages/Blocklist/Spam
│   └── testing/                 # fakes: FakeTelephonyDataSource, FakeSpamClassifier...
│
├── feature/
│   ├── conversations/           # Inbox / Archived / Spam & Blocked / Search
│   ├── thread/                  # message thread + new-conversation state
│   ├── settings/
│   └── onboarding/              # permissions + default-SMS-app request
│
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── gradle.properties
├── gradlew / gradlew.bat
└── CLAUDE.md                    # this file
```

`settings.gradle.kts` module list:

```kotlin
include(
    ":app",
    ":core:common", ":core:model", ":core:designsystem",
    ":core:database", ":core:telephony", ":core:ml", ":core:notifications",
    ":core:i18n", ":core:data", ":core:testing",
    ":feature:conversations", ":feature:thread", ":feature:settings", ":feature:onboarding",
)
```

That's 10 `core:*` + 4 `feature:*` + `:app` = 15 modules. Down from 17 in the first
pass of this doc — `core:ui` and `core:permissions` were cut per §4.

## 12. Migration from the current single-module layout

| Current path | New home |
|---|---|
| `MainActivity.kt` | `app/` (thin — just sets content to the NavHost + theme) |
| `ml/SpamDetector.kt` | `core/ml/` (behind `SpamClassifier` interface, §9) |
| `assets/spam_model.json` | `core/ml/src/main/assets/` |
| `navigation/AppDestinations.kt` | Split: each feature defines its own typed routes; `app/navigation/NoSpamNavHost.kt` only wires them together |
| `receiver/SmsReceiver.kt`, `receiver/MmsReceiver.kt` | `core/telephony/` (with its own manifest fragment) |
| `service/HeadlessSmsSendService.kt` | `core/telephony/` |
| `ui/NoSpamApp.kt` | `app/ui/NoSpamAppShell.kt` (drawer + top bar) |
| `ui/screens/HomeScreen.kt` | `feature/conversations/` |
| `ui/screens/FavoritesScreen.kt` | Likely folds into the "Starred" filter in `feature/conversations/` — confirm against your intent for it first (§6) |
| `ui/screens/SettingsScreen.kt` | `feature/settings/` |
| `ui/theme/{Color,Theme,Type}.kt` | `core/designsystem/` (replace contents per §8) |

**Do this incrementally, not as one big-bang PR** — you already have a working,
tested app, and a single massive modularization commit is exactly how that
regresses silently. Extract in dependency order, building and smoke-testing the
app after each step: `core:model` → `core:designsystem` → `core:ml` →
`core:database` → `core:telephony` → `core:data` → one feature module at a time,
starting with whichever screen has the least cross-talk with the others (probably
`feature:settings`).

Before publishing: `com.nospam.nospam` is the Android Studio template
`applicationId`/package — rename it to something you actually own.

## 13. Deliberately deferred (not missing, just not needed yet)

- **`build-logic` convention plugins** — worth it once you're hand-editing the same
  Compose/Kotlin block in 8+ `build.gradle.kts` files. Not worth the ceremony at 15
  modules of a solo project on day one.
- **Baseline profiles / macrobenchmark module** — add once the app is feature-complete
  and you're tuning cold-start, not before.
- **Dynamic feature modules** — no on-demand delivery use case here.

## 14. Useful commands

```bash
./gradlew :app:assembleDebug
./gradlew :feature:conversations:testDebugUnitTest
./gradlew :core:telephony:testDebugUnitTest
./gradlew build   # full project, all modules
```
