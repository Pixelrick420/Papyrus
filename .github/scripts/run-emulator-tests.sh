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

# An "isn't responding" dialog takes input focus from the app under test and nothing on CI dismisses it. With dialogs
# hidden, Android kills the process that would have shown one and the launcher restarts; the setting applies at once.
"$adb" shell settings put global hide_error_dialogs 1 || true

# A dialog that came up before the setting did outlives it, but not its process, so stop that process.
anr_process="$("$adb" shell dumpsys window windows 2>/dev/null | tr -d '\r' \
  | grep -o 'Application Not Responding: [A-Za-z0-9_.:]*' | head -n 1 | cut -d' ' -f4)"
if [ -n "$anr_process" ]; then
  echo "::warning::An ANR dialog for $anr_process was already showing; stopping that process to clear it."
  "$adb" shell am force-stop "${anr_process%%:*}" || true
fi

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
