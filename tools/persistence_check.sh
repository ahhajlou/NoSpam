#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/persistence_check.sh — the block/unblock persistence-across-process-death
# regression check for commit 7256993 ("fix(data): unblocking a sender returns
# the conversation to the inbox").
#
# The bug: BlocklistRepository.unblock() deleted the `blocklist` row but left
# `sender_state.state = BLOCKED`, so ConversationsRepository (which treats
# `sender_state.state == BLOCKED` as equivalent to being on the blocklist —
# core/data/ConversationsRepository.kt) never returned the thread to the inbox
# even after unblocking.
#
# This drives the REAL app code path (long-press > Block, a live inbound SMS
# while blocked, long-press > Unblock) through Maestro, then force-stops and
# relaunches and asserts on both the UI and the on-disk DB state. It is a
# separate script rather than a single Maestro flow because the step in the
# middle — a live inbound message from the blocked sender — needs
# `adb emu sms send`, and Maestro cannot shell out to the emulator console.
#
# Usage: tools/persistence_check.sh
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
DB="/data/data/${PKG}/databases/nospam.db"
ADDR="NSTEST_UNBLOCK1"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAESTRO_DIR="$ROOT_DIR/.maestro"

adbs() { if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi; }
sql() { adbs shell "sqlite3 '$DB' \"$1\""; }
maestro_flow() { maestro test ${SERIAL:+--udid "$SERIAL"} --include-tags=manual-only "$MAESTRO_DIR/flows/$1"; }

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; exit 1; }

echo "== block/unblock persistence check (commit 7256993 regression) =="
adbs root >/dev/null 2>&1 || true; sleep 1; adbs wait-for-device

echo "-- clean slate for $ADDR --"
adbs shell "content delete --uri content://sms --where \"address='${ADDR}'\"" >/dev/null 2>&1 || true
sql "DELETE FROM blocklist WHERE address='${ADDR}'" >/dev/null 2>&1 || true
sql "DELETE FROM sender_state WHERE normalizedAddress='${ADDR}'" >/dev/null 2>&1 || true
sql "DELETE FROM message_verdict WHERE normalizedAddress='${ADDR}'" >/dev/null 2>&1 || true

echo "-- step 1: live inbound message creates the thread --"
adbs emu sms send "$ADDR" "first message before blocking" >/dev/null
sleep 3

echo "-- step 2: block via the real long-press > Block UI action --"
maestro_flow "_unblock_part1_block.yaml"

echo "-- step 3: a second live inbound message while blocked --"
adbs emu sms send "$ADDR" "second message while blocked, should be absorbed" >/dev/null
sleep 3

echo "-- step 4: unblock via the real long-press > Unblock UI action --"
maestro_flow "_unblock_part2_deliver_and_unblock.yaml"

echo "-- step 5: force-stop + relaunch --"
adbs shell am force-stop "$PKG"
sleep 1
adbs shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3

echo "-- step 6: assert on-disk state after relaunch --"
blocklist_row="$(sql "SELECT address FROM blocklist WHERE address='${ADDR}'" || true)"
state_row="$(sql "SELECT state FROM sender_state WHERE normalizedAddress='${ADDR}'" || true)"

[ -z "$blocklist_row" ] && pass "blocklist row absent after unblock + restart" \
    || fail "blocklist row for $ADDR still present after unblock + restart"

[ "$state_row" != "BLOCKED" ] && pass "sender_state is '$state_row' (not BLOCKED) after unblock + restart" \
    || fail "sender_state for $ADDR is still BLOCKED after unblock + restart — this is exactly the 7256993 regression"

echo "-- step 7: assert on the UI too: present in Inbox, absent from Spam & blocked --"
cat > /tmp/nstest_unblock_ui_assert.yaml <<EOF
appId: $PKG
tags: [manual-only]
---
- launchApp:
    clearState: false
- assertVisible: "$ADDR"
- tapOn: "Menu"
- tapOn: "Spam & blocked"
- assertNotVisible: "$ADDR"
EOF
maestro test ${SERIAL:+--udid "$SERIAL"} --include-tags=manual-only /tmp/nstest_unblock_ui_assert.yaml
rm -f /tmp/nstest_unblock_ui_assert.yaml
pass "$ADDR visible in Inbox and absent from Spam & blocked after unblock + restart"

echo "-- cleanup --"
adbs shell "content delete --uri content://sms --where \"address='${ADDR}'\"" >/dev/null 2>&1 || true
sql "DELETE FROM sender_state WHERE normalizedAddress='${ADDR}'" >/dev/null 2>&1 || true
sql "DELETE FROM message_verdict WHERE normalizedAddress='${ADDR}'" >/dev/null 2>&1 || true
echo "All checks passed."
