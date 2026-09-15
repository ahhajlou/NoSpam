#!/usr/bin/env bash
# tools/seed.sh — idempotent seed/teardown for NoSpam manual QA (Maestro + persistence checks).
#
# Seeds a fixed set of SMS threads directly into the Telephony provider (content://sms)
# and the app's own nospam.db (blocklist / sender_state / message_verdict / starred /
# pinned / muted / archived_threads), so Maestro flows have deterministic, repeatable
# fixtures to assert against instead of depending on the on-device ML classifier's
# live verdict (which can legitimately drift as the model/preprocessor changes).
#
# Every row this script creates uses an address starting with the NSTEST_ prefix (or
# the literal test contact name "NoSpam QA Contact"), so teardown can remove exactly
# what seed added and nothing else — safe to run against a device with real user data
# on it, though you would not normally do that.
#
# Usage:
#   tools/seed.sh seed        # create all fixtures (idempotent: re-running replaces them)
#   tools/seed.sh teardown    # remove everything this script created
#   tools/seed.sh status      # print what's currently seeded
#
# Requires: adb on PATH (or ANDROID_SDK_ROOT/platform-tools), a running emulator/device,
# NoSpam already installed, default-SMS role held, and adb root (the script will try
# `adb root` if not already root — required to read/write nospam.db directly and to
# query Telephony as a shell that isn't the app itself).
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
PKG="com.nospam.nospam"
DB="/data/data/${PKG}/databases/nospam.db"
PREFIX="NSTEST_"
CONTACT_NAME="NoSpam QA Contact"
CONTACT_PHONE="+15551110001"

if ! command -v adb >/dev/null 2>&1; then
    for cand in "$HOME/Android/Sdk/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" "${ANDROID_HOME:-}/platform-tools/adb"; do
        if [ -x "$cand" ]; then
            export PATH="$(dirname "$cand"):$PATH"
            break
        fi
    done
fi
command -v adb >/dev/null 2>&1 || { echo "adb not found on PATH and not found under common SDK locations" >&2; exit 1; }

adbs() { if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi; }

# ---- preconditions -----------------------------------------------------------------
ensure_root() {
    if [ "$(adbs shell whoami 2>/dev/null | tr -d '\r')" != "root" ]; then
        adbs root >/dev/null 2>&1 || true
        sleep 1
        adbs wait-for-device
    fi
}

ensure_role_and_perms() {
    local holder
    holder="$(adbs shell cmd role get-role-holders android.app.role.SMS 2>/dev/null | tr -d '\r')"
    if [ "$holder" != "$PKG" ]; then
        echo "ERROR: $PKG does not hold android.app.role.SMS (got: '$holder')." >&2
        echo "This script does not grant roles/permissions — set it up once via onboarding, then re-run." >&2
        exit 1
    fi
}

# ---- low-level helpers ---------------------------------------------------------------

# sql QUERY  — run one SQL statement against nospam.db as the app UID (run-as; the
# app is debuggable so run-as works even without adb root, but we already have root
# from ensure_root, which also lets sqlite3 open the file directly).
sql() {
    adbs shell "sqlite3 '$DB' \"$1\""
}

# sms_insert ADDRESS BODY DATE_MILLIS TYPE(1=inbox,2=sent) READ(0/1)
# Direct content-provider insert — bypasses the app's ingress pipeline (no
# classification happens). Used for bulk/backdated history where we want full
# control over `date` and insertion order instead of live delivery timing.
sms_insert() {
    local address="$1" body="$2" date="$3" type="${4:-1}" read="${5:-1}"
    # The on-device `content` tool splits --bind COLUMN:TYPE:VALUE naively on ':'
    # with no escaping, so any colon inside VALUE (e.g. "http://..." or "Note:")
    # makes it report "Binding not well formed" and silently drop the whole
    # insert. None of the fixtures need a literal colon, so strip it rather than
    # fight the parser.
    body="${body//:/-}"
    adbs shell "content insert --uri content://sms --bind address:s:'${address}' --bind body:s:'${body}' --bind date:l:${date} --bind type:i:${type} --bind read:i:${read} --bind seen:i:${read}" >/dev/null
}

sms_delete_address() {
    adbs shell "content delete --uri content://sms --where \"address='${1}'\"" >/dev/null 2>&1 || true
}

