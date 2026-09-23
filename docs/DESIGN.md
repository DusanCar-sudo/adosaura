# adosaura — design

The launcher is Aura OS moved onto a phone, not an Android app with a dark
theme bolted on. This file is the contract between the two.

## Where the numbers come from

Every colour is taken key-for-key from
`/usr/share/aura-os/themes/purplerain/palette` on the laptop and lives in
`theme/Palette.kt`. If a colour changes there, change it here in the same
commit.

| key | value | used for |
| --- | --- | --- |
| `bg` | `#0e0e14` | the page, the drawer, menus |
| `bgAlt` | `#14141e` | tile panes at 85% over the wallpaper |
| `border` | `#2a2a3a` | the 1px hairline on every pane |
| `fg` | `#c8cad8` | body text |
| `fgBright` | `#e6e8f2` | the clock |
| `dim` | `#5c5f77` | titles, hints, the `apps` key |
| `muted` | `#7f849c` | dates, the footer |
| `accent` | `#8b7cf6` | the current page dot, today |
| `accentBright` | `#a594f9` | today's number |
| `red` `green` `yellow` `blue` `cyan` `orange` | — | notices and state |

`/home/dusan/.config/aura-os/look` sets `panels=glass` and `panel_alpha=85`;
that is `Metrics.PANEL_ALPHA`, so panes are glass over the wallpaper exactly as
they are on the desktop. `Metrics.SCRIM_ALPHA` keeps text readable on any
wallpaper.

Everything else is flat: square corners (`Metrics.corner = 0`), hairline
borders, `gaps inner 0`, no elevation, no blur, no shadows, and
`windowAnimationStyle = @null` so nothing animates — the same rule the desktop
follows. sway draws its titles as `─ title ─`; so does every pane here.

## One font

JetBrains Mono Regular and Bold, bundled in `res/font` with the SIL OFL 1.1 in
`assets/licenses/`. `theme/Type.kt` exposes `AdosauraMono`, and every `Text` in
the launcher passes it — there is no second typeface and no icon font.

Status dots are drawn with `●` and `○` from the same family, the same language
herdr uses for agent state (`● working · ○ idle`), so page dots and agent dots
mean the same thing across both.

If you ever want the Nerd Font glyphs, swap the two files in `res/font` for
`JetBrainsMonoNLNerdFontMono-{Regular,Bold}.ttf` from
`/usr/share/fonts/TTF/` and add its licence — nothing else changes.

## No icons, anywhere

There is no app icon in the drawer, no icon on a tile, no icon in a menu. The
one exception is the launcher's own icon in Android's settings list, which the
platform requires: a flat purple pane with a cursor dot in
`res/drawable/ic_launcher_foreground.xml`. The UI itself never draws an icon.

## Tile types

| type | draws | notes |
| --- | --- | --- |
| `clock` | `HH:mm` large, the date under it | 1s tick aligned to the second |
| `calendar` | the month grid | week starts Monday, today in `accentBright` |
| `clockcal` | the clock over a compact month grid | the tall middle tile |
| `apps:<group>` | text rows from one group in `apps.conf` | scrolls |
| `stats` | battery / free storage / uptime | the numbers Aura reports |

An unknown type renders as `? type` in `red`, so a typo in `layout.conf` is
obvious rather than invisible.

## Interaction rules

Every action is reachable from a labelled text key or a labelled text menu —
never from an ambiguous gesture over a tile.

- `add` and `apps` are two dim text keys in the status strip
- drag a tile's `↘` handle to resize; one step per 48dp, clamped to weight 1–8
- long-press a tile's **title** to edit it (never its body, which holds taps)
- long-press an app row for `open / app info / hide`
- pressing Home again collapses to page 0 and closes the drawer

Edits splice one line and leave the rest of the file byte-for-byte intact:
`LayoutDocument` and `AppsDocument` blank a line rather than deleting it, so
every other line number stays valid and no comment is ever rewritten. The 52
unit tests in `app/src/test` are what back that promise.

## What each phase owns

**Phase 1 — launcher (this build).** Home screen, pages, tiles, the drawer,
the config files and their hot reload. Runs as a normal APK on stock HyperOS.
No root, no Shizuku, nothing that can destabilise the system.

**Phase 2 — the surfaces a normal app can own.**
- our own status strip (done — the launcher already draws its own)
- a lock-screen overlay: `LockOverlayActivity` with `setShowWhenLocked(true)` +
  `setTurnScreenOn(true)`, started on `ACTION_SCREEN_ON`, holding the same
  tiles at low brightness. This is the risky one on HyperOS, so it ships behind
  a fallback ladder: (A) an overlay over the keyguard, (B) an in-launcher
  `showWhenLocked` variant, (C) reported unavailable rather than silently dead.
- a `NotificationListenerService` for the strip's notification count. Degrades
  to hidden until notification access is granted.

**Phase 3 — needs root, deliberately not built.** Recents, the notification
shade and the system-wide status bar are system components; a normal APK cannot
replace them, and HyperOS even disables full-screen gestures for third-party
launchers. On a rooted phone these become a Magisk + LSPosed module. The code
leaves the seam (`SystemSkin`, a no-op today) so that module can plug in without
the launcher changing.

## Screen

Redmi Note 12 Pro 4G (`sweet` / `sweet_k6a`): 6.67" 1080×2400 AMOLED. `minSdk
30`, `targetSdk 34` — 34 rather than 35 because HyperOS here is Android 13/14
and 34 is the behaviour set that was actually verified against it.

## Speed budget

Inherited from Aura: key press to window under 100 ms, no animations. On
Android that means a single Activity, no splash, no image loading, no network,
no polling — the clock is one aligned coroutine, and every other number is
either a broadcast (`ACTION_BATTERY_CHANGED`) or read once per reload. Measure
with `adb shell am start -W dev.aura.adosaura/.LauncherActivity`.
