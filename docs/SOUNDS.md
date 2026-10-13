# In-app message sounds

How NoSpam's own sounds work, and how to change one. Changing a sound is a file
swap: no code change is needed.

## What plays, and when

| Event | Sound | File |
|---|---|---|
| The user sends a message (it is handed to the radio) | Sent | `core/notifications/src/main/res/raw/message_sent.ogg` |
| A message arrives in the conversation on screen | Received | `core/notifications/src/main/res/raw/message_received.ogg` |
| A message arrives anywhere else | None from the app: the notification plays its channel's sound, which the user picks in Android's settings | (none) |

Both sounds:

- play only when "Message sounds" is on (Settings, on by default) and the
  ringer is in normal mode, never on vibrate or silent;
- play at the **notification volume** (`USAGE_NOTIFICATION`);
- are loaded once, when a conversation opens (`MessageSoundPlayer.prepare`),
  through a `SoundPool`, so they start without delay.

A burst of incoming messages plays one received sound: a second one within
2 seconds of the last is skipped (`RECEIVED_SOUND_GAP_MS`).

The code is `BundledMessageSoundPlayer` in
`core/notifications/.../MessageSounds.kt`. It refers to the sounds only as
`R.raw.message_sent` and `R.raw.message_received`, so a file with the same
name replaces the sound.

### Why the app ships its own sounds

Android has no stock "message sent" sound. The only system sounds an app can
ask for are the ringtone, notification and alarm sounds, so every messaging app
ships its own: Google Messages has `message_sent_v2` and `message_incoming`
(OGG, about 0.2 s each), Telegram `sound_out` and `sound_in`, AOSP Messaging
`message_sent`. Until 2026-10-14 NoSpam used a `ToneGenerator` beep for sent
and the phone's default notification sound for received.

## Current sounds

From Kenney's ["Interface Sounds"](https://kenney.nl/assets/interface-sounds)
pack (CC0, public domain):

| File | Original | Length |
|---|---|---|
| `message_sent.ogg` | `drop_004` | 0.29 s |
| `message_received.ogg` | `glass_001` | 0.27 s |

Both were chosen on 2026-10-14 and leveled to a peak of -15 dBFS, about where
Google Messages' own sounds sit.

## Changing a sound

### 1. Pick a sound

- **License.** It must be free to ship in a GPL-3.0-or-later app: CC0, CC-BY,
  Apache-2.0, MIT, or GPL "v2 or later". Never another app's sounds taken from
  its APK (Google Messages' sounds are Google's), and nothing with a
  "non-commercial" or "no derivatives" clause.
- **Length.** Short: 0.1 to 0.3 s is the norm. It plays on every message.
  The script refuses anything over 1 s.
- **Character.** Soft, warm and tonal works best; clicks and hiss get tiring.
  A rising or "moving away" sound suits sent, a single soft pop suits received.

Kenney's pack has 100 more. These were the shortlist on 2026-10-14:

- Sent: `drop_004` (chosen), `select_006`, `maximize_009`, `confirmation_001`
- Received: `glass_001` (chosen), `drop_003`, `drop_002`, `bong_001`

### 2. Install it

```bash
tools/message-sound.sh sent     path/to/new_sound.wav
tools/message-sound.sh received path/to/new_sound.ogg
```

Any format ffmpeg reads works (needs ffmpeg with libvorbis). The script
overwrites `message_<sent|received>.ogg` with the sound made ready for the app:

- silence trimmed from both ends,
- mono, 48 kHz, Ogg Vorbis,
- its loudest point set to -15 dBFS, so a new sound is as loud as the old one.

Too loud or too quiet next to the other sound? Run it again with
`--peak -18` (quieter) or `--peak -12` (louder). Prefer this over the
`SENT_VOLUME` / `RECEIVED_VOLUME` constants in `MessageSounds.kt`, which are
there to set one sound below the other and are 1 otherwise.

### 3. Record its source and license

The repository follows [REUSE](https://reuse.software): every file's license
is on record. Edit the sound's entry in `REUSE.toml`:

- update the comment (pack, original file name, link),
- `SPDX-FileCopyrightText`: the author,
- `SPDX-License-Identifier`: its license.

If one file now has a different source from the other, give it an
`[[annotations]]` entry of its own. If the license is new to the repository,
add its text as `LICENSES/<SPDX-id>.txt` (copy it from
<https://github.com/spdx/license-list-data/tree/main/text>). A license that
asks for credit (CC-BY) is satisfied by this entry.

### 4. Check it on a phone

```bash
./gradlew :app:installDebug
```

1. Ringer on normal, "Message sounds" on. Open a conversation and send a
   message: the sent sound plays once.
2. From another phone, send a message to the conversation on screen: the
   received sound plays and there is no notification.
3. Send three messages to it quickly: one sound, not three.
4. Ringer on vibrate, then silent: neither sound plays.
5. Turn "Message sounds" off: neither sound plays.
6. Change the notification volume: both sounds follow it.

Then run the tests:

```bash
./gradlew :core:notifications:testDebugUnitTest
```

### 5. Commit

Commit the `.ogg` file and `REUSE.toml` together, and say in the message where
the sound came from.

## Rules

- **Keep the file names.** `message_sent` and `message_received` are what the
  code loads. A third sound means a new file, a new `R.raw` reference and a new
  method on `MessageSoundPlayer`, plus its fake in `core:testing`.
- **Keep the notification volume.** Moving the sounds to the "System" volume
  (`USAGE_ASSISTANCE_SONIFICATION`) would silence them for the many Samsung
  users who turn that slider down.
- **Do not play the notification sound in the app.** The sound for a message
  outside the conversation on screen belongs to the notification channel,
  where the user chooses it.
