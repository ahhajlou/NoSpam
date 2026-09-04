# NoSpam — Android SMS app with on-device spam filtering

A full replacement SMS messenger for Android with on-device ML spam/scam
classification (TF-IDF + linear model, no server round-trip).
Kotlin + Jetpack Compose + Material 3, English first with full RTL support
(Persian is the first RTL target).

- Architecture: [`CLAUDE.md`](CLAUDE.md)
- Task tracker: [`TASKS.md`](TASKS.md)
- Build, tests & coverage guide: [`docs/TESTING.md`](docs/TESTING.md)

```bash
./gradlew :app:assembleDebug
./gradlew build          # all 15 modules: assemble + lint + unit tests
```
