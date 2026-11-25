#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./scripts/local-release.sh <version> [options]

Options:
  --bump                  Bump version in Android/Apple/Windows files, commit to main, and push tag v<version>
  --run-ci                Run ./scripts/local-ci.sh before bumping/releasing
  --arm64-only            Build Apple Silicon (arm64) only for macOS DMG (cuts Catalyst build time roughly in half)
  --reuse-apple           Reuse existing Musico/dist/Musico-<version>-macOS.dmg & iOS IPA if present
  --local-android         Build Android APK locally instead of on GitHub Actions
  --release-only-android  Skip building MusicoAndroid-<version>-debug.apk for faster releases
  --no-ci-trigger         Do not dispatch release.yml on GitHub Actions (Apple/local assets only)
  --no-watch              Do not wait for the GitHub Actions release workflow after dispatching
  --notes-file <path>     Use a custom markdown release notes file
  --allow-dirty           Allow --bump on a dirty working tree (also ignores uncommitted edits check)
  --dry-run               Print planned version bump, release notes, and commands without changing files/git/GitHub
  -h, --help              Show this help
EOF
}

if [[ "$#" -lt 1 ]]; then
  usage >&2
  exit 1
fi

RAW_VERSION=""
DO_BUMP=0
RUN_CI=0
ARM64_ONLY=0
REUSE_APPLE=0
LOCAL_ANDROID=0
BUILD_DEBUG_APK=1
NO_CI_TRIGGER=0
WATCH_CI=1
NOTES_FILE=""
ALLOW_DIRTY=0
DRY_RUN=0

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --bump)                 DO_BUMP=1 ;;
    --run-ci)               RUN_CI=1 ;;
    --arm64-only)           ARM64_ONLY=1 ;;
    --reuse-apple)          REUSE_APPLE=1 ;;
    --local-android)        LOCAL_ANDROID=1 ;;
    --release-only-android) BUILD_DEBUG_APK=0 ;;
    --no-ci-trigger)        NO_CI_TRIGGER=1 ;;
    --no-watch)             WATCH_CI=0 ;;
    --allow-dirty)          ALLOW_DIRTY=1 ;;
    --dry-run)              DRY_RUN=1 ;;
    --notes-file)
      shift
      NOTES_FILE="${1:-}"
      if [[ -z "$NOTES_FILE" || ! -f "$NOTES_FILE" ]]; then
        echo "--notes-file requires an existing markdown file" >&2
        exit 1
      fi
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -z "$RAW_VERSION" ]]; then
        RAW_VERSION="$1"
      else
        echo "Unexpected positional argument: $1" >&2
        usage >&2
        exit 1
      fi
      ;;
  esac
  shift
done

if [[ -z "$RAW_VERSION" ]]; then
  echo "Missing <version> (e.g. 1.2.9)" >&2
  usage >&2
  exit 1
fi

VERSION="${RAW_VERSION#v}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid SemVer '$RAW_VERSION' (expected MAJOR.MINOR.PATCH, e.g. 1.2.9)." >&2
  exit 1
fi
TAG="v${VERSION}"

START_EPOCH="$(date +%s)"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE_DIR="$ROOT_DIR/dist/release-${TAG}"
GENERATED_NOTES_FILE="$ROOT_DIR/dist/release-notes-${TAG}.md"

GRADLE_FILE="$ROOT_DIR/MusicoAndroid/app/build.gradle.kts"
PBXPROJ_FILE="$ROOT_DIR/Musico/Musico.xcodeproj/project.pbxproj"
CSPROJ_FILE="$ROOT_DIR/MusicoWindows/MusicoWindows/MusicoWindows.csproj"

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

