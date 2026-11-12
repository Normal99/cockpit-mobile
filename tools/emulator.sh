#!/usr/bin/env bash
# Headless Android emulator for trying the app on a machine without a display, then install and start the debug build.
#   tools/emulator.sh            boot (if needed), install, launch
#   tools/emulator.sh shot NAME  save a screenshot to ~/.cache/shots/NAME.png
#   tools/emulator.sh stop       shut the emulator down
# SDK and AVD live under ~/.cache (ANDROID_HOME / ANDROID_AVD_HOME override that).
#
# -gpu lavapipe: the emulator's default software GPU (SwiftShader) segfaults in its JIT on some older CPUs
# (seen on an i5-3470, also with -gpu guest). Lavapipe (Mesa) works and boots in about 40 s.
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.cache/android-sdk}
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.cache/avd}
export PATH=$PATH:$ANDROID_HOME/platform-tools
AVD=cm
IMAGE="system-images;android-35;google_apis;x86_64"
cd "$(dirname "$0")/.."

case ${1:-run} in
  shot)
    mkdir -p ~/.cache/shots
    adb exec-out screencap -p > ~/.cache/shots/"${2:-emulator}".png
    echo ~/.cache/shots/"${2:-emulator}".png
    exit 0 ;;
  stop)
    adb emu kill || true
    exit 0 ;;
esac

if ! adb get-state >/dev/null 2>&1; then
  if [ ! -f "$ANDROID_AVD_HOME/$AVD.ini" ]; then
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install emulator platform-tools "$IMAGE" >/dev/null
    mkdir -p "$ANDROID_AVD_HOME"
    echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_4 --force >/dev/null
  fi
  setsid "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -gpu lavapipe -no-snapshot \
    -no-boot-anim -memory 3072 -cores 3 > ~/.cache/emulator.log 2>&1 &
  echo "booting…"
  for _ in $(seq 1 60); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep 5
  done
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { echo "emulator didn't boot; see ~/.cache/emulator.log" >&2; exit 1; }
fi

./gradlew -q assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb shell pm grant dev.normal99.cockpit android.permission.POST_NOTIFICATIONS || true
adb shell am start -n dev.normal99.cockpit/.MainActivity >/dev/null
echo "running; tools/emulator.sh shot NAME for a screenshot"
