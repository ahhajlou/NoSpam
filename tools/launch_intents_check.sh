#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/launch_intents_check.sh — the ways another app or the system asks
# NoSpam to open a conversation (phase 2, P2.2):
#
#   1. SENDTO while the app is not running (cold start), then rotation must not
#      reopen it;
#   2. SENDTO while the app is open on the inbox (onNewIntent);
#   3. tapping a message notification while the app is in the background;
#   4. tapping a message notification after the process was killed.
#
# A script rather than plain flows because Maestro can neither send an
# arbitrary intent nor deliver an SMS; `adb` does those, the `_launch_*`
# flows (tagged manual-only, so tools/run-e2e.sh skips them) assert.
#
# Needs the debug build installed, the SMS role and permissions granted
# (tools/run-e2e.sh does both), and an emulator for `adb emu sms send`.
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

cleanup() {
    for a in "$NOTIF_ADDR" "+$NOTIF_ADDR" "+15557770001" "+15557770003"; do
        adbs shell "content delete --uri content://sms --where \"address='$a'\"" >/dev/null 2>&1 || true
    done
    adbs shell cmd statusbar collapse >/dev/null 2>&1 || true
}
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
flow _launch_sendto_warm.yaml
pass "warm SENDTO opens the conversation with its text"

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

echo "All checks passed."
