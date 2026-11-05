#!/usr/bin/env bash
# Installs the minified release APK on the running emulator, opens the app and a PDF,
# and fails if the app crashes (catches missing R8 keep rules).
set -u
./gradlew -q :app:assembleRelease -PdebugSignedRelease
apk=app/build/outputs/apk/release/app-release.apk
adb install -r "$apk" || exit 1
adb logcat -c
adb shell am start -W -n com.inkwell.app/com.office.app.MainActivity >/dev/null
sleep 4
# Open a PDF through the system "send" path using a file the shell can read.
adb push core/src/test/resources/fixtures/form.pdf /data/local/tmp/form.pdf >/dev/null
adb shell am start -W -a android.intent.action.VIEW -t application/pdf -d file:///data/local/tmp/form.pdf -n com.inkwell.app/com.office.app.MainActivity >/dev/null
sleep 6
if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: Process: com.inkwell.app" ; then
  echo "Release build crashed"; adb logcat -d | grep -A30 "FATAL EXCEPTION" | head -60
  exit 1
fi
adb shell pidof com.inkwell.app >/dev/null || { echo "App is not running"; exit 1; }
echo "Release smoke test passed"
