#!/bin/bash
cd /sdcard/Download/MA/MASS
export ANDROID_HOME=/opt/android-sdk
# versionCode 迭代（1786625881 → 1786625882）
sh gradlew assembleDebug -PversionCode=1786625882 -PskipNativeBuild -Pandroid.aapt2DaemonMode=false --no-daemon --no-configuration-cache --console=plain 2>&1 | tail -8
# 复制为带版本号的名字（跟随 versionName）
V=$(aapt dump badging app/build/outputs/apk/debug/app-debug.apk 2>/dev/null | grep -o "versionName='[^']*'" | head -1 | cut -d"'" -f2)
cp app/build/outputs/apk/debug/app-debug.apk "MAS_${V}.apk"
ls -lh "MAS_${V}.apk"
echo BUILD_AND_COPY_DONE