thread_id_for() {
    # `content query --where` is unreliable through adb's own shell-argument
    # quoting (intermittently "no such column: <value>" SQLite errors — a
    # nested-quoting artifact, not a real schema problem). Querying the whole
    # table and filtering client-side avoids it entirely and has proven
    # reliable in practice.
    #
    # One retry after a short settle: a `content insert` immediately followed
    # by `content query` occasionally raced and returned this address's *old*
    # thread_id (or none) on a re-seed, one observed cause of a fixture
    # ending up unflagged after `tools/seed.sh core` — cheap insurance against
    # it recurring.
    local out
    out="$(adbs shell "content query --uri content://sms" 2>/dev/null | grep "address=${1}," | head -1)"
    if [ -z "$out" ]; then
        sleep 1
        out="$(adbs shell "content query --uri content://sms" 2>/dev/null | grep "address=${1}," | head -1)"
    fi
    echo "$out" | sed -n 's/.*thread_id=\([0-9]*\).*/\1/p'
}

contact_insert() {
    # Minimal raw_contact + data row so ContactLookup / "Known" filter can resolve it.
    local raw_id
    raw_id="$(adbs shell "content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s: --bind account_name:s:" 2>/dev/null | sed -n 's#.*/\([0-9]*\)$#\1#p')"
    if [ -z "$raw_id" ]; then return 0; fi
    adbs shell "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:${raw_id} --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:'${CONTACT_NAME}'" >/dev/null 2>&1 || true
    adbs shell "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:${raw_id} --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:'${CONTACT_PHONE}' --bind data2:i:2" >/dev/null 2>&1 || true
}

contact_delete() {
    adbs shell "content delete --uri content://com.android.contacts/raw_contacts --where \"display_name='${CONTACT_NAME}'\"" >/dev/null 2>&1 || true
    # Belt-and-braces: some OEM contact providers need deletion by data row too.
    adbs shell "content delete --uri content://com.android.contacts/data --where \"data1='${CONTACT_PHONE}'\"" >/dev/null 2>&1 || true
}

now_ms() { date +%s%3N; }
days_ago_ms() { echo $(( $(now_ms) - $1 * 86400000 )); }

# db_upsert_sender_state ADDRESS STATE SPAM_COUNT HAM_COUNT OVERRIDE(0/1)
db_upsert_sender_state() {
    sql "INSERT OR REPLACE INTO sender_state (normalizedAddress, state, spamCount, hamCount, isUserOverride, updatedAt) VALUES ('${1}', '${2}', ${3}, ${4}, ${5}, $(now_ms))"
}

db_insert_verdict() {
    # messageId is an autoincrement rowid in the real Sms table; we don't know it
    # here without round-tripping the insert result, so message_verdict rows are
    # seeded only where a flow needs a per-message "Suspected spam" marker, keyed
    # off the real _id looked up right after the sms_insert call (see seed_mixed).
    sql "INSERT OR REPLACE INTO message_verdict (messageId, threadId, normalizedAddress, isSpam, score, createdAt, userLabel) VALUES (${1}, ${2}, '${3}', ${4}, ${5}, ${6}, NULL)"
}

db_block() {
    sql "INSERT OR IGNORE INTO blocklist (address, reason, createdAt) VALUES ('${1}', 'seeded', $(now_ms))"
    db_upsert_sender_state "${1}" BLOCKED 0 0 1
}

db_flag_thread() {
    # table in {starred_threads, pinned_threads, muted_threads, archived_threads}
    sql "INSERT OR IGNORE INTO ${1} (threadId) VALUES (${2})"
}

last_sms_id_for() {
    # See thread_id_for's comment on why --where is avoided here. The pattern
    # is anchored to the start of the line ("Row: N _id=...") rather than a
    # bare ".*_id=" — every row also contains "thread_id=NNN" later on the
    # same line, and a greedy ".*_id=" matches *that* occurrence instead of
    # the real message id (this bit seed_mixed's message_verdict fixture).
    adbs shell "content query --uri content://sms" 2>/dev/null \
        | grep "address=${1}," | sed -n 's/^Row: [0-9]* _id=\([0-9]*\).*/\1/p' | sort -n | tail -1
}

