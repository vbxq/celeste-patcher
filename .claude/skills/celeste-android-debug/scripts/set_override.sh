#!/bin/bash
# usage: set_override.sh [api_base] [gateway_base]   (default: local stack on the host via 10.0.2.2)
#        set_override.sh --clear
set -euo pipefail
. "$(dirname "$0")/env.sh"
f="/data/user/0/$APP_PKG/files/pyoncord/celeste_target_override.json"
if [ "${1:-}" = "--clear" ]; then
    adb shell rm -f "$f"; echo cleared; exit 0
fi
api="${1:-http://10.0.2.2:5002}"
gw="${2:-http://10.0.2.2:5001}"
printf '{"alpha.celeste.gg":"%s","alpha-gateway.celeste.gg":"%s"}\n' "$api" "$gw" > "$SCRATCH/override.json"
adb push "$SCRATCH/override.json" /data/local/tmp/o.json > /dev/null
adb shell "d=\$(dirname $f); [ -d \$d ] || { echo 'launch the app once first'; exit 1; }; \
  cp /data/local/tmp/o.json $f && chown \$(stat -c %U:%G \$d) $f && chmod 600 $f && restorecon $f; cat $f"
echo "relaunch the app; logcat must show: W Celeste : Target override active"
