#!/usr/bin/env bash
# Screenshot walk of the five tabs, run against a booted emulator or a phone
# with adb. Used on the dev VM: CI proves the APK builds, this proves the
# screens actually draw (a compile is not a run).
#
#   adb install -r -t app/app/build/outputs/apk/debug/*.apk
#   bash scripts/emulator-shots.sh [outdir]
#
# Needs uiautomator (bundled) and python3 for the XML parse.
set -uo pipefail

PKG=dev.zunvein.pocketglm
OUT=${1:-/tmp/shots}
mkdir -p "$OUT"

dump() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null
}

# Tap the centre of the first node whose text or content-desc matches exactly.
tap_text() {
  local want="$1" b
  b=$(dump | python3 -c '
import re, sys
want = sys.argv[1]
for node in re.findall(r"<node[^>]*>", sys.stdin.read()):
    t = re.search(r"text=\"([^\"]*)\"", node)
    d = re.search(r"content-desc=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if not b:
        continue
    if (t and t.group(1) == want) or (d and d.group(1) == want):
        x1, y1, x2, y2 = (int(v) for v in b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$want")
  if [ -z "$b" ]; then echo "no node: $want"; return 1; fi
  adb shell input tap $b
}

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "$1.png $(stat -c%s "$OUT/$1.png") bytes"
}

adb shell pm clear $PKG > /dev/null
adb shell am start -n $PKG/.MainActivity > /dev/null
sleep 8
shot 0-setup
tap_text "Continue as guest" && sleep 5

for tab in Home Stats Accounts Chat Settings; do
  tap_text "$tab" && sleep 4
  shot "tab-$tab"
done

# Settings is the only long page: two more frames after the first screen.
adb shell input swipe 540 1500 540 600 300 && sleep 3 && shot tab-Settings-2
adb shell input swipe 540 1500 540 600 300 && sleep 3 && shot tab-Settings-3

# A crash would be written here by CrashLog; empty output means none.
adb shell run-as $PKG cat files/bridge/crash-last.log 2>/dev/null | head -20
