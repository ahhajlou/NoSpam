# Changelog

All notable changes to NoSpam, newest first, in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Each version's section
is the description of its GitHub Release, so write for the people who install
the app. Add a line under [Unreleased] with each change they would notice, one
line per entry (a release page shows every line break). `tools/release.sh
<version>` turns that section into the release's. Versions before 0.8.0 are
described on their [GitHub Releases](https://github.com/ahhajlou/NoSpam/releases).

## [Unreleased]

### Added

- The classifier test screen now shows how often the spam check of an incoming message was too slow, and how many spam messages notified as normal because of it.

### Changed

- Export SMS now exports only the messages you received, with passwords, codes, card and account numbers, national IDs, phone numbers and emails replaced by tags such as [CARD]. Messages you sent are no longer exported.

## [0.9.0-beta.1] - 2026-10-20

### Added

- Links, phone numbers and email addresses in messages can be tapped: a link opens in your browser, a number can be called, messaged or copied, and an address emailed or copied. Messages flagged as spam keep them as plain text.

## [0.8.1] - 2026-10-19

### Fixed

- Typing fast no longer drops or reorders letters in the message box or in the inbox search.

## [0.8.0] - 2026-10-19

### Added

- Settings → About → Open-source licenses lists every library the app ships, each with its license.

### Changed

- The spam filter prepares each message with the same code its model is trained with (the Rust core of persian-sms-spam), and the model was retrained for it.
- The spam filter is ready sooner after an incoming SMS starts the app: on a Galaxy A26 its model loaded in 1.15 s, against 1.54 s with the previous text processing.

### Removed

- Support for 32-bit x86 devices: the spam filter's native code is built for ARM (32- and 64-bit) and 64-bit x86 only.

### Fixed

- On the classifier test screen, a negative score keeps its minus sign in front in Persian.
