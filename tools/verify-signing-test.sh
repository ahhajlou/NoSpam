#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/verify-signing-test.sh — tests for tools/verify-signing.sh.
#
# That script broke the release pipeline three times, each time by reporting a
# correctly signed APK as signed with the wrong key. It was only ever exercised
# by the release workflow itself, so every regression was found by a tag push.
# These tests exist so that stops being how we find out.
#
# The stub cases need no Android SDK, no keystore and no built APK: a fake
# apksigner emits real PEM certificates generated here, so they run anywhere,
# including on CI. The cases that need a real signed APK and keystore.properties
# are skipped when those are absent rather than failing.
#
#   tools/verify-signing-test.sh
set -uo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"
SUT="$REPO/tools/verify-signing.sh"

command -v openssl >/dev/null || { echo "openssl required"; exit 1; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT INT TERM
PASSED=0; FAILED=0; SKIPPED=0

for n in ours other debug; do
    case "$n" in
        debug) DN="/C=US/O=Android/CN=Android Debug" ;;
        *)     DN="/C=IR/O=NoSpam/CN=$n" ;;
    esac
    openssl req -x509 -newkey rsa:2048 -keyout "$T/$n.key" -out "$T/$n.pem" \
        -days 30 -nodes -subj "$DN" >/dev/null 2>&1
    openssl x509 -in "$T/$n.pem" -outform DER | openssl dgst -sha256 -r | cut -d' ' -f1 > "$T/$n.fp"
done
OURS="$(cat "$T/ours.fp")"; OTHER="$(cat "$T/other.fp")"
: > "$T/dummy.apk"

# A stub apksigner: $1 is the exit code for `verify`, the rest are PEM files it
# should emit for `--print-certs-pem`.
mkstub() {
    local rc="$1"; shift
    mkdir -p "$T/fake/build-tools/99.0.0"
    {   echo '#!/usr/bin/env bash'
        echo 'for a in "$@"; do [ "$a" = "--print-certs-pem" ] && PEM=1; done'
        echo 'if [ -n "${PEM:-}" ]; then'
        echo '  echo "Signer #1 certificate DN: CN=stub"'
        for f in "$@"; do
            [ -f "$f" ] && { echo "cat <<'PEOF'"; cat "$f"; echo "PEOF"; }
        done
        echo 'fi'
        echo "exit $rc"
    } > "$T/fake/build-tools/99.0.0/apksigner"
    chmod +x "$T/fake/build-tools/99.0.0/apksigner"
}

ck() { # name want_exit want_substring got_exit got_output
    if [ "$4" = "$2" ] && printf '%s' "$5" | grep -qF "$3"; then
        printf 'PASS  %s\n' "$1"; PASSED=$((PASSED + 1))
    else
        printf 'FAIL  %s\n      wanted exit=%s containing "%s", got exit=%s\n%s\n' \
            "$1" "$2" "$3" "$4" "$5"; FAILED=$((FAILED + 1))
    fi
}
stub() { ANDROID_HOME="$T/fake" "$SUT" "$T/dummy.apk" "$@" 2>&1; }

# A copy of the script in a tree with NO keystore.properties, which is the shape
# CI runs in. Without this, cases meant to have nothing to compare against
# silently pick up the developer's real keystore.properties and test the
# opposite of what they claim.
mkdir -p "$T/repo/tools" && cp "$SUT" "$T/repo/tools/"
nokeystore() { ANDROID_HOME="$T/fake" "$T/repo/tools/verify-signing.sh" "$T/dummy.apk" "$@" 2>&1; }

echo "== argument and file handling =="
O=$("$SUT" --bogus 2>&1);            ck "unknown option rejected"        2 "unknown option" $? "$O"
O=$("$SUT" /nonexistent.apk 2>&1);   ck "missing APK named"              1 "no such file" $? "$O"
cp "$T/dummy.apk" "$T/app-release-unsigned.apk"
mkstub 0 "$T/ours.pem"
O=$(stub 2>&1); : # warm the stub
O=$(ANDROID_HOME="$T/fake" "$SUT" "$T/app-release-unsigned.apk" 2>&1)
                                     ck "'unsigned' in filename rejected" 1 "filename says unsigned" $? "$O"
O=$(ANDROID_HOME="$T/empty" "$SUT" "$T/dummy.apk" 2>&1)
                                     ck "no apksigner under ANDROID_HOME" 1 "apksigner not found" $? "$O"

echo "== signature state =="
mkstub 1 "$T/ours.pem"
O=$(stub --expect "$OURS");          ck "apksigner verify fails"          1 "could not verify the signature" $? "$O"
mkstub 0
O=$(stub --expect "$OURS");          ck "verifies but emits no PEM"       1 "emitted no PEM certificate" $? "$O"

