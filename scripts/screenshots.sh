#!/usr/bin/env bash
# Build, install, run the screenshot instrumentation, and pull the PNGs.
#
# Deliberately NOT `gradlew connectedDebugAndroidTest`. That task uninstalls both
# APKs when it finishes, and uninstalling an app deletes
# /sdcard/Android/data/<pkg>, which is where the test writes its PNGs. The tests
# pass and the output is gone before anything can collect it.
#
# Driving `am instrument` directly keeps the app installed until the pull is done.
set -u

OUT="${1:-captured}"
APP_APK="tool/build/outputs/apk/debug/tool-debug.apk"
TEST_APK="tool/build/outputs/apk/androidTest/debug/tool-debug-androidTest.apk"

echo "=== build ==="
./gradlew --console=plain :tool:assembleDebug :tool:assembleDebugAndroidTest || exit 1

for f in "$APP_APK" "$TEST_APK"; do
  [ -f "$f" ] || { echo "missing $f" >&2; find tool/build/outputs/apk -name '*.apk' >&2; exit 1; }
done

echo "=== install ==="
# -t allows an APK flagged testOnly, which debug builds are.
adb install -r -t "$APP_APK"  || exit 1
adb install -r -t "$TEST_APK" || exit 1

PKG=$(adb shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://' \
      | grep -i solitaire | grep -v '\.test$' | head -1 || true)
[ -n "$PKG" ] || { echo "app package not installed" >&2; exit 1; }
echo "app package:  $PKG"
echo "test package: ${PKG}.test"

echo "=== instrument ==="
# -w blocks until the run finishes. am instrument reports failures in its output
# rather than through the exit code, so the result is parsed below.
adb shell am instrument -w -r \
  "${PKG}.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee /tmp/instr.txt

if grep -q 'INSTRUMENTATION_STATUS: stack=\|FAILURES!!!\|Process crashed' /tmp/instr.txt; then
  echo "instrumentation reported failures" >&2
  exit 1
fi

echo "=== pull ==="
SRC="/sdcard/Android/data/$PKG/files/screenshots"
adb shell ls -l "$SRC" 2>&1 | tr -d '\r'

mkdir -p "$OUT"
adb pull "$SRC/." "$OUT/" || exit 1
ls -la "$OUT"

count=$(find "$OUT" -name '*.png' | wc -l | tr -d ' ')
echo "png count: $count"
[ "$count" -gt 0 ] || { echo "no PNGs pulled" >&2; exit 1; }
