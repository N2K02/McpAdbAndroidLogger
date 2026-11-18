# Dual architecture release policy

## Branches and pull requests

- `main-adb`: integrated wireless ADB architecture, stable code. Existing `main` is preserved for legacy releases.
- `main-shizuku`: external Shizuku / LocalADB-server architecture, stable code.
- One reusable experimental PR per architecture per app, targeted only to its matching main branch.
- Do not merge either architecture's main into the other blindly. Shared bug fixes are **cherry-picked selectively** with independent tests; privilege transport, setup UI, startup and permission logic remain intentionally different.
- MorpheAutoMirror currently has only a Shizuku implementation. Never advertise a working integrated-ADB APK until an actual ADB implementation and CI pass exist.

## Cross-branch bug-fix protocol

1. File/fix the issue in the affected architecture's **existing** experimental PR. Do not create a second experimental PR for that same architecture.
2. Mark the change **shared**, **ADB-only**, or **Shizuku-only** after reviewing imports, permissions, service lifetime, interface changes, and UI.
3. For a shared fix (business logic, generic UI, parsing, validation, tests), port the **minimal commit/diff** to the other architecture's existing experimental branch. Preserve its local transport code; no full-branch merge.
4. Add or update regression tests on **both** tracks. If the other track does not need the fix, record why in the PR description.
5. Require a green test, lint, release-variant/R8 and signing check for **both** before merging each experimental PR into its corresponding stable branch.
6. Keep release notes and GitHub tags clearly labelled `ADB` or `SHIZUKU`; never cross the release tracks accidentally.

## Updates

- Installed integrated ADB APK: only `adb-vN.N` (or `adb-sdcleaner-vN.N`) stable and `experimental-adb-pr-*-run-*` prereleases with `-ADB-` APK filenames.
- Installed Shizuku APK: only `shizuku-vN.N` (or `shizuku-sdcleaner-vN.N`) stable and `experimental-shizuku-pr-*-run-*` prereleases with `-SHIZUKU-` APK filenames.
- Stable update preference: stable only. With experimental enabled: newest eligible stable or experimental **from that same architecture**.
- Reject legacy/unscoped tags, other architecture tags and APKs, unknown tracks and mismatched cached offers. Do not fall back to GitHub `/releases/latest` for track-pinned installations.
- Preserve package ID and signing identity within a track; verify APK versionCode strictly increases. Because both tracks share a package ID, switching architectures is a **manual migration**, not an automatic update.
- On installed APK the release track must be compiled in as a constant in its corresponding branch, not selected via a user preference or guessed from running service state.

## Shared n2k02updater package

- Update `N2K02/n2k02updater` in its **single existing experimental PR**, test architecture routing and backward compatibility, publish only after review/green checks.
- Apps unrelated to these two architecture tracks use `releaseTrack = null` and must behave identically to before.
- Never trigger or require APK builds for unrelated apps when only updater-package code/documentation changes. Only explicitly opted-in ADB/Shizuku app branches should be rebuilt to consume a **published** newer updater version; changing the library alone does not change already installed APKs.
- Do not bump dependencies in unrelated apps as part of an architecture-routing fix.
- The track-aware updater version must be published and consumed in app dependencies before track-specific update selection can work at runtime.

## Release-workflow checks

- Branch-scoped concurrency and release tags; experimental releases are prereleases; stable releases are not a competing GitHub global latest.
- Verify nonempty signed APK, exact track tag and asset prefix, version metadata and same signing lineage.
- Maintain check/update metadata routing for private packages and public assets; test ADB→ADB, Shizuku→Shizuku, experimental/stable switching and cross-track rejection.
