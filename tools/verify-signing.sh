#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/verify-signing.sh — check that a built APK is signed, and signed with
# OUR key.
#
# Why this exists rather than a bare `apksigner verify`:
#
#   1. `apksigner verify` answers "is this APK internally consistent?", NOT
#      "is this the key that signs NoSpam?". A debug-signed APK, or one signed
#      with a throwaway key, passes it happily. The check that matters is
#      comparing the APK's signer fingerprint against the keystore's, so that
#      is what this does.
#   2. The release signing config in app/build.gradle.kts is absent when no
#      keystore is configured, and `assembleRelease` then emits an UNSIGNED
#      APK rather than failing. A typo in storeFile therefore looks like a
#      successful build. This turns that into a loud failure.
#   3. minSdk is 26, so v1 (JAR) signing is deliberately off and only the v2/v3
#      blocks are expected. `apksigner verify` without --min-sdk-version
#      assumes minSdk 1 and complains about the missing v1 block, which is not
#      a real problem for this app.
#
# Usage:
#   tools/verify-signing.sh                       # newest release APK
#   tools/verify-signing.sh path/to/some.apk
#   tools/verify-signing.sh --expect <sha256>     # compare without the keystore
#   tools/verify-signing.sh --require-identity    # unresolvable fingerprint = failure
set -uo pipefail

cd "$(dirname "$0")/.."

MIN_SDK=26
EXPECTED=""
APK=""
REQUIRE_IDENTITY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --expect) EXPECTED="$2"; shift 2 ;;
        --require-identity) REQUIRE_IDENTITY=1; shift ;;
        -h|--help) sed -n '5,30p' "$0"; exit 0 ;;
        *) APK="$1"; shift ;;
    esac
done

fail() { printf '\033[31mFAIL\033[0m  %s\n' "$*"; exit 1; }
pass() { printf '\033[32mOK\033[0m    %s\n' "$*"; }
warn() { printf '\033[33mWARN\033[0m  %s\n' "$*"; }

normalise() { tr -d ': ' | tr '[:upper:]' '[:lower:]'; }

# --- locate apksigner -------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
[ -x "$APKSIGNER" ] || fail "apksigner not found under $SDK/build-tools"

# --- locate the APK ---------------------------------------------------------
if [ -z "$APK" ]; then
    APK="$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
    [ -n "$APK" ] || fail "no APK in app/build/outputs/apk/release — run ./gradlew :app:assembleRelease first"
fi
[ -f "$APK" ] || fail "no such file: $APK"
printf 'APK       %s\n' "$APK"
printf 'apksigner %s\n' "$APKSIGNER"

case "$APK" in
    *unsigned*) fail "filename says unsigned — the release signing config did not apply" ;;
esac

# --- 1. is it signed at all, for our minSdk? --------------------------------
OUT="$("$APKSIGNER" verify --min-sdk-version "$MIN_SDK" --verbose --print-certs "$APK" 2>&1)"
STATUS=$?
echo "$OUT" | grep -qE '^Verifies$' || {
    echo "$OUT" | grep -vE '^WARNING: (A restricted|java.lang.System|Use --enable|Restricted)' >&2
    fail "apksigner could not verify the signature (exit $STATUS)"
}
# Deliberately not anchored on "Signer #1": apksigner also emits
# "Signer (minSdkVersion=N, maxSdkVersion=M) certificate DN:" depending on
# version and on how the signature blocks map to SDK ranges. On 2026-09-20 a CI
# run parsed nothing with the "#1" form, printed an empty fingerprint, and then
# reported it as a key MISMATCH -- the APK was correctly signed and the build
# had succeeded. "certificate DN:" is the stable part of both forms.
# "certificate SHA-256" also keeps this off the "public key SHA-256" line.
DN="$(echo "$OUT" | sed -n 's/^Signer .*certificate DN: //p' | head -1)"
ACTUAL="$(echo "$OUT" | sed -n 's/^Signer .*certificate SHA-256 digest: //p' | head -1 | normalise)"

# A fingerprint we could not read is a broken check, never a verdict about the
# key. Comparing an empty string against the expected one would "fail" for the
# right exit code and entirely the wrong reason, which is what happened above.
if [ -z "$ACTUAL" ]; then
    printf 'apksigner %s\n' "$APKSIGNER" >&2
    echo "--- raw apksigner output ---" >&2
    echo "$OUT" | grep -vE '^WARNING: (A restricted|java.lang.System|Use --enable|Restricted)' >&2
    echo "--- end ---" >&2
    fail "could not read the signer certificate from apksigner's output (above). This says nothing about whether the APK is correctly signed."
