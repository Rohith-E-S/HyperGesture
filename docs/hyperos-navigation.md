# HyperOS navigation

The gesture service does not depend on any of this. Everything here concerns the optional second capability: getting HyperOS to hide its three-button navigation bar.

## Current behaviour

`navigation/HyperOSNavigationIntegration.kt` fires the vendor settings intent:

```kotlin
startActivity(
    Intent("com.android.settings.NAVIGATION_MODE_SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
```

Confirmed working on a Redmi 14 running Android 16 and HyperOS OS3.0. It opens the navigation-mode screen and the user taps the option. `adb shell am start` reaches that screen by the same `ActivityManager.startActivity` path, so no privileged permission is involved.

Choosing gesture navigation there hides the bar and gains screen space. It does not give a third-party launcher working native gestures, so HyperGesture's own recognizer remains the thing that performs navigation.

## TODO: apply the overlay directly

Opening a settings window is a poor substitute for doing the thing. The state to reach is this one:

```bash
cmd overlay enable com.android.internal.systemui.navbar.gestural
```

`cmd overlay list` shows the current state.

This is blocked because the call needs `android.permission.CHANGE_OVERLAY_PACKAGES`, which is signature-level, so only the platform signer can hold it. If a route ever appears that does not require a signature permission, replacing the settings intent with a direct overlay call is the change to make.

## Shizuku recovery

HyperGesture has an explicit, off-by-default Shizuku option that maintains `Settings.Global force_fsg_nav_bar=1` and `Settings.Secure navigation_mode=2`. It runs shell commands directly instead of a Shizuku UserService, which avoids a UserService startup crash on some MediaTek builds.

### Probe first, repair only on mismatch

Non-forced triggers start with a zero-process read of the target values through the app's own `ContentResolver`; on HyperOS that includes `use_gesture_version_three`, so a gesture flag reset on its own still triggers a repair. Those reads need no permission, so a steady state costs no Shizuku call and spawns no shell. Only a mismatch reaches Shizuku, and the script re-checks the values itself before writing, so a probe that raced a repair does not write again. The script is generated from the same target list the probe verifies, so the two cannot drift. Forced triggers (enable, boot, self-update, or an ungranted exemption) skip the probe and write unconditionally, subject to the setup guard below.

The repair also disables the three-button overlay when it is active, but only on HyperOS builds. The overlay state cannot be read without a shell, so it is re-applied whenever a target mismatch or a forced run reaches the script. Shell output is drained while the command runs, so a chatty `cmd overlay list` cannot fill the pipe buffer and hang the wait. Shizuku's shell runs as user 0, which is the user these settings target, so the commands carry no explicit `--user` flag.

### The buttons stay until the safety setup is live

Before any repair, the runner checks the same facts as the setup checklist: the accessibility service must be running right now, and the Autostart and Battery steps must be marked done (Battery also counts when the app already ignores battery optimizations). If any is missing, the run records `SETUP_INCOMPLETE` and stops before spawning a shell. This matters because HyperOS can revoke accessibility across a reboot: without the guard, the forced boot repair would write gestural mode and disable the three-button overlay while the gestures are dead, leaving the phone with no way to navigate. The check runs at repair time, not when recovery is enabled, so a stale "done" from before the restart cannot cause it.

### Exemptions belong to forced runs

The app-ops grants (10008, 10021, usage stats), the doze whitelist entry and the active standby bucket stop MIUI's cleaner from killing the service. They are granted by enabling recovery, at boot, and after the app replaces itself; a settings change, launcher switch, or periodic check only needs the two writes. Because the grants cannot be read back, the app records whether a forced run reported success and, until it has, keeps retrying the forced path on every trigger. Disabling recovery clears that record, so a later enable grants them again.

### Triggers

