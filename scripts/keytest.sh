#!/usr/bin/env bash
# Installs the debug APK, launches it, sends each remote key with `adb shell input keyevent`
# and saves the FlicksTV logcat lines plus a screenshot per key.
#
# Usage: scripts/keytest.sh [serial] [out_dir]
set -euo pipefail
cd "$(dirname "$0")/.."

SERIAL=${1:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}
if [ -z "$SERIAL" ]; then
  echo "FAIL: no adb device attached" >&2
  exit 1
fi
OUT=${2:-build/keytest/$SERIAL}
PKG=com.project.flickstv
APK=app/build/outputs/apk/debug/app-debug.apk
# Override with KEYS="DPAD_RIGHT DPAD_CENTER ..." scripts/keytest.sh
read -r -a KEYS <<< "${KEYS:-DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT MEDIA_PLAY_PAUSE MEDIA_FAST_FORWARD MEDIA_REWIND DPAD_CENTER ENTER BACK}"
SETTLE=${SETTLE:-3}

adb_() { adb -s "$SERIAL" "$@"; }
log_() { adb_ logcat -d -v time -s FlicksTV:V chromium:V cr_AwContents:V; }

mkdir -p "$OUT"
[ -f "$APK" ] || { echo "FAIL: $APK missing, run ./gradlew :app:assembleDebug" >&2; exit 1; }
adb_ install -r "$APK"
adb_ shell am force-stop "$PKG"
adb_ logcat -c
adb_ shell am start -W -n "$PKG/.MainActivity"

echo "waiting up to 60s for vertex-ready..."
ready=no
for _ in $(seq 60); do
  if log_ | grep -q "PAGE player vertex-ready"; then ready=yes; break; fi
  sleep 1
done
log_ > "$OUT/00-startup.log"
adb_ exec-out screencap -p > "$OUT/00-startup.png"
if [ "$ready" = no ]; then
  echo "WARN: no vertex-ready within 60s, see $OUT/00-startup.log" >&2
fi
sleep "$SETTLE"

i=1
for key in "${KEYS[@]}"; do
  adb_ logcat -c
  adb_ shell input keyevent "KEYCODE_$key"
  sleep "$SETTLE"
  name=$(printf '%02d-%s' "$i" "$key")
  log_ > "$OUT/$name.log"
  adb_ exec-out screencap -p > "$OUT/$name.png"
  echo "== $key"
  grep -E "KEY android.*DOWN|PAGE (keydown|nav|focus|player)|BACK|LOAD|STATUS load" "$OUT/$name.log" | sed 's/^/   /' || echo "   (no FlicksTV lines)"
  i=$((i + 1))
done

echo "== resumed activity after BACK"
adb_ shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -2 || true
echo "logs and screenshots in $OUT"
