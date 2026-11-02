# Shared ADB lifecycle parity contract

For VREC, LocalAdb and McpAdbAndroidLogger, the shared ADB transport must:
1. Use an RSA key persisted across app restarts. Do not rotate it on reconnect or Quit.
2. Pass a freshly decoded disposable private-key object to each libadb manager.
3. Use `disconnect()` (not manager `close()`) for ADB teardown, and retire the manager.
4. On shell timeout, close the stream, disconnect the socket, invalidate manager
   and connection state, then reverify shell UID 2000 after reconnect.
5. Serialize shell commands and transport transitions. Close readers and writers.
6. Reuse a live, already verified session without unnecessary disconnect/reconnect.
7. Verify UID 2000 after connecting, and after TLS-to-TCP/5555 or USB mode changes.
   A listening TCP port or saved pairing flag is never proof of authorization.
8. Keep automatic recovery single-flight, bounded and cancellation-safe; respect
   manual Stop/Quit and never override disabled Wireless debugging same-boot.
9. Ensure one Quit owner awaits socket cleanup before finishing the Activity/Service.
10. Test key isolation, reconnect, timeout, mode switches, and Quit cancellation.

Only feature-specific work should vary: VREC's capture helper, LocalAdb's
console/Shizuku server, and MCP Logger's Logcat/MCP/Cloudflare resources.

Reference upstream: `MuntashirAkon/libadb-android` tag `3.1.1`.
The upstream connection reader may print `Socket closed` on intentional
shutdown; do not globally suppress unexpected I/O errors.
