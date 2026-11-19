#!/bin/bash
# usage: launch.sh <tag> [seconds]  -> cold start the patched app, save logcat + screenshot, print the lines that matter
set -euo pipefail
. "$(dirname "$0")/env.sh"
adb shell am force-stop "$APP_PKG"
adb logcat -c
adb shell monkey -p "$APP_PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep "${2:-30}"
adb logcat -d > "$SCRATCH/logs/logcat_$1.txt"
"$(dirname "$0")/shot.sh" "$1"
grep -E "LSPosed-Bridge|LSPatch|FATAL EXCEPTION| Celeste |BundleUpdater|E ReactNativeJS|E ReactNative" "$SCRATCH/logs/logcat_$1.txt" \
    | grep -vE "Class not found: com.facebook.react.devsupport|Bridge method already exists|^\S+ \S+ +[0-9]+ +[0-9]+ [A-Z] [A-Za-z-]+ *: +	at " \
    | cut -c1-240 | head -"${LINES_MAX:-60}"
