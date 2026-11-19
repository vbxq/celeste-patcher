#!/bin/bash
# usage: emu.sh setup|start|wait|stop|delete
set -euo pipefail
. "$(dirname "$0")/env.sh"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDM="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

case "${1:-}" in
setup)
    [ -e /dev/kvm ] || { echo "no /dev/kvm, emulator would be unusably slow"; exit 1; }
    df -h / | tail -1
    { yes || true; } | "$SDKM" --install emulator platform-tools "build-tools;36.0.0" "$SYS_IMAGE" > "$SCRATCH/logs/sdkmanager.log" 2>&1
    mkdir -p "$ANDROID_AVD_HOME"
    echo no | "$AVDM" create avd -n "$AVD_NAME" -k "$SYS_IMAGE" -d pixel_6 --force
    cfg="$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini"
    sed -i 's/^hw.ramSize=.*/hw.ramSize=3072M/' "$cfg"
    sed -i 's/^disk.dataPartition.size=.*/disk.dataPartition.size=6G/' "$cfg"
    grep -E 'hw.ramSize|dataPartition.size' "$cfg"
    ;;
start)
    port="${ANDROID_SERIAL#emulator-}"
    nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-window -no-audio -gpu swiftshader_indirect \
        -no-snapshot -no-boot-anim -port "$port" -cores 4 > "$SCRATCH/logs/emulator.log" 2>&1 &
    echo "emulator pid $!"
    ;;
wait)
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done
    # adbd restarts as root, the device is briefly offline
    adb root > /dev/null 2>&1 || true; sleep 3; adb wait-for-device
    echo booted
    ;;
stop)
    adb emu kill
    ;;
delete)
    "$AVDM" delete avd -n "$AVD_NAME" || true
    rm -rf "$ANDROID_AVD_HOME"
    ;;
*)
    echo "usage: $0 setup|start|wait|stop|delete"; exit 2
    ;;
esac
