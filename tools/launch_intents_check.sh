#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/launch_intents_check.sh — the ways another app or the system asks
# NoSpam to open a conversation (phase 2, P2.2):
#
#   1. SENDTO while the app is not running (cold start), then rotation must not
#      reopen it;
#   2. SENDTO while the app is open on the inbox, and (2b) after its process
#      was killed;
#   3. tapping a message notification while the app is in the background;
#   4. tapping a message notification after the process was killed, before
#      and after the notification was posted;
#   5. text shared from another app (ACTION_SEND), app not running, and (5b)
#      after its process was killed.
#
# A script rather than plain flows because Maestro can neither send an
# arbitrary intent nor deliver an SMS; `adb` does those, the `_launch_*`
# flows (tagged manual-only, so tools/run-e2e.sh skips them) assert.
#
# Needs the debug build installed, the SMS role and permissions granted
# (tools/run-e2e.sh does both), and an emulator for `adb emu sms send` whose
# image allows adb root (google_apis, not Google Play): only root can delete
# the test messages afterwards. The script restarts adb as root itself.
# Usage: tools/launch_intents_check.sh
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
    for cand in "$HOME/Android/Sdk/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" "${ANDROID_HOME:-}/platform-tools/adb"; do
        [ -x "$cand" ] && { export PATH="$(dirname "$cand"):$PATH"; break; }
    done
fi
if ! command -v maestro >/dev/null 2>&1; then
    [ -x "$HOME/.maestro/bin/maestro" ] && export PATH="$HOME/.maestro/bin:$PATH"
fi
command -v adb >/dev/null 2>&1 || { echo "adb not found" >&2; exit 1; }
command -v maestro >/dev/null 2>&1 || { echo "maestro not found" >&2; exit 1; }

SERIAL="${ANDROID_SERIAL:-}"
PKG="com.nospam.nospam"
# Digits only: the emulator console strips every non-digit from a sender, so an
# alphanumeric id like NSTEST_NOTIF1 arrives as "1".
NOTIF_ADDR="15557770002"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLOWS="$ROOT_DIR/.maestro/flows"

adbs() { if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi; }
flow() {
    local name="$1"; shift
    maestro test ${SERIAL:+--udid "$SERIAL"} --include-tags=manual-only "$@" "$FLOWS/$name" >/dev/null 2>&1 \
        || { echo "FAIL: $name"; exit 1; }
}
pass() { echo "PASS: $1"; }
# The app that sends a request is in front when it does, which is what makes a
# request after process death go wrong; the home screen in front does not.
other_app_in_front() { adbs shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 1; }

TEST_ADDRS=("$NOTIF_ADDR" "+$NOTIF_ADDR" "+15557770001" "+15557770003" "+15557770004" "+15557770005" "+15557770006")

delete_messages() { adbs shell "content delete --uri content://sms --where \"address='$1'\"" >/dev/null 2>&1 || true; }
messages_left() {
    adbs shell "content query --uri content://sms --projection _id --where \"address='$1'\"" 2>/dev/null \
        | grep -c '^Row' || true
}

# Checks rather than trusts that the test messages are gone, retrying once, and
# says what is left. It used to discard every error unchecked: on 2026-10-07, 21
# messages from seven runs were found in the inbox, pushing the seeded rows of
# tools/run-e2e.sh off screen, and nothing had said so (see ensure_root).
cleanup() {
    local a left
    for a in "${TEST_ADDRS[@]}"; do delete_messages "$a"; done
    for a in "${TEST_ADDRS[@]}"; do
        left="$(messages_left "$a")"
        if [ "${left:-0}" -gt 0 ]; then
            delete_messages "$a"
            left="$(messages_left "$a")"
            if [ "${left:-0}" -gt 0 ]; then
                echo "WARNING: cleanup left $left message(s) from $a" >&2
            fi
        fi
    done
    adbs shell cmd statusbar collapse >/dev/null 2>&1 || true
}
# The test messages can only be deleted as root. Android ignores a write to the
# SMS provider from anything but the default SMS app, silently: as the shell
# user a delete reports success and removes nothing. Every run left its messages
# behind until something else (tools/seed.sh) had restarted adb as root.
# Checked before any message is sent, so a run that could not clean up never
# starts; an emulator image with Google Play does not allow adb root.
ensure_root() {
    if [ "$(adbs shell id -u 2>/dev/null | tr -d '\r')" != "0" ]; then
        adbs root >/dev/null 2>&1 || true
        sleep 1
        adbs wait-for-device
    fi
    if [ "$(adbs shell id -u 2>/dev/null | tr -d '\r')" != "0" ]; then
        echo "adb cannot run as root on this device, so this check could not delete its test messages." >&2
        echo "Use an emulator image without Google Play (google_apis), which allows adb root." >&2
        exit 1
    fi
}
ensure_root
cleanup
trap cleanup EXIT

