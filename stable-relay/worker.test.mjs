import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import relay, { allowedMcpPath, validQuickTunnel, McpRoute } from "./worker.mjs";

test("only valid Cloudflare quick tunnel endpoints can be registered", () => {
  assert.equal(validQuickTunnel("https://example-001.trycloudflare.com/mcp"), true);
  for (const url of [
    "https://n2k02.github.io/McpAdbAndroidLogger/mcp",
    "https://attacker.trycloudflare.com.evil.test/mcp",
    "http://valid.trycloudflare.com/mcp",
    "https://valid.trycloudflare.com:8443/mcp",
    "https://valid.trycloudflare.com/mcp?x=1",
    "https://valid.trycloudflare.com/not-mcp",
    "https://u:p@valid.trycloudflare.com/mcp",
    "https://127.0.0.1/mcp",
    "not a url",
  ]) assert.equal(validQuickTunnel(url), false, url);
});

test("only MCP, OAuth and health endpoints may be proxied", () => {
  for (const path of ["/mcp", "/health", "/oauth/authorize",
    "/oauth/token", "/oauth/register", "/oauth/approve",
    "/oauth/revoke", "/oauth/consent.js",
    "/.well-known/oauth-authorization-server",
    "/.well-known/oauth-protected-resource",
    "/.well-known/oauth-protected-resource/mcp"])
    assert.equal(allowedMcpPath(path), true, path);
  for (const path of ["/", "/admin", "/oauth/evil", "/.well-known/other", "/ingest"])
    assert.equal(allowedMcpPath(path), false, path);
});

test("unauthenticated registration cannot update tunnel routing", async () => {
  let hit = false;
  const env = { DEVICE_TOKEN: "x".repeat(64), MCP_ROUTE: { getByName() { hit = true; throw Error(); } } };
  for (const auth of [null, "Bearer bad", "Basic " + "x".repeat(64)]) {
    const headers = auth ? { Authorization: auth } : {};
    const r = await relay.fetch(new Request("https://stable.workers.dev/register", {
      method: "POST", headers, body: "{}",
    }), env);
    assert.equal(r.status, 401);
  }
  assert.equal(hit, false);
});

test("authenticated registration maps a new quick tunnel without changing the stable hostname", async () => {
  const data = new Map();
  const obj = new McpRoute({ storage: {
    put: async (k, v) => data.set(k, v),
    get: async k => data.get(k),
  } });
  const env = { DEVICE_TOKEN: "x".repeat(64), MCP_ROUTE: {
    getByName: name => { assert.equal(name, "android-phone"); return obj; },
  } };
  for (const host of ["abc.trycloudflare.com", "xyz.trycloudflare.com"]) {
    const r = await relay.fetch(new Request("https://stable.workers.dev/register", {
      method: "POST",
      headers: { Authorization: "Bearer " + env.DEVICE_TOKEN },
      body: JSON.stringify({ mcpUrl: "https://" + host + "/mcp" }),
    }), env);
    assert.equal(r.status, 200);
    assert.equal(data.get("origin").url, "https://" + host + "/mcp");
    assert.ok(data.get("origin").expiresAt > Date.now());
  }
});

test("expired registration fails closed before contacting prior tunnel", async () => {
  const storage = new Map([["origin", {
    url: "https://old.trycloudflare.com/mcp", expiresAt: Date.now() - 1,
  }]]);
  const obj = new McpRoute({ storage: {
    get: async key => storage.get(key),
    put: async (key, value) => storage.set(key, value),
  } });
  const r = await obj.fetch(new Request("https://static.workers.dev/mcp", {
    method: "POST", headers: { Authorization: "Bearer " + "y".repeat(64) },
    body: '{"jsonrpc":"2.0","method":"initialize","id":1}',
  }));
  assert.equal(r.status, 503);
});

test("Cloudflare Workers preview config isolates Durable Object storage", () => {
  const config = readFileSync(new URL("./wrangler.toml", import.meta.url), "utf8");
  assert.match(config, /^name = "mcpadbandroidlogger"$/m);
  assert.match(config, /^main = "worker.mjs"$/m);
  assert.match(config, /^previews = \{ \}$/m);
  assert.match(config, /^\[\[durable_objects.bindings\]\]$/m);
  assert.match(config, /^\[\[previews.durable_objects.bindings\]\]$/m);
  assert.equal((config.match(/name = "MCP_ROUTE"/g) || []).length, 2);
  assert.equal((config.match(/class_name = "McpRoute"/g) || []).length, 2);
  assert.doesNotMatch(config, /DEVICE_TOKEN\s*=/);
});
