#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOTNET_BIN="${DOTNET_BIN:-/Users/sean/.dotnet/dotnet}"
if [[ ! -x "$DOTNET_BIN" ]]; then
  DOTNET_BIN="$(command -v dotnet)"
fi
MAKENSIS_BIN="${MAKENSIS_BIN:-$(command -v makensis)}"
VERSION="1.0.0"

PORTABLE_DIR="$ROOT_DIR/dist/MusicoWindows-${VERSION}-win-x64-portable"
PORTABLE_ZIP="$ROOT_DIR/dist/MusicoWindows-${VERSION}-win-x64-portable.zip"
PAYLOAD_DIR="$ROOT_DIR/dist/installer-payload"

rm -rf "$PORTABLE_DIR" "$PORTABLE_ZIP" "$PAYLOAD_DIR" "$ROOT_DIR/dist/MusicoWindows-${VERSION}-win-x64-Setup.exe"
mkdir -p "$ROOT_DIR/dist"

echo "==> Running unit tests..."
"$DOTNET_BIN" test "$ROOT_DIR/MusicoWindows.Tests/MusicoWindows.Tests.csproj"

echo "==> Publishing Portable self-contained win-x64 build..."
"$DOTNET_BIN" publish "$ROOT_DIR/MusicoWindows/MusicoWindows.csproj" \
  -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true \
  -p:EnableCompressionInSingleFile=true -p:DebugType=none \
  -o "$PORTABLE_DIR"

cat > "$PORTABLE_DIR/portable.txt" <<'EOF'
Portable mode enabled.
When portable.txt exists next to MusicoWindows.exe, library index, playlists, queues, history, and settings are stored in .\Data\.
EOF

(cd "$ROOT_DIR/dist" && zip -r -9 "$(basename "$PORTABLE_ZIP")" "$(basename "$PORTABLE_DIR")")

echo "==> Publishing Installer payload..."
"$DOTNET_BIN" publish "$ROOT_DIR/MusicoWindows/MusicoWindows.csproj" \
  -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=false -p:DebugType=none \
  -o "$PAYLOAD_DIR"

echo "==> Building NSIS Windows Installer..."
"$MAKENSIS_BIN" "$ROOT_DIR/installer/MusicoWindows.nsi"

echo "==> Done:"
ls -lh "$PORTABLE_ZIP" "$ROOT_DIR/dist/MusicoWindows-${VERSION}-win-x64-Setup.exe"
