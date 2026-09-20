#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/verify-signing.sh — check that a built APK is signed, and signed with
# OUR key.
#
# Why this exists rather than a bare `apksigner verify`:
#
#   1. `apksigner verify` answers "is this APK internally consistent?", NOT
#      "is this the key that signs NoSpam?". A debug-signed APK, or one signed
#      with a throwaway key, passes it happily.
#   2. The release signing config in app/build.gradle.kts is ABSENT when no
#      keystore is configured, and `assembleRelease` then emits an UNSIGNED APK
#      rather than failing. A typo in storeFile therefore looks like a
#      successful build. This turns that into a loud failure.
#
# WHY IT DOES NOT READ apksigner's PRINTED TEXT.
#
# It used to, and that broke CI three times. apksigner names each signer
# differently depending on build-tools version and on which schemes verified:
#
#   Signer #1 certificate DN: ...                                  <= 36.x
#   Signer (minSdkVersion=N, maxSdkVersion=M) certificate DN: ...   v3.1 verified
#   V3.0 Signer: certificate DN: ...                                37.0.0
#
# Every time, a correctly signed APK from a successful build was reported as
# signed with the wrong key. That output is a human-readable UI with no
# compatibility promise, so this script no longer parses it at all. Instead:
#
#   * whether the APK is signed  -> apksigner's EXIT CODE
#   * which certificate signed it -> `--print-certs-pem`, and we compute the
#     SHA-256 of the DER bytes ourselves with openssl
#   * which certificate SHOULD have -> `keytool -exportcert` from the keystore,
#     hashed the same way
#
# Both sides are then the same hash of the same standard encoding. PEM
# delimiters are RFC 7468, not a vendor's log format.
#
# Usage:
#   tools/verify-signing.sh                        # newest release APK
#   tools/verify-signing.sh path/to/some.apk
#   tools/verify-signing.sh --expect <sha256>      # compare without the keystore
#   tools/verify-signing.sh --require-identity     # unresolvable expectation = failure
#   tools/verify-signing.sh --fingerprint-out FILE # write the hex fingerprint to FILE
set -uo pipefail

# The script works from the repo root (keystore.properties, the default APK
# path), but an APK argument must still mean what the CALLER meant by it, so
# remember where we were invoked from and resolve relative paths against that.
ORIG_PWD="$PWD"
cd "$(dirname "$0")/.."

MIN_SDK=26
EXPECTED=""
APK=""
REQUIRE_IDENTITY=0
FP_OUT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --expect)          EXPECTED="${2:-}"; shift 2 ;;
        --require-identity) REQUIRE_IDENTITY=1; shift ;;
        --fingerprint-out) FP_OUT="${2:-}"; shift 2 ;;
        -h|--help)         sed -n '5,45p' "$0"; exit 0 ;;
        -*)                echo "unknown option: $1" >&2; exit 2 ;;
        *)                 APK="$1"; shift ;;
    esac
done

if [ -t 1 ]; then R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; N=$'\033[0m'
else R=''; G=''; Y=''; N=''; fi
fail() { printf '%sFAIL%s  %s\n' "$R" "$N" "$*"; exit 1; }
pass() { printf '%sOK%s    %s\n' "$G" "$N" "$*"; }
warn() { printf '%sWARN%s  %s\n' "$Y" "$N" "$*"; }

normalise() { tr -d ': \r' | tr '[:upper:]' '[:lower:]'; }

TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT INT TERM

# --- tools ------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] || fail "apksigner not found under $SDK/build-tools"
command -v openssl >/dev/null || fail "openssl not found; it is how this script hashes certificates"
KEYTOOL="keytool"
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ] && KEYTOOL="$JAVA_HOME/bin/keytool"

# --- the APK ----------------------------------------------------------------
if [ -z "$APK" ]; then
    APK="$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
    [ -n "$APK" ] || fail "no APK in app/build/outputs/apk/release — run ./gradlew :app:assembleRelease first"
