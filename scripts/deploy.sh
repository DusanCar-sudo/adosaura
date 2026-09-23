#!/usr/bin/env bash
# adosaura — build, test, and install onto a connected phone.
#
#   scripts/deploy.sh
#
# Needs the toolchain from scripts/bootstrap-toolchain.sh and a phone with USB
# debugging (or wireless debugging) turned on.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="${ADOSAURA_TOOLCHAIN:-$HOME/android-toolchain}"
PKG="dev.aura.adosaura"

# Release by default: R8-trimmed, ~2 MB instead of ~23 MB, and a shorter cold
# start because there is far less class data to verify. `--debug` installs the
# untrimmed build while iterating.
VARIANT=release
[[ "${1:-}" == "--debug" ]] && VARIANT=debug

APK="$ROOT/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
GRADLE_TASK=":app:assemble${VARIANT^}"

say() { printf '\033[35m::\033[0m %s\n' "$*"; }

[[ -f "$TC/env.sh" ]] || {
    echo "no toolchain at $TC" >&2
    echo "run:  scripts/bootstrap-toolchain.sh" >&2
    exit 1
}
# shellcheck disable=SC1091
source "$TC/env.sh"

cd "$ROOT"

say "build ($VARIANT) + unit tests"
./gradlew :app:testDebugUnitTest "$GRADLE_TASK" --console=plain

echo
say "devices"
adb devices -l
echo

STATE="$(adb get-state 2>/dev/null || echo none)"
if [[ "$STATE" == "unauthorized" ]]; then
    cat <<'EOF'
The phone is plugged in but has not authorised this laptop.

  Unlock the phone and tap "Allow USB debugging" on the dialog.

  (If no dialog appeared: unplug and replug, or turn USB debugging off and on.)
EOF
    exit 1
fi

if [[ "$STATE" != "device" ]]; then
    cat <<'EOF'
No device. On the phone:

  Settings > About phone
    tap "OS version" seven times to unlock Developer options
  Settings > Additional settings > Developer options
    turn on USB debugging        (then plug the cable in and allow this PC)
    or turn on Wireless debugging (then run the pair/connect commands below)

  USB:      scripts/deploy.sh
  wireless: adb pair <phone-ip>:<shown-port>
            adb connect <phone-ip>:<port>
            scripts/deploy.sh

The laptop is on 172.16.10.76 — the phone has to be on the same network.
EOF
    exit 1
fi

say "installing $(basename "$APK")"
adb install -r "$APK"

"$ROOT/scripts/adb-grants.sh"

cat <<EOF

done. Press Home and choose adosaura as the launcher.

  cold start:  adb shell am start -W $PKG/.LauncherActivity
  the config:  adb shell cat /sdcard/adosaura/layout.conf
  push an edit: adb push layout.conf /sdcard/adosaura/layout.conf
EOF
