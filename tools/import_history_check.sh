#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/import_history_check.sh — does NoSpam show history it did not receive itself,
# complete and in date order, when that history was restored out of order?
#
# The two oldest bugs in TODO.md ("messages from before install are not listed",
# "message order is wrong after installing on a phone with old messages") came from
# the thread pagination cursor: it paged by row id while sorting by date, so a
# message that is older but was stored later was unreachable. A phone whose history
# arrived in order never shows it (checked on a Galaxy A26, 2026-10-02: no
# inversions in 2,086 messages). History restored by a backup or transfer tool does,
# and this script builds that on an emulator:
#
#   1. Uninstalls NoSpam and makes Google Messages the default SMS app, as on a
#      phone before NoSpam was installed.
#   2. Restores three conversations the way backup tools do, straight into the
#      provider with past dates:
#        RESTORE_A 450 messages, newest first (every older message has a higher
#                  row id; more than two pages of 200)
#        RESTORE_B 120 messages in shuffled order
#        RESTORE_C  30 messages oldest first (the order a phone would have them)
#      Each body carries its position by date ("msg 0007 of 0450") so the check
#      can tell missing and misordered messages apart. Incoming and sent alternate.
#   3. A live SMS then arrives in RESTORE_A through the emulator console.
#   4. Installs the APK, grants the permissions, takes the SMS role.
#   5. Checks the inbox lists all three, then opens each conversation, scrolls to
#      its oldest message, and checks every message was shown, in date order.
#
# Destructive on the target: uninstalling NoSpam drops its data and seeded
# fixtures (tools/seed.sh puts them back). Emulator only: needs adb root and the
# emulator console. It removes its own conversations before restoring them, so
# it can be re-run.
#
# Usage:
#   tools/import_history_check.sh [--apk PATH] [--keep]
#     --apk   APK to test (default: app/build/outputs/apk/debug/app-debug.apk)
#     --keep  leave the restored conversations in place afterwards
#   ANDROID_SERIAL selects the emulator when more than one device is attached.
set -euo pipefail

PKG="com.nospam.nospam"
MESSAGES_PKG="com.google.android.apps.messaging"
APK="app/build/outputs/apk/debug/app-debug.apk"
KEEP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --keep) KEEP=1; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -f "$APK" ] || { echo "APK not found: $APK (build it with ./gradlew :app:assembleDebug)" >&2; exit 2; }

if ! command -v adb >/dev/null 2>&1; then
    for cand in "$HOME/Android/Sdk/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" "${ANDROID_HOME:-}/platform-tools/adb"; do
        [ -x "$cand" ] && { export PATH="$(dirname "$cand"):$PATH"; break; }
    done
fi
command -v adb >/dev/null 2>&1 || { echo "adb not found" >&2; exit 2; }
adbs() { if [ -n "${ANDROID_SERIAL:-}" ]; then adb -s "$ANDROID_SERIAL" "$@"; else adb "$@"; fi; }

serial="$(adbs get-serialno | tr -d '\r')"
case "$serial" in emulator-*) ;; *) echo "refusing to run on $serial: emulator only (this uninstalls NoSpam)" >&2; exit 2 ;; esac

# Numeric addresses: the emulator console keeps only a sender's digits, and step 3
# must land in the same conversation as the restored history.
ADDR_A="5559100001"; ADDR_B="5559100002"; ADDR_C="5559100003"
N_A=450; N_B=120; N_C=30
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "== target $serial"
adbs root >/dev/null 2>&1 || true; sleep 1; adbs wait-for-device

delete_restored() {
    for a in "$ADDR_A" "$ADDR_B" "$ADDR_C"; do
        adbs shell "content delete --uri content://sms --where \"address='$a'\"" >/dev/null 2>&1 || true
    done
}

echo "== 1. uninstall NoSpam, Google Messages becomes the default SMS app"
adbs uninstall "$PKG" >/dev/null 2>&1 || true
adbs shell cmd role add-role-holder android.app.role.SMS "$MESSAGES_PKG" 0 >/dev/null 2>&1 || true
holder="$(adbs shell cmd role get-role-holders android.app.role.SMS | tr -d '\r')"
echo "   SMS role: $holder"

