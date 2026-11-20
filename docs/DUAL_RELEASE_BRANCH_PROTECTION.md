# Dual-architecture branch security and release process

## Branches

- `main-adb`: integrated ADB stable source.
- `main-shizuku`: external Shizuku Server stable source.
- `main`: legacy branch; do not target new release-development PRs here.
- Existing architecture-specific experimental branches: reuse the current experimental PR for that track rather than creating duplicates.

Every feature/fix PR MUST target **exactly one** of `main-adb` or `main-shizuku`. Changes common to both tracks must be tested on both and cherry-picked or independently ported as appropriate. Never merge one architecture wholesale into the other.

## GitHub ruleset configuration — repository administrator action required

Open **Settings → Rules → Rulesets** and edit the existing active **Protect main** branch ruleset.

1. Keep its existing `refs/heads/main` or `~DEFAULT_BRANCH` target.
2. Add **`refs/heads/main-adb`** and **`refs/heads/main-shizuku`** to the included branches.
3. Preserve **Require a pull request before merging** and **Block force pushes**. Do not introduce an unrestricted bypass.
4. Require the corresponding Android APK build and public release-contract checks before merging. Use the exact check names produced by each branch's workflow; do not require an unavailable check.
5. For a sole maintainer, zero mandatory approving reviews is supported, but PRs and green checks remain mandatory.

GitHub branch rulesets must be enforced by GitHub. A workflow cannot prevent a direct push from landing if the branch is unprotected.

## Publishing

- Open/updated experimental PR → signed `McpAdbAndroidLogger-ADB-<version>-experimental.<N>.apk` or `McpAdbAndroidLogger-SHIZUKU-<version>-experimental.<N>.apk` prerelease.
- Merged PR targeting the matching stable branch → stable `McpAdbAndroidLogger-ADB-<version>.apk` or `McpAdbAndroidLogger-SHIZUKU-<version>.apk`.
- Direct push to a stable branch must never publish a stable release.
- The updater must match both the installed architecture and opted stable/experimental channel; reject cross-track offers.

## Verification before merge

Unit tests, lint, release shrinking, signing identity/lineage, filename and release-tag contracts, updater track filtering, and an install/upgrade smoke test must be green. Do not merge solely because a debug APK builds.