fi

printf 'Signer    %s\n' "$DN"
printf 'SHA-256   %s\n' "$ACTUAL"

# --- 2. is it the DEBUG key? ------------------------------------------------
# Checked BEFORE the v2/v3 assertions below, not after. AGP signs debug builds
# with v2 only (verified 2026-09-20: v1 false, v2 true, v3 false), so a debug
# APK trips the v3 check first and gets rejected with the useless diagnostic
# "no v3 signature block" instead of being named for what it is.
case "$DN" in
    *"CN=Android Debug"*) fail "signed with the Android debug key — this is not a releasable APK" ;;
esac

SIGNERS="$(echo "$OUT" | grep -c '^Signer .*certificate DN:')"
[ "$SIGNERS" = "1" ] || fail "$SIGNERS signers — expected exactly 1"

V2="$(echo "$OUT" | sed -n 's/^Verified using v2 scheme.*: //p')"
V3="$(echo "$OUT" | sed -n 's/^Verified using v3 scheme.*: //p')"
[ "$V2" = "true" ] || fail "no v2 signature block"
[ "$V3" = "true" ] || fail "no v3 signature block"
pass "signature verifies for minSdk $MIN_SDK (v2 and v3 present)"

# --- 3. is it OUR key? ------------------------------------------------------
# The expected fingerprint is READ FROM THE KEYSTORE, never hardcoded. Locally
# that is keystore.properties; in CI it is the same four values from the
# environment (see app/build.gradle.kts). --expect exists for a machine that has
# the APK but not the key, and as opt-in hardening: a literal pinned in the
# workflow would also catch a SWAPPED ANDROID_KEYSTORE_BASE64 secret, which this
# cannot, because it trusts whatever keystore it was handed. That trade is
# deliberate -- a constant nobody updates when the key changes fails the build
# for the wrong reason, and secrets and workflow files are edited by the same
# person on this project.
KEYTOOL="keytool"
[ -x "${JAVA_HOME:-}/bin/keytool" ] && KEYTOOL="$JAVA_HOME/bin/keytool"

if [ -z "$EXPECTED" ]; then
    if [ -f keystore.properties ]; then
        STORE="$(sed -n 's/^storeFile=//p' keystore.properties)"
        ALIAS="$(sed -n 's/^keyAlias=//p' keystore.properties)"
        PASSWD="$(sed -n 's/^storePassword=//p' keystore.properties)"
    else
        STORE="${ANDROID_KEYSTORE_FILE:-}"
        ALIAS="${ANDROID_KEY_ALIAS:-}"
        PASSWD="${ANDROID_KEYSTORE_PASSWORD:-}"
    fi
    if [ -n "$STORE" ] && [ -f "$STORE" ] && [ -n "$PASSWD" ]; then
        EXPECTED="$("$KEYTOOL" -list -v -keystore "$STORE" -alias "$ALIAS" -storepass "$PASSWD" 2>/dev/null \
            | sed -n 's/.*SHA256: //p' | head -1 | normalise)"
        [ -n "$EXPECTED" ] || fail "could not read the certificate for alias '$ALIAS' out of $STORE"
    fi
fi

if [ -z "$EXPECTED" ]; then
    # Without something to compare against, this script has checked that the APK
    # is signed, not BY WHOM. In CI that is not good enough to publish on, so the
    # workflow passes --require-identity and an unresolvable fingerprint stops
    # the release rather than printing a warning nobody reads.
    if [ "$REQUIRE_IDENTITY" = "1" ]; then
        fail "no keystore fingerprint to compare against, and --require-identity was given: cannot confirm who signed this APK"
    fi
    warn "no keystore fingerprint to compare against — signature is valid, but its IDENTITY is unchecked"
    warn "pass --expect <sha256>, set ANDROID_KEYSTORE_FILE/_PASSWORD/_KEY_ALIAS, or fill in keystore.properties"
    exit 0
fi

EXPECTED="$(echo "$EXPECTED" | normalise)"
if [ "$ACTUAL" = "$EXPECTED" ]; then
    pass "signer matches our release key"
else
    printf 'expected  %s\n' "$EXPECTED"
    fail "signed with a DIFFERENT key than our keystore — do not publish this APK"
fi