# Nth (1-indexed) sms _id for an address, sorted ascending by _id — used where
# a fixture needs the id of a specific message among several from the same
# sender (e.g. seed_mixed's spam message is the 2nd of 3).
nth_sms_id_for() {
    adbs shell "content query --uri content://sms" 2>/dev/null \
        | grep "address=${1}," | sed -n 's/^Row: [0-9]* _id=\([0-9]*\).*/\1/p' | sort -n | sed -n "${2}p"
}

# ---- fixtures --------------------------------------------------------------------

seed_contact_clean() {
    local addr="$CONTACT_PHONE"
    contact_insert
    sms_insert "$addr" "Hey, are we still on for lunch tomorrow?" "$(days_ago_ms 2)" 1 1
    sms_insert "$addr" "Yes! Noon works for me." "$(days_ago_ms 2)" 2 1
    sms_insert "$addr" "See you then." "$(days_ago_ms 1)" 1 0
    db_upsert_sender_state "$addr" CLEAN 0 3 0
}

seed_unknown_spam() {
    local addr="${PREFIX}SPAM1"
    sms_insert "$addr" "WINNER!! You have been selected for a free cruise. Click http://bit.ly/claim-now to claim your prize before it expires!" "$(days_ago_ms 1)" 1 0
    db_upsert_sender_state "$addr" SPAM 1 0 0
    local mid; mid="$(last_sms_id_for "$addr")"
    local tid; tid="$(thread_id_for "$addr")"
    [ -n "$mid" ] && [ -n "$tid" ] && db_insert_verdict "$mid" "$tid" "$addr" 1 0.97 "$(days_ago_ms 1)"
}

# NSTEST_SPAM1 above is owned by .maestro/flows/spam_notspam_and_bulk.yaml,
# which swipes it to not-spam. NSTEST_SPAM2 below is the same shape but is
# never mutated by any flow — .maestro/flows/inbox_filters_and_search.yaml's
# "spam never appears in the inbox" check uses this one so it stays true
# regardless of what order the suite runs flows in. NSTEST_SPAM3 is the one
# .maestro/flows/persistence.yaml mutates for its "not-spam override survives
# a restart" check, kept separate from both for the same reason.
seed_spam_static() {
    local addr="${PREFIX}SPAM2"
    sms_insert "$addr" "Congratulations, you have won a 1000 dollar gift card! Reply YES to claim." "$(days_ago_ms 2)" 1 0
    db_upsert_sender_state "$addr" SPAM 1 0 0
    local mid; mid="$(last_sms_id_for "$addr")"
    local tid; tid="$(thread_id_for "$addr")"
    [ -n "$mid" ] && [ -n "$tid" ] && db_insert_verdict "$mid" "$tid" "$addr" 1 0.95 "$(days_ago_ms 2)"
}

seed_spam_for_persistence() {
    local addr="${PREFIX}SPAM3"
    sms_insert "$addr" "Act now- limited time offer just for you, click to redeem" "$(days_ago_ms 2)" 1 0
    db_upsert_sender_state "$addr" SPAM 1 0 0
    local mid; mid="$(last_sms_id_for "$addr")"
    local tid; tid="$(thread_id_for "$addr")"
    [ -n "$mid" ] && [ -n "$tid" ] && db_insert_verdict "$mid" "$tid" "$addr" 1 0.95 "$(days_ago_ms 2)"
}

seed_mixed() {
    local addr="${PREFIX}MIXED1"
    sms_insert "$addr" "Your one-time password is 482913. Do not share it." "$(days_ago_ms 5)" 1 1
    sms_insert "$addr" "Big summer sale! 70% off everything, click here now: http://bit.ly/sale" "$(days_ago_ms 3)" 1 1
    sms_insert "$addr" "Your one-time password is 118820. Do not share it." "$(days_ago_ms 1)" 1 0
    local tid; tid="$(thread_id_for "$addr")"
    local spam_mid; spam_mid="$(nth_sms_id_for "$addr" 2)"   # the "Big summer sale" message, 2nd inserted
    [ -n "$spam_mid" ] && [ -n "$tid" ] && db_insert_verdict "$spam_mid" "$tid" "$addr" 1 0.9 "$(days_ago_ms 3)"
    db_upsert_sender_state "$addr" MIXED 1 2 0
}

seed_archived() {
    local addr="${PREFIX}ARCHIVE1"
    sms_insert "$addr" "Your package has been delivered." "$(days_ago_ms 10)" 1 1
    db_upsert_sender_state "$addr" CLEAN 0 1 0
    local tid; tid="$(thread_id_for "$addr")"
    [ -n "$tid" ] && db_flag_thread archived_threads "$tid"
}

