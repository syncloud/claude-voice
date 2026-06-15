#!/bin/sh
# Build the signed (if KEY_STORE is set) release APK.
# Needs the Android SDK (run inside the SDK image / on the build host).
# Output: app/build/outputs/apk/release/*.apk, copied to ../ (repo root).
set -eu
cd "$(dirname "$0")"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'
./gradlew clean test assemble
cp app/build/outputs/apk/release/*.apk ../
echo "built $(ls app/build/outputs/apk/release/*.apk)"
