#!/usr/bin/env bash
# Pull the PNGs the screenshot test wrote on the device into ./captured.
#
# Lives in a file because android-emulator-runner runs each LINE of its
# `script:` input as a separate `sh -c`: multi-line constructs break and shell
# variables do not survive between lines.
#
# Deliberately no `set -e` or `pipefail` around the discovery step. A grep that
# matches nothing returns 1, which under `set -eo pipefail` aborts the script
# before it can say why, and a silent exit 1 is the worst possible failure here.
set -u

OUT="${1:-captured}"

echo "--- packages containing light or solitaire ---"
adb shell pm list packages 2>&1 | tr -d '\r' | sed 's/^package://' \
  | grep -iE 'light|solitaire' || echo "(none matched)"

PKG=$(adb shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://' \
      | grep -i solitaire | grep -v '\.test$' | head -1 || true)
echo "chosen package: '${PKG}'"

FOUND=""
if [ -n "$PKG" ]; then
  SRC="/sdcard/Android/data/$PKG/files/screenshots"
  echo "--- listing $SRC ---"
  if adb shell ls "$SRC" 2>&1 | tr -d '\r' | grep -q '\.png'; then
    FOUND="$SRC"
  fi
fi

# Fallback: the app-specific directory depends on applicationId and on which
# Android user owns it. Just look for the files.
if [ -z "$FOUND" ]; then
  echo "--- searching /sdcard for the PNGs ---"
  HIT=$(adb shell find /sdcard -name 'cards-*.png' 2>/dev/null | tr -d '\r' | head -1 || true)
  echo "find hit: '${HIT}'"
  [ -n "$HIT" ] && FOUND=$(dirname "$HIT")
fi

if [ -z "$FOUND" ]; then
  echo "No screenshots on the device." >&2
  echo "--- /sdcard/Android/data ---" >&2
  adb shell ls /sdcard/Android/data 2>&1 | tr -d '\r' | grep -iE 'light|solitaire' >&2 || true
  exit 1
fi

echo "pulling from $FOUND"
mkdir -p "$OUT"
adb pull "$FOUND/." "$OUT/" || exit 1
ls -la "$OUT"

# Fail loudly if the pull produced nothing usable.
count=$(find "$OUT" -name '*.png' | wc -l | tr -d ' ')
echo "png count: $count"
[ "$count" -gt 0 ] || { echo "Pull produced no PNGs." >&2; exit 1; }
