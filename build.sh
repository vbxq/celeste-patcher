#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE="$ROOT/release"
mkdir -p "$RELEASE"

cd "$ROOT/kettu-src"
bun install
bun run build
cp "$ROOT/kettu-src/dist/kettu.js" "$RELEASE/celeste.js"

cd "$ROOT/CelesteXposed"
chmod +x gradlew
./gradlew assembleRelease --no-daemon --console=plain
cp "$ROOT/CelesteXposed/app/build/outputs/apk/release/app-release.apk" "$RELEASE/CelesteXposed.apk"

cd "$ROOT/CelesteManager"
chmod +x gradlew
./gradlew assembleRelease --no-daemon --console=plain
cp "$ROOT/CelesteManager/app/build/outputs/apk/release/app-release.apk" "$RELEASE/CelesteManager.apk"

echo "release artifacts:"
ls -la "$RELEASE/celeste.js" "$RELEASE/CelesteManager.apk" "$RELEASE/CelesteXposed.apk"
