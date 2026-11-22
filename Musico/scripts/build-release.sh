#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-1.0.0}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
DMG_STAGE="$DIST_DIR/dmg-stage"
IPA_STAGE="$DIST_DIR/ipa-stage"
DMG_OUT="$DIST_DIR/Musico-${VERSION}-macOS.dmg"
IPA_OUT="$DIST_DIR/Musico-${VERSION}-iOS-unsigned.ipa"

cleanup() {
  rm -rf "$DMG_STAGE" "$IPA_STAGE"
}
trap cleanup EXIT

cd "$ROOT_DIR"
mkdir -p "$DIST_DIR"
rm -rf "$DMG_STAGE" "$IPA_STAGE" "$DMG_OUT" "$IPA_OUT"

echo "==> Xcode & SDK versions:"
xcodebuild -version
echo "macOS SDK: $(xcrun --sdk macosx --show-sdk-version)"
echo "iOS SDK: $(xcrun --sdk iphoneos --show-sdk-version)"

echo "==> Building macOS (Mac Catalyst, Release)..."
xcodebuild -project Musico.xcodeproj -scheme Musico \
  -configuration Release \
  -destination 'generic/platform=macOS,variant=Mac Catalyst' \
  -derivedDataPath "$ROOT_DIR/build/ReleaseCatalyst" \
  CODE_SIGNING_ALLOWED=NO \
  build

CATALYST_APP="$ROOT_DIR/build/ReleaseCatalyst/Build/Products/Release-maccatalyst/Musico.app"
if [[ ! -d "$CATALYST_APP" ]]; then
  echo "Missing built Mac Catalyst app at $CATALYST_APP" >&2
  exit 1
fi

mkdir -p "$DMG_STAGE"
ditto "$CATALYST_APP" "$DMG_STAGE/Musico.app"

if ! codesign --force --deep --sign - --entitlements "$ROOT_DIR/Musico-DMG.entitlements" "$DMG_STAGE/Musico.app"; then
  codesign --force --deep --sign - "$DMG_STAGE/Musico.app"
fi
codesign --verify --deep --strict "$DMG_STAGE/Musico.app"

ln -s /Applications "$DMG_STAGE/Applications"

echo "==> Packaging macOS DMG..."
hdiutil create -volname "Musico" -srcfolder "$DMG_STAGE" -ov -format UDZO "$DMG_OUT"
hdiutil verify "$DMG_OUT"

echo "==> Building iOS (Release, unsigned)..."
xcodebuild -project Musico.xcodeproj -scheme Musico \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$ROOT_DIR/build/ReleaseIOS" \
  CODE_SIGNING_ALLOWED=NO \
  build

IOS_APP="$ROOT_DIR/build/ReleaseIOS/Build/Products/Release-iphoneos/Musico.app"
if [[ ! -d "$IOS_APP" ]]; then
  echo "Missing built iOS app at $IOS_APP" >&2
  exit 1
fi

mkdir -p "$IPA_STAGE/Payload"
ditto "$IOS_APP" "$IPA_STAGE/Payload/Musico.app"

echo "==> Packaging iOS IPA..."
(cd "$IPA_STAGE" && zip -r -y -9 "$IPA_OUT" Payload)

echo "==> Done:"
ls -lh "$DMG_OUT" "$IPA_OUT"