echo "== 2. restore three conversations out of order"
delete_restored
now=$(date +%s%3N)
day=86400000
# gen ADDRESS N ORDER(newest|shuffled|oldest) LABEL — one `content insert` per line,
# message i (1 = oldest) dated (N - i + 30) days * 0.7 ago, alternating in/sent.
gen() {
    local addr="$1" n="$2" order="$3" label="$4"
    local idx
    case "$order" in
        newest) idx=$(seq "$n" -1 1) ;;
        oldest) idx=$(seq 1 "$n") ;;
        shuffled) idx=$(seq 1 "$n" | shuf --random-source=<(yes)) ;;
    esac
    for i in $idx; do
        local d=$(( now - (n - i + 30) * day * 7 / 10 ))
        local type=$(( i % 2 == 0 ? 2 : 1 ))
        printf "content insert --uri content://sms --bind address:s:'%s' --bind body:s:'%s msg %04d of %04d' --bind date:l:%d --bind type:i:%d --bind read:i:1 --bind seen:i:1 >/dev/null\n" \
            "$addr" "$label" "$i" "$n" "$d" "$type"
    done
}
{
    echo "#!/system/bin/sh"
    gen "$ADDR_A" "$N_A" newest RESTORE_A
    gen "$ADDR_B" "$N_B" shuffled RESTORE_B
    gen "$ADDR_C" "$N_C" oldest RESTORE_C
} > "$WORK/restore.sh"
adbs push "$WORK/restore.sh" /data/local/tmp/nstest_restore.sh >/dev/null
adbs shell "sh /data/local/tmp/nstest_restore.sh; rm -f /data/local/tmp/nstest_restore.sh"

echo "== 3. a live SMS arrives in RESTORE_A"
adbs emu sms send "$ADDR_A" "RESTORE_A live message after the restore" >/dev/null
sleep 5

echo "== 4. install $APK, grant permissions, take the SMS role"
adbs install -r "$APK" >/dev/null
for p in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE READ_PHONE_NUMBERS POST_NOTIFICATIONS; do
    adbs shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1 || true
done
adbs shell cmd role add-role-holder android.app.role.SMS "$PKG" 0 >/dev/null
adbs shell am force-stop "$PKG"
adbs shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 6

