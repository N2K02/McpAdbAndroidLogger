# MCP ADB diagnostics (experimental)

## On demand only

Every remote MCP request executes a bounded, read-only snapshot and returns.
There are **no MCP-triggered background monitors, timers, streams, or remote
shell commands**. Android's circular Logcat buffers permit repeated snapshots;
an agent may call again when it needs new evidence. A completed ADB request
can take longer than 10 seconds on a slow or blocked transport; a universal
10-second capture guarantee is not advertised.

Home and the Logs menu are for **manual Logcat**. Their cache, search count,
and captured-entry statistics are separate from MCP reads. Home additionally
shows a read-only MCP Logcat request count and active tool count. The Logs
page includes a **Search** button and owner-only TXT, JSON, ZIP export.

MCP Audits is the second main-navigation item, available only in the Android
UI. Remote access to audit history has been removed; request metadata remains
recorded locally for troubleshooting.

## MCP diagnostic features

- `list_logcat_profiles`: read built-in and locally saved tag profiles.
- `get_live_logs`, `search_logs`, `get_crashes`, `get_anrs`:
  bounded Android Logcat snapshots with optional verified package UID,
  tag/priority, PID, time, buffer, text and severity filters.
- `get_app_runtime`: exact-package, read-only snapshot of visible process IDs,
  per-app memory PSS (when exposed) and Android permission grant states.
  Raw process tables, package dumps and private paths are not returned.
- `get_app_ops`: read-only, allowlisted AppOps modes (audio recording, camera,
  location, notifications, background execution, overlays and media). No
  timestamps or proxy-attribution details are returned.
- `get_diagnostic_correlation`: sequential Logcat, CPU, memory and battery
  observations with a capture window and explicit non-causality caveat.
- Existing CPU, frame timing, battery, PSI memory, wakelock, screen-off,
  SurfaceFlinger and Perfetto tools remain available on demand.

Custom profiles are created/deleted locally in the Logs menu, validated
before private persistence, and only listed/read remotely. Presets include
`audio`, `crashes`, `performance`, `battery`, and `system`.

Sample Logcat arguments:

```json
{
  "profile": "audio",
  "tags": ["SEQ-Audio:V", "AndroidRuntime:E"],
  "buffers": ["main", "system", "crash"],
  "limit": 100
}
```

Sample app diagnosis:

```json
{"package_name": "com.example.app"}
```

## Important limitations

- Actual ADB capture keeps the LogFox-compatible `uid` and `epoch` output
  format regardless of the optional `format` argument. This is intentional:
  switching the transport format would break the existing parser. Use event
  timestamps and fields instead of assuming native `threadtime` output.
- ADB snapshot capture currently retains the newest 2,000 lines; old events
  already evicted by Android's Logcat ring buffer cannot be recovered.
- `since` filters parsed events, not a live kernel or continuous capture.
- Package filtering uses a verified UID mapping. Android framework logs under
  system UID are not falsely attributed to a target app.
- `get_app_runtime` is a sequential snapshot; reported processes may exit
  before subsequent commands. Android version, SELinux and OEM policies can
  prevent memory or permission details from being visible.
- Some existing SurfaceFlinger / Perfetto tools deliberately sample for 3–10
  seconds and alter transient TimeStats collection state. They are explicit
  tool calls, not persistent monitoring, and may exceed 10 seconds end to end.

## Recommended next tools

1. App process/foreground importance and service summaries from ActivityManager.
2. Audio-focus/playback/recording state reports that return only safe parsed
   metadata, never user audio or other app content.
3. Short thermal/CPU frequency snapshots from supported, readable sysfs paths
   or `dumpsys thermalservice`.
4. Automated crash triage with timestamped log context, deduplicated signatures
   and an explicit "insufficient evidence" state.

Avoid remotely exposing arbitrary `adb shell`, `am` actions, writes to
system settings, permission grants, full bugreports, content providers, or
unredacted `dumpsys` output. Privileged ADB access does not imply root or
unrestricted access to protected Android app data.
