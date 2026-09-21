#!/usr/bin/env bash
# deploy.sh - Unified build/install entry for CAM-P (Android-Agent).
#
# Usage: bash scripts/deploy.sh <action>
#   start     - build debug APK (old dist APK backed up to dist/clean-<ts>/), copy to dist/
#   install   - adb install -r dist/app-debug.apk
#   uninstall - adb uninstall the debug package
#   clean     - remove dist/
#   verify    - gradle dry-run of the debug build (no compile)
#
# Notes:
#   - LF line endings only (enforced via .gitattributes); CRLF breaks Git Bash.
#   - ASCII-only messages (keep it simple across shells/terminals).
set -euo pipefail

APK_NAME="app-debug.apk"
PKG_DEBUG="com.rickeal.agent.debug"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST_DIR="$ROOT/dist"

timestamp() { date +%Y%m%d-%H%M%S; }

do_start() {
  mkdir -p "$DIST_DIR"
  # Keep previous build as backup: dist/clean-<ts>/
  if [ -f "$DIST_DIR/$APK_NAME" ]; then
    bak="$DIST_DIR/clean-$(timestamp)"
    mkdir -p "$bak"
    mv "$DIST_DIR/$APK_NAME" "$bak/"
    echo "Previous APK moved to $bak"
  fi
  (cd "$ROOT" && ./gradlew :app:assembleDebug --no-daemon)
  src="$(find "$ROOT/app/build/outputs/apk/debug" -name '*.apk' | head -1)"
  cp "$src" "$DIST_DIR/$APK_NAME"
  echo "APK copied to dist/$APK_NAME"
}

do_install() {
  command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found in PATH"; exit 1; }
  [ -f "$DIST_DIR/$APK_NAME" ] || { echo "ERROR: dist/$APK_NAME not found. Run 'start' first."; exit 1; }
  adb install -r "$DIST_DIR/$APK_NAME"
}

do_uninstall() {
  command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found in PATH"; exit 1; }
  adb uninstall "$PKG_DEBUG" || echo "(not installed, skipped)"
}

do_clean() {
  rm -rf "$DIST_DIR"
  echo "dist/ removed"
}

do_verify() {
  (cd "$ROOT" && ./gradlew :app:assembleDebug --dry-run --no-daemon)
}

case "${1:-}" in
  start)     do_start ;;
  install)   do_install ;;
  uninstall) do_uninstall ;;
  clean)     do_clean ;;
  verify)    do_verify ;;
  *) echo "Usage: bash scripts/deploy.sh <start|install|uninstall|clean|verify>"; exit 1 ;;
esac
