# OpenBook Launcher

The desktop launcher of [OpenBook](https://github.com/Mhsbrian/OpenBook): the home screen, taskbar, All Apps and
Overview of an Android laptop. It is a fork of AOSP's Launcher3 (`platform/packages/apps/Launcher3`, Apache-2.0),
started on 2026-11-02 (ANAL-352).

**Why a fork and not a new launcher.** On an Android desktop, Launcher3's Quickstep half is more than a home screen.
It runs the taskbar, Overview, app switching and the hooks into desktop windowing, through SystemUI's and WM Shell's
private interfaces. A fork is built in the same AOSP tree as SystemUI, so those interfaces always match. Google's
own launcher binary doesn't run on OpenBook for exactly that reason.

## Branches

| Branch | What it is |
|---|---|
| `aosp/android17-release` | Upstream, untouched: the commit OpenBook's AOSP pin uses (`c612e6ec`, `android17-release`, 26Q2) |
| `main` | Upstream plus OpenBook's commits. OpenBook builds an exact commit of it |

Moving to a new AOSP drop: fetch the upstream branch, fast-forward `aosp/android17-release` (or add `aosp/<next>`),
then merge it into `main` and fix the conflicts. Keep our code in our own files and packages where possible
(`openbook/`, `com.android.launcher3.openbook.*`), so that a merge stays small.

## How OpenBook builds it

OpenBook's `aosp/patches/packages/apps/Launcher3/IMPORT` names this repository and a commit of `main`.
`scripts/aosp-patches.sh` replaces the AOSP tree's `packages/apps/Launcher3` with that commit's tree before every
build. The module names (`Launcher3QuickStep`), the package (`com.android.launcher3`) and its place in the image
(`/system_ext/priv-app`) stay as they are, so the recents role, permissions, overlays and taskbar pins keep working.

A change reaches OpenBook in two steps:
1. A pull request here, with its VM test.
2. A pull request in OpenBook that moves the `rev` in `IMPORT` to the merged commit.

## Rules

- One feature or one fix per pull request, with the [Conventional Commits](https://www.conventionalcommits.org/)
  title, as in OpenBook (`CONTRIBUTING.md` there).
- Test results, screenshots and logs go on the Multica issue, not in this repository.
- A pull request merges, squashed, when CI is green and OpenBook's Checker says Proceed. CI
  (`openbook/ci/check.py`) checks what OpenBook changed above `aosp/android17-release`: commit identities, hygiene
  (no logs, images outside `res*/`, keys, home-network addresses or big files), whitespace and XML. Launcher3 only
  builds inside an AOSP tree, so the build check is the author's lane build in OpenBook, written in the pull request.
- Nothing from Google's proprietary images, apps or design assets. Googlebook is a reference for behaviour, not a
  source of code or art.

The plan is in [`openbook/ROADMAP.md`](openbook/ROADMAP.md).
