# NoSpam — Android SMS app with on-device spam filtering

A full replacement SMS messenger for Android with on-device ML spam/scam
classification (TF-IDF + linear model, no server round-trip).
Kotlin + Jetpack Compose + Material 3, English first with full RTL support
(Persian is the first RTL target).

- Architecture and the authoritative module list: [`CLAUDE.md`](CLAUDE.md)
- Task tracker: [`TASKS.md`](TASKS.md)
- Build, tests & coverage guide: [`docs/TESTING.md`](docs/TESTING.md)

## Build

Requirements: JDK 25 (JetBrains), the Android SDK and NDK, and Rust (rustup). The spam
filter's text processing is Rust code from
[persian-sms-spam](https://github.com/ahhajlou/persian-sms-spam), included as a git submodule
and compiled by Gradle.

```bash
git clone --recurse-submodules https://github.com/ahhajlou/NoSpam
cd NoSpam
cargo install cargo-ndk --locked   # once; rustup installs the Rust version and Android targets from rust-toolchain.toml
./gradlew :app:assembleDebug
./gradlew build                    # every module: assemble + lint + unit tests
```

Already cloned without submodules: `git submodule update --init`.

The NDK version is `ndk` in `gradle/libs.versions.toml`; install it with the SDK
Manager or `sdkmanager "ndk;<version>"`.

The spam model (`core/ml/src/main/assets/spam_model.json`) is trained on a private SMS
dataset and committed here, so building needs no dataset. To use your own model, train it
with persian-sms-spam ("Train your own model" in its README) and run its
`make export-android` against this checkout.

## License

NoSpam is free software, licensed under the
[GNU General Public License v3.0 or later](LICENSE) (`GPL-3.0-or-later`).
You may use, study, modify and redistribute it under the terms of that license;
derived works must remain under the same license and make their source available.
