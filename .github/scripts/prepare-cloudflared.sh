#!/usr/bin/env bash
# Verified official Cloudflare release, never a runtime third-party download.
set -euo pipefail
VERSION=2026.10.7
EXPECTED=3df43aedc9ff291d1735b5cb576cb97e0b157488796db2e32b28aecabb445775
TARGET=app/src/main/jniLibs/arm64-v8a/libcloudflared.so
mkdir -p "$(dirname "$TARGET")"
# GitHub release/CDN asset endpoints occasionally return transient 502/503/504.
# Retry only this pinned, SHA-checked official asset with an overall bound.
curl --fail --location --silent --show-error \
  --retry 10 --retry-all-errors --retry-delay 3 \
  --connect-timeout 15 --max-time 120 \
  "https://github.com/cloudflare/cloudflared/releases/download/$VERSION/cloudflared-linux-arm64" \
  --output "$TARGET"
printf '%s  %s\n' "$EXPECTED" "$TARGET" | sha256sum --check --status || {
  echo "::error::Official cloudflared SHA-256 mismatch"
  exit 1
}
file "$TARGET"
test "$(stat -c%s "$TARGET")" -gt 1000000
echo "Verified official cloudflared v$VERSION ARM64 included in APK."
