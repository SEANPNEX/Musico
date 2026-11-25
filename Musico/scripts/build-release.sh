#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-1.0.0}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
DMG_STAGE="$DIST_DIR/dmg-stage"
IPA_STAGE="$DIST_DIR/ipa-stage"
DMG_OUT="$DIST_DIR/Musico-${VERSION}-macOS.dmg"
IPA_OUT="$DIST_DIR/Musico-${VERSION}-iOS-unsigned.ipa"
VERIFY_TMP="$DIST_DIR/verify-tmp.dmg"
CATALYST_LOG="$DIST_DIR/build-catalyst.log"
IOS_LOG="$DIST_DIR/build-ios.log"

cleanup() {
  rm -rf "$DMG_STAGE" "$IPA_STAGE" "$VERIFY_TMP"
}
trap cleanup EXIT

cd "$ROOT_DIR"
mkdir -p "$DIST_DIR"
rm -rf "$DMG_STAGE" "$IPA_STAGE" "$DMG_OUT" "$IPA_OUT" "$VERIFY_TMP" "$CATALYST_LOG" "$IOS_LOG"

echo "==> Xcode & SDK versions:"
xcodebuild -version
echo "macOS SDK: $(xcrun --sdk macosx --show-sdk-version)"
echo "iOS SDK: $(xcrun --sdk iphoneos --show-sdk-version)"

CATALYST_ARGS=()
if [[ -n "${CATALYST_ARCHS:-}" ]]; then
  echo "==> Building Mac Catalyst (${CATALYST_ARCHS}) & iOS (arm64) in parallel..."
  CATALYST_ARGS+=(ARCHS="$CATALYST_ARCHS" ONLY_ACTIVE_ARCH=NO)
else
  echo "==> Building Mac Catalyst (Universal) & iOS (arm64) in parallel..."
fi

(
  xcodebuild -project Musico.xcodeproj -scheme Musico \
    -configuration Release \
    -destination 'generic/platform=macOS,variant=Mac Catalyst' \
    -derivedDataPath "$ROOT_DIR/build/ReleaseCatalyst" \
    ${CATALYST_ARGS[@]+"${CATALYST_ARGS[@]}"} \
    CODE_SIGNING_ALLOWED=NO \
    build
) >"$CATALYST_LOG" 2>&1 &
CATALYST_PID=$!

(
  xcodebuild -project Musico.xcodeproj -scheme Musico \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "$ROOT_DIR/build/ReleaseIOS" \
    CODE_SIGNING_ALLOWED=NO \
    build
) >"$IOS_LOG" 2>&1 &
IOS_PID=$!

CATALYST_RC=0
IOS_RC=0
wait "$CATALYST_PID" || CATALYST_RC=$?
wait "$IOS_PID" || IOS_RC=$?

if [[ "$CATALYST_RC" -ne 0 ]]; then
  echo "Mac Catalyst Release build failed (exit $CATALYST_RC). Last 80 log lines:" >&2
  tail -n 80 "$CATALYST_LOG" >&2 || true
  exit "$CATALYST_RC"
fi
if [[ "$IOS_RC" -ne 0 ]]; then
  echo "iOS Release build failed (exit $IOS_RC). Last 80 log lines:" >&2
  tail -n 80 "$IOS_LOG" >&2 || true
  exit "$IOS_RC"
fi
rm -f "$CATALYST_LOG" "$IOS_LOG"

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
DMG_CREATED=0
for create_attempt in 1 2 3; do
  rm -f "$DMG_OUT"
  if hdiutil create -volname "Musico" -srcfolder "$DMG_STAGE" -ov -format UDZO "$DMG_OUT"; then
    DMG_CREATED=1
    break
  fi
  echo "hdiutil create failed on attempt $create_attempt" >&2
  if [[ "$create_attempt" -lt 3 ]]; then
    sleep 3
  fi
done
if [[ "$DMG_CREATED" -ne 1 ]]; then
  echo "hdiutil create failed after 3 attempts" >&2
  exit 1
fi

sync
sleep 2

DMG_VERIFIED=0
BACKOFFS=(3 5 8 10 0)
VERIFY_OUT=""
for idx in "${!BACKOFFS[@]}"; do
  attempt=$((idx + 1))
  if VERIFY_OUT="$(hdiutil verify -nocache "$DMG_OUT" 2>&1)"; then
    echo "$VERIFY_OUT"
    DMG_VERIFIED=1
    break
  fi
  echo "hdiutil verify attempt $attempt failed: $VERIFY_OUT" >&2
  if [[ "$attempt" -lt 5 ]]; then
    sleep "${BACKOFFS[$idx]}"
  fi
done

if [[ "$DMG_VERIFIED" -ne 1 ]]; then
  if [[ "$VERIFY_OUT" != *"Resource temporarily unavailable"* ]]; then
    echo "hdiutil verify failed with a non-transient error" >&2
    exit 1
  fi

  echo "Attempting hdiutil verify on a synced copy ($VERIFY_TMP)..." >&2
  cp "$DMG_OUT" "$VERIFY_TMP"
  sync
  if VERIFY_COPY_OUT="$(hdiutil verify -nocache "$VERIFY_TMP" 2>&1)"; then
    echo "$VERIFY_COPY_OUT"
    rm -f "$VERIFY_TMP"
    DMG_VERIFIED=1
  else
    rm -f "$VERIFY_TMP"
    echo "Copy verify failed: $VERIFY_COPY_OUT" >&2
    if [[ "$VERIFY_COPY_OUT" != *"Resource temporarily unavailable"* ]]; then
      exit 1
    fi

    DMG_FMT="$(hdiutil imageinfo -format "$DMG_OUT" 2>&1 || true)"
    if [[ "$DMG_FMT" == "UDZO" ]]; then
      echo "WARNING: hdiutil verify hit macOS runner EAGAIN (Resource temporarily unavailable), but hdiutil imageinfo confirmed UDZO format; continuing." >&2
      DMG_VERIFIED=1
    elif [[ "$DMG_FMT" == *"Resource temporarily unavailable"* ]]; then
      DMG_SIZE="$(stat -f%z "$DMG_OUT")"
      if [[ "$DMG_SIZE" -gt 1048576 ]] && python3 -c 'import sys
with open(sys.argv[1], "rb") as f:
    f.seek(-512, 2)
    magic = f.read(4)
assert magic == b"koly", f"unexpected UDIF trailer magic: {magic!r}"
' "$DMG_OUT"; then
        echo "WARNING: hdiutil hit macOS runner EAGAIN (Resource temporarily unavailable), but DMG size ($DMG_SIZE bytes) and Apple UDIF koly trailer are valid; continuing." >&2
        DMG_VERIFIED=1
      else
        echo "DMG structural fallback check failed (size=$DMG_SIZE)" >&2
        exit 1
      fi
    else
      echo "hdiutil imageinfo did not confirm UDZO format: $DMG_FMT" >&2
      exit 1
    fi
  fi
fi

if [[ "$DMG_VERIFIED" -ne 1 ]]; then
  echo "hdiutil verify failed" >&2
  exit 1
fi

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
