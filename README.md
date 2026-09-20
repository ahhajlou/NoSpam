# NoSpam — Android SMS app with on-device spam filtering

A full replacement SMS messenger for Android with on-device ML spam/scam
classification (TF-IDF + linear model, no server round-trip).
Kotlin + Jetpack Compose + Material 3, English first with full RTL support
(Persian is the first RTL target).

- Architecture and the authoritative module list: [`CLAUDE.md`](CLAUDE.md)
- Task tracker: [`TASKS.md`](TASKS.md)
- Build, tests & coverage guide: [`docs/TESTING.md`](docs/TESTING.md)

```bash
./gradlew :app:assembleDebug
./gradlew build          # every module: assemble + lint + unit tests
```

## License

NoSpam is free software, licensed under the
[GNU General Public License v3.0 or later](LICENSE) (`GPL-3.0-or-later`).
You may use, study, modify and redistribute it under the terms of that licence;
derived works must remain under the same licence and make their source available.
