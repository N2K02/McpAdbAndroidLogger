# MCP ADB Android Logger

Android Logcat monitoring and a read-only AI MCP bridge, using the **same shared N2K02 template** as mRadio and AAYouTube.

**Development:** one experimental PR, no changes to the production branch until CI/device verification. GPL-3.0-or-later (LogFox-derived parsing).

## Components

- **Android app:** Material 3 Expressive via `dev.n2k02:theme:0.1.4`, single mRadio-style header, Home, Settings, Logs, Diagnostics, Quit.
- **In-app updater:** `dev.n2k02:updater:0.1.19`, stable/experimental channels, shared update screen, pending-update badges and install guard while capture is active. Configured for `https://n2k02.github.io/mcpadbandroidlogger/updates`.
- **Embedded ADB:** VRec-derived private ADB client; first-pairing via Android Wireless debugging, mDNS port discovery, UID 2000 verification, optional TCP/5555 promotion and reconnect.
- **Logcat:** LogFox-inspired `logcat -v epoch -v uid` parser, package/UID filtering, bounded history, exception/ANR detection; read-only.
- **MCP gateway:** outbound HTTPS ingestion and authenticated Streamable HTTP MCP server. The gateway cannot execute device commands.

## Build

Requires JDK 17, Android SDK platform 37 and `N2K02_PACKAGES_TOKEN` with read:packages access to `N2K02/n2k02theme` and `N2K02/n2k02updater`:

```bash
export N2K02_PACKAGES_TOKEN='your_read_only_package_token'
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

This repository's Actions secrets must be configured; secrets from other repositories are **not inherited**. CI uses its GitHub token as a fallback if both packages grant this repository access.

## Device setup

1. Install debug APK. Enable USB debugging and Wireless debugging.
2. Open **ADB connection**, choose Android's **Pair device with pairing code**, and enter the code and port (or use discovered port). Android must authorize the initial connection.
3. The app validates `id -u == 2000` before starting capture. TCP/5555 is enabled by default but can expose an ADB listener to the LAN; disable in Settings if not wanted.
4. Start monitoring and select a package. Logcat access varies by Android build, and monitoring requires a visible foreground notification.
5. For AI access, deploy the gateway and enter its HTTPS URL and `DEVICE_API_KEY` in **MCP connection**, then opt in to upload.

## MCP gateway

```bash
export DEVICE_API_KEY='different-strong-device-secret'
export MCP_CLIENT_TOKEN='strong-ai-client-secret'
docker compose up --build -d
```

By default Docker binds only localhost:8000. Put an authenticated TLS reverse proxy in front of the service. Mobile ingestion uses `POST /ingest` with `X-Device-Key`; MCP clients use `Authorization: Bearer <MCP_CLIENT_TOKEN>` against `/mcp`. **Do not expose plain HTTP publicly.**

MCP tools: `get_device_status`, `list_installed_apps`, `get_live_logs`, `search_logs`, `get_crashes`, `get_anrs`. These are read-only, bounded and redact common secrets. Device monitoring starts and stops locally, not through an AI command.

A static bearer-token MCP gateway is compatible with clients supporting that authentication method. **Claude's remote MCP connector may require an OAuth-capable proxy/provider**; the Android app does not make custom MCP connectors available in the official ChatGPT Android app. Both require supported client configuration.

```bash
python -m pip install -r gateway/requirements.txt
pytest gateway/tests
```

## CI/release conventions

`.github/workflows/build.yml`: PR unit tests, lint, Gradle APK, Python gateway tests, artifacts; stable/manual versioning and optional signed releases and website manifest publication. To enable updateable releases, add `ANDROID_SIGNING_KEYSTORE_B64`, `ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_PASSWORD`, `ANDROID_SIGNING_ALIAS`, `N2K02_PACKAGES_TOKEN`, and (for public feed) `ANDROID_DISTRIBUTION_TOKEN`. Otherwise CI publishes a debug artifact only, which **is not suitable for stable in-place upgrades**.

## Hardware validation needed

Initial pairing and permission approvals, Android 13+ Logcat availability, TCP/5555 handoff, OEM reboot recovery, foreground-service time limits, update signer continuity and Claude OAuth integration must be tested on an Android device and deployment. CI passing alone cannot verify these.