# Owned by .maestro/flows/persistence.yaml, kept separate from NSTEST_ARCHIVE1
# (owned by .maestro/flows/archived_unarchive.yaml) so the two archive/
# unarchive round-trips never race each other within one suite run.
seed_archived_for_persistence() {
    local addr="${PREFIX}ARCHIVE2"
    sms_insert "$addr" "Your prescription is ready for pickup." "$(days_ago_ms 10)" 1 1
    db_upsert_sender_state "$addr" CLEAN 0 1 0
    local tid; tid="$(thread_id_for "$addr")"
    [ -n "$tid" ] && db_flag_thread archived_threads "$tid"
}

seed_starred() {
    local addr="${PREFIX}STAR1"
    sms_insert "$addr" "Meeting moved to 3pm, room 4B." "$(days_ago_ms 1)" 1 1
    db_upsert_sender_state "$addr" CLEAN 0 1 0
    local tid; tid="$(thread_id_for "$addr")"
    [ -n "$tid" ] && db_flag_thread starred_threads "$tid"
}

seed_pinned() {
    local addr="${PREFIX}PIN1"
    sms_insert "$addr" "Reminder: rent is due Friday." "$(days_ago_ms 1)" 1 1
    db_upsert_sender_state "$addr" CLEAN 0 1 0
    local tid; tid="$(thread_id_for "$addr")"
    [ -n "$tid" ] && db_flag_thread pinned_threads "$tid"
}

seed_muted() {
    local addr="${PREFIX}MUTE1"
    sms_insert "$addr" "Weekly newsletter: 5 tips for your garden this fall." "$(days_ago_ms 1)" 1 1
    db_upsert_sender_state "$addr" CLEAN 0 1 0
    local tid; tid="$(thread_id_for "$addr")"
    [ -n "$tid" ] && db_flag_thread muted_threads "$tid"
}

seed_long_thread() {
    local addr="${PREFIX}LONG1"
    local count=220 # > TelephonyDataSource.MESSAGES_PAGE_SIZE (200) so backward pagination
                     # in ThreadScreen actually has to load a second page.
    local remote="/data/local/tmp/nstest_long_seed.sh"
    {
        echo "#!/system/bin/sh"
        for i in $(seq 1 "$count"); do
            local d=$(( $(now_ms) - (count - i) * 3600000 ))
            local type=1
            [ $((i % 7)) -eq 0 ] && type=2
            echo "content insert --uri content://sms --bind address:s:'${addr}' --bind body:s:'Long thread message number ${i}' --bind date:l:${d} --bind type:i:${type} --bind read:i:1 --bind seen:i:1 >/dev/null"
        done
    } > /tmp/nstest_long_seed.sh
    adbs push /tmp/nstest_long_seed.sh "$remote" >/dev/null
    adbs shell "sh $remote"
    adbs shell "rm -f $remote"
    rm -f /tmp/nstest_long_seed.sh
    db_upsert_sender_state "$addr" CLEAN 0 "$count" 0
}

seed_persian_thread() {
    local addr="${PREFIX}FA1"
    sms_insert "$addr" "سلام! حالت چطوره؟" "$(days_ago_ms 2)" 1 1
    sms_insert "$addr" "خوبم، ممنون. فردا جلسه رو فراموش نکن." "$(days_ago_ms 2)" 2 1
    sms_insert "$addr" "باشه، حتما یادداشت می‌کنم. کد تایید شما: ۱۲۳۴۵۶" "$(days_ago_ms 1)" 1 0
    db_upsert_sender_state "$addr" CLEAN 0 3 0
}