fi
case "$APK" in
    /*) ;;
    *)  [ -f "$ORIG_PWD/$APK" ] && APK="$ORIG_PWD/$APK" ;;
esac
[ -f "$APK" ] || fail "no such file: $APK"
printf 'APK       %s\n' "$APK"
printf 'apksigner %s\n' "$APKSIGNER"

case "$APK" in
    *unsigned*) fail "filename says unsigned — the release signing config did not apply" ;;
esac

# --- 1. is it signed, and does it verify for our minSdk? --------------------
# Exit code only. Nothing here depends on how apksigner words its output.
if ! VERIFY_OUT="$("$APKSIGNER" verify --min-sdk-version "$MIN_SDK" "$APK" 2>&1)"; then
    echo "--- apksigner output ---"
    printf '%s\n' "$VERIFY_OUT"
    echo "--- end ---"
    fail "apksigner could not verify the signature for minSdk $MIN_SDK"
fi
pass "signed, and verifies for minSdk $MIN_SDK"

# --- 2. which certificate signed it? ----------------------------------------
PEM_OUT="$("$APKSIGNER" verify --min-sdk-version "$MIN_SDK" --print-certs-pem "$APK" 2>/dev/null)"
printf '%s\n' "$PEM_OUT" | awk -v d="$TMPD" '
    /^-----BEGIN CERTIFICATE-----$/ { n++; f = sprintf("%s/cert%03d.pem", d, n) }
    f                               { print > f }
    /^-----END CERTIFICATE-----$/   { close(f); f = "" }'

NCERT=0
for c in "$TMPD"/cert*.pem; do [ -e "$c" ] && NCERT=$((NCERT + 1)); done
if [ "$NCERT" -eq 0 ]; then
    echo "--- apksigner --print-certs-pem output ---"
    printf '%s\n' "$PEM_OUT"
    echo "--- end ---"
    fail "apksigner emitted no PEM certificate (above). This says nothing about whether the APK is correctly signed."
fi

# Every distinct certificate that signed this APK. A source stamp or a second
# signer shows up here as an extra entry and is rejected below rather than
# quietly ignored: failing closed on an unexpected certificate is the point.
for c in "$TMPD"/cert*.pem; do
    fp="$(openssl x509 -in "$c" -outform DER 2>/dev/null | openssl dgst -sha256 -r 2>/dev/null | cut -d' ' -f1 | normalise)"
    [ -n "$fp" ] || fail "openssl could not read a certificate apksigner emitted ($c)"
    printf '%s\n' "$fp" >> "$TMPD/fps"
done
FPS="$(sort -u "$TMPD/fps")"
NFP="$(printf '%s\n' "$FPS" | wc -l | tr -d ' ')"
[ "$NFP" -eq 1 ] || fail "$NFP distinct signing certificates — expected exactly 1"
ACTUAL="$FPS"

DN="$(openssl x509 -in "$TMPD"/cert001.pem -noout -subject -nameopt RFC2253 2>/dev/null | sed 's/^subject= *//')"
printf 'Signer    %s\n' "$DN"
printf 'SHA-256   %s\n' "$ACTUAL"

case "$DN" in
    *"CN=Android Debug"*) fail "signed with the Android debug key — this is not a releasable APK" ;;
esac

# --- 3. is it OUR key? ------------------------------------------------------
# The expectation is READ FROM THE KEYSTORE, never hardcoded: keystore.properties
# locally, the same four variables from the environment in CI. --expect is for a
# machine that has the APK but not the key.
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
        # -file, not stdout, and the exit status is checked. `keytool
        # -exportcert` with a bad alias exits 1 but still writes its error
        # message to STDOUT (verified: 70 bytes). Piping stdout straight into a
        # hash turned that message into a plausible-looking fingerprint, and the
        # comparison below then blamed the signing key for a wrong alias --
        # precisely the misleading verdict this script exists to avoid.
        if ! "$KEYTOOL" -exportcert -keystore "$STORE" -alias "$ALIAS" \
                -storepass "$PASSWD" -file "$TMPD/expected.der" >"$TMPD/keytool.out" 2>&1; then
            echo "--- keytool output ---"; cat "$TMPD/keytool.out"; echo "--- end ---"
            fail "could not export the certificate for alias '$ALIAS' from $STORE (above). This says nothing about the APK."
        fi
        # Round-tripped through openssl so a non-certificate cannot slip through,
        # and hashed exactly like the APK's certificate above: DER bytes, SHA-256.
        EXPECTED="$(openssl x509 -inform DER -in "$TMPD/expected.der" -outform DER 2>/dev/null \
            | openssl dgst -sha256 -r 2>/dev/null | cut -d' ' -f1 | normalise)"
        [ -n "$EXPECTED" ] || fail "keytool exported something for alias '$ALIAS' that is not a certificate"
    fi
fi

if [ -z "$EXPECTED" ]; then
    if [ "$REQUIRE_IDENTITY" = "1" ]; then
        fail "nothing to compare against and --require-identity was given: cannot confirm who signed this APK"
    fi
    warn "no keystore fingerprint to compare against — signature is valid, but its IDENTITY is unchecked"
    warn "pass --expect <sha256>, set ANDROID_KEYSTORE_FILE/_PASSWORD/_KEY_ALIAS, or fill in keystore.properties"
else
    EXPECTED="$(printf '%s' "$EXPECTED" | normalise)"
    if [ "$ACTUAL" = "$EXPECTED" ]; then
        pass "signer matches our release key"
    else
        printf 'expected  %s\n' "$EXPECTED"
        fail "signed with a DIFFERENT key than our keystore — do not publish this APK"
    fi
fi

# Machine-readable handoff, so the workflow never greps the text above.
if [ -n "$FP_OUT" ]; then
    printf '%s' "$ACTUAL" > "$FP_OUT" || fail "could not write the fingerprint to $FP_OUT"
fi
