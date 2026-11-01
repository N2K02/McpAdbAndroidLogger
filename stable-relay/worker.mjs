/**
 * Stable HTTPS facade for the Android app's changing Cloudflare Quick Tunnel.
 * No MCP credentials or Logcat data are stored here. The Android MCP server
 * remains the only authority for client OAuth and read-only tool permissions.
 *
 * Configure DEVICE_TOKEN as a Wrangler secret; never commit it to Git.
 */
const MAX_REGISTER_BODY = 2048;
const MAX_MCP_BODY = 131072;
const NO_CACHE = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };

export function validQuickTunnel(value) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" &&
      /^[a-z0-9-]+\.trycloudflare\.com$/i.test(url.hostname) &&
      url.pathname === "/mcp" && !url.search && !url.hash &&
      !url.username && !url.password && !url.port;
  } catch { return false; }
}

export function allowedMcpPath(path) {
  return path === "/mcp" || path === "/health" ||
    /^\/\.well-known\/oauth-(?:protected-resource(?:\/mcp)?|authorization-server)$/.test(path) ||
    /^\/oauth\/(?:authorize|token|register|approve|revoke|consent\.js)$/.test(path);
}

const error = (code, message) => new Response(message, {
  status: code, headers: { ...NO_CACHE, "Content-Type": "text/plain; charset=utf-8" },
});

export default {
  async fetch(request, env) {
    const pathname = new URL(request.url).pathname;
    if (!env.DEVICE_TOKEN || env.DEVICE_TOKEN.length < 32) {
      return error(503, "Stable relay is not provisioned");
    }
    if (pathname === "/register") {
      if (request.method !== "POST") return error(405, "POST required");
      // The device's separate high-entropy credential is used only to publish
      // the current Quick Tunnel; clients never receive it.
      if (request.headers.get("Authorization") !== "Bearer " + env.DEVICE_TOKEN) {
        return error(401, "Unauthorized");
      }
      if (Number(request.headers.get("Content-Length") || 0) > MAX_REGISTER_BODY) {
        return error(413, "Registration too large");
      }
      const raw = await request.text();
      if (raw.length > MAX_REGISTER_BODY) return error(413, "Registration too large");
      let origin;
      try { origin = JSON.parse(raw).mcpUrl; } catch { return error(400, "Invalid JSON"); }
      if (!validQuickTunnel(origin)) return error(400, "Unsupported tunnel URL");
      const route = env.MCP_ROUTE.getByName("android-phone");
      return route.fetch(new Request("https://relay.internal/register", {
        method: "POST", body: JSON.stringify({ origin }), headers: { "Content-Type": "application/json" },
      }));
    }

    if (!allowedMcpPath(pathname)) return error(404, "Not found");
    if (!["GET", "POST"].includes(request.method)) return error(405, "Unsupported method");
    if (Number(request.headers.get("Content-Length") || 0) > MAX_MCP_BODY) {
      return error(413, "Request too large");
    }
    const route = env.MCP_ROUTE.getByName("android-phone");
    return route.fetch(request);
  },
};

/** SQLite-backed Durable Object: one current URL, no log storage. */
export class McpRoute {
  constructor(ctx) { this.ctx = ctx; }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/register" && request.method === "POST") {
      // Only the parent Worker calls this private method after token validation.
      const body = await request.json();
      if (!validQuickTunnel(body.origin)) return error(400, "Unsupported tunnel");
      await this.ctx.storage.put("origin", body.origin);
      return new Response(JSON.stringify({ ok: true }), {
        headers: { ...NO_CACHE, "Content-Type": "application/json" },
      });
    }

    if (!allowedMcpPath(url.pathname)) return error(404, "Not found");
    const origin = await this.ctx.storage.get("origin");
    if (!validQuickTunnel(origin)) return error(503, "Android phone not registered");
    const target = new URL(origin);
    target.pathname = url.pathname;
    target.search = url.search;

    const headers = new Headers(request.headers);
    for (const key of ["host", "connection", "content-length", "transfer-encoding",
                       "cf-connecting-ip", "x-forwarded-for", "x-forwarded-host",
                       "x-forwarded-proto", "cookie"]) headers.delete(key);
    // Forward Authorization to the phone; it independently verifies OAuth.
    // Never cache or follow redirects during OAuth registration/consent.
    try {
      const body = request.method === "POST" ? await request.arrayBuffer() : undefined;
      if (body?.byteLength > MAX_MCP_BODY) return error(413, "Request too large");
      const upstream = await fetch(target, {
        method: request.method, headers, body, redirect: "manual",
        signal: AbortSignal.timeout(20000),
      });
      const outgoing = new Headers(upstream.headers);
      for (const key of ["set-cookie", "transfer-encoding", "content-length", "connection"])
        outgoing.delete(key);
      for (const [key, value] of Object.entries(NO_CACHE)) outgoing.set(key, value);
      return new Response(upstream.body, { status: upstream.status, headers: outgoing });
    } catch {
      return error(503, "Android tunnel unavailable");
    }
  }
}
