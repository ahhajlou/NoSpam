# Release signing

How the release signing key is generated, configured, kept out of the
repository, and verified in a build.

> **Written 2026-09-20**, when signing was first wired up. Before that there
> was no signing config at all: `assembleRelease` produced
> `app-release-unsigned.apk`, and `.github/workflows/release.yml` shipped debug
> APKs with the comment "no point shipping unsigned release builds yet". That
> workflow was rewritten the same day to build, sign, verify and publish. Every
> command below was run on that date. If a claim here contradicts the code, the
> code wins; fix this file.

**The build wiring is summarised in [`../CLAUDE.md`](../CLAUDE.md) §10.** This
file is the operational detail behind it.

## 1. Generating a key

```bash
mkdir -p ~/.local/share/keystores && chmod 700 ~/.local/share/keystores
keytool -genkeypair -v -storetype PKCS12 \
  -keystore ~/.local/share/keystores/NoSpam.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias upload
chmod 600 ~/.local/share/keystores/NoSpam.jks
```

| Flag | Why this value |
|---|---|
| `-storetype PKCS12` | The standard format. The old proprietary `JKS` type still works but `keytool` warns on every use and tells you to migrate. The `.jks` file extension is just a name; the container inside is PKCS12. |
| `-keyalg RSA -keysize 4096` | 2048 is the common default and is accepted everywhere; 4096 costs nothing at signing time. |
| `-validity 10000` | ~27 years, expiring 2054. Google Play requires a certificate valid past 2033-10-22, and an expired certificate means no further updates ever. |
| `-alias upload` | A **local label only.** It is not in the APK, not in the signature block, and Android's update check compares the certificate, not the alias. Renaming it with `keytool -changealias` leaves the fingerprint byte-identical (verified). `upload` is Google's convention for a Play App Signing *upload key*; for self-distribution `release` would read more honestly. Not worth changing on its own. |

**There is no Android convention for where a release keystore lives.** No
tool looks for one, Google's guidance says only "keep it in a safe place", and
`~/.android/` is the SDK's own directory for the *debug* keystore and adb key,
which is managed state you do not want a release key mixed into. So the path
below is a general Linux convention applied to a gap, not a requirement —
`storeFile` is an absolute path and nothing resolves it by convention.

`~/.local/share/keystores/` follows the XDG Base Directory Specification,
where `$XDG_DATA_HOME` (default `~/.local/share`) is the place for
user-specific data that is not config and not cache. If you have
`XDG_DATA_HOME` set to something else, use that instead. The `chmod 700` is
the part that matters: `~/.local/share` itself is ordinarily world-readable,
unlike `~/.ssh` or `~/.gnupg`, whose permissions their tools enforce for you.
Nothing here depends on the location — `storeFile` is an absolute path — so
`~/.keystores/` in the `~/.ssh` style is equally fine if you prefer it.

The keystore lives **outside the working tree** so it cannot be committed by
accident. `*.jks`, `*.keystore` and `*.p12` are gitignored anyway as a backstop.

For a PKCS12 keystore created this way the key password and the store password
are the same. Keep it that way; it is one fewer secret to lose.

## 2. Configuring the project

Create `keystore.properties` at the repository root — **not** in `app/`, since
`app/build.gradle.kts` reads it with `rootProject.file(...)`:

```bash
cp keystore.properties.template keystore.properties
chmod 600 keystore.properties
```

```properties
storeFile=/home/you/.local/share/keystores/NoSpam.jks
storePassword=…
keyAlias=upload
```

Omit `keyPassword` entirely when it matches `storePassword` — the build falls
back to `storePassword`. Do not leave it present-but-commented with a real
value in it; a commented secret is still a secret.

`keystore.properties` is gitignored. Confirm it on any new clone:

```bash
git check-ignore -v keystore.properties   # must print the .gitignore line
```

**Resolution order** is per value: `keystore.properties`, then the environment.

| Property | Environment variable |
|---|---|
| `storeFile` | `ANDROID_KEYSTORE_FILE` |
| `storePassword` | `ANDROID_KEYSTORE_PASSWORD` |
| `keyAlias` | `ANDROID_KEY_ALIAS` |
| `keyPassword` | `ANDROID_KEY_PASSWORD` |

