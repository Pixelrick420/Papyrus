#!/usr/bin/env bash
# Runs the instrumented tests on the emulator android-emulator-runner has already booted.
#
# Failing TESTS must not fail this step: evaluate-emulator-tests.py decides whether the suite as a whole
# passes. A run that never produced results (build error, install failure, no device) still fails here,
# so the step that went red is the one that explains why.
#
# Leaves in app/build/emulator-ci/:  gradle.log, gradle-exit-code, logcat.txt
set -uo pipefail

ci_dir=app/build/emulator-ci
results_dir=app/build/outputs/androidTest-results

adb="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools/adb"
[ -x "$adb" ] || adb=adb

mkdir -p "$ci_dir"
# Results from an earlier run would otherwise be graded as this one's.
rm -rf "$results_dir"

# The default log buffer wraps within a couple of minutes of this suite, losing exactly the lines
# (crashes, ANRs, RetryRule's FLAKY reports) the artifact is uploaded for.
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
