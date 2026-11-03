import asyncio
import pytest
from starlette.testclient import TestClient
from gateway import server

@pytest.fixture(scope="module")
def client():
    # MCPServer session managers deliberately have a one-shot lifespan.
    # Share one ASGI lifespan across all tests; never restart it.
    with TestClient(server.application) as instance:
        yield instance


def test_gateway_uses_mcp_sdk_v2_and_exposes_tools(client):
    from mcp.server import MCPServer
    assert isinstance(server.mcp, MCPServer)
    assert client.get("/mcp").status_code == 401
    result = asyncio.run(server.mcp.list_tools())
    assert {tool.name for tool in result} >= {
        "get_device_status", "list_installed_apps", "get_live_logs",
        "search_logs", "get_crashes", "get_anrs",
    }


def test_ingest_requires_auth(monkeypatch, client):
    monkeypatch.setattr(server, "device_key", "unit-device")
    monkeypatch.setattr(server, "client_token", "unit-client")
    assert client.post("/ingest", json={"logs": []}).status_code == 401
    assert client.post("/ingest", headers={"X-Device-Key": "wrong"}, json={"logs": []}).status_code == 401
    assert client.post("/ingest", headers={"X-Device-Key": "unit-device"},
                           json={"logs": []}).status_code == 200


def test_ingest_redacts_and_supports_mcp_tools(monkeypatch, client):
    monkeypatch.setattr(server, "device_key", "unit-device")
    server.logbook.clear()
    result = client.post("/ingest", headers={"X-Device-Key": "unit-device"}, json={
            "device": "android",
            "package": "dev.n2k02.test",
            "packages": ["dev.n2k02.test"],
            "logs": [{"id": 1, "time": 42, "level": "E", "tag": "AndroidRuntime",
                      "message": "FATAL EXCEPTION; token=abcd password:secret"}]})
    assert result.status_code == 200
    entries = asyncio.run(server.get_live_logs())
    assert len(entries) == 1
    assert "abcd" not in entries[0]["message"]
    assert "secret" not in entries[0]["message"]
    assert asyncio.run(server.get_crashes())[0]["level"] == "E"
    assert asyncio.run(server.list_installed_apps()) == ["dev.n2k02.test"]


def test_invalid_payload_is_rejected(monkeypatch, client):
    monkeypatch.setattr(server, "device_key", "unit-device")
    header = {"X-Device-Key": "unit-device"}
    assert client.post("/ingest", headers=header, content=b"[]").status_code == 400
    assert client.post("/ingest", headers=header, content=b"x" * 512001).status_code == 413


def test_public_health_discloses_no_data(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"service": "mcp-adb-logger", "status": "ok"}
    assert "device" not in response.text
    assert "token" not in response.text
    assert client.post("/health").status_code == 401


def test_mcp_remains_authenticated(client, monkeypatch):
    monkeypatch.setattr(server, "client_token", "unit-client")
    assert client.get("/mcp").status_code == 401