If the resolved `storeFile` does not exist on disk, the release build type gets
**no signing config** and `assembleRelease` emits an unsigned APK instead of
failing. That is deliberate — a clone without the key still builds — but it
means a typo in the path looks like a successful build. §4 is how you catch it.

### Android Studio

Studio runs the same Gradle build and reads the same file, so no extra setup:
**Build Variants** → set `:app` to `release` → **Build > Build Bundle(s)/APK(s)
> Build APK(s)**.

Do **not** use **Build > Generate Signed App Bundle / APK**. That wizard
collects the keystore path, alias and password itself and signs through its own
config, bypassing this one — two sources of truth that will drift.

The properties file, rather than environment variables alone, is what makes
this work in Studio: a desktop-launched IDE does not inherit variables from
your shell rc, so an env-only setup would build signed on the CLI and unsigned
in the IDE.

Configuration cache is safe here (verified): Gradle tracks the file as an input,
reuses the entry on an unchanged run, and invalidates with `file
'keystore.properties' has changed` when you edit it.

## 3. CI/CD (GitHub Actions)

**Decide before you do this.** Uploading the keystore to GitHub puts your
signing key on infrastructure you do not control. For a self-distributed app
the key cannot be rotated without breaking every existing install (§5), so the
alternative — building and signing releases locally and uploading the APK by
hand — is a legitimate choice.

This repository made that call on 2026-09-20:
[`.github/workflows/release.yml`](../.github/workflows/release.yml) builds,
signs, verifies and publishes on a `v*` tag. **Read that file for the steps.**
This section covers only what it cannot say about itself: how to create the
secrets it consumes, and why it is shaped the way it is.

### Storing the secrets

The keystore is binary, so it goes in as base64. Round-trip verified:

```bash
KS=~/.local/share/keystores/NoSpam.jks
# confirm it survives the trip before trusting it — these two must match:
base64 -w0 "$KS" | base64 -d | sha256sum
sha256sum "$KS"
base64 -w0 "$KS" | xclip -selection clipboard
```

Add under **Settings → Secrets and variables → Actions → New repository
secret**:

| Secret | Contents |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the base64 blob above |
| `ANDROID_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_KEY_ALIAS` | `upload` |
| `ANDROID_KEY_PASSWORD` | only if it differs from the store password |

The workflow maps the last three straight onto the environment variables in
§2, and sets `ANDROID_KEYSTORE_FILE` itself to wherever it decoded the
keystore. Use **repository** secrets; a GitHub **environment** with required
reviewers is worth adding if you want a human in the loop before any signed
build.

### Why the workflow is shaped the way it is

- **An unset secret must be a hard failure.** An empty `ANDROID_KEYSTORE_BASE64`
  decodes to an empty file, which the build config correctly reads as "no
  keystore, build unsigned" (§2) — so without an explicit emptiness check a
  misconfigured repository publishes an unsigned APK and reports success. The
  workflow checks, and then verifies the finished APK anyway (§4).
- **The fingerprint is pinned as a literal, not kept in a secret.** It is public
  — it is in every APK you ship — and keeping it in the repo means swapping the
  signing key shows up in a diff and in review, which is exactly the change you
  want to be loud.
- **Signing and publishing are separate jobs.** The build job holds the secrets
  with `contents: read`; the publish job has `contents: write` but no keystore,
  so the third-party release action never runs beside the key.
- **`$RUNNER_TEMP`, not the workspace.** A keystore written into the checkout can
  be swept up by an artifact upload with a careless `path:` glob.
- **`printf '%s'`, not `echo`.** Some shells' `echo` mangles a long blob with
  backslashes.
- **Never pass a secret as a command-line argument** — it lands in process
  listings. Use `env:`.
- **Secrets are not exposed to workflows triggered by pull requests from
  forks.** A signing job must run on tag pushes or `workflow_dispatch`, which is
  how `release.yml` is triggered.
- Do not `set -x` in a step that touches these values.
- GitHub masks a secret's exact string in logs, but not a transformation of it
  (base64-of-base64, a substring, an error message that reformats it). Masking
  is a safety net, not a control.
