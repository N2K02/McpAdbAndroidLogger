# Scrcpy-inspired read-only diagnostics (experimental PR #53)

## Scope

This app does **not** run scrcpy, mirror a screen, intercept another app's decoded frames,
or collect media. It exposes only bounded, authenticated diagnostics from its existing
local ADB and MCP infrastructure.

### `get_adb_transport_profile`

- 60 in-memory samples maximum, cleared on Quit.
- Connect and UID-2000-verified shell call latency (nearest-rank p50/p95) and failures.
- No pairing codes, keys, commands, output, remote addresses or raw frame content.
- Current results are **consumer-side call durations**, including mutex contention,
  not individual TLS handshake timing.
- Upgrade the source to shared `n2k02AdbCoreLibrary` telemetry only after its
  PR #9 has passed release and package integration tests.

### `get_decoder_surface_profile`

Adds `playback_reliability_hints` alongside the existing output:

- Decoder configuration/teardown/error **log events**.
- Surface creation/destruction/replacement **log events**, plus a churn count hint.
- Attribution category separating explicit app package references and historical PID correlation.
- Unknown external decoder FPS, dropped frames, socket-to-surface latency, and
  audio/video synchronization are explicitly JSON null; none are fabricated.

Historical Logcat entries may be absent, overwritten, redacted, or attributed through
recycled PIDs. These are not actual per-app decoder lifecycle callbacks, frame counts,
stall detection, audio timestamps or proof of hardware acceleration.

## Regression gates

- All 29 MCP tools must remain read-only and explicitly invoked after OAuth checks.
- Run unit tests, lint, minified signed release builds, emulator cold-start tests,
  and experimental distribution CI.
- Test ADB pair/reconnect, monitoring, decoder hints, Stop/Quit, and same-device
  Chromium renderer attribution on a physical Android phone before main merge.
- Keep the existing `adb-core:0.1.7` pin until shared telemetry is released.
