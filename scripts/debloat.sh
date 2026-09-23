#!/usr/bin/env bash
# adosaura — de-bloat the phone. Reversible at every step.
#
#   scripts/debloat.sh scan       rebuild docs/scan/bloat.conf from the phone
#   scripts/debloat.sh apply      drop everything marked `remove` in that file
#   scripts/debloat.sh restore    put back everything apply dropped
#   scripts/debloat.sh status     what is removed right now
#
# bloat.conf is the whole input: one line per package, and you decide by
# editing the first word.
#
#     keep   com.miui.calculator   # keep it
#     remove com.miui.msa.global   # drop it
#     force  com.miui.home         # drop it even though it is a system app
#
# Nothing is removed unless its line says `remove`, so the file is safe to
# read and safe to edit half-way through.
#
# Removal is `pm uninstall --user 0`: the APK stays in /system and `restore`
# brings it straight back with `install-existing`. Two hard limits on top of
# that, so this can never brick the phone:
#
#   1. anything under /system/priv-app, /vendor or /apex is refused outright,
#      no matter what the file says
#   2. telephony, SystemUI, Settings, storage and the package manager are on a
#      deny list that apply checks again at run time

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="${ADOSAURA_TOOLCHAIN:-$HOME/android-toolchain}"
SCAN="$ROOT/docs/scan"
CONF="$SCAN/bloat.conf"
LEDGER="$SCAN/removed.txt"

say()   { printf '\033[35m::\033[0m %s\n' "$*"; }
note()  { printf '   %s\n' "$*"; }
warn()  { printf '\033[33m  !\033[0m %s\n' "$*"; }

if ! command -v adb >/dev/null 2>&1; then
    [[ -f "$TC/env.sh" ]] && source "$TC/env.sh"
