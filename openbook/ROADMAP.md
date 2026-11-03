# OpenBook Launcher roadmap

As of 2026-11-02 (ANAL-352). The goal is a desktop launcher that feels made for a laptop: quiet by default, fast
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
| 7 | **Type to search:** typing on the empty desktop opens All Apps with the text in its search field | code | |

## M2: high design

| # | Item | Kind | Notes |
|---|---|---|---|
| 8 | **Taskbar redesign:** our own all-apps button (a pill), focused and running indicators, spacing on a 4 dp grid | code | |
| 9 | **All Apps as a desktop panel:** centred card, frosted over a sharp wallpaper, a type scale in Google Sans Flex, keyboard focus rings | code + value | Builds on `reduce_workspace_blur_usage` (OpenBook PR #158) |
| 10 | **Overview for desks:** wallpaper behind desktop tiles, blurred recents window | flag | `enable_overview_desktop_tile_wallpaper_background`, `enable_recents_window_blur`, measured on the 890M |
| 11 | **Context menus restyled:** desktop right-click (wallpaper, widgets, display, launcher settings) and taskbar icon menus (pin, new window, close all windows) | code | |
| 12 | **Motion pass:** one set of durations and curves for open, minimize, restore and panel transitions | code | |

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
