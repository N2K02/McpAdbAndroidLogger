# MCP ADB Android Logger

Android Logcat monitoring and a read-only AI MCP bridge, using the **same shared N2K02 template** as mRadio and AAYouTube.

**Development:** one experimental PR, no changes to the production branch until CI/device verification. GPL-3.0-or-later (LogFox-derived parsing).

## Start here: public MCP connection through Cloudflare Worker

**Cloudflare Worker setup is required only once.** Cloudflare **Deployments → Ready** means the code deployed, not that the Android app can be reached.

**Verified deployment gotcha:** The Worker needs a **runtime secret** called `DEVICE_TOKEN` (32+ characters) under **Worker Settings → Variables and Secrets**. A build-time variable is not sufficient. Without the runtime secret, the Worker returns `503 Stable relay is not provisioned` and Android remains **Offline** even if the Worker shows **Ready**. The `MCP_ROUTE` Durable Object binding must also be present.

1. Deploy **Worker** (not Cloudflare Pages/static site) from GitHub `main`: root directory `stable-relay`, build command **None**, deploy command `npx wrangler deploy`, Worker name `mcpadbandroidlogger` as defined in `stable-relay/wrangler.toml`.
2. Add **runtime Secret** `DEVICE_TOKEN` with your own private 32–256 character secret and deploy it. The phone **Worker registration secret** must be exactly the same value.
3. In Cloudflare **Domains**, copy the actual `https://<worker>.<your-subdomain>.workers.dev` hostname. Append `/mcp`. Do not use GitHub Pages or the hidden Quick Tunnel URL.
4. Android **Settings → MCP connection → Public MCP**: enter the complete Worker `/mcp` URL and **Worker registration secret**, tap **Save and connect → Start server → Reconnect tunnel**.
5. When Android reports **Online**, add the single `/mcp` URL to a compatible remote MCP client using **OAuth + Dynamic Client Registration (DCR)**. Your separate existing **MCP client access key** remains unchanged and is entered only on the OAuth approval page.

**Offline diagnosis:** In Chrome open your real Worker address ending in **`/health`** (not `/mcp`). `Stable relay is not provisioned` means the runtime `DEVICE_TOKEN` is missing; `Android phone is offline or route expired` means the device has not registered a fresh tunnel; `Android tunnel unavailable` means the route exists but the tunnel is unreachable; `{"service":"mcp-adb-logger","status":"ok"}` means the Worker reached the phone. Keep your keys private.

**Full step-by-step setup and troubleshooting:** [Cloudflare Worker permanent URL guide](stable-relay/README.md). This includes where to set runtime secrets (not build variables), Durable Object checks, HTTP errors, connection recovery, OAuth and security guidance.

## Components

- **Android app:** Material 3 Expressive via `dev.n2k02:theme:0.1.4`, single mRadio-style header, Home, Settings, Logs, Quit.
- **In-app updater:** `dev.n2k02:updater:0.1.19`, stable/experimental channels, shared update screen, pending-update badges and install guard while capture is active. Configured for `https://n2k02.github.io/mcpadbandroidlogger/updates`.
- **Embedded ADB:** VRec-derived private ADB client; first-pairing via Android Wireless debugging, mDNS port discovery, UID 2000 verification, optional TCP/5555 promotion and reconnect.
- **Logcat:** On-demand, bounded `logcat -d` snapshots via ADB (no continuous polling), LogFox-inspired parsing, package/UID filtering, cursor history and crash/ANR detection; read-only.
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
2. In **Home → Connection → Connect Android ADB**, open Android's **Pair device with pairing code** and enter the code using **Pair manually** (or enable the optional Accessibility helper in **Settings → ADB connection**). Android must authorize the app's ADB key once.
3. The app validates `id -u == 2000` before starting capture. TCP/5555 is enabled by default but can expose an ADB listener to the LAN; disable in Settings if not wanted.
4. Use **Home → Refresh** or **Logs → Refresh logs from Android**, or request a Logcat tool from MCP. Each request reads Android's existing logd buffer once and stops. ADB and the phone-hosted MCP foreground service remain connected; no continuous capture runs.
5. For remote AI access, follow the **Start here** Cloudflare Worker steps above. Your phone hosts the MCP service; once the permanent Worker endpoint is **Online**, connect via OAuth/DCR. **Do not generate or rotate the MCP client access key merely to troubleshoot a Worker deployment.**

