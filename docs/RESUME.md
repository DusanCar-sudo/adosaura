# RESUME — read this first

Written before a machine restart. Everything here is durable: source in the
repo, config on the phone, toolchain in `~/android-toolchain`.

## Reconnect in one step

```sh
cd "/home/dusan/android launcher adosaura"
source ~/android-toolchain/env.sh
adb devices          # phone must show `device`, not `unauthorized`
```

Toolchain survives the restart (`~/android-toolchain`, `~/.gradle`). If `adb`
cannot see the phone after reboot: replug the cable, or `adb pair` /
`adb connect` over wireless. Laptop IP is `172.16.10.76`, phone must share
that network. If the phone says `unauthorized`, unlock it and tap
**Allow USB debugging**. If install is blocked, MIUI needs
*Settings > Additional settings > Developer options > Install via USB*.

---

## What is done and verified on the phone

Phase 1 is **complete and verified end to end** on the Redmi Note 12 Pro 4G
(`sweet` / `2209116AG`, **Android 13 / HyperOS 1.0 (MIUI V816)**, 1080x2400 @
440dpi). Checks actually run against the device:

| checked | result |
| --- | --- |
| install + grants | done (`MANAGE_EXTERNAL_STORAGE`, doze exemption, default home) |
| config generation | `apps.conf` + `layout.conf` written to `/sdcard/adosaura/` |
| all 6 tiles | `─ status ─ ─ calendar ─ ─ clock ─ ─ pinned ─ ─ time ─ ─ all ─` |
| hot reload | pushed a changed `layout.conf`, `─ pinned ─` became `─ tools ─` live |
| drawer + search | `chrom` ranks `Chrome` first, then package matches |
| launch from drawer | Chrome actually launched |
| Home contract | returns to tiles, drawer closed |
| tile edit menu | `bigger / smaller / move to page 2 / add tile / remove tile` |
| crashes | none in logcat |
| cold start | **~0.63–0.9 s** (was 1.5 s before loading was moved off the main thread) |
| APK size | **2.1 MB** with R8 (was 22.9 MB) |

JVM tests: 52 passing (`:app:testDebugUnitTest`).

## Files that exist and work

- `scripts/bootstrap-toolchain.sh` — done, toolchain installed
- `scripts/deploy.sh` — builds release (R8) by default, `--debug` for untrimmed
- `scripts/adb-grants.sh` — idempotent HyperOS grants
- `scripts/debloat.sh` — **has a bug, see below**
- `app/src/main/java/dev/aura/adosaura/` — full launcher (Phase 1 + status strip)
- `README.md`, `docs/DESIGN.md`, `THIRD-PARTY-NOTICES.md`

## Phone baseline (docs/scan/baseline.txt)

```
storage    225G total, 170G used (76%)
RAM        7.5 GB total, 2.6 GB available
processes  951
packages   510 total, 166 user, 344 system, 4 disabled
```

Compare these after de-bloating to show the phone got faster.

---

## In-flight work — half built

These files were **just written and are NOT wired into anything yet**. They
compile-clean in isolation but the launcher does not use them:

- `app/src/main/assets/themes/<9 names>/palette` — all nine Aura OS themes,
  copied verbatim from `/mnt/data/aura-os/themes/`
- `config/KeyValue.kt` — `key=value` parser (shared by palette + `look`)
- `theme/Palette.kt` — **rewritten**: `ThemePalette` data class + `Palette`
  object now backed by `mutableStateOf` so a theme swap recomposes everything
  with no call-site changes
- `theme/Themes.kt` — loads a bundled theme by name

**Still needed to finish themes:** `Metrics.PANEL_ALPHA` call sites must move to
`Palette.panelAlpha` (Pane.kt x2, DrawerScreen.kt) or the compile will fail
with two `Palette` definitions.

## Edge swipes — built and tested, not yet on the phone

Full-swipe control over every app: in from the right edge = Back, in from
the left edge = Forward (back into the app you just left with an edge Back),
down along either edge = the switcher over the current app.

- `gestures/EdgeGesture.kt`: stroke → action, pure. `gestures/AppHistory.kt`: the
  forward stack, pure. Both unit-tested.
- `gestures/EdgeService.kt`: an accessibility service. It draws two 16dp
  invisible strips from 12% to 60% of the screen height, clear of the
  keyboard, and buzzes CONFIRM or REJECT.
- `switcher/SwitcherActivity.kt`: SwitcherScreen in its own task, so
  closing it returns to the app.
- Enable: `scripts/adb-grants.sh` appends it to `enabled_accessibility_services`.
  If HyperOS refuses, do it by hand: App info → ⋮ → Allow restricted settings,
  then Settings → Accessibility → adosaura edge swipes.
- **Check on crDroid:** system gesture nav takes edge swipes before our strips
  do. Use 3-button nav, or turn down the system back sensitivity.

## Queued launcher features (user asked, not built)

1. **App tile** — `app:<package>`, one app as its own tile
2. **Free resize by dragging a tile edge** — hold a side and drag, not the `↘` step handle
3. **Weather tile** — `dry 35c` / `rain 15c`, two tokens, from Open-Meteo
   (needs `INTERNET`; coords from `look`)
4. **Perf graph tile** — text sparkline `▁▂▃▄▅▆▇█` for mem / cpu-freq / temp,
   like Aura Pulse
5. **Theme picker + opacity choice** — settings UI; persist to
   `/sdcard/adosaura/theme` and `/sdcard/adosaura/look`, mirroring
   `~/.config/aura-os/theme` and `look`
6. **`pin` / `unpin` in the drawer long-press menu** — answers "how do i pin apps"
7. **apps.conf grouping** — `dev.aura.*` and utility apps (Settings, Aura Pulse)
   into `tools`

## Queued de-bloat work

- `scripts/debloat.sh` is **broken**: `adb shell grep -E 'MemTotal|MemAvailable'`
  leaks the `|` to the remote shell. Fixed version was blocked by a stale-file
  write. Fix: `adb shell cat /proc/meminfo | grep -E ...` (grep locally).
  The rewrite also replaces the fragile `PROTECTED` regex with
  `is_system_partition()` + `is_denied()` functions — a POSIX ERE cannot
  express the negative lookahead that was in there.
- Then: `./scripts/debloat.sh scan` -> `docs/scan/bloat.conf`
- User marks `keep` / `remove` per line
- `./scripts/debloat.sh apply`, verify, `./scripts/debloat.sh status`
- Restore is `./scripts/debloat.sh restore` (`pm uninstall --user 0` is
  reversible via `cmd package install-existing`)

## Phase 2 remainder

`lockskin/ScreenWatcher.kt` + `lockskin/SystemSkin.kt` are written;
`lockskin/LockOverlayActivity.kt` is **not**. Manifest has no Phase 2 entries.
Phase 3 (recents, shade, system status bar) stays deferred — needs root.

Goal `goal-88ad6145-2b84-4ae1-b831-c749615305a8` is armed, revision 1, max 40
rounds. Mark complete only when everything above is verified on the phone.