echo "== certificate identity =="
mkstub 0 "$T/ours.pem"
O=$(stub --expect "$OURS");          ck "single cert, matches"            0 "signer matches our release key" $? "$O"
O=$(stub --expect "$OTHER");         ck "single cert, mismatch"           1 "DIFFERENT key" $? "$O"
O=$(stub --expect "$(printf '%s' "$OURS" | tr 'a-f' 'A-F')")
                                     ck "expected fingerprint case-insensitive" 0 "signer matches our release key" $? "$O"
mkstub 0 "$T/ours.pem" "$T/ours.pem"
O=$(stub --expect "$OURS");          ck "same cert in two blocks = 1 signer" 0 "signer matches our release key" $? "$O"
mkstub 0 "$T/ours.pem" "$T/other.pem"
O=$(stub --expect "$OURS");          ck "two distinct certs rejected"     1 "2 distinct signing certificates" $? "$O"
mkstub 0 "$T/debug.pem"
O=$(stub --expect "$OURS");          ck "debug cert named, not 'mismatch'" 1 "Android debug key" $? "$O"

echo "== where the expectation comes from =="
mkstub 0 "$T/ours.pem"
O=$(nokeystore);                     ck "nothing to compare = warn, exit 0" 0 "IDENTITY is unchecked" $? "$O"
O=$(nokeystore --require-identity);  ck "--require-identity with nothing"  1 "cannot confirm who signed" $? "$O"
O=$(ANDROID_KEYSTORE_FILE=/nope ANDROID_KEYSTORE_PASSWORD=x ANDROID_KEY_ALIAS=a nokeystore --require-identity)
                                     ck "env keystore path absent"         1 "cannot confirm who signed" $? "$O"
O=$(stub --require-identity);        ck "keystore.properties satisfies --require-identity" 1 "DIFFERENT key" $? "$O"
mkstub 0 "$T/ours.pem"
O=$(stub --fingerprint-out "$T/fp.txt" --expect "$OURS")
if [ "$(cat "$T/fp.txt" 2>/dev/null)" = "$OURS" ]
then echo "PASS  --fingerprint-out writes the bare hex"; PASSED=$((PASSED + 1))
else echo "FAIL  --fingerprint-out: got '$(cat "$T/fp.txt" 2>/dev/null)'"; FAILED=$((FAILED + 1)); fi

echo "== against the real keystore and a real signed APK =="
APK="$(ls -t "$REPO"/app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
if [ -n "$APK" ] && [ -f keystore.properties ] && [ -d "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" ]; then
    KS="$(sed -n 's/^storeFile=//p' keystore.properties)"
    KP="$(sed -n 's/^storePassword=//p' keystore.properties)"
    KA="$(sed -n 's/^keyAlias=//p' keystore.properties)"
    O=$("$SUT" "$APK" 2>&1);         ck "real APK via keystore.properties" 0 "signer matches our release key" $? "$O"
    O=$("$SUT" "$APK" --expect "$OTHER" 2>&1)
                                     ck "real APK, wrong --expect"         1 "DIFFERENT key" $? "$O"
    # The CI shape: no keystore.properties in the tree, values from the env.
    O=$(ANDROID_KEYSTORE_FILE="$KS" ANDROID_KEYSTORE_PASSWORD="$KP" ANDROID_KEY_ALIAS="$KA" \
        "$T/repo/tools/verify-signing.sh" "$APK" --require-identity 2>&1)
                                     ck "CI shape: env vars, no properties file" 0 "signer matches our release key" $? "$O"
    O=$(ANDROID_KEYSTORE_FILE="$KS" ANDROID_KEYSTORE_PASSWORD="$KP" ANDROID_KEY_ALIAS=wrongalias \
        "$T/repo/tools/verify-signing.sh" "$APK" --require-identity 2>&1)
                                     ck "wrong alias blamed on the alias"  1 "could not export the certificate" $? "$O"
    D="$(ls -t "$REPO"/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
    if [ -n "$D" ]; then
        O=$("$SUT" "$D" 2>&1);       ck "real debug APK rejected"          1 "Android debug key" $? "$O"
    else echo "SKIP  real debug APK (none built)"; SKIPPED=$((SKIPPED + 1)); fi
else
    echo "SKIP  real-APK cases (need a signed release APK, keystore.properties and an SDK)"
    SKIPPED=$((SKIPPED + 1))
fi

echo
echo "passed $PASSED, failed $FAILED, skipped $SKIPPED"
[ "$FAILED" -eq 0 ]