generate_release_notes() {
  local out_path="$1"
  local prev_tag
  prev_tag="$(git -C "$ROOT_DIR" tag -l 'v*' --sort=-v:refname | grep -Fxv "$TAG" | head -n1 || true)"

  local changelog=""
  if [[ -n "$prev_tag" ]]; then
    changelog="$(git -C "$ROOT_DIR" log "${prev_tag}..HEAD" --pretty=format:'- %s (%h)' | grep -vE '^- chore\(release\): ' || true)"
  else
    changelog="$(git -C "$ROOT_DIR" log -n 15 --pretty=format:'- %s (%h)' | grep -vE '^- chore\(release\): ' || true)"
  fi

  local apk_line
  if [[ "$BUILD_DEBUG_APK" -eq 1 ]]; then
    apk_line="- **Android (API 26+)**: \`MusicoAndroid-${VERSION}-release.apk\` & \`MusicoAndroid-${VERSION}-debug.apk\`"
  else
    apk_line="- **Android (API 26+)**: \`MusicoAndroid-${VERSION}-release.apk\`"
  fi

  {
    echo "## Musico ${TAG}"
    echo ""
    echo "Unified release across **Windows 10/11**, **Android**, **macOS**, and **iOS**."
    if [[ -n "$changelog" ]]; then
      echo ""
      if [[ -n "$prev_tag" ]]; then
        echo "### What's New (since ${prev_tag})"
      else
        echo "### What's New"
      fi
      echo "$changelog"
    fi
    echo ""
    echo "### Downloads"
    echo "- **Windows 10/11 Installer**: \`MusicoWindows-${VERSION}-win-x64-Setup.exe\` (self-contained x64, Start Menu & uninstaller)"
    echo "- **Windows 10/11 Portable**: \`MusicoWindows-${VERSION}-win-x64-portable.zip\` (self-contained single-file EXE + \`portable.txt\` using \`.\Data\\\`)"
    echo "${apk_line}"
    echo "- **macOS (Catalyst)**: \`Musico-${VERSION}-macOS.dmg\` (ad-hoc signed disk image with \`Applications\` drag-install shortcut)"
    echo "- **iOS (17+)**: \`Musico-${VERSION}-iOS-unsigned.ipa\` (unsigned IPA for sideloading via AltStore, SideStore, Sideloadly, or Xcode)"
    echo ""
    echo "Checksums are included in \`SHA256SUMS.txt\`."
  } > "$out_path"
}

perform_bump_check_and_apply() {
  local dry_run="$1"
  python3 - "$VERSION" "$dry_run" "$GRADLE_FILE" "$PBXPROJ_FILE" "$CSPROJ_FILE" <<'PY'
import re, sys

new_version = sys.argv[1]
dry_run = sys.argv[2] == "1"
gradle_path, pbxproj_path, csproj_path = sys.argv[3:6]

def read_text(p):
    with open(p, "r", encoding="utf-8") as f:
        return f.read()

def write_text(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)

# 1. Android build.gradle.kts
gradle_src = read_text(gradle_path)
vc_match = re.search(r'(\bversionCode\s*=\s*)(\d+)', gradle_src)
vn_match = re.search(r'(\bversionName\s*=\s*")([^"]+)(")', gradle_src)
if not vc_match or not vn_match:
    sys.exit(f"Failed to locate versionCode/versionName in {gradle_path}")
old_vc = int(vc_match.group(2))
new_vc = old_vc + 1
old_vn = vn_match.group(2)
gradle_new = re.sub(r'(\bversionCode\s*=\s*)\d+', rf'\g<1>{new_vc}', gradle_src, count=1)
gradle_new = re.sub(r'(\bversionName\s*=\s*")[^"]+(")', rf'\g<1>{new_version}\g<2>', gradle_new, count=1)

# 2. Apple project.pbxproj
pbx_src = read_text(pbxproj_path)
cpv_matches = re.findall(r'\bCURRENT_PROJECT_VERSION\s*=\s*(\d+);', pbx_src)
mv_matches = re.findall(r'\bMARKETING_VERSION\s*=\s*([^;]+);', pbx_src)
if not cpv_matches or not mv_matches:
    sys.exit(f"Failed to locate CURRENT_PROJECT_VERSION/MARKETING_VERSION in {pbxproj_path}")
old_cpv = int(cpv_matches[0])
new_cpv = old_cpv + 1
old_mv = mv_matches[0]
pbx_new = re.sub(r'(\bCURRENT_PROJECT_VERSION\s*=\s*)\d+;', rf'\g<1>{new_cpv};', pbx_src)
pbx_new = re.sub(r'(\bMARKETING_VERSION\s*=\s*)[^;]+;', rf'\g<1>{new_version};', pbx_new)

# 3. Windows .csproj
cs_src = read_text(csproj_path)
v_match = re.search(r'<Version>([^<]+)</Version>', cs_src)
av_match = re.search(r'<AssemblyVersion>([^<]+)</AssemblyVersion>', cs_src)
fv_match = re.search(r'<FileVersion>([^<]+)</FileVersion>', cs_src)
if not (v_match and av_match and fv_match):
    sys.exit(f"Failed to locate Version/AssemblyVersion/FileVersion in {csproj_path}")
old_win_v = v_match.group(1)
new_win_asm = f"{new_version}.0"
cs_new = re.sub(r'(<Version>)[^<]+(</Version>)', rf'\g<1>{new_version}\g<2>', cs_src, count=1)
cs_new = re.sub(r'(<AssemblyVersion>)[^<]+(</AssemblyVersion>)', rf'\g<1>{new_win_asm}\g<2>', cs_new, count=1)
cs_new = re.sub(r'(<FileVersion>)[^<]+(</FileVersion>)', rf'\g<1>{new_win_asm}\g<2>', cs_new, count=1)

print(f"  Android : versionCode {old_vc} -> {new_vc}, versionName {old_vn} -> {new_version}")
print(f"  Apple   : CURRENT_PROJECT_VERSION {old_cpv} -> {new_cpv}, MARKETING_VERSION {old_mv} -> {new_version}")
print(f"  Windows : Version {old_win_v} -> {new_version}, Assembly/FileVersion -> {new_win_asm}")

if not dry_run:
    write_text(gradle_path, gradle_new)
    write_text(pbxproj_path, pbx_new)
    write_text(csproj_path, cs_new)
PY
}

