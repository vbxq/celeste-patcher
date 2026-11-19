#!/bin/bash
# usage: shot.sh <name>  -> full png plus a 360px preview (cheap to read back)
set -euo pipefail
. "$(dirname "$0")/env.sh"
adb exec-out screencap -p > "$SCRATCH/shots/$1.png"
magick "$SCRATCH/shots/$1.png" -resize 360x "$SCRATCH/shots/small_$1.png" 2>/dev/null \
    || convert "$SCRATCH/shots/$1.png" -resize 360x "$SCRATCH/shots/small_$1.png"
echo "$SCRATCH/shots/small_$1.png"