| Trigger | Source | Notes |
| --- | --- | --- |
| Enable / disable | Recovery card | Enabling forces a run; disabling cancels the periodic work |
| App resume, manual check | `AppModel` and the recovery card | Probe only; forces while an exemption grant is still pending |
| Boot | `BOOT_COMPLETED` | Forced, but skipped unless accessibility is already running |
| Service connected | `HyperGestureAccessibilityService.onServiceConnected` | Probe only; runs once the safety setup can actually be complete |
| Settings change | ContentObserver in the accessibility service | A repair re-enters the observer with correct values, which stops the loop |
| Default home change | `ACTION_PREFERRED_ACTIVITY_CHANGED`, registered at runtime | Fires only while the accessibility service is connected |
| App installed or removed | `PACKAGE_ADDED` / `PACKAGE_REMOVED`, registered at runtime | Same requirement |
| App updated | `MY_PACKAGE_REPLACED` in the manifest | The only package broadcast Android 8+ still delivers to a manifest receiver |
| Periodic fallback | WorkManager, every 15 minutes | `KEEP`, so restarts do not reset the schedule |

Package and default-home broadcasts are deliberately absent from the manifest: since Android 8's implicit-broadcast restrictions none of them reach a manifest receiver, so an entry there would look like a trigger and never fire. They are registered when `HyperGestureAccessibilityService` connects and removed when it unbinds.

HyperOS's watchdog can reset the settings shortly after the broadcast, so a launcher or package trigger follows with one unconditional probe 1.5 seconds later. That pass is only a probe, so it costs nothing when the values stayed correct.

### Status vocabulary

- `VERIFIED` — all target values read back correctly (on HyperOS, including `use_gesture_version_three`).
- `COMMAND_FAILED` — the shell exited non-zero or timed out and the readback was still wrong.
- `NOT_VERIFIED` — the commands succeeded but the readback did not stick, usually the vendor watchdog reverting them.
- `SHIZUKU_UNAVAILABLE`, `PERMISSION_DENIED` — no repair was attempted.
- `SETUP_INCOMPLETE` — accessibility is not running, or Autostart/Battery are not done, so no repair was attempted and the navigation buttons were left alone.
- `DISABLED` — recovery is off.

The readback outranks the exit code: a timed-out script that landed both values still reports `VERIFIED`. The card shows the status, when the last check ran, and which trigger caused it. Legacy `WRITE_FAILED` records from older builds read as `COMMAND_FAILED`.

Known builds: Redmi 14, Android 16, HyperOS OS3.0 — the settings intent works, but writes were reported unavailable; compatibility must be established per device and build.

Shizuku does not remove the setup requirement: on non-rooted devices it must be started again after each reboot. Disabling recovery cancels scheduled work and does not restore or otherwise change the current values. The manual navigation settings shortcut remains available.

This maintains the navigation bar settings and the app's exemptions from MIUI's cleaner. It does **not** enable Xiaomi's native gesture engine, make native gestures work under third-party launchers, or touch accessibility configuration or gesture tuning.

## Routes already closed

| Route | Why it fails |
| --- | --- |
| `cmd overlay enable ...` from the app | `CHANGE_OVERLAY_PACKAGES` is signature-level |
| Writing `Settings.Secure` `navigation_mode` | `WRITE_SECURE_SETTINGS` is signature or privileged. Reading it is unrestricted, so the app can report the mode but never change it |
| Shizuku as an ADB-free privilege source | Its privileged server still has to be started by ADB, wireless debugging, or root; the optional recovery above only uses Shizuku when it is already available |
| Granting the permission over adb | On the target device `adb shell` holds neither `WRITE_SECURE_SETTINGS` nor `GRANT_RUNTIME_PERMISSIONS`. Xiaomi keeps both behind the Mi-account "USB debugging (Security settings)" toggle |

Community consensus through 2025 and 2026 is that no ADB-free way exists to force native gestures for a third-party launcher. Lawnchair closed its own issue on this as not planned.

## Also unfinished

Auto-clicking the toggle once the settings screen opens, using the app's own accessibility service (`findAccessibilityNodeInfosByText` with `ACTION_CLICK`). That needs only public APIs and no extra permission, but it is fragile:

- labels are localised
- view ids change between builds
- HyperOS versions drift

Worth doing only as best-effort, with a manual fallback.

HyperOS also resets the navigation mode across a reboot. `boot/BootCompletedReceiver.kt` observes that and, when recovery is on, restores the settings through Shizuku. Without Shizuku a normal APK cannot restore them.
