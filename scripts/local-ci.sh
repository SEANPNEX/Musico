#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

RUN_WINDOWS=0
RUN_ANDROID=0
RUN_APPLE=0

if [[ "$#" -eq 0 ]]; then
  RUN_WINDOWS=1
  RUN_ANDROID=1
  RUN_APPLE=1
else
  for arg in "$@"; do
    case "$arg" in
      --windows) RUN_WINDOWS=1 ;;
      --android) RUN_ANDROID=1 ;;
      --apple)   RUN_APPLE=1 ;;
      --all)     RUN_WINDOWS=1; RUN_ANDROID=1; RUN_APPLE=1 ;;
      -h|--help)
        echo "Usage: $0 [--windows] [--android] [--apple] [--all]"
        exit 0
        ;;
      *)
        echo "Unknown option: $arg" >&2
        exit 1
        ;;
    esac
  done
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

select_iphone_simulator() {
  python3 - <<'PY'
import json, subprocess, sys
data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))
candidates = []
for runtime, devices in data.get("devices", {}).items():
    if "iOS" not in runtime:
        continue
    for d in devices:
        if d.get("isAvailable") and "iPhone" in d.get("name", ""):
            candidates.append((runtime, d["name"], d["udid"]))
if not candidates:
    sys.exit("No available iPhone simulator found")
candidates.sort(reverse=True)
print(f"Selected simulator: {candidates[0][0]} / {candidates[0][1]}", file=sys.stderr)
print(candidates[0][2])
PY
}

if [[ "$RUN_WINDOWS" -eq 1 ]]; then
  echo "==> [Windows] Running xUnit tests..."
  (
    cd "$ROOT_DIR/MusicoWindows"
    dotnet test MusicoWindows.Tests/MusicoWindows.Tests.csproj -c Release
    case "$(uname -s)" in
      MINGW*|MSYS*|CYGWIN*|Windows_NT)
        dotnet build MusicoWindows/MusicoWindows.csproj -c Release --no-restore
        ;;
      *)
        echo "==> [Windows] Skipping WPF EXE build on $(uname -s) (Microsoft.NET.Sdk.WindowsDesktop is Windows-only)."
        ;;
    esac
  )
fi

if [[ "$RUN_ANDROID" -eq 1 ]]; then
  echo "==> [Android] Running unit tests & Debug APK build..."
  JAVA_17="$(detect_java_home)"
  (
    cd "$ROOT_DIR/MusicoAndroid"
    export JAVA_HOME="$JAVA_17"
    chmod +x gradlew
    ./gradlew testDebugUnitTest assembleDebug --stacktrace
  )
fi

if [[ "$RUN_APPLE" -eq 1 ]]; then
  echo "==> [Apple] Building Mac Catalyst (Debug, arm64) & running iOS Simulator tests..."
  SIM_UDID="$(select_iphone_simulator)"
  (
    cd "$ROOT_DIR/Musico"
    xcodebuild -project Musico.xcodeproj -scheme Musico \
      -destination 'platform=macOS,variant=Mac Catalyst' \
      -configuration Debug \
      ARCHS=arm64 \
      ONLY_ACTIVE_ARCH=YES \
      CODE_SIGNING_ALLOWED=NO \
      build
    xcodebuild -project Musico.xcodeproj -scheme Musico \
      -destination "platform=iOS Simulator,id=$SIM_UDID" \
      CODE_SIGNING_ALLOWED=NO \
      test
  )
fi

echo "==> Local CI completed successfully."
