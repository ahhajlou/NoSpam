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

Checked top to bottom, the first rule that fits wins (`ThreadSpamPolicy`; the
reasoning is in `TODO.md`, "Spam routing — agreed model"):

1. **You blocked the sender** → **Spam & Blocked**, no notification.
2. **You marked the sender `Not spam` or `Report spam`** → stays where you put it.
   New messages never change your decision; only you can.
3. **A saved contact** → never checked for spam at all. No label, no routing,
   a normal notification. A conversation the filter moved before you saved the
   contact comes back to the inbox.
4. **Someone who has sent you a normal message, or whom you have replied to** →
   always stays in the **Inbox**. A message that looks like spam is labelled
   `Suspected spam` and does not notify. Replying "STOP" to a sender you
   reported or blocked does not undo that.
5. **A new sender whose first message looks like spam** → stays in the
   **Inbox**, labelled **"Suspected spam"**, unread, no notification. One wrong
   guess never hides a code you are waiting for.
6. **A sender that has only ever sent spam, two or more messages** → **Spam &
   Blocked**, no notification, marked read.
7. **Everything else** → Inbox, normal notification.

**"Notify for suspected spam"** (same page, off by default) gives the messages
in rules 4 and 5 that look like spam a quiet notification: no sound, labelled
"Suspected spam", on its own notification channel. It never changes which
folder a message goes to. The default lives in one place,
`SettingsRepository.DEFAULT_NOTIFY_SUSPECTED_SPAM`.

A sender in Spam that then sends one normal message (or that you reply to)
comes back to the inbox. The outcome depends only on what the sender has sent,
not on the order it arrived in. Deleting a conversation does not reset what the
app knows about the sender, so it does not remove the protection either.

**Seeing what was filtered.** The drawer's **Spam & blocked** entry shows how
many conversations were filtered since you last opened it. In the inbox, a
conversation that holds a message flagged as spam right now carries a
`Suspected spam` label; it goes away when that message is deleted or marked
`Not spam`. Opening a flagged conversation shows a banner at the top saying
why (the on-device filter flagged messages here, or moved it to Spam) with a
`Not spam` button for the whole sender. A conversation you reported or blocked
yourself has no banner.

Inside a conversation, `Not spam` / `Report spam` on a single message corrects
that message. `Not spam` can bring a conversation back to the inbox; `Report
spam` on one message never hides the conversation you are reading.

In short: **ON = quiet but never lose a real message.**

### When Spam protection is OFF

- Every incoming SMS — even if the detector says “spam” — stays in the **Inbox**, stays **unread**, and **notifies** normally (`NotificationDecision.NORMAL` in `core/data/SmsIngressUseCase.kt`).
- The conversation never moves to Spam on its own.
- **But** the app still saves its guess (`MessageVerdict` per message) and the per-sender counters (`SenderState`). Nothing is thrown away.
- Turning the switch back ON applies to each sender from their next message on, using the counts kept meanwhile. To re-sort everything at once, use **Re-check all messages**.

**Use OFF when:** you’re getting false positives (e.g., Persian OTPs flagged), you want to audit what would have been filtered, or you’re on a work phone where missing a message is worse than seeing spam.

---

## Technical notes (for contributors)

- **Storage:** `MessageVerdict` (per-message, immutable) + `SenderState` (per-normalized-address, `E.164` via `PhoneNumberUtils` or raw alphanumeric) in `core/database` (`SqliteNoSpamOpenHelper`; the routing model arrived in v5, which re-derived existing senders' states without hiding anything that was showing). 30-day pruning affects only auto `MessageVerdict` rows; user overrides and `SenderState` are kept forever.
- **Address key:** normalized address, not `threadId` (threads are recycled after deletion). See `CLAUDE.md §15`.
- **Persistence:** `SettingsRepository` in `core:data` over `core:preferences` (DataStore file `settings`, key `spam_protection_enabled`, default on; a failed read also means on). `SpamSettingsViewModel` drives the switch; `SmsIngressUseCase.kt` reads it before `ThreadSpamPolicy.decideWithAddress` and short-circuits to `CLEAN/NORMAL` when disabled.

## FAQ

**Will I lose my “Not spam” corrections if I turn it off?** No. `isUserOverride` rows are never pruned or overwritten by auto classification.

**Does OFF make the phone vibrate for spam?** Yes — that’s the point. Every spam that would have been silent will notify until you turn it back on or manually `Report spam`.

**Where do I see what was filtered?** With spam protection ON, check **Spam & Blocked** (conversations), and in the inbox look for the `Suspected spam` label: it marks a conversation that holds a message flagged as spam right now, and goes away when that message is deleted or marked `Not spam`. With it OFF, Spam & Blocked will be empty unless you block/report manually.
