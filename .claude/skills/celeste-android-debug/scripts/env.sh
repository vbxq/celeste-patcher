#!/bin/bash
# sourced by the other helpers; override any of these from the environment
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5580}"
export SCRATCH="${SCRATCH:-$HOME/.cache/celeste-android}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$SCRATCH/avd}"
export AVD_NAME="${AVD_NAME:-celeste34}"
export SYS_IMAGE="${SYS_IMAGE:-system-images;android-34;google_apis;x86_64}"
export APP_PKG="${APP_PKG:-gg.celeste.app}"
export MANAGER_PKG="${MANAGER_PKG:-gg.celeste.manager}"
export BT="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p "$SCRATCH/shots" "$SCRATCH/logs"
