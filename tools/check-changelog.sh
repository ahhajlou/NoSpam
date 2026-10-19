#!/usr/bin/env bash

# SPDX-License-Identifier: GPL-3.0-or-later

# tools/check-changelog.sh — fail a change to the app that CHANGELOG.md does
# not mention.
#
# CHANGELOG.md is the release notes (CLAUDE.md §10), so a change the people who
# install the app would notice adds a line under [Unreleased] in the same pull
# request (CLAUDE.md §3, rule 9). The changelog workflow runs this on every
# pull request; it also runs locally against any two commits.
#
# Passes when nothing below changed, when CHANGELOG.md changed, or when the
# pull request's description has a line "No changelog: <reason>" (an internal
# change). App code here is what ships in the APK: main source sets and their
# resources and assets (the spam model included), the Rust core's submodule and
# app/build.gradle.kts. Tests, docs, tools and CI are not.
#
# Usage: tools/check-changelog.sh <base> <head> [pr-description-file]
set -euo pipefail

base="${1:?usage: $0 <base> <head> [pr-description-file]}"
head="${2:?usage: $0 <base> <head> [pr-description-file]}"
body="${3:-}"

changed=$(git diff --name-only "$base...$head")
app=$(printf '%s\n' "$changed" | grep -E \
    -e '^app/src/main/' \
    -e '^feature/[^/]+/src/main/' \
    -e '^core/[^/]+/src/main/' \
    -e '^third_party/persian-sms-spam$' \
    -e '^app/build\.gradle\.kts$' || true)

if [ -z "$app" ]; then
    echo "No app code changed: no changelog entry needed."
    exit 0
fi
if printf '%s\n' "$changed" | grep -qx 'CHANGELOG.md'; then
    echo "CHANGELOG.md changed."
    exit 0
fi
if [ -n "$body" ] && [ -f "$body" ]; then
    reason=$(grep -m1 -iE '^[[:space:]]*No changelog:[[:space:]]*[^[:space:]]' "$body" || true)
    if [ -n "$reason" ]; then
        echo "Exempt: $(printf '%s' "$reason" | sed 's/^[[:space:]]*//')"
        exit 0
    fi
fi

echo "error: app code changed, but CHANGELOG.md did not:" >&2
printf '%s\n' "$app" | sed 's/^/  /' | head -20 >&2
echo "Add a line under [Unreleased] in CHANGELOG.md for what users will notice, or," >&2
echo "for a change they will not, a line \"No changelog: <reason>\" in the pull request's description." >&2
exit 1
