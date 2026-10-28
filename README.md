# HyperGesture

> Edge gestures for Xiaomi HyperOS, so you can keep the launcher you actually like.

HyperOS ties its gesture navigation to the stock launcher. Switch to Nova, Niagara or Lawnchair and the phone drops you back to three buttons. HyperGesture brings the gestures back with one app, without root, ADB, Shizuku or a computer. It works on any phone with Android 11 or newer.

**[Download the latest release](https://github.com/Rohith-E-S/HyperGesture/releases/latest)**

## Gestures

| Gesture | Does |
| --- | --- |
| Swipe in from the left or right edge | Back |
| Swipe up from the bottom edge | Home |
| Swipe up from the bottom and hold | Recent apps |
| Swipe right or left along the bottom edge | Previous or next app, once app switching is on |
| Touch and hold the middle of the bottom edge | Circle to Search, once it is on |

In landscape the first bottom swipe only shows a pill, and a second one within 2 seconds goes Home, as HyperOS does in games. Every distance and delay is adjustable in the app's Gesture settings.

## Install

1. Install `hypergesture.apk` from [the latest release](https://github.com/Rohith-E-S/HyperGesture/releases/latest).
2. Allow restricted settings under Settings › Apps › HyperGesture › ⋮ › **Allow restricted settings**.
3. Turn it on under Settings › Accessibility › **HyperGesture**, then open the app. Its setup checklist covers the rest.

If the gestures stop, the app's Troubleshooting screen has the fixes.

## Privacy

Gestures need an accessibility service. This one watches thin strips along the screen edges and turns a swipe into Back, Home, Recents or an app switch. Its configuration withholds window content and accessibility events, so it never reads the screen or what you type. The app goes online only for an update check that stays off until you turn it on, and then asks GitHub for the latest release. It has no ads, analytics or account.

## What it can't do

It can't switch on HyperOS's own gesture navigation, since only the system is allowed to do that. It can open the navigation settings so you can hide the three-button bar. If HyperOS resets your navigation mode, the opt-in Shizuku recovery described below can restore it when enabled and Shizuku is running with permission. [docs/hyperos-navigation.md](docs/hyperos-navigation.md) has the details, and [docs/development.md](docs/development.md) covers working on the code.

## Navigation setting recovery

HyperOS resets full-screen gestures at boot, when a third-party launcher becomes the default home, and whenever SystemUI's MIUI watchdog runs. This opt-in switch in the setup checklist undoes those resets through Shizuku. It writes `Settings.Global force_fsg_nav_bar=1` and `Settings.Secure navigation_mode=2` for the current user and user 0, reads both back, sets MIUI's gesture-version flag, and disables the three-button overlay when it is active. The same pass grants HyperGesture MIUI's autostart and battery-saver exemptions, usage stats, a doze whitelist entry and an active standby bucket, so the vendor cleaner does not kill the service that performs the checks. It leaves accessibility configuration and gesture tuning alone, and it does not turn on HyperOS's native gesture engine. HyperGesture's own gestures remain responsible for Back, Home and Recents.

Install and start [Shizuku](https://shizuku.rikka.app/download/), grant HyperGesture Shizuku permission, then turn on **Navigation setting recovery (Shizuku)**. Commands run directly through Shizuku rather than a UserService, which avoids a UserService startup crash on some MediaTek builds. Checks run when HyperGesture resumes, when Shizuku becomes available, after boot, after package or default-home changes (each followed by a second check 1.5 seconds later to catch MIUI's delayed reset), on every observed change of either setting through a ContentObserver the accessibility service registers, and from an inexact 15-minute WorkManager fallback. The card and the Troubleshooting screen report whether both values verified, Shizuku is not running, permission is missing, or a write failed. The manual **Open navigation settings** action remains available.

On a non-rooted device, Shizuku must be started again after every reboot. Setting writes are device and build dependent: the upstream investigation found Shizuku writes unavailable on a Redmi 14, so a verified result on one POCO or HyperOS build does not establish compatibility elsewhere. Turning recovery off cancels the 15-minute job and leaves the current settings unchanged.

## Contributors

- Henrique Almeida ([@h3nc4](https://github.com/h3nc4)), author and maintainer
- Victor Eduardo ([@victoresidoro](https://github.com/victoresidoro)), Circle to Search, the Control Center tile, edge to edge and wrapping labels
- Rohith-E-S ([@Rohith-E-S](https://github.com/Rohith-E-S)), Shizuku navigation setting recovery for HyperOS

Circle to Search starts the way [MiCTS](https://github.com/parallelcc/MiCTS) and [CTSLauncher](https://github.com/chenxiaolong/CTSLauncher) do. Everyone who has sent code is on [the contributors page](https://github.com/Rohith-E-S/HyperGesture/graphs/contributors).

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). Copyright (C) 2026 Henrique Almeida and the contributors above.