## Legacy external MCP gateway (optional, not the recommended phone-hosted flow)

The following Docker/Python gateway setup is legacy functionality and **is not required for the permanent Cloudflare Worker URL**. Its `DEVICE_API_KEY` and `MCP_CLIENT_TOKEN` settings are **not** the Worker `DEVICE_TOKEN` and should not be entered in the Android Public MCP card.

The app's **Generate secure keys** button only creates secrets; it does not register or deploy a public hostname. **Test gateway** calls the non-sensitive public `GET /health` endpoint to verify that the deployed HTTPS gateway is reachable, but does not authenticate the MCP client. Key rotation requires updating gateway environment variables and restarting the gateway. Don't give Claude or ChatGPT the **device upload key**; use the separate **MCP client token**.

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

## APK configuration: stable and experimental

Release setup matches AAYouTube/VRec. **Both PR and stable produce an R8-minified release APK signed with the same signing key**, never a debug-key APK as a published update.

- Experimental PR: `McpAdbAndroidLogger-1.0.apk` (name based on latest stable baseline), Android versionName `1.0-experimental.RUN`, versionCode `100000 + GITHUB_RUN_NUMBER`. Published as `experimental-pr-N-run-RUN` prerelease in source Releases and `mcpadbandroidlogger/updates/experimental.json` in the public distribution.
- Stable main build: increments the last stable `major.minor` version, publishes `McpAdbAndroidLogger-X.Y.apk` under `vX.Y` as the latest stable release, and updates `mcpadbandroidlogger/updates/stable.json`.
- Signing sources supported: `signing/keys.tar.enc` + `SIGNING_PASSPHRASE` (AAYouTube/MRadio), `SIGNING_KEYSTORE_ENC_BASE64` + `SIGNING_PASSPHRASE` (VRec), or `ANDROID_SIGNING_KEYSTORE_B64` + `ANDROID_SIGNING_STORE_PASSWORD`/`ANDROID_SIGNING_ALIAS`. Missing signing material **fails CI**, not a silent debug fallback.
- Signed APKs are retained as Actions artifacts and source Releases; `ANDROID_DISTRIBUTION_TOKEN` additionally publishes the public APK and per-channel SHA-256 manifest.
- `n2k02updater` uses the same stable/experimental toggle, installation-time signer validation, and user-triggered update flow as the other apps. Updates are blocked while live log capture is active.

CI validates MCP gateway tests, Android unit tests/lint, R8 release builds, and public version-resolution regression tests. The signed release APK is installed on an Android API 35 emulator and cold-launched three times before it can be published, catching WorkManager/Room startup crashes that debug/unit tests miss.

## Hardware validation needed

Initial pairing and permission approvals, Android 13+ Logcat availability, TCP/5555 handoff, OEM reboot recovery, foreground-service time limits, update signer continuity and Claude OAuth integration must be tested on an Android device and deployment. CI passing alone cannot verify these.


The **Home → Connection** tab hosts the pairing-code entry, live discovery status, manual Retry, and Developer options shortcut. **Settings → ADB connection** contains only VRec's two cards and five choices. USB debugging alone does not authorize an app, start local TCP/5555 or establish a loopback ADB connection: pair/authorize through Wireless debugging or use an already authorized TCP/5555 listener. Every successful connection must verify shell UID 2000.


## Permanent MCP URL (Cloudflare Worker)

The embedded Cloudflare Quick Tunnel remains an internal Android transport.
The app presents only **one permanent public MCP URL** and **one persistent MCP
client access key** on a single **MCP connection → Public MCP** card.
Cloudflare Worker + Durable Object provides the permanent address; the app
publishes each newly connected hidden `trycloudflare.com` address to it
automatically using a separate private device registration token. Neither
reconnecting nor restarting the app rotates the MCP client key. OAuth remains
on the Android phone, and the Worker stores no logs.

**GitHub Pages cannot provide `/McpAdbAndroidLogger/mcp` as a functional
endpoint** because it cannot run an HTTP/OAuth reverse proxy. The stable endpoint
must be a real server-side Worker, for example
`https://mcpadbandroidlogger.<your-account-subdomain>.workers.dev/mcp`.
See [stable-relay/README.md](stable-relay/README.md) for the one-time Worker
deployment and secret configuration. Cloudflare account access is unavoidable;
there is no way to deploy a permanent URL from a GitHub Pages static repo alone.
Never commit a registration secret to source control.

