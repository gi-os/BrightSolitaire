#!/usr/bin/env bash
# Rewrite a freshly created AVD to the Light Phone III panel.
#
# Runs as android-emulator-runner's pre-emulator-launch-script, after the AVD
# exists and before the emulator boots. avdmanager cannot express a custom
# panel, so the geometry goes straight into config.ini.
#
# 1080 x 1240 over 3.92 inches is 419.5 dpi, which is the 420 bucket, which
# gives 411 x 472 dp. That is what the tool lays out against. A different
# density silently produces a screenshot of the wrong layout.
set -euo pipefail

AVD_NAME="${AVD_NAME:-lightos-lp3}"
CFG="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
[ -f "$CFG" ] || { echo "No AVD config at $CFG" >&2; exit 1; }

set_ini() {
  local k="$1" v="$2"
  if grep -q "^${k}=" "$CFG"; then
    sed -i.bak "s|^${k}=.*|${k}=${v}|" "$CFG"
  else
    printf '%s=%s\n' "$k" "$v" >> "$CFG"
  fi
}

set_ini hw.lcd.width   1080
set_ini hw.lcd.height  1240
set_ini hw.lcd.density 420
set_ini hw.keyboard    yes
set_ini skin.name      1080x1240
set_ini skin.path      _no_skin
rm -f "${CFG}.bak"

echo "LP3 panel applied to ${AVD_NAME}:"
grep -E '^hw\.lcd\.' "$CFG"
