# NoSpam — implementation review

Reviewed 2026-09-11 against `dev` (branched from `main` at `72636a9`).
Scope: 11,553 lines of Kotlin across 17 Gradle modules.
Priorities, as requested: **thread safety** and **optimization** deepest, then
modern-Android-API correctness and structure.

---

## 1. Verdict

The architecture is sound and the module boundaries are real, not decorative.
The build is green across the board:

| Check | Result |
|---|---|
| `./gradlew testDebugUnitTest test` | exit 0, all 22 JVM suites pass |
| `./gradlew lint` | exit 0 |
| `./gradlew :app:assembleDebug` | exit 0 |

`TODO.md` says a lint error at `feature/export/ExportScreen.kt:214` blocks a full
build. That is **stale**; the build is clean. But lint passing means less than it
appears, because `app/build.gradle.kts:66` sets `abortOnError = false`, and the
app module currently reports 42 lint issues including one Error. The CI gate that
runs `./gradlew build` cannot fail on any of them.

The serious problems are concentrated in two places: the `sender_state` write
path, and the `StateFlow`-over-SQLite pattern that every DAO uses. Three findings
are high severity. All three are new, none are in `TODO.md`, and each has a
concrete failure scenario below.

**What I could not verify.** Only JDK 25 is installed, so Robolectric is broken,
and there is no emulator, so the 7 `androidTest` suites never ran. Every finding
below is derived from reading the source plus the build output. Findings that
depend on device behavior are labelled as such. One JDK-level claim was confirmed
by direct execution and is marked accordingly.

---

## 1b. Status

Fixed since this review was written. Each finding below is left as originally
written, so the reasoning stays readable.

| Finding | Status | Regression test |
|---|---|---|
| HIGH-1 ingress loses counter updates | fixed | yes, fails without the fix |
| HIGH-2 contact misses never cached | fixed | no, needs a content resolver |
| HIGH-3 DAO flow write race | fixed | no, Sqlite DAOs need a device |
| MED-2 stale cancel flag | fixed | yes, fails without the fix |
| MED-3 no indexes | fixed | no, schema change needs a device |
| MED-4 write amplification | fixed | yes, 19 tests on the delta transforms |
| MED-1 telephony cache read | fixed | no, needs a content resolver |
| MED-5 non-lifecycle collection | fixed | no, Compose UI test needs a device |

Still open: MED-6 (broadcast completion budget, device-dependent and unverified),
and the LOW items marked OPEN in the table below. L-1 was withdrawn on
inspection and L-6 was examined and deliberately left alone.


MED-4 became more pressing once HIGH-3 was fixed: putting the mutate and the
refresh under one lock made the full table re-read the length of the critical
section. Writes now apply a delta to the observed snapshot instead. The
transforms live in `FlowDeltas.kt`, free of Android imports, so the part most
likely to drift from its SQL is unit-tested even though the DAOs themselves
cannot be.

Note on HIGH-3: `SqliteSpamVerdictDao` names its read `readSpamSync` rather than
`readAllSync`, so it was missed on the first pass of this review and only found
while fixing. It had the same defect in 4 write methods. All 8 flow-backed DAOs
now serialize both the mutate-then-refresh pair and the lazy-init read, which had
the same race against a concurrent writer.

---

## 1c. Inbox latency: what the trace actually showed

A 20-second Perfetto system trace of cold start into first inbox render on the
SM-A730F (Android 9, 121 conversations, 4374 verdict rows) contradicts the
assumption Phase 11 was built on. The trace is clean, no data loss.

**3,149 binder transactions for one inbox load**, split into two unrelated
problems:

| Caller | Destination | Calls | Wall time |
|---|---|---|---|
| main thread | `com.android.phone` | 1332 | 1444 ms |
| main thread | `servicemanager` | 1332 | 650 ms |
| `DefaultDispatch` | `android.process.acore` | 444 | 181757 ms summed |

**Cause 1: the country code was fetched over binder per address, on the main
thread.** `PhoneNumberNormalizer.getCountryIso` read `networkCountryIso` and
`simCountryIso` on every `normalize()` call, and evaluated both eagerly even
though only one is used. `ConversationsRepository` normalizes the same address
three or more times per emission, in `withFlags`, `applyFilter` and the
blocklist check. That is the 1332 pairs, about 2.1 s of main-thread IPC.

