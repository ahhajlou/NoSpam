#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/run-e2e.sh — run the Maestro suite against a connected device.
#
# Why this exists rather than plain `maestro test .maestro/flows`:
# the flows share one fixture pool seeded into the Telephony provider and
# nospam.db. Several of them mutate that pool (block, unblock, archive, star,
# mark-not-spam), so a flow can silently break the preconditions of the next
# one and the failure looks like a flake. This reseeds before EVERY flow, so
# each starts from the same known state and results are order-independent.
#
# `tools/seed.sh core` is used rather than `seed`: it skips the two heavy
# threads (220 and 210 messages), taking ~38s instead of ~9min. Flows needing
# those call for `seed.sh pagebug` themselves.
#
# Emulator note: if you start one by hand, use `-qt-hide-window`, NOT
# `-no-window`. The latter selects qemu-system-x86_64-headless, which segfaults
# during startup on Android 16+ images on Linux. Same emulator build, different
# binary. This cost most of a session to find; do not "simplify" it back.
set -uo pipefail

export PATH="$HOME/Android/Sdk/platform-tools:$HOME/.maestro/bin:$PATH"
cd "$(dirname "$0")/.."

command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
command -v maestro >/dev/null || { echo "maestro not found" >&2; exit 1; }

if ! adb devices | grep -qE "device$"; then
    echo "No device. Start an emulator from Android Studio, or a physical device over USB." >&2
    exit 1
fi
# Maestro does not run while any attached device is unauthorized or offline,
# even with --udid naming another one: it reports the requested device "not
# connected" and every flow fails (Maestro 2.10, 2026-10-17, with a phone on
# USB whose debugging prompt had not been accepted).
not_ready=$(adb devices | awk 'NR > 1 && NF == 2 && $2 != "device" { print $1 " (" $2 ")" }')
if [ -n "$not_ready" ]; then
    echo "Not ready: $not_ready. Unplug it or accept its USB debugging prompt: Maestro does not run while it is attached." >&2
    exit 1
fi
# With two devices the install below goes to both, and adb refuses to pick one.
if [ -z "${ANDROID_SERIAL:-}" ] && [ "$(adb devices | awk 'NR > 1 && $2 == "device"' | wc -l)" -gt 1 ]; then
    echo "More than one device attached: set ANDROID_SERIAL to the one to test (e.g. ANDROID_SERIAL=emulator-5554)." >&2
    exit 1
fi
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
    echo "Device attached but not finished booting." >&2; exit 1; }

echo "== installing debug build"
./gradlew :app:installDebug -q || exit 1

echo "== granting the SMS role and permissions"
adb shell cmd role add-role-holder android.app.role.SMS com.nospam.nospam >/dev/null 2>&1
for p in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE READ_PHONE_NUMBERS POST_NOTIFICATIONS; do
    adb shell pm grant com.nospam.nospam "android.permission.$p" >/dev/null 2>&1
done
role=$(adb shell cmd role get-role-holders android.app.role.SMS 2>/dev/null | tr -d '\r')
[ "$role" = "com.nospam.nospam" ] || { echo "SMS role not held (got '$role')" >&2; exit 1; }

# The archive snackbar's Undo is on screen for 4s, and Maestro's waits for the
# screen to settle after a swipe and before a tap take about that long:
# swipe_actions tapped Undo after it had gone in 1 run of 8 (2026-10-19).
# Android's "Time to take action" accessibility setting, which Compose's
# snackbars honor for those with an action (only that one here), keeps it for
# 10s. The device's own value is put back when the run ends.
timeout_key=accessibility_interactive_ui_timeout_ms
old_timeout=$(adb shell settings get secure "$timeout_key" | tr -d '\r')
restore_timeout() {
    if [ "$old_timeout" = "null" ]; then
        adb shell settings delete secure "$timeout_key" >/dev/null 2>&1
    else
        adb shell settings put secure "$timeout_key" "$old_timeout" >/dev/null 2>&1
    fi
}
trap restore_timeout EXIT
adb shell settings put secure "$timeout_key" 10000

pass=0; fail=0; failed_flows=""
# Each flow's seed and Maestro output, for the failures this prints in one line.
logs=build/e2e
rm -rf "$logs" && mkdir -p "$logs"
for flow in .maestro/flows/*.yaml; do
    name=$(basename "$flow" .yaml)
    # Tagged flows are opt-in: `destructive` rewrites global classifier state,
    # and `manual-only` flows are driven by tools/persistence_check.sh, which
    # injects SMS between their parts — reseeding between them here guaranteed
    # part 2 failed.
    grep -qE "^\s+- (destructive|manual-only)$" "$flow" && { echo "-- skip $name (tagged)"; continue; }
    # tools/permission_gate_check.sh revokes permissions to assert the onboarding
    # gate and restores them at the end; if it was interrupted, every flow here
    # would strand on onboarding. Re-granting is cheap insurance against that.
    for p in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE READ_PHONE_NUMBERS; do
        adb shell pm grant com.nospam.nospam "android.permission.$p" >/dev/null 2>&1
    done

    echo "-- reseed + $name"
    # A failed seed leaves the fixtures half made, and the flow would then fail
    # for that instead: on a fresh install it once failed after deleting them.
    if ! bash tools/seed.sh core > "$logs/$name.seed.log" 2>&1; then
        echo "   FAIL $name: seeding failed ($(tail -1 "$logs/$name.seed.log")), see $logs/$name.seed.log"
        fail=$((fail+1)); failed_flows="$failed_flows $name"
        continue
    fi
    # --udid: with more than one device attached (a phone plus the emulator)
    # Maestro would otherwise pick one itself, whichever adb and seed.sh used.
    if maestro test ${ANDROID_SERIAL:+--udid "$ANDROID_SERIAL"} "$flow" > "$logs/$name.log" 2>&1; then
        echo "   PASS $name"; pass=$((pass+1))
    else
        echo "   FAIL $name: $(grep -m1 'FAILED' "$logs/$name.log" | sed 's/\.\.\. FAILED$//'), see $logs/$name.log"
        fail=$((fail+1)); failed_flows="$failed_flows $name"
    fi
done

echo
echo "== $pass passed, $fail failed"
if [ "$fail" -gt 0 ]; then
    echo "   failed:$failed_flows"
    exit 1
fi
exit 0
