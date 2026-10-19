#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/changelog-section.sh — print one version's notes from CHANGELOG.md.
#
# The release workflow makes this the GitHub Release's description, and runs it
# before building too, so a tag whose version has no notes fails in seconds
# instead of after the build. Prints the lines under "## [<version>]" up to the
# next "## " heading, without the heading, Keep a Changelog's link definitions
# ("[0.8.0]: https://...") or surrounding blank lines. Fails when the section is
# missing or empty.
#
# Usage: tools/changelog-section.sh <version> [changelog=CHANGELOG.md]
#        tools/changelog-section.sh 0.8.0
#        tools/changelog-section.sh Unreleased
set -uo pipefail

version="${1:?usage: $0 <version> [changelog=CHANGELOG.md]}"
file="${2:-CHANGELOG.md}"
[ -f "$file" ] || { echo "error: $file not found" >&2; exit 1; }

notes=$(awk -v heading="## [$version]" '
    /^## / {
        if (found) exit
        found = (index($0, heading) == 1)
        next
    }
    found && !/^\[[^]]+\]: / { lines[++n] = $0 }
    END {
        first = 1
        while (first <= n && lines[first] ~ /^[[:space:]]*$/) first++
        last = n
        while (last >= first && lines[last] ~ /^[[:space:]]*$/) last--
        for (i = first; i <= last; i++) print lines[i]
        if (!found) exit 2
        if (first > last) exit 3
    }
' "$file")
case $? in
    0) printf '%s\n' "$notes" ;;
    2) echo "error: $file has no \"## [$version]\" section. Rename [Unreleased] to it, with the date, before tagging." >&2
       exit 1 ;;
    3) echo "error: the \"## [$version]\" section of $file is empty." >&2
       exit 1 ;;
    *) exit 1 ;;
esac
