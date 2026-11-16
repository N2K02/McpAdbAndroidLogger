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

## Second implementation batch: reliability + Shizuku

- Fair two-slot diagnostic execution gate, 8-second maximum queue wait, bounded
  recent queue/execution p50/p95/failure telemetry in `get_adb_transport_profile`.
- The queue returns stable `diagnostic_busy` instead of indefinite blocking;
  the existing authenticated MCP worker pool remains bounded.
- One reconnect-and-replay is attempted only for a fixed allowlist of
  side-effect-free reads on recoverable ADB connection failures.
  Pairing, permissions, mode switches, TimeStats reset/enable/disable,
  cancellation, authorization errors and arbitrary shell never retry.
- Official Shizuku 13.1.5 API + provider is an OPTIONAL dependency and
  authorization is requested only when the user taps the ADB Settings button.
- An optional Shizuku UserService running as UID 2000 or 0 handles three
  fixed read-only operations: thermal status, service discovery, JobScheduler.
  All other diagnostics use shared ADB. If Shizuku is not installed/authorized,
  returns an error or dies, each selected read automatically falls back to ADB.
- UserService exposes only a small integer operation enum, never remote shell
  commands or package names. Output is bounded and the remote process has
  a seven-second deadline. Quit unbinds the service.
- A fourth fixed operation checks existence of a short list of services
  using hidden `ServiceManager.checkService` from the authorized Shizuku
  process. It makes no raw Binder transactions, does not bypass hidden-API
  enforcement in the main app, and cannot prove permission to use a service.
- `get_device_capabilities` reports permission/UID/backend readiness and
  optional service probe; missing service access is reported as unavailable.

These implementations do NOT mirror a screen or capture audio/video. The
scrcpy-inspired diagnostics remain historical codec and Surface lifecycle
hints plus the already supported SurfaceFlinger/Perfetto counters. True
external decoder frame times and A/V offsets remain unavailable rather than
invented.

### Validation still required

- Android CI compilation, unit tests, lint, signed minified release and cold
  launch. Re-run after the **last** commit; intermediate green runs are not sufficient.
- Explicit Shizuku permission/denial/revocation, server restart and
  UserService teardown on Quit on a real device.
- Concurrent MCP calls, a dead/disconnected ADB stream, deliberate Quit
  during a queued diagnostic, and a baseline-dependent report.
- Verify the new dependency/provider manifests on API 26 through Android 17.