It ran on the main thread because `observeConversations(filter)` was the one
flow in the file with no `flowOn`. `observeSpam` and `observeArchived` both hop
to IO; the inbox flow inherited `viewModelScope`, which is `Main`. Phase 11.1
recorded that the final combine was "cheap" once the telephony work was hot;
it is not cheap, because it normalizes.

**Cause 2: unbounded parallel contact lookups saturate one provider process.**
444 lookups fanned out via `async(Dispatchers.IO)` with no concurrency limit,
reaching 29 threads in flight against a single contacts provider that serializes
them anyway. Average wait 300-500 ms each, sustained from 0.9 s to 4.1 s of the
trace, which is the visible inbox delay. The process ran 88 threads.

The tail of that timeline is the useful part: at 4.0 s the average drops to
18 ms and at 4.1 s to 3 ms, which is the contact cache from HIGH-2 finally
taking effect. Before that fix it never did, so this cost was paid on every
rebuild rather than once.

Phase 11 explicitly marked contact lookup "do not rework" on the grounds that it
was already parallel. Parallel was the problem, not the solution.

### Result

Measured on the same device and inbox, `NoSpamPerf: inbox loaded: 121`, from
ViewModel init to the first populated list:

| Build | Launches |
|---|---|
| Before this session | 9943, 10917 ms |
| Main-thread IPC removed | 9223, 7536, 7169 ms |
| Contacts + cursor walk fixed | 4406, 5585, 1686, 1831 ms |
| Same code, **release** build | 2457, 909, 895, 946, 910 ms |
| Release + app baseline profile, compiled | 427, 349, 375, 465 ms |
| Control: same, app profile removed | 1043, 974, 876, 911 ms |

The first launches after each install include dex/JIT compilation, which the
trace showed as 2.5 s of `Compiling` slices; the later launches are the honest
steady state.

**The build type is worth as much as all the code fixes combined.** The same
commit measures ~1.75 s debuggable and ~0.91 s release, and the release numbers
are far steadier (four launches inside a 51 ms band, versus 1686-1831 ms). The
trace explains why: JIT accounted for 2646 ms of compilation plus 3178 ms of
code-cache and arena support, and a `debuggable` APK also runs StrictMode with
`penaltyLog` on every disk touch.

So the 500 ms gate in `CLAUDE.md` §14 has been measured against the slower of
the two builds all along, and the doc does not say which build type it means.
It should. Against release the figure is ~910 ms, not ~1.75 s.

Release already shipped a baseline profile at `assets/dexopt/baseline.prof`,
contributed by AndroidX and Compose and merged by AGP. What was absent was a
profile covering NoSpam's own classes. Generating one took the inbox from ~910 ms
to ~400 ms, clearing the 500 ms gate for the first time.

That attribution was controlled, not assumed. Forcing compilation is itself a
change, so a control build with identical code and the app profile removed — the
AndroidX/Compose profiles the APK always carried still present — was given the
same forced compilation and stayed at 876-1043 ms. The win is the profile.

Two things this exposed about how the profile actually reaches users:

- **It does nothing until ART compiles it.** On API 28 `ProfileInstaller` writes
  the profile and logs "Skipping profile installation", then compilation waits for
  a background dexopt job that runs when the device is idle and charging. The
  first measurement with the profile packaged was unchanged for exactly this
  reason. Users get the benefit within a day or so, not at install.
- **Generation needs a workaround on an emulator.** `startActivityAndWait()`
  confirms a launch through `dumpsys gfxinfo framestats`, which a
  software-rendered emulator leaves empty, so it fails with "Unable to confirm
  activity launch completion []". The generator launches by shell and waits on the
  UI instead; a profile records which code ran, not how fast, so nothing is lost.

### What is left, and one thing that did not work

The release trace shows the IPC problems are gone: calls into `com.android.phone`
fell from 1332 to 7, and `android.process.acore` no longer appears at all.

What remains, measured:

