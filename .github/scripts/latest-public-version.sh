#!/usr/bin/env bash
# Resolve versions exclusively from this app repository; website tags are never consulted.
set -euo pipefail
slug="${1:?Usage: latest-public-version.sh app-slug}"
[[ "$slug" == "mcpadbandroidlogger" ]] || { echo "::error::Unexpected app slug" >&2; exit 1; }
repo="${GITHUB_REPOSITORY:-N2K02/McpAdbAndroidLogger}"
[[ "$repo" == "N2K02/McpAdbAndroidLogger" ]] || { echo "::error::Unexpected repository" >&2; exit 1; }
refs=$(gh api --paginate "repos/$repo/git/matching-refs/tags/v" --jq '.[].ref') || { echo "::error::Unable to read source app tags" >&2; exit 1; }
printf '%s\n' "$refs" | sed -nE 's|^refs/tags/v([0-9]+[.][0-9])$|\1|p' | sort -V | tail -1 | sed '/^$/!s/^/v/'
