#!/bin/bash
# usage: inspect_discord.sh <versionCode e.g. 354012>
# downloads the split APKs the manager would use and reports whether the hook targets CelesteXposed relies on still resolve
set -eu
. "$(dirname "$0")/env.sh"
V="$1"; D="$SCRATCH/apk$V"; mkdir -p "$D/dex"
for s in base config.x86_64; do
    [ -s "$D/$s.apk" ] || curl -sL -m 600 -o "$D/$s.apk" "https://tracker.vendetta.rocks/tracker/download/$V/$s"
done
"$BT/aapt2" dump badging "$D/base.apk" | head -1
echo "launcher activity / app component factory:"
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$D/base.apk" | grep -E 'appComponentFactory|android:name\(0x01010003\)="com.discord.main' | head -3
echo -n "hermes bytecode version: "
unzip -p "$D/base.apk" assets/index.android.bundle | head -c 12 | od -An -tu1 | awk '{print $9}'
unzip -o -q "$D/base.apk" 'classes*.dex' -d "$D/dex"
for d in "$D"/dex/*.dex; do "$BT/dexdump" "$d" 2>/dev/null > "$d.txt"; done
grep -h "Class descriptor" "$D"/dex/*.dex.txt > "$D/classes.txt"
echo "classes kept by name (MISS = obfuscated or removed):"
for c in okhttp3/HttpUrl com/discord/react_activities/ReactActivity com/discord/main/MainActivity \
         'com/facebook/react/runtime/ReactInstance$loadJSBundle$1' com/facebook/react/runtime/ReactInstance \
         com/facebook/react/bridge/CatalystInstanceImpl com/facebook/react/modules/network/NetworkingModule \
         com/facebook/react/modules/websocket/WebSocketModule com/facebook/react/bridge/Arguments \
         com/horcrux/svg/RNSVGRenderableManager com/facebook/react/modules/blob/FileReaderModule \
         com/discord/crash_reporting/CrashReporting com/discord/theme/utils/ColorUtilsKt \
         'com/facebook/react/views/text/ReactFontManager$Companion' com/discord/bridge/DCDReactNativeHost; do
    grep -qF "'L$c;'" "$D/classes.txt" && echo "  OK   $c" || echo "  MISS $c"
done
echo "ReactInstance static accessors (the bundle loader target on r8 builds):"
grep -h -A3000 "Class descriptor  : 'Lcom/facebook/react/runtime/ReactInstance;'" "$D"/dex/*.dex.txt \
    | awk 'NR>1 && /Class descriptor/{exit} {print}' | grep -E "^      (name|type)" | paste - - \
    | grep -E "Lcom/facebook/react/runtime/ReactInstance;(Landroid/content/res/AssetManager;|Ljava/lang/String;)Ljava/lang/String;\)V|loadJSBundleFrom" || true
echo "HttpUrl (obfuscated name) = class whose 9-arg ctor the redirect hooks:"
grep -h -B3 "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/util/\(Array\)\?List;Ljava/util/\(Array\)\?List;Ljava/lang/String;Ljava/lang/String;)V" "$D"/dex/*.dex.txt | head -4 || true
for d in "$D"/dex/*.dex; do
    "$BT/dexdump" -d "$d" 2>/dev/null | grep -m1 -B0 'const-string.*"unexpected host: "' > /dev/null && echo "  HttpUrl.Builder lives in $(basename "$d")"
done
rm -f "$D"/dex/*.dex.txt