- **~750 ms of main-thread disk stalls**, arriving as many small faults (17 in a
  single 100 ms bucket) rather than a few large reads. That is cold-start paging
  of code and resources, so code layout from an app-specific baseline profile is
  the lever, not an app-code change.
- **~475 ms across 7 calls into the telephony service**, the country lookup now
  paid once instead of 1332 times.
- **All 121 conversations are built before first paint** when ~10 are visible.
  This is the only remaining item that can plausibly close the gap to 500 ms, and
  the only one needing an architectural change.

**A hypothesis that failed.** Those 7 telephony calls looked like critical-path
cost, so the country lookup was moved into the existing application warm-up.
Measured result: 920, 936, 920, 930 ms against 909, 895, 946, 910 ms without —
neutral. The calls are evidently not blocking the inbox path. The change was kept
because it is free and folds two never-cancelled `CoroutineScope(Dispatchers.IO)`
instances into `appScope`, which closes the scope-leak item from pass 3, but it
is not a performance win and should not be recorded as one.

The contact matching change was verified rather than assumed. The new key is the
last 7 digits, the platform's own `PHONE_NUMBERS_EQUAL` suffix rule. Across the
device's 227 contact phone rows there are 136 distinct keys and **zero** keys
mapping to more than one contact, so the suffix cannot alias two people here. Six
sampled numbers were queried through the platform's `phone_lookup` and all six
returned the name the new directory would return, across mobile and landline,
local and international formats.


---

## 2. Findings

### HIGH-1 — Concurrent inbound SMS silently lose `sender_state` counter updates

**New.** `core/data/.../SmsIngressUseCase.kt:98` and `:128`.

`SpamStateWriter` exists to make the read-decide-write on `sender_state` atomic,
and `CLAUDE.md` §15 states that guarantee. The ingress path breaks it. The
previous state is read at line 98, **outside** the lock:

```kotlin
val prevStateEntity = db.senderStateDao.getByAddress(normalized)   // line 98, unlocked
```

The policy computes `policyOut` from that snapshot. The lock is then taken at
line 128, but the lambda **ignores the `current` value the writer hands it** and
writes the already-computed result:

```kotlin
spamStateWriter.upsertIfNotOverridden(normalized) {
    SenderStateEntity(..., spamCount = policyOut.newState.spamCount, ...)   // ignores `current`
}
```

So the mutex protects only the user-override check, not the counters.

**Failure scenario.** Two messages arrive from the same short code close
together. Each `SMS_DELIVER` broadcast constructs its own `AppSmsReceiver` with
its own scope (`app/.../sms/AppSmsReceiver.kt:25`) and both run on
`Dispatchers.IO`. Both read `spamCount = 5` at line 98, both compute 6, both
write 6. One increment is lost. The graduation rule in `CLAUDE.md` §15 is "≥3
messages and ≥80% spam", so drifting counters mean a genuine spam sender is not
promoted to `SPAM` when it should be, or a mixed sender is promoted when it
should not be.

That this is a bug rather than a deliberate trade-off is clear from the two
neighbouring paths that get it right. The blocked branch at line 72 does use the
locked value:

```kotlin
spamCount = (current?.spamCount ?: 0) + 1     // line 73, correct
```

And `SpamBackfillUseCase` goes further, carrying `seedSpamCount`/`seedHamCount`
through `PendingStateWrite` specifically so a scan cannot clobber increments that
raced in from live ingress. The main classification path is the one place the
pattern was not applied.

**Fix shape.** Move the line 98 read inside the lock, or recompute the counters
from `current` inside the `compute` lambda. `withSpamStateLock` at
`SpamStateWriter.kt:68` already exists for exactly this.

**Confirmable without a device**, by a test in `core:data` that runs two
`handle()` calls concurrently against the in-memory DAO and asserts the final
`spamCount`.

---

### HIGH-2 — Contact lookups are never negatively cached, so every non-contact re-queries forever

**New.** `core/telephony/.../ContactLookup.kt:10`, `:15`, `:17`.

```kotlin
private val cache = ConcurrentHashMap<String, Participant?>()   // line 10

fun lookup(address: String): Participant? {
    if (address.any { it.isLetter() }) return null
    cache[address]?.let { return it }       // line 15
    val result = query(address)
    cache[address] = result                 // line 17
    return result
}
```

