#!/usr/bin/env bash
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
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
    echo "Device attached but not finished booting." >&2; exit 1; }

echo "== installing debug build"
./gradlew :app:installDebug -q || exit 1

echo "== granting the SMS role and permissions"
adb shell cmd role add-role-holder android.app.role.SMS com.nospam.nospam >/dev/null 2>&1
for p in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS POST_NOTIFICATIONS; do
    adb shell pm grant com.nospam.nospam "android.permission.$p" >/dev/null 2>&1
done
role=$(adb shell cmd role get-role-holders android.app.role.SMS 2>/dev/null | tr -d '\r')
[ "$role" = "com.nospam.nospam" ] || { echo "SMS role not held (got '$role')" >&2; exit 1; }

pass=0; fail=0; failed_flows=""
for flow in .maestro/flows/*.yaml; do
    name=$(basename "$flow" .yaml)
    # Tagged flows are opt-in: `debug` needs a debug-only feature module,
    # `destructive` rewrites global classifier state.
    grep -qE "^\s+- (debug|destructive)$" "$flow" && { echo "-- skip $name (tagged)"; continue; }

    echo "-- reseed + $name"
    bash tools/seed.sh core >/dev/null 2>&1
    if maestro test "$flow" >/dev/null 2>&1; then
        echo "   PASS $name"; pass=$((pass+1))
    else
        echo "   FAIL $name"; fail=$((fail+1)); failed_flows="$failed_flows $name"
    fi
done

echo
echo "== $pass passed, $fail failed"
if [ "$fail" -gt 0 ]; then
    echo "   failed:$failed_flows"
    exit 1
fi
exit 0