fi
command -v adb >/dev/null 2>&1 || { echo "no adb" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device connected" >&2; exit 1; }

# ---------------------------------------------------------------- safety

# Where the APK lives decides what we are allowed to touch. A user-installed
# app lives in /data/app; the OS lives everywhere else.
is_system_partition() {
    case "$1" in
    /data/app/*) return 1 ;;
    /*) return 0 ;;
    esac
    return 0
}

# Packages that WOULD take the phone down with them. Not even `force` removes
# these: no launcher should be able to brick telephony or SystemUI.
is_hard_denied() {
    case "$1" in
    dev.aura.adosaura) return 0 ;;
    android|android.*|com.android.system*|com.android.settings*|com.android.phone*|com.android.providers.*|com.android.server.*|com.android.packageinstaller*|com.android.permissioncontroller*|com.android.shell|com.android.externalstorage|com.android.vending|com.android.bluetooth*|com.android.input*) return 0 ;;
    com.miui.system|com.miui.system.*|com.miui.systemui*|com.miui.securitycore|com.miui.securitycenter|com.miui.securityadd|com.miui.daemon|com.miui.rom|com.miui.powerkeeper) return 0 ;;
    com.miui.phone|com.miui.phone.*|com.miui.mms*|com.miui.guardprovider|com.miui.fmservice|com.miui.face*) return 0 ;;
    esac
    return 1
}

# Packages that are PROBABLY safe but worth a second thought. `force` in
# bloat.conf overrides this and the partition check below.
is_denied() {
    case "$1" in
    dev.aura.adosaura) return 0 ;;      # the launcher we just built
    android|android.*) return 0 ;;
    com.android.*|com.google.android.*) return 0 ;;
    com.qualcomm.*|com.qti.*|vendor.*|org.codeaurora.*|com.wapi.*|com.wfd*|org.mipay*|org.ifaa*) return 0 ;;
    com.miui.system|com.miui.system.*|com.miui.systemui*|com.miui.system.*) return 0 ;;
    com.miui.securitycore|com.miui.securitycenter|com.miui.securityadd) return 0 ;;
    com.miui.core|com.miui.core.*|com.miui.daemon|com.miui.rom) return 0 ;;
    com.miui.powerkeeper|com.miui.settings*|com.miui.permissioncontroller*) return 0 ;;
    com.miui.guardprovider|com.miui.home|com.miui.global.packageinstaller) return 0 ;;
    com.miui.fmservice|com.miui.face|com.miui.face.*|com.miui.notification) return 0 ;;
    com.miui.phone|com.miui.phone.*|com.miui.mms*|com.miui.misync*) return 0 ;;
    com.miui.bluetooth*|com.miui.inputmethod*) return 0 ;;
    esac
    return 1
}

# ---------------------------------------------------------------- labels

label_for() {
    case "$1" in
    com.miui.msa.global)            echo "MIUI System Ads — the ad SDK, phones home" ;;
    com.miui.analytics)             echo "MIUI analytics — telemetry" ;;
    com.miui.bugreport)             echo "Bug report + log upload" ;;
    com.miui.miservice)             echo "Services & feedback (asks you to rate things)" ;;
    com.miui.cleaner)               echo "Cleaner" ;;
    com.miui.qr|com.miui.qr.apk)    echo "QR scanner" ;;
    com.miui.compass)               echo "Compass" ;;
    com.miui.aod)                   echo "Always-on display" ;;
    com.miui.touchassistant)        echo "Quick ball (floating touch helper)" ;;
    com.miui.freeform)              echo "Floating windows" ;;
    com.miui.screenrecorder)        echo "Screen recorder" ;;
    com.miui.screenshot)            echo "Screenshot editor" ;;
    com.miui.extraphoto)            echo "Extra photo modes" ;;
    com.miui.mediaeditor)           echo "Media editor" ;;
    com.miui.phrase)                echo "Text snippets" ;;
    com.miui.player)                echo "Music player" ;;
    com.miui.videoplayer)           echo "Video player" ;;
    com.miui.weather2)              echo "Weather (adosaura has a weather tile now)" ;;
    com.miui.notes)                 echo "Notes" ;;
    com.miui.calculator)            echo "Calculator" ;;
    com.miui.gallery)               echo "Gallery — the default photo viewer, careful" ;;
    com.miui.audiomonitor)          echo "Audio monitor" ;;
    com.miui.backup)                echo "Local backup" ;;
    com.miui.cloudbackup)           echo "Mi Cloud backup" ;;
    com.miui.cloudservice)          echo "Mi Cloud" ;;
    com.miui.micloudsync)           echo "Mi Cloud sync" ;;
    com.miui.mishare.connectivity)  echo "Mi Share" ;;
    com.miui.misound)               echo "Mi Sound (audio effects)" ;;
    com.miui.miwallpaper*)          echo "Live wallpapers" ;;
    com.miui.wallpaper*)            echo "Wallpaper overlay" ;;
    com.miui.miinput)               echo "Mi Input (extra input handling)" ;;
    com.miui.globalminusscreen)     echo "App vault / the minus-1 screen" ;;
    com.miui.stk*)                  echo "SIM toolkit" ;;
    com.milink.service)             echo "Mi Link (screen cast)" ;;
    com.mi.globallayout)            echo "Global layout service" ;;
    com.mi.android.global*)         echo "Xiaomi global app" ;;
    com.miui.*)                     echo "MIUI service" ;;
    com.mi.*)                       echo "Xiaomi service" ;;

    com.facebook.appmanager)        echo "Facebook App Manager — resident, no icon" ;;
    com.facebook.services)          echo "Facebook Services — resident, no icon" ;;
    com.facebook.system)            echo "Facebook System — resident, no icon" ;;
    com.facebook.stella)            echo "Facebook Stella — resident, no icon" ;;
    com.facebook.*)                 echo "Facebook component" ;;

    com.amazon.appmanager)          echo "Amazon App Manager (preloaded)" ;;
    android.autoinstalls.config.*)  echo "auto-install config — pushes app suggestions" ;;

    com.ss.android.ugc.trill)       echo "TikTok" ;;
    com.zing.zalo)                  echo "Zalo" ;;
    jp.naver.line.android)          echo "LINE" ;;
    com.viber.voip)                 echo "Viber" ;;
    com.linkedin.android)           echo "LinkedIn" ;;
    com.Slack)                      echo "Slack" ;;
    com.whatsapp)                   echo "WhatsApp" ;;
    org.telegram.messenger)         echo "Telegram" ;;
    org.videolan.vlc)               echo "VLC" ;;
    com.niksoftware.snapseed)       echo "Snapseed" ;;
    com.canva.editor)               echo "Canva" ;;
    com.termux*)                    echo "Termux" ;;
    com.tailscale.ipn)              echo "Tailscale" ;;
    com.openai.chatgpt)             echo "ChatGPT" ;;
    ai.x.grok)                      echo "Grok" ;;
    ai.qwenlm.chat.android)         echo "Qwen" ;;
    com.minimax.ai)                 echo "MiniMax" ;;
    ai.mainfunc.genspark)           echo "Genspark" ;;
    com.abacusai.chatllm)           echo "ChatLLM" ;;
    kling.ai.video.chat)            echo "Kling" ;;
    ai.toloka.expert)               echo "Toloka" ;;
    ai.offgridmobile)               echo "Offgrid Mobile" ;;
    io.aura.pulse.mobile)           echo "Aura Pulse — yours" ;;
    com.jndapp.*|com.appslab.*|com.sparkine.*|slide.widgetFrenzy) echo "widget pack" ;;
    com.microsoft.*)                echo "Microsoft app" ;;
    com.shopee.*|com.grabtaxi.*|com.g2a.*|com.payoneer.*|com.payhip.*) echo "shopping / payments" ;;
    *)                              echo "" ;;
    esac
}

# `remove` is preset only where the thing has no function at all.
default_state() {
    case "$1" in
    com.miui.msa.global|com.miui.analytics|com.miui.bugreport) echo "remove" ;;
    com.facebook.appmanager|com.facebook.services|com.facebook.system|com.facebook.stella) echo "remove" ;;
    android.autoinstalls.config.*|com.amazon.appmanager) echo "remove" ;;
    *) echo "keep" ;;
    esac
}

# Which heading a package belongs under. A/B/C preset to remove, D is real
# MIUI apps, E is everything the user installed.
group_of() {
    case "$1" in
    com.miui.msa.global|com.miui.analytics|com.miui.bugreport) echo "A" ;;
    com.facebook.appmanager|com.facebook.services|com.facebook.system|com.facebook.stella) echo "B" ;;
    android.autoinstalls.config.*|com.amazon.appmanager) echo "C" ;;
    com.miui.*|com.mi.*|com.milink.service) echo "D" ;;
    *) echo "E" ;;
    esac
}

# ---------------------------------------------------------------- scan

cmd_scan() {
    mkdir -p "$SCAN"
    say "reading the phone"

    # "path.apk=com.foo"  ->  "com.foo<TAB>path.apk"
    adb shell pm list packages -f \
        | sed 's/^package://' \
        | awk -F= '{ path=$1; for (i=2; i<NF; i++) path=path "=" $i; print $NF "\t" path }' \
        | sort > "$SCAN/raw-packages.tsv"

    adb shell pm list packages -3 | sed 's/^package://' | sort > "$SCAN/raw-user.txt"
    adb shell pm list packages -d | sed 's/^package://' | sort > "$SCAN/raw-disabled.txt"

    # a baseline, so "fast and young" is measured rather than felt.
    # grep runs here on the laptop: a `|` passed to the device shell is
    # interpreted by /system/bin/sh and the command fails.
    {
        echo "captured: $(date -Iseconds)"
        echo "-- storage --";   adb shell df -h /data | tail -1
        echo "-- ram --";       adb shell cat /proc/meminfo | grep -E 'MemTotal|MemAvailable'
        echo "-- processes --"; adb shell 'ps -A' | wc -l
        echo "-- packages --"
        echo "total:    $(adb shell pm list packages | wc -l)"
        echo "user:     $(adb shell pm list packages -3 | wc -l)"
        echo "disabled: $(adb shell pm list packages -d | wc -l)"
    } > "$SCAN/baseline.txt"
    say "baseline -> docs/scan/baseline.txt"

    {
        cat <<'EOF'
# adosaura — what is on this phone, and what to do about it.
#
# Change the FIRST word of a line to decide:
#
#     keep   com.miui.calculator   # keep it
#     remove com.miui.msa.global   # drop it
#
# `remove` runs `pm uninstall --user 0`. The APK stays in /system, so
#     scripts/debloat.sh restore
# puts every one of them straight back. Nothing here can brick the phone:
# anything under /system/priv-app, /vendor or /apex is refused at apply time
# regardless of what this file says.
#
# Lines already marked `remove` are the ones with no function at all — ad SDKs,
# telemetry, and the four Facebook services that sit resident in RAM with no
# icon anywhere. Everything else is `keep` and waits for you.
#
# When you are done marking:
#     scripts/debloat.sh apply
EOF

        emit() {
            local grp="$1" title="$2" blurb="$3"
            printf '\n# ===== %s =====\n' "$title"
            printf '# %s\n' "$blurb"
            local pkg path desc any=0
            while IFS=$'\t' read -r pkg path; do
                [[ -z "$pkg" ]] && continue
                if is_denied "$pkg" || is_system_partition "$path"; then continue; fi
                [[ "$(group_of "$pkg")" == "$grp" ]] || continue
                desc="$(label_for "$pkg")"
                printf '%-7s %-44s # %s\n' "$(default_state "$pkg")" "$pkg" "$desc"
                any=1
            done < "$SCAN/raw-packages.tsv"
            if [[ $any -eq 0 ]]; then printf '# (nothing here)\n'; fi
        }

        emit A "A. MIUI ads and telemetry"  "No UI and no purpose beyond profiling you. Preset to remove."
        emit B "B. Facebook resident services" "Run all day with no app attached. Preset to remove."
        emit C "C. Preloaded vendor junk"   "Suggestion pushers and preloads. Preset to remove."
        emit D "D. MIUI apps"               "Real apps. Keep the ones you use."
        emit E "E. Your apps"               "You installed these. You decide."

        printf '\n# ===== F. Refused: OS components =====\n'
        printf '# These live in /system/priv-app, /vendor or /apex and apply will not\n'
        printf '# touch them even if you write remove. Listed so you can see the shape\n'
        printf '# of the system.\n'
    } > "$CONF"

    say "written -> docs/scan/bloat.conf"
    note "flip keep -> remove for anything you want gone, then:"
    note "  scripts/debloat.sh apply"
}

# ---------------------------------------------------------------- apply

refused() {
    local pkg="$1" path="$2" state="${3:-remove}"
    if is_hard_denied "$pkg"; then warn "refused (would brick the phone): $pkg"; return 0; fi
    [[ "$state" == "force" ]] && return 1
    if is_denied "$pkg"; then warn "refused (protected): $pkg  —  write 'force' to override"; return 0; fi
    if is_system_partition "$path"; then warn "refused (OS partition): $pkg  —  write 'force' to override"; return 0; fi
    return 1
}

cmd_apply() {
    [[ -f "$CONF" ]] || { warn "no docs/scan/bloat.conf — run: scripts/debloat.sh scan"; exit 1; }

    mapfile -t todo < <(awk '/^(remove|force) /{print $1" "$2}' "$CONF")

    if [[ ${#todo[@]} -eq 0 ]]; then
        say "nothing marked 'remove' or 'force' in docs/scan/bloat.conf"
        return 0
    fi

    say "removing ${#todo[@]} package(s)"
    : >> "$LEDGER"
    local gone=0 pkg path state line
    for line in "${todo[@]}"; do
        state="${line%% *}"; pkg="${line##* }"
        path="$(awk -F'\t' -v p="$pkg" '$1==p{print $2}' "$SCAN/raw-packages.tsv")"
        if refused "$pkg" "${path:-/system}" "$state"; then continue; fi

        if adb shell pm uninstall --user 0 "$pkg" >/dev/null 2>&1; then
            printf '%s\n' "$pkg" >> "$LEDGER"
            note "gone    $pkg   # $(label_for "$pkg")"
            gone=$((gone + 1))
        else
            warn "could not remove $pkg (protected by the OS, or already gone)"
        fi
    done
    say "removed $gone. undo with:  scripts/debloat.sh restore"
}

# ---------------------------------------------------------------- rest

cmd_restore() {
    if [[ ! -s "$LEDGER" ]]; then say "nothing to restore"; return 0; fi
    local pkg n=0
    while read -r pkg; do
        [[ -z "$pkg" ]] && continue
        if adb shell cmd package install-existing "$pkg" >/dev/null 2>&1; then
            note "back    $pkg"
            n=$((n + 1))
        else
            warn "could not restore $pkg"
        fi
    done < "$LEDGER"
    say "restored $n package(s)"
    : > "$LEDGER"
}

cmd_status() {
    say "packages removed for user 0"
    adb shell pm list packages -u | sed 's/^package://' | sort -u > /tmp/ada-all.txt
    adb shell pm list packages   | sed 's/^package://' | sort -u > /tmp/ada-live.txt
    local pkg n=0
    while read -r pkg; do
        [[ -z "$pkg" ]] && continue
        note "$pkg   # $(label_for "$pkg")"
        n=$((n + 1))
    done < <(comm -23 /tmp/ada-all.txt /tmp/ada-live.txt)
    if [[ $n -eq 0 ]]; then note "(nothing removed)"; fi
}

case "${1:-scan}" in
scan)    cmd_scan ;;
apply)   cmd_apply ;;
restore) cmd_restore ;;
status)  cmd_status ;;
*) sed -n '3,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
