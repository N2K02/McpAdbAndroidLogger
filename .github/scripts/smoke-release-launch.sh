#!/usr/bin/env bash
# Executed against the signed, R8-shrunk RELEASE APK on an Android emulator.
# Build+unit-test success cannot detect initializer/Room constructor failures.
set -euo pipefail
: "${APP:?APP required}"
: "${VERSION:?VERSION required}"
APK="$APP-$VERSION.apk"
test -s "$APK"
PACKAGE="dev.n2k02.mcplogger"
ACTIVITY="$PACKAGE/.MainActivity"
adb install -r "$APK"
for attempt in 1 2 3; do
  adb shell am force-stop "$PACKAGE" || true
  adb logcat -c
  adb shell am start -W -n "$ACTIVITY"
  sleep 4
  PID=$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)
  if [ -z "$PID" ]; then
    echo "::error::Release APK exited during cold-launch attempt $attempt"
    adb logcat -d -b crash -v threadtime | tail -180
    exit 1
  fi
  if adb logcat -d -b crash -v threadtime | grep -qE "Process: $PACKAGE(,|$)"; then
    echo "::error::Crash logged for app on cold-launch attempt $attempt"
    adb logcat -d -b crash -v threadtime | tail -180
    exit 1
  fi
  echo "Cold-launch $attempt: app process $PID still running"
done
# End-to-end loopback verification against the SIGNED R8-minified APK.
# It must be started by the app's actual foreground service, not a unit-test stub.
adb forward tcp:18765 tcp:8765
python - <<'PY'
import urllib.error
import urllib.request
import json
base = "http://127.0.0.1:18765"
with urllib.request.urlopen(base + "/health", timeout=8) as response:
    data = json.load(response)
    assert data == {"service": "mcp-adb-logger", "status": "ok"}, data
request = urllib.request.Request(
    base + "/mcp",
    method="POST",
    data=json.dumps({"jsonrpc": "2.0", "method": "tools/list", "id": 1}).encode(),
    headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream"},
)
try:
    urllib.request.urlopen(request, timeout=8)
    raise AssertionError("Unauthenticated MCP tool list was exposed")
except urllib.error.HTTPError as exc:
    assert exc.code == 401, "Unexpected unauthenticated status: " + str(exc.code)
print("Signed APK local MCP server responds on loopback and rejects unauthorized requests")
PY
adb forward --remove tcp:18765
echo 'Signed minified APK: 3 successful cold launches plus local MCP HTTP smoke test'
