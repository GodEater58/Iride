#!/bin/bash
# Fotografa le schermate della guida sull'emulatore: ogni scena avvia Iride in modalità demo
# (extra "scene") e salva lo screenshot in docs/screenshots.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=it.goletto.iride
OUT=docs/screenshots
mkdir -p "$OUT"

adb install -r "$APK"
adb shell pm grant $PKG android.permission.NEARBY_WIFI_DEVICES || true

# barra di stato pulita (modalità demo di Android)
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 2130
demo battery -e level 84 -e plugged false
demo network -e mobile show -e datatype 5g -e level 4
demo network -e wifi hide
demo notifications -e visible false

shot() {
  local name=$1 wait=$2
  adb shell am start -S -W -n $PKG/.MainActivity --es scene "$name"
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
}

shot connect 6
shot shoot 12
shot dial 10
shot stars 24
shot interval 10
shot gallery 14
shot log 8
shot night 12

ls -la "$OUT"
