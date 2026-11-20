# Release channels and branch consolidation

## Intended public releases

Publish **one Android application (Shizuku)** through exactly **two user-facing channels** after migration PR [#59](https://github.com/N2K02/McpAdbAndroidLogger/pull/59) is completed:

| Channel | Source | GitHub tag pattern | APK asset |
| --- | --- | --- | --- |
| Stable | `main` | `shizuku-vX.Y` | `McpAdbAndroidLogger-SHIZUKU-X.Y.apk` |
| Experimental | `experimental` via a PR to `main` | `experimental-shizuku-pr-N-run-R` | `McpAdbAndroidLogger-SHIZUKU-X.Y-experimental.R.apk` |

Stable and experimental must use the same Android application ID and signing lineage. Both APKs must be signed release variants; debug builds are test artifacts only. Retain the Shizuku release track in update metadata and asset names; do not silently switch installed users to the integrated-ADB variant.

The public grouped downloads, `downloads-stable` and `downloads-experimental`, should each contain **one current Shizuku APK**, not parallel ADB/Shizuku APKs. Keep the correct latest stable pointer, release notes, signature/version metadata, and updater routing. Historical tags and previously published releases are retained until update continuity has been validated.

## Migration verification and acceptance

[PR #59](https://github.com/N2K02/McpAdbAndroidLogger/pull/59) brings the Shizuku implementation onto the protected `main` branch. Treat `main` as canonical only after the migration PR and its stable build pass, and verify that the two grouped releases are populated exclusively with Shizuku APKs.

The migration acceptance checks are:

1. Resolve the conflicting commits between `main` and `main-shizuku` without discarding compatible diagnostic fixes or importing integrated-ADB transport into the Shizuku architecture.
2. Migrate both the stable and experimental verification workflow triggers from `main-shizuku` to `main`; keep required PR/build/release approval checks on the protected stable branch.
3. Ensure only the Shizuku implementation is bundled: external Shizuku Binder shell, no embedded ADB key pairing, TCP/5555 or ADB-specific setup UI.
4. Confirm gateway/unit tests, Android lint, debug build, R8 release build, APK signing, cold-start/smoke checks, download-index contract and update-version tests on the exact candidate commit.
5. Verify release signer continuity, monotonic `versionCode`, and correct stable/experimental Shizuku update selection on an installed APK.
6. Confirm one new stable and one new experimental Shizuku download, without new ADB releases or cross-architecture updates.

Do **not** bypass failing CI, force-push protected stable branches, delete historical release assets in advance, or disable the stable publication gate to make a release appear green.

## Branch cleanup policy

- **Keep:** `main` (future stable), `experimental` (single reused experimental working branch).
- **Keep through migration:** `main-shizuku` (former stable and head of PR #59). Remove only after the PR is merged, migration is verified, and any downstream references are updated.
- **Archive/hold before removal:** `main-adb` and `experimental-adb-integrated` were integrated-ADB branches; they may be gone but historical versioned ADB releases must remain available until explicitly retired. Disable their release triggers as part of cutover, then consider archival/branch deletion after verification.
- **Cleanup candidates:** `experimental-scrcpy-diagnostics`, `maintenance/group-architecture-downloads`, and `maintenance/preserve-canonical-latest` if their changes are fully merged or superseded and no workflows/PRs rely on them. Check commit reachability before deletion.
- **Keep while PR open:** `dependabot/all_dependencies-ab7af4b34d` is associated with an open dependency PR; do not delete it until the PR is resolved.

Only remove obsolete workflows and distribution machinery once the replacements are running and tests are green. Cleanup must not remove GitHub release tags/assets, break Cloudflare Worker deployment, or revoke signing material.

## Updater and transport invariants

The Shizuku app uses the pinned `n2k02updater` package with a Shizuku-scoped release track. Its automatic update eligibility must reject `adb-*` tags, ADB APK assets, unscoped historical tags, and mismatched cached offers. The integrated-ADB and Shizuku APKs share an application ID historically, so switching architectures is a **manual migration**, not an automatic update.

The external Shizuku service remains a separate permission/availability dependency; the logger must not start an embedded ADB TCP transport as a fallback. Keep the existing package name, signing identity and monotonic version codes. If these cannot be demonstrated, **do not publish** a replacement stable APK.
