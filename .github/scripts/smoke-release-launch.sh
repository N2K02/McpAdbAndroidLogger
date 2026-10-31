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
echo 'Signed minified APK: 3 successful real Android cold launches'