`ConcurrentHashMap` rejects null values. When `query()` returns null, meaning the
number is not in contacts, line 17 throws `NullPointerException`. I confirmed the
JDK behavior by direct execution rather than relying on the docs:

```
put(null) threw NullPointerException
map size after: 0
```

The exception does not surface, because both call sites wrap the lookup in
`runCatching { ... }.getOrNull()` (`RealTelephonyDataSource.kt:140` and `:56`).
So the failure is invisible: the lookup appears to work, returns null correctly,
and caches nothing.

Line 15 compounds it. Even if a null could be stored, `?.let` treats a cached
null as a miss, so a negative result could never be served.

**Failure scenario.** An inbox where most senders are not saved contacts, which
is the normal case for an app whose purpose is spam. Every conversation-list
rebuild issues one `ContactsContract.PhoneLookup` query per non-contact sender.
`CLAUDE.md` §5 describes exactly this symptom as a solved problem, "269
`ContactLookup` IPCs, was 3573ms on SM-A730F". It was not solved. It was masked
by the repository-level replay cache added in the same phase, which hides the
cost on revisit but not on first load or after any provider change.

**Fix shape.** Two lines:

```kotlin
if (cache.containsKey(address)) return cache[address]
...
cache[address] = result ?: NOT_A_CONTACT_SENTINEL
```

or change the map to hold a non-null wrapper. Also consider bounding the cache,
which is currently unbounded and only cleared by an explicit `invalidate`.

---

### HIGH-3 — Every DAO refreshes its `StateFlow` with an unsynchronized read-modify-write

**New.** 19 call sites across 8 DAOs. Representative:
`core/database/.../dao/SqliteBlocklistDao.kt:66`,
`SqliteMessageVerdictDao.kt:44`, `SqliteSenderStateDao.kt:54`.

Every write method ends with the same two steps:

```kotlin
db.insertWithOnConflict(...)      // step 1: mutate the table
flow.value = readAllSync()        // step 2: re-read the whole table into the flow
```

Step 2 is a read-modify-write on shared state with nothing serializing it. SQLite
makes each statement atomic; it does nothing to order these two steps between
coroutines.

**Failure scenario.** Writer A blocks a number; writer B blocks a different one.
Interleaving: A inserts, A reads `{x}`, B inserts, B reads `{x,y}`, B publishes
`{x,y}`, A publishes `{x}`. The flow now says the second number is not blocked.
It stays wrong until the next write to that table, because the flow is only ever
refreshed by a write. The UI reads `sharedFlags` from these flows
(`ConversationsRepository.kt:47-69`), so the inbox shows a conversation as
unblocked, unarchived, or not-spam when the database says otherwise.

This is reachable whenever ingress and a backfill flush touch the same table, and
whenever the user acts on two conversations quickly.

**Fix shape.** A per-DAO `Mutex` around the mutate-and-refresh pair, or drop the
full re-read and apply the delta to `flow.value` with `MutableStateFlow.update`.
The second also fixes MED-4.

---

### MED-1 — The telephony conversation cache is read across four separate lock acquisitions

**New.** `core/telephony/.../RealTelephonyDataSource.kt:150-218`.

Three fields form one logical cache generation:

```kotlin
private var cachedConversations: List<Conversation>? = null   // :37
private var cachedMetas: List<ThreadMeta>? = null             // :38
private var cachedLatestMap: Map<Long, SmsLatest> = emptyMap() // :39
```

They are written together inside one critical section, which is correct. But they
are read in four separate ones: `withLock { cachedMetas }`, then
`withLock { cachedConversations }`, then `withLock { cachedMetas?.associateBy }`,
then `withLock { cachedLatestMap }`. Another writer can install a whole new
generation between any two of them.

**Failure scenario.** The code validates `cached == metas` against generation N,
then separately reads `cachedConversations`, which by then belongs to generation
N+1. It returns a conversation list that was never validated against the metas it
was checked against, so the inbox renders rows from a different snapshot. Also,
`mapLatest` in `observeConversations` cancels an in-flight reload, and
cancellation between two of these acquisitions leaves the read half-done.