## Phone-hosted MCP (no separate server)

Opening the app starts a read-only **local MCP server** at
`http://127.0.0.1:8765/mcp` in the foreground service. Supported ARM64
phones also run Cloudflare Quick Tunnel in the app, forwarding traffic to the
local server through the single configured permanent Worker endpoint.
The changing Quick Tunnel URL is deliberately hidden and is never a user
connection option. No Docker, Termux, or separate PC is needed.

On the **Public MCP** card, configure the deployed Cloudflare Worker URL
and private registration secret once. After connecting, copy the one permanent
MCP URL and the existing MCP client access key. The key stays the same across
normal reconnects, tunnel renewals, device restarts, and app updates. Some
clients require OAuth and account-side connector approval; those requirements
cannot be bypassed.

The loopback server address works only with apps on the Android phone; GitHub
Pages cannot proxy cloud-hosted ChatGPT/Claude traffic. The app uses JSON-only
Streamable HTTP MCP responses; its hidden Cloudflare Quick Tunnel URL may
change without altering the public Worker URL.

The signed APK contains the official Cloudflare 2026.10.7 ARM64 binary
verified with SHA-256. Other CPU architectures expose only local MCP.
No executable is downloaded at runtime. The server binds to 127.0.0.1,
validates Origin, uses constant-time bearer-token authentication and strict
request size limits. Log tools trigger one bounded, read-only ADB Logcat dump, redact
results and return them; they do not grant an MCP client arbitrary shell access.

Logcat is idle between explicit refreshes; Quit stops the service, local MCP,
and Cloudflare tunnel. Foreground-service and battery restrictions may stop
background connectivity. The previous external gateway implementation is
retained for legacy use but no longer uploads logs in phone-hosted mode.

## MCP package filtering (remote Logcat tools)

The Android **Logs** page already has an app/package picker. **Remote MCP
clients now have their own optional `package_name` argument**, independent of
the phone's UI filter, on `get_live_logs`, `search_logs`, `get_crashes` and
`get_anrs`. To find exact names call `list_installed_apps` first (the ADB
shell lists third-party packages with UID mappings).

For example, ask your connected MCP agent to call
`get_live_logs` with `{"package_name":"com.example.app","limit":100}` or
`search_logs` with
`{"package_name":"com.example.app","query":"exception","limit":50}`.
You can similarly filter `get_crashes` and `get_anrs`. The
`after_cursor`, `level` and `query` options on `get_live_logs` continue
to combine with the package filter as before.

**Matching is UID-based, not a substring of the log message.** If a requested
package has no verified UID mapping or is not currently discoverable, the
query returns **no entries**, never all-device logs. Omit `package_name` to
read logs from all captured packages. Android-system crash/ANR records may use
system UIDs rather than the target app's UID and therefore may not appear in a
package-scoped query; omit the filter to investigate such records.

All filtering is read-only: each MCP log query triggers one bounded Android Logcat
snapshot, followed by filtering of the deduplicated 2,000-entry in-memory cache.
This preserves cursor continuity and local review without continuous collection.
It does not change the phone's on-screen app filter, permissions, public MCP URL,
registration token or client access key. Update
the experimental APK and reconnect/refresh MCP tools in your AI client for the
new `package_name` schema to become visible.

## Home and Android Logcat redesign

The bottom navigation is **Home · Logs · Settings · Quit**. Home shows ADB shell
readiness, an on-demand Refresh/Cancel control, this-session metrics (lines scanned,
unique events, buffered entries, scan cycles, last successful scan, errors),
and local/public MCP status. It no longer displays recent Logcat entries or an
app selector.

**Logs** is a separate top-level Android Logcat screen. The app picker uses real
installed-app names and icons when Android allows launcher visibility, with
package-name/icon fallbacks for hidden packages. Filter by app UID, severity
minimum, text, time window, crash/ANR, tag and PID. Filtering never changes
which Android events are read on demand: recent snapshots accumulate in a bounded
2,000-entry in-memory buffer for local review and MCP cursors. No continuous Logcat reader runs. **Reset filters** changes only the
view. **Clear logs** drops captured rows but preserves recent deduplication so
a repeated logcat snapshot does not immediately repopulate old messages.
**Copy** copies up to the 200 newest visible redacted entries; **Export** saves
all currently visible redacted entries using Android's system document picker.
No all-files storage permission or persistent private issue-log journal is used.


