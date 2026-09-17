#!/usr/bin/env bash
# tools/permission_gate_check.sh — the half of the permission gate Maestro cannot reach.
#
# This lives in a script rather than a Maestro flow because Maestro cannot deny
# these permissions: verified 2026-09-18 that `launchApp: permissions: {phone:
# deny}` leaves READ_PHONE_STATE granted. Holding the default-SMS role makes
# Android re-grant contacts and phone (they show GRANTED_BY_ROLE), so only an
# explicit `pm revoke` — or the user in system settings — actually takes them
# away.
#
# Both turn out to kill the process (SMS permissions are granted by the role, so
# losing either takes the process with it), which is why NoSpamNavHost checks on
# cold start as well as on resume. The resume check is the belt-and-braces for a
# process that survives; this script proves the user-visible outcome either way.
#
# Usage: tools/permission_gate_check.sh      # needs a device with the app installed
set -uo pipefail
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
PKG="com.nospam.nospam"
fail=0

# The dump is written to a file rather than piped: `grep -q` exits on its first
# match, adb takes SIGPIPE, and `set -o pipefail` then reports failure for a
# pipeline that actually found what it was looking for.
ui_has() {
    local dump
    dump="$(mktemp)"
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/ui.xml > "$dump" 2>/dev/null
    tr '>' '\n' < "$dump" | grep -q "text=\"$1\""
    local found=$?
    rm -f "$dump"
    return $found
}
# Revoking a permission or moving the SMS role kills the process, and relaunching
# into that teardown races it — hence the generous waits.
launch() { adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8; }
check() { # check DESCRIPTION EXPECTED_TEXT
    if ui_has "$2"; then echo "   PASS $1"; else echo "   FAIL $1 (expected '$2' on screen)"; fail=1; fi
}

command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
adb devices | grep -qE "device$" || { echo "No device." >&2; exit 1; }

echo "== restoring a fully granted starting state"
adb shell cmd role add-role-holder android.app.role.SMS "$PKG" >/dev/null 2>&1
for p in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE READ_PHONE_NUMBERS POST_NOTIFICATIONS; do
    adb shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
done
adb shell am force-stop "$PKG"; launch
check "starts on the inbox when everything is granted" "Search conversations"

echo "== revoking contacts while the app is in the background"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell pm revoke "$PKG" android.permission.READ_CONTACTS >/dev/null 2>&1
sleep 4
launch
check "returns to onboarding after a revoked permission" "Welcome to NoSpam"
adb shell pm grant "$PKG" android.permission.READ_CONTACTS >/dev/null 2>&1

echo "== revoking the phone permissions"
adb shell am force-stop "$PKG"
adb shell pm revoke "$PKG" android.permission.READ_PHONE_STATE >/dev/null 2>&1
adb shell pm revoke "$PKG" android.permission.READ_PHONE_NUMBERS >/dev/null 2>&1
launch
check "phone permissions are required too" "Welcome to NoSpam"
adb shell pm grant "$PKG" android.permission.READ_PHONE_STATE >/dev/null 2>&1
adb shell pm grant "$PKG" android.permission.READ_PHONE_NUMBERS >/dev/null 2>&1

echo "== notifications denied is NOT a gate"
adb shell am force-stop "$PKG"
adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
launch
check "notifications denied still opens the inbox" "Search conversations"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1

echo "== handing the default-SMS role to another app while ours runs"
adb shell am force-stop "$PKG"; launch
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell cmd role add-role-holder android.app.role.SMS com.google.android.apps.messaging >/dev/null 2>&1
sleep 5
launch
check "losing the SMS role returns to onboarding" "Set as default SMS app"

echo "== restoring"
adb shell cmd role add-role-holder android.app.role.SMS "$PKG" >/dev/null 2>&1
sleep 2
for p in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE READ_PHONE_NUMBERS POST_NOTIFICATIONS; do
    adb shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
done
adb shell am force-stop "$PKG"

[ "$fail" -eq 0 ] && echo "== all permission-gate checks passed" || echo "== FAILURES above"
exit "$fail"
