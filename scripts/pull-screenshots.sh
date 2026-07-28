#!/usr/bin/env bash
# Pull the PNGs the screenshot test wrote on the device into ./captured.
#
# This lives in a file because android-emulator-runner runs each LINE of its
# `script:` input as a separate `sh -c`. Multi-line constructs and shell
# variables do not survive between lines.
set -euo pipefail

OUT="${1:-captured}"

# applicationId comes from tool.id in lighttool.toml, but read it off the device
# so a rename cannot silently break the pull.
PKG=$(adb shell pm list packages \
      | tr -d '\r' \
      | sed 's/^package://' \
      | grep -i solitaire \
      | grep -v '\.test$' \
      | head -1)

if [ -z "$PKG" ]; then
  echo "Could not find the tool package on the device. Installed:" >&2
  adb shell pm list packages | tr -d '\r' | grep -i 'light\|solitaire' >&2 || true
  exit 1
fi
echo "package: $PKG"

SRC="/sdcard/Android/data/$PKG/files/screenshots"
adb shell ls "$SRC" || { echo "Nothing at $SRC" >&2; exit 1; }

mkdir -p "$OUT"
adb pull "$SRC/." "$OUT/"
ls -la "$OUT"