## VRec ADB parity and persistent background MCP

ADB connection matches the latest VRec `main` design (Pairing & recovery,
Connection & security) and copies VRec's behavioral policy where it applies:
automatic pairing-code detection through explicit Accessibility consent;
2-minute in-process ADB watchdog with a 15-minute WorkManager backstop;
boot-aware TLS-port verification, conservative authorization retry, opt-in
Wireless debugging restoration after reboot (never overriding same-boot manual
off), and verified TCP/5555 disable. The logger does not copy VRec's recording
daemon or call capture routines.

The phone-hosted MCP listener/tunnel belongs to a persistent
`connectedDevice` foreground service, not the UI. It continues when the
user opens Claude/ChatGPT, switches apps, or turns off the screen, subject to
Android battery restrictions, task-manager stop and device power policies.
Android 15 limits `dataSync` foreground services to six hours, so the
ADB/network service uses the documented `connectedDevice` type with its
required permission. Cloudflared retries transient outages with backoff and
keeps a confirmed Quick Tunnel registration. No battery or wakelock bypass
is attempted.

Closing the Activity leaves the connection available; Stop server persists
until explicitly restarted. Quit cancels ADB workers, discovery, foreground
capture, local MCP listener and public tunnel. Start on boot controls
best-effort recovery, which is still subject to Android's boot/foreground
service launch rules.


## OAuth for any remote MCP agent (Claude, ChatGPT, other providers)

The same public HTTPS MCP URL supports OAuth 2.1 Authorization Code + S256 PKCE,
Dynamic Client Registration (DCR), Protected Resource Metadata (RFC 9728),
Authorization Server Metadata (RFC 8414), refresh token rotation and revocation.

1. Add the public MCP URL to any compatible client's remote MCP connector.
2. Choose OAuth and automatic client registration (DCR). Claude may also offer
   "Use published identity"; select **Register automatically** for this server.
3. Check the registered client name AND actual callback domain on the approval
   page. Enter your existing MCP client key only on the phone-hosted approval
   page, not in the third-party service's custom HTTP headers.
4. Approve. The remote client receives separate opaque OAuth access/refresh
   tokens scoped only to `logcat:read`, never the existing key.
5. Settings → MCP connection → Authorized MCP agents can revoke individual
   clients without affecting the others. Rotating the master key revokes all
   outstanding OAuth tokens as well as any legacy direct-key access.

Only read-only Logcat and device-status tools are available. OAuth client
registration works with arbitrary compatible vendors (no provider allowlist);
clients must provide secure registered redirect URIs, and the approval page
displays the real callback host for explicit verification. This app currently
uses **DCR**, not Client ID Metadata Documents (CIMD): choose DCR where a client
offers both. A few clients may require CIMD, confidential client credentials
or external OAuth hosting and therefore cannot connect to this version.

Phone-hosted OAuth remains available only while the connectedDevice foreground
service and its public tunnel are running.


## OAuth consent on Android browsers

The approval page keeps keyboard input inside the browser: pasting the MCP
access key automatically blurs the password field and focuses **Approve access**
without submitting on its own. The button is disabled once submitted to avoid
accidental duplicate POSTs. A second request using an already-approved ticket
shows a friendly completion page; it never grants a second code. The first
successful approval returns HTTP 302 to the registered OAuth callback. For
connection verification, look for a subsequent POST /oauth/token with HTTP 200
and an authenticated MCP initialize in the audit, not only the approval 302.

## Chrome/Safari consent redirect compatibility

The OAuth consent page applies a callback-specific CSP: its form action stays
same-origin (`POST /oauth/approve`), while the browser may follow the
successful HTTP 302 redirect to **only the previously registered callback
origin**. A blanket `form-action 'self'` blocks this redirect in Chrome and
Safari after the server has already consumed the approval ticket, leaving the
button stuck at "Approving…" even though the audit shows HTTP 302. The CSP on
all other responses remains restricted, and the server never sends the shared
access key to the OAuth client. Security regression tests cover exact callback
origins, local loopback ports, hostile URI input and retry forms.

## MCP OAuth compatibility references and verification

This phone-hosted server follows the OAuth authorization-code + PKCE flow and
publishes RFC 9728 protected-resource metadata, RFC 8414 authorization-server
metadata and a DCR registration endpoint. Follow the official references:

- MCP SDK end-to-end OAuth story:
  https://github.com/modelcontextprotocol/python-sdk/tree/main/examples/stories/oauth
- MCP SDK authentication server/client example:
  https://github.com/modelcontextprotocol/python-sdk/tree/main/examples/servers/simple-auth
- ChatGPT remote MCP authentication:
  https://developers.openai.com/plugins/build/auth
- MCP authorization specification:
  https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization

Connect ChatGPT using the **public HTTPS /mcp URL**, OAuth with **dynamic
client registration (DCR)** and S256 PKCE. This version does not yet support
Client ID Metadata Documents (CIMD); never advertise CIMD until fetching and
verifying remote metadata can be done safely with SSRF protection. Clients
using the DCR registration endpoint remain compatible.

The MCP audit sequence should show: initial 401 challenge; discovery (200);
DCR registration (201); authorization page (200); approval (302);
**POST /oauth/token (200)**; **POST /mcp initialize (200)**; tools/list (200).
If token exchange fails, the audit Reason now distinguishes unknown/replayed
codes, a mismatched redirect URI, PKCE verifier failure, changed resource,
and revoked or expired refresh grants **without exposing secret values**.
A 302 approval by itself does not mean ChatGPT finished connecting.

Do not rotate the public URL or access key during the OAuth flow. If the
callback cannot reach ChatGPT, restart connection setup using the current URL.

## Settings → Agent permissions: optional MCP audit history

The Settings submenu **Agent permissions** has a **Read MCP audit history**
switch. It is **OFF by default** for new and upgraded installations, and it
can only be changed locally on the phone. When enabled, authenticated remote
MCP agents can discover and use the read-only `get_mcp_audits` tool. When
disabled, it is hidden from `tools/list` and calls are rejected, even with
an old cached tool name. Regular Logcat tools remain available.

Audit results are paged newest first (max 25/request) with a `before_id`
cursor. The `include_payloads=true` option returns capped, re-redacted
request/response previews; the default is false. All authenticated agents
share this optional permission and may inspect other agents' prior activity.
The tool never exposes raw OAuth credentials or HTTP headers. Its responses
are not saved recursively in the audit journal. Refresh agent tools after
changing the switch.

## Clear Logcat and reset Home cards

- **Logs → Clear** removes only captured in-memory Logcat events, preserving
  Android's original system buffers. An in-flight snapshot result is discarded
  so it cannot refill the list with pre-clear entries.
- **Logs → Clear device Logcat (all buffers)** requires a separate confirmation,
  a connected and newly verified ADB shell UID 2000, and executes
  `logcat -b all -c`. This deletes Android's available system-wide Logcat
  buffers (including logs from other apps). It resets the app's captured-log
  cache, dedup window and current-session counters only on success. New
  events appear after the next explicit manual or MCP refresh.
- **Home → Logcat activity → Reset Home statistics** resets the scan totals,
  error count and last-scan timestamp without deleting the buffered events.
  ADB connection, public MCP URL, access key and live statuses remain
  authoritative and unchanged.
- None of these actions deletes **Settings → MCP audits** records; use that
  screen's separate Clear history button for the private MCP audit journal.

These are on-device UI actions only. No remote MCP clear/reset tool exists.

## Settings → MCP audits

The **MCP audits** subpage captures MCP JSON-RPC requests processed by the
embedded phone server, linked to the OAuth agent's registered name, callback
domain and client ID. Direct shared bearer-key requests appear as **Direct
key** (that key cannot identify which application sent them). Unauthorized MCP
attempts are counted but their untrusted payloads and submitted credentials
are not stored.

For each request the screen shows timestamp, agent, method/tool, result,
duration, and expandable **sanitized request and response payloads**. Requests
and responses are recorded at the server socket after writing the response,
before any further tunnel or external network transport. The audit can show
what was handed off to the network, not prove that the remote AI received it.
All audit payloads are redacted and bounded: input previews are limited to
10,000 characters, response previews to 25,000 characters, with truncation
labels; the actual MCP response is never shortened. Only the latest 160
records persist in app-private **no-backup** storage. Audits are preserved
across app Quit and restart until cleared or overwritten by retention.

Search and filter by agent, tool, status and time. Tap an entry to see payloads
and copy details. **Export** uses Android's system document picker to save the
filtered audit snapshot. **Clear** deletes the local journal without revoking
the agents or clearing Android Logcat. No OAuth browser authorization forms,
API keys, bearer tokens or HTTP headers are written to the audit journal.