**Fix shape.** One `withLock` returning a snapshot of all three, or a single
immutable holder object swapped atomically.

---

### MED-2 — A stale cancel flag silently kills the next backfill

**New.** `core/data/.../SpamBackfillUseCase.kt:63-64` and `startWith`.

```kotlin
@Volatile private var cancelled = false

fun cancel() { cancelled = true }

private fun startWith(forceReclassify: Boolean) {
    if (!started.compareAndSet(false, true)) return
    scope.launch {
        try { run(forceReclassify) }
        ...
        finally { started.set(false); cancelled = false }   // only reset here
    }
}
```

`cancelled` is reset in the `finally` of a scan that actually ran. It is never
reset when a scan *starts*.

**Failure scenario.** The user taps Cancel on a backfill progress notification
that is stale, or `BackfillCancelReceiver` fires after the scan already finished.
`cancelled` is now `true` with nothing running, and nothing will clear it. The
next `ensureStarted()` or `rescanAll()` enters the per-sender loop, hits
`if (cancelled)` on the first iteration, and immediately reports `Cancelled`. The
user asks for a rescan from Settings and silently gets nothing. A cold restart is
the only recovery, since the flag is in-memory.

**Fix shape.** Set `cancelled = false` immediately after the successful
`compareAndSet`, before `scope.launch`.

---

### MED-3 — No indexes on any table

**New.** `core/database/.../SqliteNoSpamOpenHelper.kt`.

Nine tables are created, zero `CREATE INDEX` statements exist anywhere in the
module. Primary keys cover some access paths, but not the hot ones:

- `message_verdict` has `messageId` as its primary key, and is queried by
  `threadId` in `getByThread` and `deleteByThread`. Both are full table scans.
- `deleteAutoOlderThan` filters on `userLabel`, `isSpam`, and `createdAt`. Full
  scan, and it runs opportunistically on **every** inbound SMS
  (`SmsIngressUseCase.kt:185`).

This matters more than usual because `CLAUDE.md` §15 says auto-spam verdict rows
are kept indefinitely by design, so this table is the one that grows without
bound.

**Fix shape.** An index on `message_verdict(threadId)` and a composite covering
the retention predicate, added in an `onUpgrade` to version 4.

---

### MED-4 — Write amplification: each write re-reads its entire table

**Partly tracked.** `TODO.md` notes the `CONFLICT_REPLACE` race and the
per-address `getByAddress` loop, but not this.

The `flow.value = readAllSync()` in HIGH-3 is also a performance problem
independent of the race. Every single write re-reads and re-materializes the
whole table into a new list.

On the ingress path this happens up to three times per received SMS:
`messageVerdictDao.insert`, `senderStateDao.upsert`, and whichever retention
delete removes a row. With `message_verdict` growing without bound by design,
per-message cost grows linearly with total history, and combined with MED-3 each
of those reads is itself a full scan.

**Fix shape.** Same as HIGH-3: apply deltas with `MutableStateFlow.update` rather
than re-reading.

---

### MED-5 — UI flows keep collecting while the app is backgrounded

**New.** All 7 screens. `feature/conversations/.../ConversationsScreen.kt:73`,
`:74`, `:435`, `:543`; `feature/thread/.../ThreadScreen.kt:63`;
`feature/export/.../ExportScreen.kt:51`; `feature/mldebug/.../MlDebugScreen.kt:46`.

Every screen uses `collectAsState()`. None use `collectAsStateWithLifecycle()`,
even though `androidx-lifecycle-runtime-compose` is already in the version
catalog and available.

`collectAsState()` keeps the collector active while the app is in the background.
Because `ConversationsRepository` shares its upstream `Eagerly`
(`ConversationsRepository.kt:40`, `:69`) and the telephony source re-queries on
every `ContentObserver` change, a backgrounded app keeps rebuilding the whole
conversation list on each provider change, paying the contact-lookup cost from
HIGH-2 each time.

**Fix shape.** Mechanical: swap the call and the import at all 7 sites.

---

### MED-6 — The broadcast completion budget may be tight

