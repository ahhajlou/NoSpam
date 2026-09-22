# Spam Protection — What does the toggle do?

**Where to find it:** Inbox → ☰ Drawer → **Settings → Privacy → Spam protection** (`feature/settings/SettingsScreen.kt`).

It is a single on/off switch that controls whether NoSpam is allowed to **act** on its spam predictions. It does **not** turn off detection — the app keeps learning in the background so turning it back on is instant.

---

## In plain language

Think of NoSpam as two parts:

1.  **Detector** — reads every incoming SMS and guesses `ham` (normal) or `spam` (junk/scam) using the on-device model (`TfidfSpamClassifier`).
2.  **Gatekeeper** — decides what to *do* with that guess (hide, silence, notify).

The **Spam protection** switch controls the gatekeeper, not the detector.

### When Spam protection is ON (default)

- **Unknown sender, first message is spam** → goes straight to **Spam & Blocked**, no notification, marked read. You never get interrupted.
- **Unknown sender, first message is normal** → stays in **Inbox**.
- **Known contact or someone you’ve replied to** → even if the message looks like spam, it **never** goes to Spam automatically. At most it gets a quiet label:
  - In the inbox list you’ll see a small **“Mixed”** pill.
  - Opening the thread shows `Suspected spam` on that bubble with `Not spam` / `Report spam` buttons.
  - The message is marked read so it doesn’t buzz, but it is **not hidden** — an OTP from your bank won’t bury an important code.
- **A sender you’ve already marked** `Not spam` (TRUSTED) or `Report spam`/`Block` (SPAM/BLOCKED) → your choice is **sticky**. New messages from the same sender will not flip the conversation back and forth. Only you can change it.
- **A sender that mixes good and bad** (e.g., a bank that sends OTPs *and* promos) → first promo makes the conversation `MIXED` (stays in Inbox, promo silenced). It only graduates to real Spam after **3+ messages and ≥80% spam** — spammers can’t rescue themselves with one innocent opener.

In short: **ON = quiet but never lose a real message.**

### When Spam protection is OFF

- Every incoming SMS — even if the detector says “spam” — stays in the **Inbox**, stays **unread**, and **notifies** normally (`NotificationDecision.NORMAL` in `core/data/SmsIngressUseCase.kt`).
- The conversation never moves to Spam on its own.
- **But** the app still saves its guess (`MessageVerdict` per message) and the per-sender counters (`SenderState`). Nothing is thrown away.
- Turning the switch back ON immediately applies the correct `CLEAN / MIXED / SPAM / TRUSTED / BLOCKED` state without rescanning your history.

**Use OFF when:** you’re getting false positives (e.g., Persian OTPs flagged), you want to audit what would have been filtered, or you’re on a work phone where missing a message is worse than seeing spam.

---

## Technical notes (for contributors)

- **Storage:** `MessageVerdict` (per-message, immutable) + `SenderState` (per-normalized-address, `E.164` via `PhoneNumberUtils` or raw alphanumeric) in `core/database` (`SqliteNoSpamOpenHelper` v2). 30-day pruning affects only auto `MessageVerdict` rows; user overrides and `SenderState` are kept forever.
- **Address key:** normalized address, not `threadId` (threads are recycled after deletion). See `CLAUDE.md §15`.
- **Persistence:** `SettingsRepository` in `core:data` over `core:preferences` (DataStore file `settings`, key `spam_protection_enabled`, default on; a failed read also means on). `SpamSettingsViewModel` drives the switch; `SmsIngressUseCase.kt` reads it before `ThreadSpamPolicy.decideWithAddress` and short-circuits to `CLEAN/NORMAL` when disabled.

## FAQ

**Will I lose my “Not spam” corrections if I turn it off?** No. `isUserOverride` rows are never pruned or overwritten by auto classification.

**Does OFF make the phone vibrate for spam?** Yes — that’s the point. Every spam that would have been silent will notify until you turn it back on or manually `Report spam`.

**Where do I see what was filtered?** With spam protection ON, check **Spam & Blocked** (conversations) and inside a `MIXED` thread look for the muted chip. With it OFF, Spam & Blocked will be empty unless you block/report manually.
