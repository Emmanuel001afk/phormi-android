#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/reports/smoke
collect_evidence() {
  set +e
  adb logcat -d -b crash > app/build/reports/smoke/crashes.txt
  adb pull /sdcard/Android/data/com.uong.phormi/files/ app/build/reports/smoke/
  adb pull /sdcard/Download/PhormiSmoke/ app/build/reports/smoke/
  return 0
}
trap collect_evidence EXIT
adb shell settings put secure show_ime_with_hard_keyboard 1
gradle :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --no-daemon --stacktrace 2>&1 | tee /tmp/phormi-smoke.log
python3 tools/summarize_tests.py
