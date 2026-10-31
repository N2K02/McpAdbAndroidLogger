import asyncio
import pytest
from starlette.testclient import TestClient
from gateway import server


def test_ingest_requires_auth(monkeypatch):
    monkeypatch.setattr(server, "device_key", "unit-device")
    monkeypatch.setattr(server, "client_token", "unit-client")
    with TestClient(server.application) as client:
        assert client.post("/ingest", json={"logs": []}).status_code == 401
        assert client.post("/ingest", headers={"X-Device-Key": "wrong"}, json={"logs": []}).status_code == 401
        assert client.post("/ingest", headers={"X-Device-Key": "unit-device"},
                           json={"logs": []}).status_code == 200


def test_ingest_redacts_and_supports_mcp_tools(monkeypatch):
    monkeypatch.setattr(server, "device_key", "unit-device")
    server.logbook.clear()
    with TestClient(server.application) as client:
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


def test_invalid_payload_is_rejected(monkeypatch):
    monkeypatch.setattr(server, "device_key", "unit-device")
    with TestClient(server.application) as client:
        header = {"X-Device-Key": "unit-device"}
        assert client.post("/ingest", headers=header, content=b"[]").status_code == 400
        assert client.post("/ingest", headers=header, content=b"x" * 512001).status_code == 413
