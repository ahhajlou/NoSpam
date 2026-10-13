#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# Installs a sound file as one of the in-app message sounds. See docs/SOUNDS.md.
#
#   tools/message-sound.sh sent     path/to/sound.wav
#   tools/message-sound.sh received path/to/sound.ogg [--peak -15]
#
# Writes core/notifications/src/main/res/raw/message_<sent|received>.ogg:
# silence trimmed from both ends, mono, 48 kHz, Ogg Vorbis, its loudest point
# at --peak dBFS (default -15, about where Google Messages' own sounds sit).
# Refuses a result longer than 1 second: these sounds play on every message.
# Any format ffmpeg reads is accepted. Needs ffmpeg with libvorbis.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "FAIL: $*" >&2; exit 1; }

[ $# -ge 2 ] || { sed -n '5,14p' "$0"; exit 1; }
KIND="$1" INPUT="$2" PEAK="-15"
shift 2
while [ $# -gt 0 ]; do
    case "$1" in
        --peak) [ $# -ge 2 ] || die "--peak needs a value"; PEAK="$2"; shift 2 ;;
        *) die "unknown argument: $1" ;;
    esac
done
case "$KIND" in
    sent|received) ;;
    *) die "first argument must be 'sent' or 'received', not '$KIND'" ;;
esac
[ -f "$INPUT" ] || die "no such file: $INPUT"
command -v ffmpeg >/dev/null || die "ffmpeg is not installed"
ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libvorbis || die "this ffmpeg has no libvorbis encoder"

OUT="core/notifications/src/main/res/raw/message_$KIND.ogg"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Trim silence at the start, then (reversed) at the end; downmix to mono.
TRIM="silenceremove=start_periods=1:start_threshold=-60dB,areverse,silenceremove=start_periods=1:start_threshold=-60dB,areverse"
ffmpeg -v error -y -i "$INPUT" -af "$TRIM" -ac 1 -ar 48000 "$TMP/trimmed.wav"

MAX=$(ffmpeg -hide_banner -i "$TMP/trimmed.wav" -af volumedetect -f null - 2>&1 \
    | sed -n 's/.*max_volume: \([-0-9.]*\) dB.*/\1/p')
[ -n "$MAX" ] || die "could not measure the sound's level (is it silent?)"
GAIN=$(awk -v p="$PEAK" -v m="$MAX" 'BEGIN { printf "%.2f", p - m }')

ffmpeg -v error -y -i "$TMP/trimmed.wav" -af "volume=${GAIN}dB" -c:a libvorbis -q:a 5 "$OUT"

DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT")
awk -v d="$DUR" 'BEGIN { exit !(d > 1.0) }' && { rm -f "$OUT"; die "result is ${DUR}s long; keep it under 1 s"; }

echo "Wrote $OUT: ${DUR}s, peak ${PEAK} dBFS (gain ${GAIN} dB), $(stat -c %s "$OUT") bytes"
echo "Now record the sound's source and license in REUSE.toml (docs/SOUNDS.md, step 3)."
