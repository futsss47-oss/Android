#!/usr/bin/env bash
set -euo pipefail
pkg=ru.futsss47.kordon
adb install "$RUNNER_TEMP/previous.apk"
adb shell am start -W -n "$pkg/.GameActivity"
first=$(adb shell dumpsys package "$pkg" | sed -n '/firstInstallTime=/{p;q;}' | tr -d '\r')
test -n "$first"
adb install -r "$RUNNER_TEMP/Lesnoy-Kordon.apk"
adb shell am start -W -n "$pkg/.GameActivity"
next=$(adb shell dumpsys package "$pkg" | sed -n '/firstInstallTime=/{p;q;}' | tr -d '\r')
test "$first" = "$next"
adb shell pidof "$pkg"