regenerate_release_checksums() {
  local tag="$1"
  local sums_dir="$2"
  mkdir -p "$sums_dir"
  gh release view "$tag" --json assets > "$sums_dir/assets.json"
  python3 - "$sums_dir/assets.json" "$sums_dir/SHA256SUMS.txt" <<'PY'
import json, sys
assets_path, out_path = sys.argv[1], sys.argv[2]
with open(assets_path, "r", encoding="utf-8") as f:
    assets = json.load(f).get("assets", [])
lines = []
for asset in sorted(assets, key=lambda a: a.get("name", "")):
    name = asset.get("name", "")
    if not name or name == "SHA256SUMS.txt":
        continue
    digest = (asset.get("digest") or "").strip()
    if digest.startswith("sha256:"):
        lines.append(f"{digest.split(':', 1)[1]}  {name}\n")
with open(out_path, "w", encoding="utf-8") as out:
    out.writelines(lines)
PY
  if [[ -s "$sums_dir/SHA256SUMS.txt" ]]; then
    gh release upload "$tag" "$sums_dir/SHA256SUMS.txt" --clobber
  fi
}

if [[ "$RUN_CI" -eq 1 && "$DRY_RUN" -eq 0 ]]; then
  echo "==> Running local CI before release..."
  "$ROOT_DIR/scripts/local-ci.sh"
fi

if [[ "$DO_BUMP" -eq 1 ]]; then
  if [[ "$DRY_RUN" -eq 0 && "$ALLOW_DIRTY" -eq 0 ]]; then
    BRANCH="$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)"
    if [[ "$BRANCH" != "main" ]]; then
      echo "--bump requires being on branch 'main' (currently on '$BRANCH')." >&2
      exit 1
    fi
    if [[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]]; then
      echo "Working tree has uncommitted changes. Commit/stash them or pass --allow-dirty." >&2
      exit 1
    fi
  fi

  if git -C "$ROOT_DIR" rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    echo "Tag ${TAG} already exists locally." >&2
    exit 1
  fi
  if git -C "$ROOT_DIR" ls-remote --tags origin "refs/tags/${TAG}" | grep -q "$TAG"; then
    echo "Tag ${TAG} already exists on origin." >&2
    exit 1
  fi

  echo "==> Version bump plan for ${TAG}:"
  perform_bump_check_and_apply "$DRY_RUN"
fi