- The runner is ephemeral, so the cleanup `rm` is belt-and-braces — and the one
  line that has to stay if this ever moves to a self-hosted runner.

**Untested in CI as of 2026-09-20.** The local equivalent is verified — the four
environment variables above drive a correctly signed `assembleRelease`, checked
against a throwaway key — but no tag has been pushed through the workflow yet.

## 4. Verifying a build

```bash
tools/verify-signing.sh                    # newest release APK
tools/verify-signing.sh path/to/some.apk
tools/verify-signing.sh --expect <sha256>  # on a machine without the keystore
```

```
APK       app/build/outputs/apk/release/NoSpam-0.1.0-alpha.5-release.apk
OK    signature verifies for minSdk 26 (v2 and v3 present)
Signer    CN=Amirhossein Hajlou, OU=NoSpam, O=NoSpam, L=Tehran, ST=Tehran, C=IR
SHA-256   55869c56eefb9bf16e2f8f2285524d9d93d33f067d54a53dce896a88799dfac1
OK    signer matches our release key
```

It exits non-zero on an unsigned APK, a debug-signed APK (`CN=Android Debug`),
a missing v2/v3 block, or a signer that is not our key.

By hand, the two commands it wraps:

```bash
$ANDROID_HOME/build-tools/36.1.0/apksigner verify --min-sdk-version 26 --print-certs -v app.apk
keytool -list -v -keystore ~/.local/share/keystores/NoSpam.jks -alias upload | grep SHA256
```

Two things people get wrong here:

- **`apksigner verify` alone is not enough.** It proves an APK is internally
  consistent, not that it is yours — a debug-signed or throwaway-signed APK
  passes it. Comparing the fingerprints is the check that matters.
- **Pass `--min-sdk-version 26`.** Without it apksigner assumes minSdk 1 and
  reports the deliberately-absent v1 (JAR) block as a failure. minSdk is 26, so
  only the v2/v3 blocks are needed and `enableV1Signing = false` is correct.

## 5. Safety rules

1. **The keystore never enters the repository.** It lives outside the working
   tree, `chmod 600`. `*.jks`/`*.keystore`/`*.p12` and `keystore.properties` are
   gitignored as a backstop, not as the primary control.
2. **Back up the keystore and its password separately, offline, today.** For a
   self-distributed app (GitHub Releases, F-Droid) this key *is* the app's
   identity. Lose it and no existing install can ever be updated: users must
   uninstall and reinstall, which destroys `nospam.db` — every spam verdict,
   sender state and user override the app has learned.
3. **Never paste the password anywhere it can be printed or shared**, including
   a commented-out line in a config file. This happened on 2026-09-20: a
   commented `keyPassword=` survived redaction and was disclosed. The response
   is `keytool -storepasswd`, which changes the password and leaves the key and
   its fingerprint untouched, so nothing downstream is affected.
4. **A leaked *password* is recoverable; a leaked *keystore* is not.** There is
   no revocation for a self-signed Android signing key. If the `.jks` itself
   leaks, anyone can ship a convincing update to anyone who sideloads — and the
   only real remedy is a new package name and an orphaned user base.
5. **Verify before publishing** (§4). The unsigned-fallback behaviour means a
   bad `storeFile` path yields a green build and an unpublishable APK.
6. **Never ship a debug-signed APK.** The debug key is shared, well-known, and
   on every developer machine.
7. **Sign every release with the same key**, including hotfixes and
   release-candidate builds handed to testers, or their next update fails with
   a signature mismatch.
8. **Play App Signing is a one-way door per app.** Enrolling hands Google the
   real signing key and makes yours a replaceable upload key — good insurance,
   but not reversible. The alias name is not the decision; this is. Note that
   Play also restricts `READ_SMS`/`RECEIVE_SMS` to default SMS handlers and
   requires a Permissions Declaration, which bears on whether Play is the
   distribution channel at all.
9. **Rotate the CI secrets, not just the local file,** whenever the password
   changes. A stale `ANDROID_KEYSTORE_PASSWORD` fails the build loudly, which is
   fine, but a stale keystore blob signing with an old key would not.
