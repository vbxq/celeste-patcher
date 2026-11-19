#!/bin/bash
# usage: push_bundle.sh <celeste.js>  -> replaces the cached bundle (root), keeps etag.txt so the updater gets a 304 and does not overwrite it
set -euo pipefail
. "$(dirname "$0")/env.sh"
adb push "$1" /data/local/tmp/bundle.js > /dev/null
adb shell "d=/data/user/0/$APP_PKG/cache/pyoncord; [ -f \$d/etag.txt ] || { echo 'launch the app once first'; exit 1; }; \
  cp /data/local/tmp/bundle.js \$d/bundle.js && chown \$(stat -c %U:%G \$d/etag.txt) \$d/bundle.js && chmod 600 \$d/bundle.js && restorecon \$d/bundle.js; ls -la \$d"