mkdir -p "$STAGE_DIR"
NOTES_TO_USE="$NOTES_FILE"
if [[ -z "$NOTES_TO_USE" ]]; then
  generate_release_notes "$GENERATED_NOTES_FILE"
  NOTES_TO_USE="$GENERATED_NOTES_FILE"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo ""
  echo "==> [Dry Run] Release notes preview (${NOTES_TO_USE}):"
  cat "$NOTES_TO_USE"
  echo ""
  echo "==> [Dry Run] Planned actions:"
  [[ "$DO_BUMP" -eq 1 ]] && echo "  1. Commit version bump to main, tag ${TAG}, and push main + ${TAG}."
  echo "  2. Create/update GitHub release ${TAG} and dispatch release.yml (build_android=$((1 - LOCAL_ANDROID)), build_debug_apk=${BUILD_DEBUG_APK}, include_apple=false)."
  echo "  3. In parallel on this Mac, build Apple DMG/IPA (arm64_only=${ARM64_ONLY}, reuse=${REUSE_APPLE}) and upload directly to ${TAG}."
  [[ "$LOCAL_ANDROID" -eq 1 ]] && echo "  4. Build Android APKs locally and upload to ${TAG}."
  [[ "$NO_CI_TRIGGER" -eq 0 && "$WATCH_CI" -eq 1 ]] && echo "  5. Watch release.yml run with retry and finalize SHA256SUMS.txt."
  rm -rf "$STAGE_DIR" "$GENERATED_NOTES_FILE"
  exit 0
fi

if [[ "$DO_BUMP" -eq 1 ]]; then
  echo "==> Committing version bump, tagging ${TAG}, and pushing..."
  git -C "$ROOT_DIR" add "$GRADLE_FILE" "$PBXPROJ_FILE" "$CSPROJ_FILE"
  git -C "$ROOT_DIR" commit -m "chore(release): bump version to ${VERSION}"
  git -C "$ROOT_DIR" tag -a "$TAG" -m "Release ${TAG}"
  git -C "$ROOT_DIR" push origin main "$TAG"
fi

# Dispatch fast cloud Windows (+ Android) release BEFORE starting local Apple build so both run in parallel!
CI_RUN_ID=""
if [[ "$NO_CI_TRIGGER" -eq 0 ]]; then
  BUILD_ANDROID_FLAG="true"
  if [[ "$LOCAL_ANDROID" -eq 1 ]]; then
    BUILD_ANDROID_FLAG="false"
  fi
  DEBUG_APK_FLAG="true"
  if [[ "$BUILD_DEBUG_APK" -eq 0 ]]; then
    DEBUG_APK_FLAG="false"
  fi

  PREV_LATEST_RUN_ID="$(gh run list -R "$(gh repo view --json nameWithOwner -q .nameWithOwner)" --workflow=release.yml --limit 1 --json databaseId -q '.[0].databaseId // ""' || true)"
  DISPATCH_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "==> [GitHub Actions] Dispatching release.yml in parallel (tag=${TAG}, build_android=${BUILD_ANDROID_FLAG}, build_debug_apk=${DEBUG_APK_FLAG}, include_apple=false)..."
  (
    cd "$ROOT_DIR"
    gh workflow run release.yml --ref main \
      -f tag="$TAG" \
      -f build_android="$BUILD_ANDROID_FLAG" \
      -f build_debug_apk="$DEBUG_APK_FLAG" \
      -f include_apple=false
  )

  # Grab newly created run ID (brief non-blocking poll up to ~15s)
  for _ in 1 2 3 4 5; do
    CANDIDATE_ID="$(cd "$ROOT_DIR" && gh run list --workflow=release.yml --limit 3 --json databaseId,event,createdAt --jq ".[] | select(.event==\"workflow_dispatch\" and .createdAt >= \"${DISPATCH_UTC}\") | .databaseId" | head -n1 || true)"
    if [[ -n "$CANDIDATE_ID" && "$CANDIDATE_ID" != "$PREV_LATEST_RUN_ID" ]]; then
      CI_RUN_ID="$CANDIDATE_ID"
      echo "==> [GitHub Actions] Running in background: https://github.com/$(cd "$ROOT_DIR" && gh repo view --json nameWithOwner -q .nameWithOwner)/actions/runs/${CI_RUN_ID}"
      break
    fi
    sleep 3
  done
fi

# Build Apple locally (parallel Catalyst + iOS via build-release.sh)
DMG_SRC="$ROOT_DIR/Musico/dist/Musico-${VERSION}-macOS.dmg"
IPA_SRC="$ROOT_DIR/Musico/dist/Musico-${VERSION}-iOS-unsigned.ipa"
if [[ "$REUSE_APPLE" -eq 1 && -f "$DMG_SRC" && -f "$IPA_SRC" ]]; then
  echo "==> [Apple] Reusing existing ${VERSION} artifacts in Musico/dist/..."