dump() { adbs shell uiautomator dump /sdcard/nstest_ui.xml >/dev/null 2>&1; adbs shell cat /sdcard/nstest_ui.xml; }
# open_thread ADDRESS — from a fresh inbox, scroll until the row shows and tap it.
# Tapping rather than a launch intent works on builds older than the intent
# handling (2026-09-23), which the check must be able to run against.
open_thread() {
    adbs shell am force-stop "$PKG"
    adbs shell am start -n "$PKG/.MainActivity" >/dev/null
    sleep 4
    for _ in $(seq 1 25); do
        dump > "$WORK/row.xml"
        local xy
        xy="$(python3 - "$WORK/row.xml" "$1" <<'PY'
import re, sys
for t, a, b, c, d in re.findall(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', open(sys.argv[1]).read()):
    # Phone numbers are wrapped in invisible isolates (isolateIfPhoneNumber).
    if re.sub('[\u2066-\u2069\u200e\u200f]', '', t) == sys.argv[2]:
        print((int(a) + int(c)) // 2, (int(b) + int(d)) // 2); break
PY
)"
        if [ -n "$xy" ]; then adbs shell input tap $xy; sleep 4; return 0; fi
        adbs shell input swipe 540 1900 540 700 250
        sleep 1
    done
    return 1
}

fail=0
echo "== 5a. the inbox lists the restored conversations"
# They are dated weeks back, so below the first screen: scroll the whole list.
: > "$WORK/inbox.xml"
for _ in $(seq 1 25); do
    dump >> "$WORK/inbox.xml"
    adbs shell input swipe 540 1900 540 700 250
    sleep 1
done
for a in "$ADDR_A" "$ADDR_B" "$ADDR_C"; do
    if grep -q "$a" "$WORK/inbox.xml"; then echo "   ok    $a listed"; else echo "   FAIL  $a not in the inbox"; fail=1; fi
done

# check_thread ADDRESS LABEL N EXTRA — open it, swipe toward older messages until
# three swipes show nothing new, then compare what was seen with what exists.
check_thread() {
    local addr="$1" label="$2" n="$3" extra="$4"
    open_thread "$addr" || { echo "   FAIL  $label: could not find $addr in the inbox to open it"; return 1; }
    : > "$WORK/seen.txt"
    local stale=0 before=0 swipes=0
    # Hard cap as well: a list that stopped loading must end the check, not hang it.
    while [ "$stale" -lt 3 ] && [ "$swipes" -lt 200 ]; do
        swipes=$((swipes + 1))
        dump > "$WORK/t.xml"
        # Markers in on-screen order (top to bottom), one line per screen.
        python3 - "$WORK/t.xml" "$label" >> "$WORK/seen.txt" <<'PY'
import re, sys
x = open(sys.argv[1]).read()
nodes = re.findall(r'text="([^"]*)"[^>]*bounds="\[\d+,(\d+)\]', x)
marks = sorted((int(y), int(m.group(1))) for t, y in nodes
               for m in [re.search(sys.argv[2] + r' msg (\d+) of', t)] if m)
live = any('live message after the restore' in t for t, _ in nodes)
print(' '.join(str(i) for _, i in marks) + (' LIVE' if live else ''))
PY
        # Distinct messages seen so far: repeats on overlapping screens must not
        # count as progress, or a list that stopped loading is scrolled forever.
        local now_seen; now_seen=$(tr ' ' '\n' < "$WORK/seen.txt" | grep -E '^[0-9]+$|^LIVE$' | sort -u | wc -l)
        if [ "$now_seen" -le "$before" ]; then stale=$((stale + 1)); else stale=0; fi
        before=$now_seen
        # Slow and half a screen: a fast swipe flings past messages that are
        # then never on screen in any snapshot, and read as missing.
        adbs shell input swipe 540 900 540 1700 1500
        sleep 1
    done
    python3 - "$WORK/seen.txt" "$n" "$label" "$extra" <<'PY'
import sys
lines = [l.split() for l in open(sys.argv[1])]
n, label, extra = int(sys.argv[2]), sys.argv[3], sys.argv[4] == "live"
seen = {int(t) for l in lines for t in l if t.isdigit()}
missing = sorted(set(range(1, n + 1)) - seen)
# On each screen, top to bottom must be oldest to newest.
misordered = [l for l in lines if [int(t) for t in l if t.isdigit()] != sorted(int(t) for t in l if t.isdigit())]
live_ok = (not extra) or any('LIVE' in l for l in lines)
ok = not missing and not misordered and live_ok
print(f"   {'ok   ' if ok else 'FAIL '} {label}: {len(seen)}/{n} restored messages shown"
      + (f", missing {len(missing)} (oldest missing: {missing[:5]})" if missing else "")
      + (f", {len(misordered)} screen(s) out of date order" if misordered else "")
      + ("" if live_ok else ", the live message was not shown"))
sys.exit(0 if ok else 1)
PY
}

echo "== 5b. every restored message is reachable, in date order"
check_thread "$ADDR_A" RESTORE_A "$N_A" live || fail=1
check_thread "$ADDR_B" RESTORE_B "$N_B" none || fail=1
check_thread "$ADDR_C" RESTORE_C "$N_C" none || fail=1
adbs shell rm -f /sdcard/nstest_ui.xml

if [ "$KEEP" -eq 0 ]; then delete_restored; fi
if [ "$fail" -eq 0 ]; then echo "== PASS"; else echo "== FAIL"; fi
exit "$fail"