# Reproduction fixture for TODO.md's two open bugs:
#  - "Lists all SMS from other SMS apps before app is installed" — messages that
#    were already in the Telephony provider before NoSpam ever ran / became default.
#  - "Message orders are wrong ... on a phone with old messages" — ThreadViewModel's
#    backward-pagination cursor (`beforeId`, in feature/thread/ThreadViewModel.kt) is
#    the *minimum row `_id`* seen so far, and RealTelephonyDataSource.queryMessages
#    filters strictly on `_ID < beforeId` (core/telephony/.../RealTelephonyDataSource.kt).
#    That assumes `_id` is monotonic with `date`. A restored/imported history breaks
#    that assumption: old messages can land with a *higher* `_id` than messages
#    already newer in wall-clock time, because `_id` reflects insertion order into
#    the provider, not send time. This fixture inserts messages with `_id` assigned
#    in one order and `date` deliberately scrambled relative to it, to reproduce
#    exactly that mismatch on-device.
seed_history_out_of_order() {
    local addr="${PREFIX}HISTORY1"
    # Insertion order (== _id order) vs. date: deliberately NOT monotonic.
    # _id 1: "recent" date (looks newest) inserted FIRST -> lowest _id
    sms_insert "$addr" "[seed 1/5] recent-looking message, inserted first (lowest _id)" "$(days_ago_ms 1)" 1 1
    # _id 2: old date (400 days = predates any plausible install), inserted SECOND
    sms_insert "$addr" "[seed 2/5] very old message, predates install, inserted second" "$(days_ago_ms 400)" 1 1
    # _id 3: even older date, inserted third — _id keeps climbing while date drops
    sms_insert "$addr" "[seed 3/5] even older message, inserted third" "$(days_ago_ms 450)" 1 1
    # _id 4: a mid-range date, inserted fourth
    sms_insert "$addr" "[seed 4/5] mid-range date, inserted fourth" "$(days_ago_ms 200)" 1 1
    # _id 5: newest real date, inserted last (highest _id, correctly also newest date)
    sms_insert "$addr" "[seed 5/5] newest message, inserted last" "$(days_ago_ms 0)" 1 1
    db_upsert_sender_state "$addr" CLEAN 0 5 0
}

# Second, sharper repro for the same two TODO bugs: a thread big enough to force
# real backward pagination (> TelephonyDataSource.MESSAGES_PAGE_SIZE = 200), where
# a batch of genuinely-oldest messages is inserted *last* (so it gets the highest
# `_id` despite having the oldest `date` — exactly what a late-discovered restored
# history / a second SMS source syncing in behind the app's back looks like).
#
# Expected-if-buggy: ThreadViewModel.loadOlder() computes its `beforeId` cursor as
# `min(_id)` of what's already loaded (feature/thread/ThreadViewModel.kt), and
# RealTelephonyDataSource.queryMessages filters strictly on `_ID < beforeId`
# (core/telephony/.../RealTelephonyDataSource.kt). The first page (DATE DESC LIMIT
# 200) returns the 200 messages with the newest `date` — call them A_1..A_200 with
# _id 1..200. `beforeId` becomes 1. The 10 older-history messages seeded below get
# _id 201..210 (inserted after A), so `_ID < 1` matches none of them: they never
# load, "load older" reports no more pages, and the thread silently ends 10
# messages short of its real oldest content.
seed_pagination_bug() {
    local addr="${PREFIX}PAGEBUG1"
    local newer=200 older=10
    local remote="/data/local/tmp/nstest_pagebug_seed.sh"
    {
        echo "#!/system/bin/sh"
        # A_1..A_200: _id 1..200 here, oldest of this group first, newest (~now) last.
        for i in $(seq 1 "$newer"); do
            local d=$(( $(now_ms) - (newer - i) * 3600000 ))
            echo "content insert --uri content://sms --bind address:s:'${addr}' --bind body:s:'recent batch, message ${i} of ${newer}' --bind date:l:${d} --bind type:i:1 --bind read:i:1 --bind seen:i:1 >/dev/null"
        done
        # B_1..B_10: inserted AFTER A (so _id 201..210) but dated ~2 years before
        # any of them — the "old history surfaces later" case.
        for i in $(seq 1 "$older"); do
            local d=$(( $(now_ms) - (800 + older - i) * 86400000 ))
            echo "content insert --uri content://sms --bind address:s:'${addr}' --bind body:s:'OLD HISTORY message ${i} of ${older}, should still be reachable' --bind date:l:${d} --bind type:i:1 --bind read:i:1 --bind seen:i:1 >/dev/null"
        done
    } > /tmp/nstest_pagebug_seed.sh
    adbs push /tmp/nstest_pagebug_seed.sh "$remote" >/dev/null
    adbs shell "sh $remote"
    adbs shell "rm -f $remote"
    rm -f /tmp/nstest_pagebug_seed.sh
    db_upsert_sender_state "$addr" CLEAN 0 $((newer + older)) 0
}

