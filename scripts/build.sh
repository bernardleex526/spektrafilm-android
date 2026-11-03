#!/usr/bin/env bash
# One-shot build of the Spektrafilm Android APK (Git Bash / Linux / macOS).
#   scripts/build.sh            -> app/build/outputs/apk/release/app-release.apk
#   scripts/build.sh install    -> also adb install onto the connected phone
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${SPEKTRAFILM_TOOLS:-$HOME/Android}"

export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLS/sdk}"
export PATH="$TOOLS/hostbin:$HOME/.cargo/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

GRADLE="$ROOT/gradlew"
[ -x "$GRADLE" ] || GRADLE="$TOOLS/gradle-8.14.3/bin/gradle"

cd "$ROOT"
"$GRADLE" --no-daemon :app:assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
echo "APK: $APK"

if [ "${1:-}" = "install" ]; then
    adb install -r "$APK"
fi
