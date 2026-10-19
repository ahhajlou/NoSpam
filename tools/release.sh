#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/release.sh — make the commit that releases a version.
#
# Turns CHANGELOG.md's [Unreleased] section into "## [<version>] - <today>",
# leaves an empty [Unreleased] above it, sets appVersionName to <version> and
# raises versionCode by one in app/build.gradle.kts, then commits the two files.
# Pushing the commit and tagging it is left to you: the tag starts the release
# workflow, which publishes that section as the release's description.
#
# Refuses, changing nothing, when the working tree has changes, when
# [Unreleased] is empty, when <version> is not above the current version or
# already has a section, or when either file is not in the expected shape.
#
# Usage: tools/release.sh <version>      e.g. tools/release.sh 0.9.0
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:?usage: $0 <version>   e.g. $0 0.9.0}"
gradle=app/build.gradle.kts
changelog=CHANGELOG.md

fail() { echo "error: $*" >&2; exit 1; }

# True when $2 is a later version than $1. A pre-release comes before its
# release (1.0.0-beta1 < 1.0.0), which sort -V alone gets backwards.
is_later() {
    local a="$1" b="$2" ac="${1%%-*}" bc="${2%%-*}"
    [ "$a" = "$b" ] && return 1
    if [ "$ac" != "$bc" ]; then
        [ "$(printf '%s\n%s\n' "$ac" "$bc" | sort -V | tail -1)" = "$bc" ]
        return
    fi
    [ "$b" = "$bc" ] && return 0   # b is the release of a's pre-release
    [ "$a" = "$ac" ] && return 1   # b is a pre-release of the released a
    [ "$(printf '%s\n%s\n' "$a" "$b" | sort -V | tail -1)" = "$b" ]
}

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] ||
    fail "'$version' is not a version like 0.9.0 or 1.0.0-beta1"
[ -z "$(git status --porcelain)" ] ||
    fail "the working tree has changes; commit or stash them first, so the release commit holds only the release"

current=$(sed -n 's/^val appVersionName = "\(.*\)"$/\1/p' "$gradle")
code=$(sed -n 's/^ *versionCode = \([0-9][0-9]*\)$/\1/p' "$gradle")
[ -n "$current" ] || fail "no 'val appVersionName = \"…\"' line in $gradle"
[ -n "$code" ] || fail "no 'versionCode = <number>' line in $gradle"
[ "$(grep -c '^val appVersionName = ' "$gradle")" = 1 ] && [ "$(grep -c '^ *versionCode = ' "$gradle")" = 1 ] ||
    fail "$gradle has more than one appVersionName or versionCode line"
is_later "$current" "$version" || fail "$version is not above the current version, $current"

grep -qF "## [$version]" "$changelog" && fail "$changelog already has a section for $version"
[ "$(grep -c '^## \[Unreleased\]$' "$changelog")" = 1 ] ||
    fail "$changelog needs exactly one '## [Unreleased]' heading"
bash tools/changelog-section.sh Unreleased "$changelog" > /dev/null ||
    fail "nothing to release: [Unreleased] in $changelog is empty"

today=$(date +%F)
next=$((code + 1))
sed -i "s/^## \[Unreleased\]$/## [Unreleased]\n\n## [$version] - $today/" "$changelog"
sed -i "s/^val appVersionName = \".*\"$/val appVersionName = \"$version\"/" "$gradle"
sed -i "s/^\( *versionCode = \)[0-9][0-9]*$/\1$next/" "$gradle"
if [ "$(sed -n 's/^val appVersionName = "\(.*\)"$/\1/p' "$gradle")" != "$version" ] ||
   [ "$(sed -n 's/^ *versionCode = \([0-9][0-9]*\)$/\1/p' "$gradle")" != "$next" ] ||
   ! grep -qF "## [$version] - $today" "$changelog"; then
    git checkout -- "$changelog" "$gradle"
    fail "the edits did not come out as expected; both files are back as they were"
fi

echo "== NoSpam $version (versionCode $next): the release's description"
bash tools/changelog-section.sh "$version" "$changelog"
echo
git --no-pager diff --stat
git add "$changelog" "$gradle"
git commit -q -m "chore: release $version"
echo
branch=$(git branch --show-current)
echo "Committed \"chore: release $version\" on ${branch:-a detached HEAD}. Next:"
echo "  git push"
echo "  git tag v$version && git push origin v$version"
echo "(If main only takes pull requests, merge this first and tag the merge commit.)"
