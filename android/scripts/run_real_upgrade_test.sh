#!/usr/bin/env bash
set -euo pipefail

exec > >(tee "$UPGRADE_REPORT") 2>&1

OLD_APK="$(find "$OLD_WORKTREE/android/app/build/outputs/apk/debug" -type f -name '*.apk' -print -quit)"
NEW_APK="$(find android/app/build/outputs/apk/debug -type f -name '*.apk' -print -quit)"
TEST_APK="$(find android/app/build/outputs/apk/androidTest/debug -type f -name '*.apk' -print -quit)"
test -n "$OLD_APK" && test -n "$NEW_APK" && test -n "$TEST_APK"

adb install -t "$OLD_APK"
adb shell run-as com.deskcubby.app mkdir -p databases
adb shell run-as com.deskcubby.app rm -f \
  databases/deskcubby.db \
  databases/deskcubby.db-shm \
  databases/deskcubby.db-wal \
  databases/deskcubby.db-journal

adb push "$V15_DATABASE" /data/local/tmp/deskcubby-v15.db
adb shell chmod 0644 /data/local/tmp/deskcubby-v15.db
adb shell run-as com.deskcubby.app cp /data/local/tmp/deskcubby-v15.db databases/deskcubby.db
adb shell run-as com.deskcubby.app chmod 0600 databases/deskcubby.db
adb shell run-as com.deskcubby.app ls -l databases/deskcubby.db
adb shell rm -f /data/local/tmp/deskcubby-v15.db

adb install -r -t "$NEW_APK"
adb install -r -t "$TEST_APK"
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n 's/^instrumentation:\([^ ]*\) (target=com.deskcubby.app)$/\1/p' | head -n 1)"
test -n "$INSTRUMENTATION"
adb shell am instrument -w -r \
  -e class com.deskcubby.app.data.local.AppDatabaseRealUpgradeTest \
  "$INSTRUMENTATION" | tee "$RUNNER_TEMP/deskcubby-instrumentation.txt"
grep -q 'INSTRUMENTATION_CODE: -1' "$RUNNER_TEMP/deskcubby-instrumentation.txt"
! grep -q 'INSTRUMENTATION_FAILED' "$RUNNER_TEMP/deskcubby-instrumentation.txt"
