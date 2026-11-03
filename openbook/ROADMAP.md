# OpenBook Launcher roadmap

As of 2026-11-03 (ANAL-352; M2 revised in ANAL-364). The goal is a desktop launcher that feels made for a laptop: quiet by default, fast
from the keyboard, precise with a touchpad, and designed to the same standard as the rest of OpenBook. Each item is
one pull request with its VM test. Order within a milestone is the order of work. The owner reorders freely.

Legend: **code** = new code in this fork; **flag** = AOSP 17 already has the code behind a flag that OpenBook keeps off
(turned on in OpenBook's value set, tested here); **value** = a resource value (OpenBook's Launcher3 RRO or here).

## Principles

- **Desktop first.** Pointer, keyboard and windows are the main way in. Touch still works.
- **Quiet.** No education pop-ups, no icons appearing by themselves, no search box nobody asked for.
- **One motion and type system.** Google Sans Flex (OFL, already in the image), OpenBook's colour tokens, the same
  corner radii and curves everywhere.
- **Our own art and code.** Googlebook is a behaviour reference only.

## M1: desktop basics (quality of life)

| # | Item | Kind | Notes |
|---|---|---|---|
| 1 | **Click to minimize:** clicking the taskbar icon of the focused window minimizes it; clicking again restores it | code | WM Shell already has `IDesktopMode.minimizeDesktopApp`; the taskbar tap handler only restores today |
| 2 | **Meta+1…9** opens or focuses the Nth taskbar app | code | Not in AOSP 17 Launcher3 |
| 3 | **Drag a pinned app off the taskbar** to unpin it | flag + code | `enable_taskbar_drag_to_remove` (OpenBook's value set), plus a quickstep fix: on the home screen a mouse drag up off the taskbar was taken as swipe-up-to-home |
| 4 | **Middle-click** a taskbar icon to open a new window of that app | code | Only for apps that support multiple instances (`supportsMultiInstance()`, as the menu's "New window"). Other apps open or come to front; a middle click never minimizes |
| 5 | **New apps stay off the desktop** (setting, default off on desktop) | code + value | The default of "Add app icons to home screen" is `bool/pref_add_icon_to_home_default` (true, as AOSP), read by `DefaultsValueProvider` and the Settings switch; OpenBook's desktop overlay sets it to false. A choice made in Settings is kept. Icons that still go on the desktop (the setting on, or an app's own pin request) take the first page with space ("new app icons go on the first desktop page") |
| 6 | **Two-line app names** in All Apps | code + value | The default of `pref_enable_two_line_toggle` is `bool/pref_enable_two_line_toggle_default` (false, as AOSP); OpenBook's desktop overlay sets it to true. AOSP 17's `enable_twoline_toggle` is already on, and `enable_twoline_allapps` has no reader. A name longer than two lines keeps both, the second ending in "…" (AOSP fell back to one line), and All Apps cells that already fit two lines keep their height, so the desktop grid doesn't change. English only, as in AOSP |
| 7 | **Type to search:** typing on the empty desktop opens All Apps with the text in its search field | code | With the home screen focused and nothing open over it, a key that types a character (alone or with Shift) opens the taskbar button's All Apps and goes into its search field; the next keys follow, also while the taskbar's All Apps window is still taking focus. AOSP did this only inside All Apps. Hints such as the home screen's bounce don't block it. Shortcuts with Ctrl, Alt or Meta, dead keys and every other launcher state keep AOSP's behaviour. Desktop form factor only |

## M2: high design

The design is OpenBook's `docs/launcher-design.md` (ANAL-364): every token, state and hook point, a prototype and renders. Items 8 to 12 build it, in the order 8, 9, 11, 10, 12.

| # | Item | Kind | Notes |
|---|---|---|---|
| 8 | **Taskbar redesign:** our own all-apps button (a pill), focused and running indicators, spacing on a 4 dp grid | code | Glass bar, still 56 dp. AOSP 17's `RunningAppState` has no "in front" state or window count; this adds both (design §2) |
| 9 | **All Apps as a desktop panel:** centred card, frosted over a sharp wallpaper, a type scale in Google Sans Flex, keyboard focus rings | code + value | Builds on `reduce_workspace_blur_usage` (OpenBook PR #158). Replaces the taskbar's full-width bottom sheet; the blur is the panel's own, not the overlay window's (design §3) |
| 10 | **Overview for desks:** wallpaper behind desk tiles, a blurred backdrop | code | Not a flag (2026-11-03): `enable_overview_desktop_tile_wallpaper_background` has no reader, and `enable_recents_window_blur` acts only in `RecentsWindowManager`, which runs Overview on external displays only while `enable_launcher_overview_in_window` is off. Overview's own blur is off whenever `all_apps_surface` is on (`LauncherDepthController`). Our code draws the wallpaper in `DesktopTaskView` and blurs the Overview state; measured on the 890M (design §5) |
| 11 | **Context menus restyled:** desktop right-click (wallpaper, widgets, display, launcher settings) and taskbar icon menus (pin, new window, close all windows) | code | Both menus are AOSP 17's Compose popup, so one restyle reaches both (design §4) |
| 12 | **Motion pass:** one set of durations and curves for open, minimize, restore and panel transitions | code | The launcher's own animations. A window's open, minimize and restore are WM Shell's (SystemUI), so matching them is a separate OpenBook change (design §7) |

## M3: smarter, without Google services

| # | Item | Kind | Notes |
|---|---|---|---|
| 13 | **One search:** apps, Settings (SettingsIntelligence), files (MediaStore and DocumentsUI) and Quire actions | code | |
| 14 | **Suggestions** from local usage only, never sent anywhere | code | `UsageStatsManager` |
| 15 | **Desktop files and folders** on the home screen | code | |

## Tests

- Every item: a QEMU run of the G14 image with the new `Launcher3QuickStep`, with screenshots and a crash-buffer check
  posted on the issue. Behaviour items also get an adb-driven check (taps, keys, `dumpsys activity` state).
- The laptop gets the launcher through a numbered OpenBook release, never a direct push.