echo "-- 1: SENDTO, cold start --"
adbs shell am force-stop "$PKG"
adbs shell am start -W -a android.intent.action.SENDTO -d "smsto:+15557770001" \
    --es sms_body "'Hello from another app'" >/dev/null
sleep 2
flow _launch_sendto_cold.yaml
pass "cold SENDTO opens the conversation with its text; rotation does not reopen it"

echo "-- 2: SENDTO, app already open --"
adbs shell am start -W -a android.intent.action.SENDTO -d "smsto:+15557770003" \
    --es sms_body "'Second request'" >/dev/null
sleep 2
flow _launch_sendto_warm.yaml -e ADDR=5557770003 -e TEXT="Second request"
pass "warm SENDTO opens the conversation with its text"

# The app has a task but no process: the request used to reach the restored
# activity, whose saved back stack (the inbox) won. Only with another app in
# front, as the app asking always is; from the home screen it got through.
echo "-- 2b: SENDTO, process killed --"
other_app_in_front
adbs shell am kill "$PKG"
sleep 1
adbs shell am start -W -a android.intent.action.SENDTO -d "smsto:+15557770005" \
    --es sms_body "'After the process died'" >/dev/null
sleep 2
flow _launch_sendto_warm.yaml -e ADDR=5557770005 -e TEXT="After the process died"
pass "SENDTO opens the conversation after the process died"

tap_notification() {
    local text="$1"
    adbs emu sms send "$NOTIF_ADDR" "$text" >/dev/null
    sleep 4
    adbs shell cmd statusbar expand-notifications >/dev/null
    sleep 1
    flow _launch_notification_tap.yaml -e TEXT="$text"
}

echo "-- 3: notification tap, app in the background --"
adbs shell input keyevent KEYCODE_HOME
tap_notification "Notification tap check one"
pass "notification tap opens its conversation (warm)"

echo "-- 4: notification tap, process killed --"
adbs shell input keyevent KEYCODE_HOME
sleep 1
adbs shell am kill "$PKG"
tap_notification "Notification tap check two"
pass "notification tap opens its conversation (cold)"

# The order case 4 misses: the SMS restarts the process there, but on a phone
# the process usually dies after the notification is posted. The task survives,
# and a tap that reused it got the saved back stack (the inbox) instead of the
# conversation.
echo "-- 4b: notification posted, then the process killed --"
adbs shell input keyevent KEYCODE_HOME
sleep 1
text="Notification tap check three"
adbs emu sms send "$NOTIF_ADDR" "$text" >/dev/null
sleep 4
adbs shell am kill "$PKG"
sleep 1
adbs shell cmd statusbar expand-notifications >/dev/null
sleep 1
flow _launch_notification_tap.yaml -e TEXT="$text"
pass "notification tap opens its conversation after the process died"

share() {
    # Limited to our package, so it resolves without the system chooser.
    adbs shell am start -W -a android.intent.action.SEND -t text/plain \
        --es android.intent.extra.TEXT "'Shared from another app'" -p "$PKG" >/dev/null
    sleep 2
}

echo "-- 5: text shared from another app, cold start --"
adbs shell am force-stop "$PKG"
share
flow _launch_share.yaml
pass "shared text opens the recipient picker, then the conversation with the text"

echo "-- 5b: text shared from another app, process killed --"
adbs shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
other_app_in_front
adbs shell am kill "$PKG"
sleep 1
share
flow _launch_share.yaml
pass "shared text opens the recipient picker after the process died"

echo "All checks passed."
