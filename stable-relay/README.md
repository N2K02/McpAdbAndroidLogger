# Stable HTTPS MCP relay

The existing Android app runs the Cloudflare Quick Tunnel internally. Its
`trycloudflare.com` URL may expire or change. This Worker stores only the
current **tunnel URL**, so MCP clients can always use the same Worker address.

A GitHub Pages URL such as
`https://n2k02.github.io/McpAdbAndroidLogger/mcp` **cannot** be the MCP
endpoint: GitHub Pages does not support server-side POST/OAuth proxying.
Leave the main website untouched.

## One-time provisioning (unavoidable)

1. Create a Cloudflare Worker in your Cloudflare account and deploy this folder
   using `npx wrangler deploy` (Wrangler will prompt for Cloudflare login).
   Deployment creates a permanent `https://n2k02-mcp-adb-logger.<account>.workers.dev/mcp` URL.
2. Set the secret `DEVICE_TOKEN` using `npx wrangler secret put DEVICE_TOKEN`.
   Choose a new random 32+ character token; **never** commit it, embed it in
   GitHub Pages, or use the MCP client's OAuth approval token.
3. In the Android app's **MCP connection → Permanent relay**, paste the Worker
   HTTPS URL ending in `/mcp` and the same `DEVICE_TOKEN`; save/reconnect.
   The device publishes each tunnel registration automatically.
4. Paste the Worker URL in your ChatGPT MCP connector once. Use the existing
   on-device OAuth approval flow. This URL remains stable when the device
   reconnects to Wi-Fi, reboots, or receives a newly assigned Quick Tunnel.

The Android device must be running and online for the endpoint to serve MCP.
If offline, the Worker returns 503. Cloudflare Workers/Durable Objects have
free-tier limits; the Worker is not deployed until an account owner provisions
it. GitHub Pages cannot remove that one-time setup.

Security: `/register` requires a separate secret; only `*.trycloudflare.com`
endpoints are allowed, to block arbitrary upstream SSRF. Client authorization
remains enforced by the Android OAuth server; the Worker never stores Logcat.
The Android MCP server is read-only and redacts captured log data. The tunnel
address still exists publicly, so OAuth and local permission checks are critical.

Run relay validation with `node --test stable-relay/worker.test.mjs`.
