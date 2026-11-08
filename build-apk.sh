#!/usr/bin/env bash
# Local build. Needs: JDK 17, Android SDK (platform 35 + build-tools), Gradle 8.9+ on PATH (or Android Studio).
set -e
[ -f gradlew ] || gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
echo
echo "APKs:"; ls app/build/outputs/apk/debug/*.apk
echo "Install: adb install -r app/build/outputs/apk/debug/app-universal-debug.apk"
