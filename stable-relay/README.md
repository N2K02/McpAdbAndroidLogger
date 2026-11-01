# One permanent HTTPS MCP URL

The Android app now displays **one** public MCP endpoint and **one persistent**
MCP client access key. The Cloudflare Quick Tunnel still runs **inside the app**
but its changing hostname is never offered to users or MCP clients.

GitHub Pages (`https://n2k02.github.io/McpAdbAndroidLogger/mcp`) cannot
receive MCP POST or OAuth requests. It cannot replace a live HTTPS proxy.

## One-time Cloudflare owner provisioning (required)

Cloudflare account ownership cannot be inferred from a GitHub Pages address.
We cannot securely deploy a Worker, select an account's `workers.dev` name,
or configure its secret without the account owner's authorization.

1. In the Cloudflare account, deploy this folder's `wrangler.toml` and
   `worker.mjs` using `npx wrangler deploy`.
2. Create a random 32+ character registration secret and set it using
   `npx wrangler secret put DEVICE_TOKEN`. Store it securely.
3. In the Android app, open **MCP connection → Public MCP**. Enter the assigned
   `https://n2k02-mcp-adb-logger.<your-account>.workers.dev/mcp` URL and
   the same registration secret; tap **Save and connect**.
4. Once the card says **Online**, copy the single permanent HTTPS MCP URL to
   your ChatGPT MCP connector. Authorize using the existing, persistent
   **MCP client access key** displayed in that same card.

Steps 1–3 cannot be skipped for a Worker that has never been deployed. Do not
paste the GitHub Pages address or a `trycloudflare.com` URL into ChatGPT.

## Automatic from then onward

- The local MCP server and Quick Tunnel run in the app foreground service.
- Wi-Fi/mobile changes and reconnects reuse the saved tunnel registration.
- If Cloudflare invalidates the hidden Quick Tunnel address, Android
  re-registers it, and updates the **same Worker URL** automatically.
- Android renews the live Worker mapping each minute. The mapping expires
  safely after four minutes without a successful update.
- **Reconnect**, app restart and phone restart do **not** rotate the Worker URL,
  MCP client key or existing OAuth approval tokens. Updating the Worker URL
  manually is an exceptional, explicit configuration change.
- **Stop server** and **Quit** shut down remote access rather than pretending to
  be online. The public URL remains unchanged, but returns unavailable.
- Uninstalling or clearing the app's data erases its locally saved secrets.
  Preserve them separately if reinstalling; they are not regenerated on
  normal upgrades and restarts.

Security: registration uses a distinct high-entropy `DEVICE_TOKEN`; never
commit it to GitHub, GitHub Pages or a public Android APK. Worker routes only
`*.trycloudflare.com` origin endpoints. Android enforces MCP client key/OAuth,
read-only tools, audit logging, and Logcat redaction. The Worker stores only
the current hidden tunnel URL, not user logs.

Run automated Worker checks with
`node --test stable-relay/worker.test.mjs`.
