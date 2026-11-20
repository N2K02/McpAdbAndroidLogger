# Shizuku-only ADB access lifecycle

MCP Android Logger uses the **LocalADB Shizuku server** and never creates an ADB
socket, opens TCP/5555, pairs over wireless debugging, or generates an RSA host identity.

## Connection

1. **Settings → ADB** is the single setup page. It displays live Binder
   availability, per-app permission, and shell UID 2000, like SEQ and VREC.
2. The user starts LocalADB and grants MCP Android Logger Shizuku permission.
3. `ShizukuHostAdapter` owns exactly one app-local `ShizukuSession`, shared by
   background Logcat and MCP tools through `AdbCoordinator`.
4. `ShizukuSession` verifies the Binder, permission, and UID 2000 when binding,
   verifies `id -u` before every privileged command, and invalidates state on
   disconnect or binder death. No cached historical grant authorizes commands.
5. `AdbTransport.exec` only forwards to the Shizuku session.
6. `AdbRecoveryLifecycle` retries only the app-side Binder connection. It never
   starts LocalADB, changes wireless-debugging settings, or pairs ADB.
7. Quit cancels app-owned recovery, unbinds the app's Shizuku user service,
   removes Binder listeners, and stops MCP monitoring/tunnel resources.
   LocalADB's separate server is not stopped by MCP Quit.

## Tests

Maintain tests covering Binder loss, permission and UID checks, command
cancellation, single-session ownership, status refresh, and Quit. Reject any
reintroduction of `AdbIdentity`, `AdbSessionKey`, pairing-code parsers,
`libadb`, or TLS/TCP transport fallbacks.
