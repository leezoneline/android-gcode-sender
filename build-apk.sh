#!/usr/bin/env bash
# 构建安卓 G 代码发送器 release APK
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_HOME:=D:/android-sdk}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE="${GRADLE:-D:/gradle/gradle-8.7/bin/gradle}"

"$GRADLE" --no-daemon assembleRelease --console=plain

APK="app/build/outputs/apk/release/app-release.apk"
ls -la "$APK"
echo "产物: $(pwd)/$APK"
