#!/usr/bin/env bash

set -uo pipefail

ci_dir=app/build/emulator-ci
results_dir=app/build/outputs/androidTest-results

adb="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools/adb"
[ -x "$adb" ] || adb=adb

mkdir -p "$ci_dir"
rm -rf "$results_dir"

"$adb" logcat -G 16M || true
"$adb" logcat -c || true

./gradlew --no-daemon connectedDebugAndroidTest 2>&1 | tee "$ci_dir/gradle.log"
status="${PIPESTATUS[0]}"
echo "$status" > "$ci_dir/gradle-exit-code"

"$adb" logcat -d -v threadtime > "$ci_dir/logcat.txt" 2>&1 || true

if [ "$status" -ne 0 ]; then
  reported="$(find "$results_dir" -name 'TEST-*.xml' 2>/dev/null | head -n 1)"
  if [ -z "$reported" ]; then
    echo "::error::Gradle exited with $status before any test reported a result"
    exit "$status"
  fi
  echo "Gradle exited with $status; results were recorded, so the gate step decides the outcome."
fi
exit 0
