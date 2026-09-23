#!/usr/bin/env bash
# adosaura — the one-time grants the launcher needs on HyperOS.
#
# Every one of these is reachable from adb, so nothing has to be tapped through
# MIUI's settings menus. Anything adb cannot do is printed as a checklist
# instead of failing silently. Safe to re-run.
#
#   scripts/adb-grants.sh

set -uo pipefail

PKG="dev.aura.adosaura"
TC="${ADOSAURA_TOOLCHAIN:-$HOME/android-toolchain}"

say() { printf '\033[35m::\033[0m %s\n' "$*"; }
hint() { printf '\033[33m  !\033[0m %s\n' "$*"; }

# A standalone script, so it brings its own adb along rather than assuming the
# calling shell already has the toolchain on PATH.
if ! command -v adb >/dev/null 2>&1; then
    if [[ -f "$TC/env.sh" ]]; then
        # shellcheck disable=SC1091
        source "$TC/env.sh"
    else
        echo "adb is not on PATH and there is no toolchain at $TC" >&2
        echo "run:  scripts/bootstrap-toolchain.sh" >&2
        exit 1
    fi
fi

adb get-state >/dev/null 2>&1 || {
    echo "no device connected" >&2
    exit 1
}

say "all files access — the launcher reads /sdcard/adosaura/*.conf"
if adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow; then
    :
else
    hint "set it by hand: Settings > Apps > adosaura > Permissions > All files access"
fi

say "battery optimisation exemption — hot reload survives doze"
adb shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 \
    || hint "set it by hand: Settings > Battery > adosaura > no restrictions"

say "autostart — HyperOS kills launchers that lack it"
if adb shell appops set "$PKG" AUTO_START allow 2>/dev/null; then
    :
else
    hint "no AUTO_START appop on this build, or set it by hand under"
    hint "Settings > Apps > adosaura > Autostart"
fi

say "default home"
if adb shell cmd package set-home-activity "$PKG/.LauncherActivity" >/dev/null 2>&1; then
    :
else
    hint "set it by hand: press Home and choose adosaura when asked"
fi

say "usage access — the app switcher reads MRU stats"
if adb shell appops set "$PKG" GET_USAGE_STATS allow 2>/dev/null; then
    :
else
    hint "set it by hand: Settings > Apps > Permissions > Usage access"
fi

say "notification access — the status strip counts notifications"
say "                          (a no-op until the Phase 2 listener lands)"
adb shell cmd notification allow_listener "$PKG/.NotifyListener" >/dev/null 2>&1 \
    || hint "set it by hand later: Settings > Notifications > Notification access"

echo
say "done"
echo
echo "One thing adb cannot fix: HyperOS disables full-screen gestures for"
echo "third-party launchers, so the phone will use 3-button navigation while"
echo "adosaura is the home screen. That is a Xiaomi limitation, not a bug."