**New.** `app/.../sms/AppSmsReceiver.kt:41-66`, `SmsIngressUseCase.kt:87`.

`goAsync()` allows roughly 10 seconds before the system considers the receiver
stuck. Inside that budget the ingress path does a blocklist check, a provider
insert, thread resolution, classification under `withTimeout(8000L)`, a contact
lookup, an outbound-history check, three or four database writes, and two
retention deletes.

If classification actually reaches its 8-second timeout, everything else has
under 2 seconds. First-message-after-boot is the worst case, because the
classifier lazily parses a 1.2 MB JSON model
(`AppContainer.kt:34`) if the pre-warm at `NoSpamApplication.kt:59` has not
finished.

The `finally { pending.finish() }` is correctly placed, so the receiver always
completes. The risk is exceeding the window, not leaking it.

**Device-dependent. Not verified.** Worth measuring on the SM-A730F that the
existing perf gates use before treating it as real.

---

### LOW

| # | Finding | Location |
|---|---|---|
| L-1 | ~~ViewModel holds a `Context` field; lint `StaticFieldLeak`.~~ **Withdrawn on inspection:** line 69 assigns `context.applicationContext`, so no Activity is retained. Lint cannot see that and reports it anyway. It is a warning, not an error, so it does not affect the now-enabled gate. | `feature/thread/.../ThreadViewModel.kt:48` |
| L-2 | Export embeds `Settings.Secure.ANDROID_ID` in exported message data. A persistent device identifier written into a file of the user's private SMS. Lint `HardwareIds`. | `feature/export/.../ExportViewModel.kt:113`, `ExportScreen.kt:153` |
| L-3 | **Fixed.** The `POST_NOTIFICATIONS` check now sits where `notify()` is called, not only in the caller. | `app/.../BackfillProgressNotifier.kt` |
| L-4 | **Fixed.** `threadId.toInt()` truncated the `Long` thread id for notification ids and request codes. A single `NotificationHelper.notificationId()` now folds the high bits via `hashCode()`, and every notify/cancel/request-code goes through it so they stay in agreement. | `NotificationHelper.kt`, `AppSmsReceiver.kt` |
| L-5 | **Fixed.** `buildMessageNotification` takes a `timestamp`, used for both `addMessage` and `setWhen`, so ordering follows when the message was sent rather than when the notification was built. | `NotificationHelper.kt` |
| L-6 | **Not fixed, deliberately.** It is an IPC per message on the ingress path, but `setShortcutId` needs the shortcut to exist for conversation-style notifications on Android 11+, so removing it would downgrade them. Left alone. | `NotificationHelper.kt:122` |
| L-7 | `LIMIT 3000` passed inside the `sortOrder` string. Works on SQLite-backed providers but is outside the `ContentResolver` contract; API 30+ wants `QUERY_ARG_LIMIT` in a `Bundle`. The cap also silently truncates history, which may relate to the two open items at the top of `TODO.md`. | `RealTelephonyDataSource.kt:107` |
| L-8 | `catch (_: Exception) { null }` wraps the entire threads query, swallowing `SecurityException` from a revoked permission and reporting it as an empty inbox. | `RealTelephonyDataSource.kt:225` |
| L-9 | **Fixed.** The legacy `package=` attribute is gone; the namespace was already declared in `feature/export/build.gradle.kts`, so the attribute was redundant. The stub now matches the other feature modules. | `feature/export/src/main/AndroidManifest.xml` |
| L-10 | The default-SMS check is reimplemented at 6 sites and the role request at 2. `feature:settings` calls `RoleManager` while declaring no telephony dependency. | see §4 |
| L-11 | Root Kover config omits `:feature:export` and `:feature:mldebug`, so 1,084 lines never count toward the coverage ratchet. | `build.gradle.kts:63-77` |
| L-12 | **Fixed.** `abortOnError = true`; `checkReleaseBuilds` and the promoted `UnsafeIntentLaunch`/`MutableImplicitPendingIntent` checks are unchanged. | `app/build.gradle.kts` |
| L-13 | **Fixed 2026-09-23** (phase 2, P2.1). `allowBackup="false"`, and `data_extraction_rules.xml` excludes every domain from both cloud backup and device-to-device transfer, which `allowBackup` alone does not stop on Android 12+. | `app/src/main/AndroidManifest.xml`, `res/xml/data_extraction_rules.xml` |

