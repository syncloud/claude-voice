#!/bin/sh
# Build the signed (if KEY_STORE is set) release APK (sideload/CI) + AAB (Play).
# Needs the Android SDK (run inside the SDK image / on the build host).
# Output: claude-voice-*-release.apk + claude-voice-release.aab at ../ (repo root).
set -eu
cd "$(dirname "$0")"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
./gradlew clean test assemble bundleRelease
cp app/build/outputs/apk/release/*.apk ../
cp app/build/outputs/bundle/release/*.aab ../claude-voice-release.aab
echo "built $(ls app/build/outputs/apk/release/*.apk) and ../claude-voice-release.aab"