## CPU and graphics performance snapshots (on demand)

The **Home → CPU & frame timing** card requires verified local ADB shell
UID 2000. Nothing polls CPU or GPU in the background. Remote MCP agents can
request one of the following read-only tools after installing the experimental
APK and refreshing their MCP tool catalog:

- **get_cpu_usage**: one short Android top process sample, listing PIDs,
  process names, resident memory and CPU percent. Optional exact
  package_name filter and limit 1–50. Multicore CPU can exceed 100%.
- **get_frame_timing**: requires exact package_name. Reads Android gfxinfo
  framestats and returns recent UI frame-count, p50/p95/p99/worst frame times,
  slow-frame count, and (where available) GPU completion latency.
  frame_budget_ms defaults to 16.67 (60 Hz reference); use 8.33 for
  120 Hz. Allowed range is 5–50 ms.
- **get_performance_snapshot**: combined CPU sample and optional
  package-specific UI frame timing.

These are **not** raw GPU hardware-utilization counters. gfxinfo may not
capture video/SurfaceView or Vulkan/OpenGL rendering. The optional
GPU-completion latency includes scheduling and must not be treated as GPU
execution time. When UI frame stats are unavailable, the response is
no_ui_framestats, not an invented 0% GPU load.

Only fixed built-in commands are executed by the authenticated local ADB
client; package names are strictly validated to prevent shell argument
injection. There is no public arbitrary-command endpoint, no daemon running
CPU/GPU probes, no persisted traces, and no file writes. One performance
request at a time is accepted. Each shell command has a finite timeout and
responses are bounded.


## Portrait-first Logs, Filters and Storage tabs

The **Logs** destination is optimized for a portrait phone with three internal
tabs: **Logs** (dense, expandable log rows with search, refresh and overflow
actions), **Filters** (application, level, time, crash/ANR, tag, PID and reset),
and **Storage** (today's private cache, count, export, clearing actions and
the explicit system-wide device Logcat clear). The Log list takes all
remaining height; rows expand on tap for PID/UID and Copy.

While the foreground MCP service is active, the app rotates its **private,
in-memory** Logcat buffer at local midnight, resetting that day's counters
and deduplication window. The daily task performs no shell commands. Each
manual or authenticated MCP Logcat refresh accepts entries from the current
local day only, even if Android's system-wide logd still contains earlier
messages. The rolling cache remains capped at **2,000 entries**, with
monotonic MCP cursors. Stopping the service stops the midnight task. There is
no ongoing Logcat sampling: new messages are collected only when requested.

**Daily rotation NEVER runs `logcat -c` or deletes Android's system buffers**.
Clearing the actual device Logcat requires separate confirmation in the More
menu. Export anything you wish to retain before the local midnight rollover;
the app deliberately does not save previous days' captured logs.

The **Home → Logcat (Today)** card also resets at local midnight: captured,
shown, buffered, read-cycle and last-refresh counters refer to the current
local day. Performance snapshots perform no background CPU/GPU polling;
parsing of potentially large gfxinfo output is dispatched off the UI thread
and only the latest 240 UI frame records are retained.


## Portrait Logcat and MCP audit retention

The portrait-first Home screen uses **Overview / Performance / Connection** tabs,
retaining the global **Home / Logs / Settings / Quit** navigation. Overview
shows compact daily captured/buffered/read-cycle/last-refresh metrics and
quick access to connection status. Only the Performance tab starts manual
CPU or UI frame probes; no CPU/GPU background sampler is enabled.

The Logs tab includes compact in-place App, Level, Time and Crashes/ANRs
filters. Every line shows a prominent color-coded severity indicator and a
chevron. Tap any line to expand its full, redacted message, severity name,
tag, millisecond timestamp and PID/UID; Copy remains protected by redaction.
More detailed filter options are in Filters; cache controls are in Storage.

**MCP audits are also current-day only.** The app-private atomic audit journal
keeps a maximum of 160 sanitized entries for the local calendar day. Older
entries are permanently pruned at midnight while MCP Logger runs, on restart
or opening the audit page, on an MCP audit query and on each new audit write.
The midnight task performs no shell commands. In-memory reuse avoids
re-reading the audit JSON file for each request. Export needed audit
history before the local midnight cutoff. This does not clear Android
Logcat, stored exports or revoke MCP agent authorization.
