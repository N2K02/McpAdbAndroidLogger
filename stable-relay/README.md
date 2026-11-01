# Cloudflare Worker: permanent MCP URL — setup and troubleshooting

This is the **current recommended connection method**. The phone hosts the MCP/OAuth server and runs a private outbound Cloudflare Quick Tunnel. A Worker fronts it with **one permanent public HTTPS URL**; it is **not** a static Cloudflare Pages site or the GitHub Pages website. No PC, Docker, or Termux is needed on the phone. The Android server must remain running for the remote URL to work.

## A. Deploy the Worker (once, using the Cloudflare dashboard)

1. Open [Cloudflare dashboard](https://dash.cloudflare.com/) → **Workers & Pages** → **Create** → **Worker** and connect the GitHub repository `N2K02/McpAdbAndroidLogger`. **Do not create a Pages/static-assets project**.
2. Set **Production branch = `main`**, **Root directory = `stable-relay`**, **Build command = empty/None**, and **Deploy command = `npx wrangler deploy`**. Use the Worker name in [wrangler.toml](wrangler.toml): **`mcpadbandroidlogger`**. Do not use `wrangler pages deploy`.
3. The Worker is configured by this folder's [wrangler.toml](wrangler.toml) and [worker.mjs](worker.mjs). Confirm **Settings → Bindings** contains a **Durable Object** named `MCP_ROUTE` with class `McpRoute`. Never delete this binding.
4. Under **Domains**, enable the `workers.dev` route. Copy the complete hostname actually assigned to your account. Cloudflare's **Deployments → Ready** means the *Worker code* is deployed, **not** that the Android MCP route is online.

**If deployment says “Could not detect a directory containing static files”:** Cloudflare tried a static-site build. Use a **Worker** project, confirm the root directory and deploy command above, and verify the Worker name matches `wrangler.toml`.

## B. Add the runtime registration secret (required)

1. In the **Worker's runtime Settings**, find **Variables and Secrets** (the runtime bindings/secrets section), then **Add → Secret**. Set **Name = `DEVICE_TOKEN`**.
2. Generate a unique random ASCII secret **32–256 characters** long, with no whitespace. Save it securely and deploy the secret binding. You may also set it using `cd stable-relay && npx wrangler secret put DEVICE_TOKEN` on a trusted computer.
3. **Do not put `DEVICE_TOKEN` only in Settings → Builds → Variables and secrets.** Those are build-time settings; the Worker needs `env.DEVICE_TOKEN` **at runtime**.
4. Under Worker Settings, verify that a runtime secret **named** `DEVICE_TOKEN` exists. Cloudflare will not reveal its value later. If you lose it, replace it in Cloudflare **and** the app with the same newly generated secret.

The Worker refuses requests with `503 Stable relay is not provisioned` when `DEVICE_TOKEN` is missing or shorter than 32 characters. A successfully deployed Worker can therefore remain **Offline** on Android.

**Two different keys, never interchange them:**
- **Worker registration secret / `DEVICE_TOKEN`:** private Android-to-Worker route registration; must match *exactly* in Cloudflare and the phone. Do not send it to an MCP client.
- **MCP client access key:** existing phone-owned secret used on the phone-hosted OAuth approval page. It survives ordinary reconnects, Wi-Fi changes and updates. **Do not rotate it to repair Cloudflare connectivity**.

Do not publish either secret in issues, screenshots, GitHub commits, logs or chat.

## C. Configure the Android app (once)

1. Go to Cloudflare **Worker → Domains** and copy the live `https://<worker>.<account>.workers.dev` URL; append **`/mcp`**. For example: `https://mcpadbandroidlogger.YOUR-SUBDOMAIN.workers.dev/mcp`. Replace `YOUR-SUBDOMAIN` with your real assigned hostname; examples are not valid endpoints.
2. Open Android **Settings → MCP connection → Public MCP**. Paste the full `https://...workers.dev/mcp` address into **Permanent HTTPS MCP URL**.
3. Paste the **same** runtime `DEVICE_TOKEN` into **Worker registration secret**; tap **Save and connect**.
4. Tap **Start server** (if stopped), then **Reconnect tunnel** if needed. The local server must say **Local server running**. Allow the foreground notification, network access and Android background operation.
5. Wait until **Public MCP → Online**. Cloudflare's “Ready” alone is insufficient. Only then copy the **single permanent URL** into an OAuth/DCR-compatible remote MCP connector.

The Worker URL remains fixed even when the **hidden** `trycloudflare.com` tunnel is replaced. The Worker route is refreshed every minute while connected and expires after **four minutes** without the phone. A stopped phone cannot provide live MCP service, even though its URL does not change.

## D. Diagnose Cloudflare “Ready” but Android “Offline”

Open `https://YOUR-REAL-WORKER.workers.dev/health` in a browser. Test **`/health`**, not `/mcp`; never paste your keys in the address bar.

| Response or symptom | Meaning | Resolution |
| --- | --- | --- |
| `503 Stable relay is not provisioned` | Missing or short **runtime** `DEVICE_TOKEN` | Add runtime Secret `DEVICE_TOKEN` (32+ chars) and deploy |
| `503 Android phone is offline or route expired` | No fresh phone route registered | Confirm the same token in Android, **Start server → Reconnect tunnel**; verify mobile internet |
| `503 Android tunnel unavailable` | Worker has a route but cannot reach the hidden tunnel | Verify cloudflared and network; reconnect, check foreground/battery restrictions |
| `{"service":"mcp-adb-logger","status":"ok"}` | Worker can reach phone's local MCP server | Now configure the MCP client with `/mcp` and OAuth/DCR |
| `401 Unauthorized` on `POST /register` | Worker registration secret does not match | Correct the Android Worker secret; **do not** rotate the MCP client key |
| `405 POST required` on `GET /register` | Expected: register accepts POST only | Do not use `/register` as a browser test |
| Worker 404 / static assets page | Wrong Worker project, hostname or route | Check Worker type, Domains and `stable-relay/wrangler.toml` |

**Phone diagnostics:** The Public MCP card differentiates **Local server running** from **Public MCP → Online**. If it says **Stable relay unavailable**, publication failed or its heartbeat expired. Check that `DEVICE_TOKEN` is a **runtime** secret, not a build variable. The current Android app does not display the exact Worker registration HTTP status; the `/health` response and Cloudflare **Observability → Logs** are the safer troubleshooting path. Do not enable logs containing tokens or include credentials in support reports.

**More checks:** Cloudflare **Settings → Bindings → MCP_ROUTE** must reference `McpRoute`. Ensure the worker deployment is active and the `workers.dev` route is enabled. The app includes a Cloudflare **ARM64** tunnel binary; other phone CPU architectures have only local MCP. If no tunnel can start, the public Worker cannot reach the device. Try a different mobile/Wi-Fi network if outbound Cloudflare tunnel traffic is blocked.

## E. Connect ChatGPT, Claude or another MCP client

Use only `https://YOUR-REAL-WORKER.workers.dev/mcp`, **not** `/health`, `/register`, localhost, GitHub Pages, or the hidden `trycloudflare.com` address. In a remote MCP client that supports OAuth authorization code with PKCE and **Dynamic Client Registration (DCR)**, add this URL, use automatic registration, and complete approval on the phone-hosted authorization page. Check the client's name and callback domain before entering the existing **MCP client access key** and approving. Remote agents receive their own revocable OAuth tokens.

Some clients require OAuth configurations this app does not support (for example CIMD), so their integration cannot be guaranteed. The phone provides bounded, redacted, **read-only** Android Logcat/MCP tools; it does not allow remote shell execution.

## F. Safety, maintenance and limitations

- **Never commit secrets** to `wrangler.toml`, a public APK, GitHub Pages, or README. Never send `DEVICE_TOKEN` to an AI MCP connector.
- **Stop server** and **Quit** stop the phone service and tunnel; the Worker route expires automatically. Android battery restrictions, network outages, process termination and reboot restrictions can interrupt connectivity.
- Normal **Reconnect tunnel**, changing Wi-Fi and app updates do not rotate either saved key. Clearing app data/uninstalling erases locally saved secrets; preserve the Worker secret independently if you intend to restore setup.
- For an active connection check, use `GET /health` and follow Android's MCP audit flow; “Ready” or an OAuth approval redirect by itself is not proof of end-to-end connection.
- Deployment is one-time; **running and keeping the phone online is ongoing**. A permanent URL is **not** permanent uptime.

## Developer regression check

Run `node --test stable-relay/worker.test.mjs` to test the Worker registration/authentication, route TTL and request filtering. These are source tests, not a live phone/Cloudflare integration test.