else
  echo "==> [Apple] Building ${VERSION} macOS DMG & unsigned iOS IPA locally..."
  (
    cd "$ROOT_DIR/Musico"
    chmod +x scripts/build-release.sh
    if [[ "$ARM64_ONLY" -eq 1 ]]; then
      CATALYST_ARCHS=arm64 ./scripts/build-release.sh "$VERSION"
    else
      ./scripts/build-release.sh "$VERSION"
    fi
  )
fi
cp "$DMG_SRC" "$IPA_SRC" "$STAGE_DIR/"

# Optional local Android build
if [[ "$LOCAL_ANDROID" -eq 1 ]]; then
  echo "==> [Android] Building ${VERSION} APKs locally..."
  JAVA_17="$(detect_java_home)"
  (
    cd "$ROOT_DIR/MusicoAndroid"
    export JAVA_HOME="$JAVA_17"
    chmod +x gradlew
    GRADLE_TASKS="assembleRelease"
    if [[ "$BUILD_DEBUG_APK" -eq 1 ]]; then
      GRADLE_TASKS="assembleDebug assembleRelease"
    fi
    ./gradlew $GRADLE_TASKS --parallel --build-cache --stacktrace
    if [[ "$BUILD_DEBUG_APK" -eq 1 && -f app/build/outputs/apk/debug/app-debug.apk ]]; then
      cp app/build/outputs/apk/debug/app-debug.apk "$STAGE_DIR/MusicoAndroid-${VERSION}-debug.apk"
    fi
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

shopt -s nullglob
STAGE_FILES=("$STAGE_DIR"/*)
shopt -u nullglob

echo "==> [GitHub] Uploading local assets to release ${TAG}..."
(
  cd "$ROOT_DIR"
  if gh release view "$TAG" >/dev/null 2>&1; then
    if [[ "${#STAGE_FILES[@]}" -gt 0 ]]; then
      gh release upload "$TAG" "${STAGE_FILES[@]}" --clobber
    fi
    gh release edit "$TAG" --title "Musico ${TAG}" --notes-file "$NOTES_TO_USE" --latest
  else
    gh release create "$TAG" ${STAGE_FILES[@]+"${STAGE_FILES[@]}"} \
      --title "Musico ${TAG}" \
      --notes-file "$NOTES_TO_USE" \
      --latest
  fi
)

# Wait with HTTP 5xx-resilient polling if CI was triggered and --no-watch wasn't passed
if [[ -n "$CI_RUN_ID" && "$WATCH_CI" -eq 1 ]]; then
  echo "==> [GitHub Actions] Waiting for run ${CI_RUN_ID} to finish uploading Windows/Android assets..."
  while true; do
    RUN_JSON="$(cd "$ROOT_DIR" && gh run view "$CI_RUN_ID" --json status,conclusion 2>/dev/null || true)"
    if [[ -n "$RUN_JSON" ]]; then
      STATUS="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("status",""))' "$RUN_JSON" 2>/dev/null || true)"
      CONCLUSION="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("conclusion",""))' "$RUN_JSON" 2>/dev/null || true)"
      if [[ "$STATUS" == "completed" ]]; then
        echo "==> [GitHub Actions] Run ${CI_RUN_ID} completed with conclusion=${CONCLUSION}."
        if [[ "$CONCLUSION" != "success" ]]; then
          echo "Release workflow failed! Inspect: gh run view ${CI_RUN_ID}" >&2
          exit 1
        fi
        break
      fi
    fi
    sleep 10
  done
fi

echo "==> Finalizing unified SHA256SUMS.txt on ${TAG}..."
(cd "$ROOT_DIR" && regenerate_release_checksums "$TAG" "$STAGE_DIR/checksums")

END_EPOCH="$(date +%s)"
ELAPSED="$((END_EPOCH - START_EPOCH))"
echo "==> Release ${TAG} ready in ${ELAPSED}s!"
(cd "$ROOT_DIR" && gh release view "$TAG" --json tagName,url,assets --jq '{tag: .tagName, url: .url, assets: [.assets[] | "\(.name) (\(.size) bytes)"]}')