---

## 3. Not findings

Recorded so they are not re-litigated later.

- **`lateinit var container` read from background threads** (`NoSpamApplication.kt:17`).
  Not volatile, but every read is either inside a coroutine launched after the
  assignment, which carries a happens-before edge through the dispatcher, or in a
  broadcast dispatched after `onCreate` completes. Safe as written.
- **`SpamStateWriter` as a defaultable constructor parameter**
  (`SmsIngressUseCase.kt:31`). The default builds a private mutex, which would
  defeat cross-component serialization, but `AppContainer.kt:60` and `:78` both
  pass the shared instance. A latent footgun, not a live bug.
- **Unsynchronized collections in the in-memory DAOs.** Reachable only through
  `NoSpamDatabase.inMemory()`, which production never calls.
- **`FLAG_MUTABLE` on the reply PendingIntent** (`NotificationHelper.kt:93`).
  Required by `RemoteInput`, and the base intent sets an explicit component via
  `setClassName`, which is what `CLAUDE.md` §16 asks for. Correct.
- **`Uri.fromParts` everywhere.** No `Uri.parse` with string interpolation exists
  in the codebase. §16 is being followed.
- **LazyColumn keys.** Every list passes a stable `key`. The Compose list code is
  in good shape.
- **The rest of the 42 app lint issues.** Dependency-freshness noise
  (`GradleDependency`, `NewerVersionAvailable`) and unused resources.

---

## 4. `CLAUDE.md` corrections

The doc drives future agent work, so fixing it has leverage beyond this review.

1. **§4, §11 module count.** Says 15 modules. `settings.gradle.kts` has 17;
   `:feature:export` and `:feature:mldebug` are undocumented.
2. **§13 AGP version.** Says Room is blocked by "AGP 9.0.0 + Kotlin 2.2.10". The
   catalog pins AGP `9.0.1`. Worth re-testing whether the KSP incompatibility
   still holds before treating Room as permanently blocked.
3. **§5 contact-lookup claim.** States the 269-IPC problem was fixed in Phase
   11.4. See HIGH-2: the underlying cache never worked. Replace the claim with
   what is actually true, that a replay cache hides the cost on revisit.
4. **§11 directory layout.** The `core:database` line still reads
   "Room: blocklist, spam verdicts...". It is `SQLiteOpenHelper`, as §2 and §13
   correctly say. Internally inconsistent.
5. **§15 atomicity guarantee.** Claims the writer lock makes every
   read-decide-write on `sender_state` non-interleaving. HIGH-1 shows ingress
   does not honour it. Either fix the code or soften the claim; right now the doc
   asserts a guarantee the code does not provide.
6. **§12 migration status.** Five dead packages still exist under `app/` and are
   excluded from coverage rather than deleted: `ui`, `navigation`, `receiver`,
   `service`, `ml`.
7. **§14 commands.** The comment says `core:data` has 11 tests. This run reports
   35, all passing. The suite tripled and the doc was not updated.

---

## 5. Suggested order of work

Severity first, but two of these are cheap enough to do immediately:

1. **HIGH-2** is a two-line fix with the largest measurable win. Do it first.
2. **HIGH-1**, then add the concurrent-ingress test that proves it.
3. **HIGH-3** and **MED-4** share one fix. Replacing the full re-read with
   `MutableStateFlow.update` addresses both.
4. **MED-2** is a one-line fix.
5. **MED-5** is mechanical across 7 files.
6. **L-12** and **L-11** restore the gates that would have caught L-3 on their own.

## 6. How to verify any of this

```bash
./gradlew testDebugUnitTest test    # currently exit 0, 22 suites
./gradlew lint                      # currently exit 0, but see L-12
./gradlew :app:assembleDebug        # currently exit 0
```

HIGH-1 and HIGH-3 are both provable off-device with a `core:data` or
`core:database` test that issues two concurrent writes and asserts the result.
HIGH-2 is provable by the JDK snippet quoted in that finding. MED-6 needs a
device and is explicitly unverified.
