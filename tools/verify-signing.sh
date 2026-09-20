#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# Local check that a release APK is signed with OUR key. The CI equivalent is the
# "Verify the APK is signed with our key" step in .github/workflows/release.yml;
# keep the two in step.
#
#   tools/verify-signing.sh [path/to.apk]        # default: newest release APK
#   tools/verify-signing.sh --expect <sha256>    # no keystore, e.g. someone else's machine
#
# Passes only if BOTH hold:
#   1. `apksigner verify --min-sdk-version 26` exits 0 (signed, consistent).
#   2. The SHA-256 of the expected certificate appears in apksigner's output.
# The expected certificate comes from keystore.properties, else the
# ANDROID_KEYSTORE_FILE / _PASSWORD / ANDROID_KEY_ALIAS variables, else --expect.
# There is no "warn and pass": if it cannot say whose key signed the APK, it fails.
#
# It matches on the hash, never on apksigner's signer labels, which differ
# between build-tools versions.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "FAIL: $*" >&2; exit 1; }

APK="" EXPECT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --expect) [ $# -ge 2 ] || die "--expect needs a value"; EXPECT="$2"; shift 2 ;;
        -h|--help) sed -n '5,20p' "$0"; exit 0 ;;
        -*) die "unknown option: $1" ;;
        *) [ -z "$APK" ] || die "only one APK may be given"; APK="$1"; shift ;;
    esac
done

if [ -z "$APK" ]; then
    APK="$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)"
    [ -n "$APK" ] || die "no APK in app/build/outputs/apk/release; run ./gradlew :app:assembleRelease"
fi
[ -f "$APK" ] || die "no such file: $APK"
case "$APK" in *unsigned*) die "filename says unsigned: the signing config did not apply" ;; esac

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
[ -x "$APKSIGNER" ] || die "apksigner not found under $SDK/build-tools"

if [ -z "$EXPECT" ]; then
    if [ -f keystore.properties ]; then
        prop() { sed -n "s/^$1=//p" keystore.properties | head -1; }
        STORE="$(prop storeFile)" ALIAS="$(prop keyAlias)" ANDROID_KEYSTORE_PASSWORD="$(prop storePassword)"
    else
        STORE="${ANDROID_KEYSTORE_FILE:-}" ALIAS="${ANDROID_KEY_ALIAS:-}"
        ANDROID_KEYSTORE_PASSWORD="${ANDROID_KEYSTORE_PASSWORD:-}"
    fi
    [ -f "$STORE" ] && [ -n "$ALIAS" ] && [ -n "$ANDROID_KEYSTORE_PASSWORD" ] \
        || die "no keystore to compare against: fill in keystore.properties, set ANDROID_KEYSTORE_FILE/_PASSWORD and ANDROID_KEY_ALIAS, or pass --expect <sha256>"
    export ANDROID_KEYSTORE_PASSWORD
    TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
    # -file, not stdout: on failure keytool prints its error to stdout, which
    # would otherwise be hashed into a plausible-looking "fingerprint".
    keytool -exportcert -keystore "$STORE" -alias "$ALIAS" \
        -storepass:env ANDROID_KEYSTORE_PASSWORD -file "$TMP/cert.der" >/dev/null 2>&1 \
        || die "could not export the certificate for alias '$ALIAS' from $STORE"
    EXPECT="$(sha256sum "$TMP/cert.der" | cut -d' ' -f1)"
fi
EXPECT="$(printf '%s' "$EXPECT" | tr -d ': \r' | tr '[:upper:]' '[:lower:]')"
printf '%s' "$EXPECT" | grep -Eq '^[0-9a-f]{64}$' || die "not a SHA-256 (64 hex digits): $EXPECT"

OUT="$("$APKSIGNER" verify --min-sdk-version 26 --print-certs "$APK" 2>&1)" \
    || { printf '%s\n' "$OUT" >&2; die "apksigner could not verify $APK for minSdk 26"; }
printf '%s\n' "$OUT" | grep -qiF "$EXPECT" \
    || { printf '%s\n' "$OUT" >&2; die "$APK is not signed with the expected certificate (SHA-256 $EXPECT)"; }

echo "OK: $APK is signed with the expected key"
echo "SHA-256 $EXPECT"
