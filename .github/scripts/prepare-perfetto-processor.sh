#!/usr/bin/env bash
# Reproducible official Perfetto Trace Processor build-time asset.
# The binary is a standalone PIE ELF intentionally packaged as a native lib
# so Android extracts it to an executable, read-only installed-app directory.
# No runtime download, installation, network access, or Shizuku dependency.
set -euo pipefail
VERSION=57.2
EXPECTED_SHA256=05e95758a26cf57fb8d552d3c1d532486681a6ecc4ee705a484a8deb4d092d09
EXPECTED_SIZE=13098064
TARGET=app/src/main/jniLibs/arm64-v8a/libtraceprocessor.so
URL="https://commondatastorage.googleapis.com/perfetto-luci-artifacts/v${VERSION}/android-arm64/trace_processor_shell"
mkdir -p "$(dirname "$TARGET")"
curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 \
  "$URL" --output "$TARGET"
printf '%s  %s\n' "$EXPECTED_SHA256" "$TARGET" | sha256sum --check --status || {
  echo "::error::Official Perfetto Trace Processor SHA-256 mismatch"
  exit 1
}
if [ "$(stat -c%s "$TARGET")" -ne "$EXPECTED_SIZE" ]; then
  echo "::error::Unexpected Perfetto Trace Processor binary size"
  exit 1
fi
file "$TARGET"
echo "Verified official Perfetto Trace Processor v$VERSION Android ARM64 asset."
