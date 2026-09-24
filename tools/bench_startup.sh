#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/bench_startup.sh — cold-start timing of one APK on one device.
#
# Installs the APK over the existing app, applies its baseline profile, discards
# the first launch, then cold-launches N times. Per launch it prints:
#
#   first-frame ms   `am start -W` TotalTime: the first frame, which is the
#                    navigation spinner, not the inbox
#   conversations    rows in the inbox
#   inbox ms         `NoSpamPerf: inbox loaded`, counted from ViewModel creation
#   fork-to-inbox ms Zygote "Process N created" to `inbox loaded`: what the
#                    user waits for
#   skipped frames   the first `Choreographer: Skipped N frames`, if any
#
# Each guard below exists because its absence produced a wrong result once
# (TODO.md, "Cold start: ~400ms main-thread stall before the inbox"):
#   - the profile is compiled after the first launch, when ProfileInstaller has
#     written it, and the run stops unless dexopt reports speed-profile;
#   - the required permissions are granted again after install, because an APK
#     that does not declare one (anything before 2026-09-17 lacks
#     READ_PHONE_NUMBERS) drops its grant and every launch lands on onboarding;
#   - a launch counts only if the inbox is on screen, because the inbox
#     ViewModel is hoisted and logs `inbox loaded` behind onboarding too.
#
# Measure a release build (CLAUDE.md §10). Sign it with the debug keystore so it
# installs over a debug build and keeps nospam.db:
#     ./gradlew :app:assembleRelease
#     cp app/build/outputs/apk/release/NoSpam-*-release.apk /tmp/bench.apk
#     apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android /tmp/bench.apk
#
# Needs NoSpam installed and holding the SMS role (it is re-asserted), and
# ANDROID_SERIAL set, so a second connected device is never the one measured.
# Usage: ANDROID_SERIAL=<serial> tools/bench_startup.sh <apk> <label> [runs=5]
set -u

if ! command -v adb >/dev/null 2>&1; then
    for cand in "$HOME/Android/Sdk/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" "${ANDROID_HOME:-}/platform-tools/adb"; do
        [ -x "$cand" ] && { export PATH="$(dirname "$cand"):$PATH"; break; }
    done
fi
command -v adb >/dev/null 2>&1 || { echo "adb not found" >&2; exit 1; }
[ -n "${ANDROID_SERIAL:-}" ] || { echo "set ANDROID_SERIAL to the device to measure" >&2; exit 1; }
[ $# -ge 2 ] || { echo "usage: ANDROID_SERIAL=<serial> $0 <apk> <label> [runs]" >&2; exit 1; }

PKG=com.nospam.nospam
APK=$1; LABEL=$2; RUNS=${3:-5}
REQUIRED="READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE READ_PHONE_NUMBERS"

# Behind the lock screen the app still starts and logs, but draws nothing the
# inbox check can see. A phone left alone mid-run locks too; Developer options
# > Stay awake keeps it on while charging.
screen_ready() {
    adb shell dumpsys power | grep -q "mWakefulness=Awake" &&
        ! adb shell dumpsys window | grep -q "isKeyguardShowing=true"
}
screen_ready || { echo "the device is asleep or locked; unlock it and run again" >&2; exit 1; }

adb install -r "$APK" >/dev/null || { echo "install failed" >&2; exit 1; }
for p in $REQUIRED; do adb shell pm grant "$PKG" "android.permission.$p"; done
adb shell cmd role add-role-holder android.app.role.SMS "$PKG"

# ProfileInstaller writes the APK's baseline profile during the first launch.
# Compiling before that finds no profile and only verifies.
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null
for _ in $(seq 40); do
    adb logcat -d | grep -q "ProfileInstaller: Installing profile for $PKG" && break
    sleep 0.5
done
sleep 3
adb shell am force-stop "$PKG"
adb shell cmd package compile -f -m speed-profile "$PKG" >/dev/null
status=$(adb shell dumpsys package dexopt | grep -A3 "\[$PKG\]" | grep -o "status=[a-z-]*" | head -1)
echo "compile: $status"
[ "$status" = "status=speed-profile" ] || { echo "profile not applied, not measuring" >&2; exit 3; }

run() {
    adb shell am force-stop "$PKG"; sleep 2; adb logcat -c
    local total line inbox e2e skipped
    total=$(adb shell am start -W -n "$PKG/.MainActivity" | sed -n 's/TotalTime: //p' | tr -d '\r')
    line=""
    for _ in $(seq 60); do
        line=$(adb logcat -d -s NoSpamPerf | grep -m1 "inbox loaded")
        [ -n "$line" ] && break
        sleep 0.25
    done
    sleep 1
    adb shell uiautomator dump /sdcard/bench_startup.xml >/dev/null 2>&1
    if ! adb shell cat /sdcard/bench_startup.xml | grep -q 'text="Search conversations"'; then
        screen_ready || { echo "the device locked during the run; unlock it and run again" >&2; exit 2; }
        echo "not on the inbox after launch, stopping" >&2; exit 2
    fi
    inbox=$(echo "$line" | sed -n 's/.*inbox loaded: \([0-9]*\) in \([0-9]*\)ms.*/\1 \2/p')
    e2e=$(adb logcat -d -v epoch | awk -v pkg="$PKG" '
        $0 ~ "Process [0-9]+ created for " pkg && !s { s = $1 }
        /NoSpamPerf.*inbox loaded/ && s { printf "%d", ($1 - s) * 1000; exit }')
    skipped=$(adb logcat -d | grep -m1 "Skipped [0-9]* frames" | sed -n 's/.*Skipped \([0-9]*\) frames.*/\1/p')
    echo "$total $inbox $e2e ${skipped:-0}"
}

run >/dev/null   # discarded: the first launch after compiling
echo "== $LABEL: first-frame ms | conversations | inbox ms | fork-to-inbox ms | skipped frames"
for _ in $(seq "$RUNS"); do run; done
