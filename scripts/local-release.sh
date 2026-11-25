#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 1 ]]; then
  echo "Usage: $0 <version> [--reuse-apple] [--local-android] [--no-ci-trigger] [--notes-file <path>]" >&2
  exit 1
fi

RAW_VERSION="$1"
VERSION="${RAW_VERSION#v}"
TAG="v${VERSION}"
shift

REUSE_APPLE=0
LOCAL_ANDROID=0
NO_CI_TRIGGER=0
NOTES_FILE=""

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --reuse-apple)   REUSE_APPLE=1 ;;
    --local-android) LOCAL_ANDROID=1 ;;
    --no-ci-trigger) NO_CI_TRIGGER=1 ;;
    --notes-file)
      shift
      NOTES_FILE="${1:-}"
      if [[ -z "$NOTES_FILE" || ! -f "$NOTES_FILE" ]]; then
        echo "--notes-file requires an existing markdown file" >&2
        exit 1
      fi
      ;;
    -h|--help)
      echo "Usage: $0 <version> [--reuse-apple] [--local-android] [--no-ci-trigger] [--notes-file <path>]"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
  shift
done

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE_DIR="$ROOT_DIR/dist/release-${TAG}"
DEFAULT_NOTES_FILE="$ROOT_DIR/dist/release-notes-${TAG}.md"

if [[ -f "$HOME/.musico-release.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$HOME/.musico-release.env"
  set +a
fi

detect_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    echo "$JAVA_HOME"
    return 0
  fi
  if [[ -x "/opt/homebrew/opt/openjdk@17/bin/java" ]]; then
    echo "/opt/homebrew/opt/openjdk@17"
    return 0
  fi
  local cellar_jdk
  cellar_jdk="$(find /opt/homebrew/Cellar/openjdk@17 -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n1 || true)"
  if [[ -n "$cellar_jdk" && -x "$cellar_jdk/bin/java" ]]; then
    echo "$cellar_jdk"
    return 0
  fi
  /usr/libexec/java_home -v 17 2>/dev/null
}

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

echo "==> Preparing local release ${TAG} (stage: ${STAGE_DIR})"

# 1. Apple build (always local on macOS — skips slow hosted macos-26 runner queue)
DMG_SRC="$ROOT_DIR/Musico/dist/Musico-${VERSION}-macOS.dmg"
IPA_SRC="$ROOT_DIR/Musico/dist/Musico-${VERSION}-iOS-unsigned.ipa"
if [[ "$REUSE_APPLE" -eq 1 && -f "$DMG_SRC" && -f "$IPA_SRC" ]]; then
  echo "==> [Apple] Reusing existing ${VERSION} artifacts in Musico/dist/..."
else
  echo "==> [Apple] Building ${VERSION} macOS DMG & unsigned iOS IPA locally..."
  (
    cd "$ROOT_DIR/Musico"
    chmod +x scripts/build-release.sh
    ./scripts/build-release.sh "$VERSION"
  )
fi
cp "$DMG_SRC" "$IPA_SRC" "$STAGE_DIR/"

# 2. Android build (optional local Gradle build; default uses fast CI ubuntu-latest with persistent signing keystore)
if [[ "$LOCAL_ANDROID" -eq 1 ]]; then
  echo "==> [Android] Building ${VERSION} Debug & Release APKs locally..."
  JAVA_17="$(detect_java_home)"
  (
    cd "$ROOT_DIR/MusicoAndroid"
    export JAVA_HOME="$JAVA_17"
    chmod +x gradlew
    ./gradlew assembleDebug assembleRelease --stacktrace
    cp app/build/outputs/apk/debug/app-debug.apk "$STAGE_DIR/MusicoAndroid-${VERSION}-debug.apk"
    if [[ -f app/build/outputs/apk/release/app-release.apk ]]; then
      cp app/build/outputs/apk/release/app-release.apk "$STAGE_DIR/MusicoAndroid-${VERSION}-release.apk"
    elif [[ -f app/build/outputs/apk/release/app-release-unsigned.apk ]]; then
      cp app/build/outputs/apk/release/app-release-unsigned.apk "$STAGE_DIR/MusicoAndroid-${VERSION}-release-unsigned.apk"
      echo "NOTE: Built unsigned release APK (set ANDROID_KEYSTORE_* in ~/.musico-release.env for signed APK)." >&2
    else
      echo "No Android release APK generated" >&2
      exit 1
    fi
  )
fi

# 3. Release notes
NOTES_TO_USE="$NOTES_FILE"
if [[ -z "$NOTES_TO_USE" ]]; then
  cat > "$DEFAULT_NOTES_FILE" <<EOF
## Musico ${TAG}

Unified release across **Windows 10/11**, **Android**, **macOS**, and **iOS**.

### Downloads
- **Windows 10/11 Installer**: \`MusicoWindows-${VERSION}-win-x64-Setup.exe\` (self-contained x64, Start Menu & uninstaller)
- **Windows 10/11 Portable**: \`MusicoWindows-${VERSION}-win-x64-portable.zip\` (self-contained single-file EXE + \`portable.txt\` using \`.\\Data\\\`)
- **Android (API 26+)**: \`MusicoAndroid-${VERSION}-release.apk\` (persistently signed release build for seamless in-app updates) & \`MusicoAndroid-${VERSION}-debug.apk\`
- **macOS (Catalyst)**: \`Musico-${VERSION}-macOS.dmg\` (ad-hoc signed disk image with \`Applications\` drag-install shortcut)
- **iOS (17+)**: \`Musico-${VERSION}-iOS-unsigned.ipa\` (unsigned IPA for sideloading via AltStore, SideStore, Sideloadly, or Xcode)

Checksums are included in \`SHA256SUMS.txt\`.
EOF
  NOTES_TO_USE="$DEFAULT_NOTES_FILE"
fi

# 4. Upload local artifacts directly to GitHub Release
echo "==> [GitHub] Publishing local assets to release ${TAG}..."
(
  cd "$ROOT_DIR"
  if gh release view "$TAG" >/dev/null 2>&1; then
    gh release upload "$TAG" "$STAGE_DIR"/* --clobber
    gh release edit "$TAG" --title "Musico ${TAG}" --notes-file "$NOTES_TO_USE" --latest
  else
    gh release create "$TAG" "$STAGE_DIR"/* \
      --title "Musico ${TAG}" \
      --notes-file "$NOTES_TO_USE" \
      --latest
  fi
)

# 5. Trigger fast Windows (+ optional signed Android) release workflow on GitHub Actions (skips macos-26)
if [[ "$NO_CI_TRIGGER" -eq 0 ]]; then
  BUILD_ANDROID_FLAG="true"
  if [[ "$LOCAL_ANDROID" -eq 1 ]]; then
    BUILD_ANDROID_FLAG="false"
  fi
  echo "==> [GitHub Actions] Triggering fast release.yml (tag=${TAG}, build_android=${BUILD_ANDROID_FLAG}, include_apple=false)..."
  (
    cd "$ROOT_DIR"
    gh workflow run release.yml --ref main -f tag="$TAG" -f build_android="$BUILD_ANDROID_FLAG" -f include_apple=false
  )
fi

echo "==> Local upload complete for ${TAG}."
