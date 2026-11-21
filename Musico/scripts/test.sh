#!/bin/bash
# Runs the unit tests on the iPhone 17 simulator. Full log: build/test.log
# Usage: scripts/test.sh [-only-testing:MusicoTests/SomeTests]
cd "$(dirname "$0")/.."
mkdir -p build
for attempt in 1 2 3; do
  xcodebuild -project Musico.xcodeproj -scheme Musico \
    -destination 'platform=iOS Simulator,name=iPhone 17' \
    -derivedDataPath build/DerivedData "$@" test > build/test.log 2>&1
  status=$?
  # The simulator sometimes refuses to launch the test host right after a previous run; retry.
  grep -q "failed preflight checks" build/test.log || break
  sleep 8
done
grep -E "error:|failed|Executed [0-9]+ tests|\*\* TEST" build/test.log | grep -v "^Test Suite" | sort -u | tail -40
exit $status
