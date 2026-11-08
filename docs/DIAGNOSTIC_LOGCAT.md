# Diagnostic Logcat MCP extension (experimental)

The Android MCP endpoint now advertises `list_logcat_profiles` and accepts
`profile`, `tags`, `since`, `pid`, `buffers`, `format`, and `mode`
on the four existing log-reading tools. These are read-only and bounded.

Profiles: `audio`, `crashes`, `performance`, `battery`, `system`.
Tags use Android priority syntax such as `SEQ-Audio:V` or
`AudioFlinger:W`. Unknown profiles, invalid tags, shell metacharacters,
and remote streaming requests are rejected.

Example MCP arguments:

```json
{
  "profile": "audio",
  "tags": ["SEQ-Audio:V", "AndroidRuntime:E"],
  "since": 1700000000000,
  "limit": 100
}
```

## Current limitations

Filtering operates on the already acquired, bounded on-device Logcat snapshot.
The validated argv builder is not yet connected to the Android ADB capture
transport. Consequently, `buffers` and `format` are validated but do
not change capture, and a tag filter cannot recover entries absent from the
snapshot. The `mode` parameter permits only `snapshot`: remote continuous
streaming would change the existing no-background-polling security model.

The existing verified package/UID boundary, redaction, limits, and local
UI filter isolation remain in place. Profiles are built in, not yet editable
or persisted by users. JSON/TXT/ZIP export, cross-source correlation, and
session lifecycle controls are not implemented in this increment.

Next integration step: route validated argv through the existing UID-2000 ADB
snapshot executor, with explicit output/time limits, before advertising native
buffer and format support.
