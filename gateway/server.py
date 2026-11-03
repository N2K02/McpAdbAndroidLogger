"""Read-only MCP server backed by an authenticated Android log upload endpoint.

The mobile app connects outbound over HTTPS. The gateway never opens an ADB
socket or accepts shell commands from the AI client.
"""
from __future__ import annotations

import asyncio
from collections import deque
from contextlib import asynccontextmanager
import hmac
import json
import os
import re
from typing import Any

from mcp.server import MCPServer
from starlette.applications import Starlette
from starlette.responses import JSONResponse, Response
from starlette.routing import Mount, Route

MAX_UPLOAD = 512_000
LOG_CAP = 5000
device_key = os.environ.get("DEVICE_API_KEY", "")
client_token = os.environ.get("MCP_CLIENT_TOKEN", "")
allowed_origins = {item.strip() for item in os.environ.get("MCP_ALLOWED_ORIGINS", "").split(",") if item.strip()}

mcp = MCPServer("MCP ADB Android Logger")
lock = asyncio.Lock()
logbook: deque[dict[str, Any]] = deque(maxlen=LOG_CAP)
snapshot: dict[str, Any] = {"device": "android", "adb": "not connected", "package": "", "packages": []}
next_cursor = 1
patterns = [
    re.compile(r"(?i)(authorization|bearer|token|api[_-]?key|password|secret|pairing.code)([=: ]+)([^\s,;]+)"),
    re.compile(r"(?i)\b[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}\b"),
    re.compile(r"(?i)\b(?:\d[ -]?){13,19}\b"),
]


def redact(value: str) -> str:
    for index, pattern in enumerate(patterns):
        if index == 0:
            value = pattern.sub(r"\1\2[REDACTED]", value)
        else:
            value = pattern.sub("[REDACTED]", value)
    return value


def trimmed(value: Any, limit: int) -> str:
    return redact(str(value))[:limit]


async def health(request):
    """Unauthenticated liveness only: never expose phone, log or token data."""
    return JSONResponse({"service": "mcp-adb-logger", "status": "ok"})


async def ingest(request):
    if request.headers.get("content-length", "").isdigit() and int(request.headers["content-length"]) > MAX_UPLOAD:
        return JSONResponse({"error": "payload too large"}, status_code=413)
    body = await request.body()
    if len(body) > MAX_UPLOAD:
        return JSONResponse({"error": "payload too large"}, status_code=413)
    try:
        data = json.loads(body)
        if not isinstance(data, dict) or not isinstance(data.get("logs"), list):
            raise ValueError("invalid payload")
    except (ValueError, TypeError):
        return JSONResponse({"error": "invalid JSON envelope"}, status_code=400)
    global next_cursor
    async with lock:
        snapshot.update({
            "device": trimmed(data.get("device", "android"), 80),
            "adb": trimmed(data.get("adb", "unknown"), 120),
            "package": trimmed(data.get("package", ""), 180),
            "packages": [trimmed(p, 160) for p in data.get("packages", [])[:500]
                         if isinstance(p, str)] if isinstance(data.get("packages"), list) else [],
        })
        for item in data["logs"][:500]:
            if not isinstance(item, dict):
                continue
            logbook.append({
                "cursor": next_cursor, "time": item.get("time", 0),
                "uid": trimmed(item.get("uid", ""), 32),
                "pid": trimmed(item.get("pid", ""), 32),
                "level": trimmed(item.get("level", ""), 1),
                "tag": trimmed(item.get("tag", ""), 120),
                "message": trimmed(item.get("message", ""), 2000),
                "package": snapshot["package"],
            })
            next_cursor += 1
    return JSONResponse({"accepted": True, "next_cursor": next_cursor})


@mcp.tool()
async def get_device_status() -> dict[str, Any]:
    """Return the last authenticated phone and Logcat collector status."""
    async with lock:
        return dict(snapshot, buffered_logs=len(logbook), newest_cursor=next_cursor - 1)


@mcp.tool()
async def list_installed_apps(limit: int = 100) -> list[str]:
    """List cached third-party package names from the Android shell."""
    async with lock:
        return list(snapshot.get("packages", []))[:max(1, min(limit, 500))]


@mcp.tool()
async def get_live_logs(after_cursor: int = 0, limit: int = 100,
                        level: str = "", query: str = "") -> list[dict[str, Any]]:
    """Read redacted Logcat events. Call again with the last cursor for fresh events."""
    async with lock:
        return [
            dict(entry) for entry in logbook
            if entry["cursor"] > after_cursor
            and (not level or entry["level"] == level.upper())
            and (not query or query.lower() in (entry["tag"] + " " + entry["message"]).lower())
        ][:max(1, min(limit, 500))]


@mcp.tool()
async def search_logs(query: str, limit: int = 100) -> list[dict[str, Any]]:
    """Search the recent buffered logs for tags, exceptions or message text."""
    if not query.strip():
        return []
    return await get_live_logs(limit=limit, query=query)


@mcp.tool()
async def get_crashes(limit: int = 100) -> list[dict[str, Any]]:
    """Retrieve buffered FATAL EXCEPTION, native crash and related error entries."""
    async with lock:
        return [
            dict(item) for item in logbook
            if "FATAL EXCEPTION" in item["message"]
            or "Fatal signal" in item["message"]
            or "crash" in item["tag"].lower()
        ][-max(1, min(limit, 500)):]


@mcp.tool()
async def get_anrs(limit: int = 100) -> list[dict[str, Any]]:
    """Retrieve buffered ANR diagnostics."""
    async with lock:
        return [
            dict(item) for item in logbook
            if "ANR in " in item["message"] or "am_anr" in item["tag"].lower()
        ][-max(1, min(limit, 500)):]


@asynccontextmanager
async def lifespan(app):
    async with mcp.session_manager.run():
        yield


http = Starlette(
    routes=[
        Route("/health", health, methods=["GET"]),
        Route("/ingest", ingest, methods=["POST"]),
        Mount("/", app=mcp.streamable_http_app(stateless_http=True, json_response=True)),
    ],
    lifespan=lifespan,
)


async def application(scope, receive, send):
    """Separate phone ingest and MCP client tokens; deny unconfigured public access."""
    if scope["type"] == "http":
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        path = scope.get("path", "")
        origin = headers.get(b"origin", b"").decode("utf-8", "replace")
        if origin and (not allowed_origins or origin not in allowed_origins):
            await Response("Origin not permitted", status_code=403)(scope, receive, send)
            return
        if path == "/health" and scope.get("method") == "GET":
            await http(scope, receive, send)
            return
        if path == "/ingest":
            provided = headers.get(b"x-device-key", b"").decode("utf-8", "replace")
            expected = device_key
        else:
            authorization = headers.get(b"authorization", b"").decode("utf-8", "replace")
            provided = authorization.removeprefix("Bearer ") if authorization.startswith("Bearer ") else ""
            expected = client_token
        if not expected or not hmac.compare_digest(provided, expected):
            await Response("Unauthorized", status_code=401)(scope, receive, send)
            return
    await http(scope, receive, send)