# Fixture for the block/unblock UI flow (_unblock_part1_block.yaml /
# _unblock_part2_deliver_and_unblock.yaml). Those two flows are the
# Maestro-drivable halves of tools/persistence_check.sh's live
# `adb emu sms send` regression check and carry the `manual-only` tag so
# `maestro test .maestro/` (which inherits config.yaml's `excludeTags:
# [manual-only]`) skips them; tools/persistence_check.sh invokes them
# directly with `--include-tags=manual-only`, which does take precedence.
# Seeded here too so running either flow file directly, outside
# persistence_check.sh, has something real to act on.
seed_unblock_fixture() {
    local addr="${PREFIX}UNBLOCK1"
    sms_insert "$addr" "Quick note before the block/unblock check." "$(days_ago_ms 1)" 1 1
    db_upsert_sender_state "$addr" CLEAN 0 1 0
}

# Disposable spam fixture for the bulk "Block all" / "Delete all" flow — kept
# separate from NSTEST_SPAM1 so that flow's destructive actions never touch a
# fixture other flows depend on. Re-run `tools/seed.sh bulkspam` to recreate it
# after a bulk-action flow consumes it.
seed_bulk_spam() {
    local addr="${PREFIX}BULK1"
    sms_insert "$addr" "FINAL NOTICE - your account will be suspended, verify now" "$(days_ago_ms 0)" 1 0
    db_upsert_sender_state "$addr" SPAM 1 0 0
}

# Re-apply just the nospam.db flags (archived/starred/pinned/muted/sender_state)
# without touching Telephony rows — cheap to re-run after a Maestro flow
# mutates fixture state, unlike a full teardown+seed (which re-inserts the
# 220+210 long-thread messages and takes minutes).
fixup_flags() {
    db_upsert_sender_state "${PREFIX}SPAM1" SPAM 1 0 0
    db_upsert_sender_state "${PREFIX}SPAM2" SPAM 1 0 0
    db_upsert_sender_state "${PREFIX}SPAM3" SPAM 1 0 0
    local tid
    tid="$(thread_id_for "${PREFIX}ARCHIVE1")"; [ -n "$tid" ] && db_flag_thread archived_threads "$tid"
    tid="$(thread_id_for "${PREFIX}ARCHIVE2")"; [ -n "$tid" ] && db_flag_thread archived_threads "$tid"
    tid="$(thread_id_for "${PREFIX}STAR1")"; [ -n "$tid" ] && db_flag_thread starred_threads "$tid"
    tid="$(thread_id_for "${PREFIX}PIN1")"; [ -n "$tid" ] && db_flag_thread pinned_threads "$tid"
    tid="$(thread_id_for "${PREFIX}MUTE1")"; [ -n "$tid" ] && db_flag_thread muted_threads "$tid"
    status_all
}

seed_all() {
    ensure_root
    ensure_role_and_perms
    echo "Seeding fixtures (prefix ${PREFIX}, contact ${CONTACT_PHONE})..."
    teardown_all >/dev/null 2>&1 || true
    seed_contact_clean
    seed_unknown_spam
    seed_spam_static
    seed_spam_for_persistence
    seed_mixed
    seed_archived
    seed_archived_for_persistence
    seed_starred
    seed_pinned
    seed_muted
    seed_long_thread
    seed_persian_thread
    seed_history_out_of_order
    seed_pagination_bug
    seed_bulk_spam
    seed_unblock_fixture
    echo "Done. Force-stop and relaunch NoSpam (or run Settings > Re-check all messages) so the"
    echo "app's own caches pick up the new Telephony rows:"
    echo "  adb shell am force-stop $PKG && adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1"
}

status_all() {
    ensure_root
    echo "-- Telephony rows (address, count) --"
    adbs shell "content query --uri content://sms" 2>/dev/null | grep -oE "address=[^,]*" | sort | uniq -c | grep -E -- "$PREFIX|$CONTACT_PHONE" || echo "(none found)"
    echo "-- nospam.db sender_state --"
    sql "SELECT normalizedAddress, state, spamCount, hamCount, isUserOverride FROM sender_state WHERE normalizedAddress LIKE '${PREFIX}%' OR normalizedAddress LIKE '%${CONTACT_PHONE#+}%'" || true
    echo "-- nospam.db blocklist --"
    sql "SELECT address FROM blocklist WHERE address LIKE '${PREFIX}%'" || true
}

# Addresses owned by each entry point. Kept next to the reset helper so adding a
# fixture means touching one list, not three.
CORE_ADDRS=("${PREFIX}SPAM1" "${PREFIX}SPAM2" "${PREFIX}SPAM3" "${PREFIX}MIXED1" \
            "${PREFIX}ARCHIVE1" "${PREFIX}ARCHIVE2" "${PREFIX}STAR1" \
            "${PREFIX}PIN1" "${PREFIX}MUTE1" "${PREFIX}FA1" \
            "${PREFIX}BULK1" "${PREFIX}UNBLOCK1")
HEAVY_ADDRS=("${PREFIX}LONG1" "${PREFIX}PAGEBUG1" "${PREFIX}HISTORY1")

# Remove every trace of the named fixture addresses: provider rows plus all
# app-owned rows keyed on them.
#
# This is what makes seeding idempotent. `content insert` has no upsert, so
# seeding on top of an existing fixture used to append a second copy of every
# message — a `core` re-seed doubled NSTEST_MIXED1 from 3 messages to 6, and
# flows asserting on counts or on a single visible row then failed for reasons
# that looked like flakes. Every seed entry point resets its own addresses
# first, so running it twice leaves the same state as running it once.
reset_addresses() {
    local addr tid
    for addr in "$@"; do
        tid="$(thread_id_for "$addr" || true)"
        sms_delete_address "$addr"
        sql "DELETE FROM sender_state WHERE normalizedAddress='${addr}'" || true
        sql "DELETE FROM message_verdict WHERE normalizedAddress='${addr}'" || true
        sql "DELETE FROM blocklist WHERE address='${addr}'" || true
        if [ -n "${tid:-}" ]; then
            sql "DELETE FROM starred_threads WHERE threadId=${tid}" || true
            sql "DELETE FROM pinned_threads WHERE threadId=${tid}" || true
            sql "DELETE FROM muted_threads WHERE threadId=${tid}" || true
            sql "DELETE FROM archived_threads WHERE threadId=${tid}" || true
        fi
    done
}

teardown_all() {
    ensure_root
    echo "Tearing down fixtures..."
    reset_addresses "${CORE_ADDRS[@]}" "${HEAVY_ADDRS[@]}" "$CONTACT_PHONE"
    contact_delete
    echo "Done."
}

case "${1:-}" in
    seed) seed_all ;;
    teardown) teardown_all ;;
    status) status_all ;;
    pagebug) ensure_root; ensure_role_and_perms; reset_addresses "${PREFIX}PAGEBUG1"; seed_pagination_bug ;; # standalone re-seed of just the pagination-bug fixture
    bulkspam) ensure_root; ensure_role_and_perms; reset_addresses "${PREFIX}BULK1"; seed_bulk_spam ;;
    fixup) ensure_root; ensure_role_and_perms; fixup_flags ;;
    core)
        # Everything except the two heavy bulk loops (seed_long_thread's 220
        # messages, seed_pagination_bug's 210) — fast re-seed of the fixtures
        # every non-pagination flow needs, useful after a bulk "Delete all"
        # run wipes fixtures out (see the CAUTION comment in
        # .maestro/flows/settings_dialogs_and_switches.yaml).
        ensure_root; ensure_role_and_perms
        # Idempotency: clear what this entry point is about to re-create. Does
        # NOT touch LONG1/PAGEBUG1/HISTORY1, which `core` deliberately skips —
        # resetting those would delete heavy fixtures it never rebuilds.
        reset_addresses "${CORE_ADDRS[@]}" "$CONTACT_PHONE"
        contact_delete
        seed_contact_clean
        seed_unknown_spam
        seed_spam_static
        seed_spam_for_persistence
        seed_mixed
        seed_archived
        seed_archived_for_persistence
        seed_starred
        seed_pinned
        seed_muted
        seed_persian_thread
        seed_bulk_spam
        seed_unblock_fixture
        status_all
        ;;
    newfixtures)
        # Cheap top-up for fixtures added after the initial full seed, without
        # re-running the 220+210-message long-thread loops.
        ensure_root; ensure_role_and_perms
        seed_spam_static
        seed_spam_for_persistence
        seed_archived_for_persistence
        status_all
        ;;
    *)
        echo "Usage: $0 {seed|teardown|status}" >&2
        exit 1
        ;;
esac
