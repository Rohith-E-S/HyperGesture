# Development

Everything a contributor needs. For what the app is and how to use it, see the [README](../README.md).

## Stack

- **Language.** Kotlin, JDK 21
- **Build.** Gradle with the Android Gradle Plugin; the wrapper pins Gradle to 9.8.2
- **Gestures.** `AccessibilityService` plus `TYPE_ACCESSIBILITY_OVERLAY` edge strips, which don't need the "draw over other apps" permission
- **UI.** Jetpack Compose, Material 3
- **Persistence.** DataStore Preferences, no database
- **Quality.** JUnit over a device-free recognizer, Android lint, shellcheck, and an emulator-driven gesture suite

Versions are pinned in `gradle/libs.versions.toml`, and Renovate raises the bumps in grouped pull requests. `minSdk` is 30 because the edge overlays need `WindowMetrics` insets and `setSystemGestureExclusionRects`. The emulator runs API 36 rather than matching `compileSdk`, because no API 37 `google_apis` x86_64 system image is published yet.

## Repository layout

```text
app/
  src/main/kotlin/com/h3nc4/hypergesture/
    gesture/      Pure, Android-free recognition (unit tested)
    service/      AccessibilityService, edge overlays, global actions
    navigation/   NavigationIntegration + HyperOS/generic implementations
    settings/     DataStore-backed configuration
    diagnostics/  Local-only device/state snapshot
    boot/         BOOT_COMPLETED observer
    setup/        Control Center tile and the setup checklist (unit tested)
    update/       Opt-in update check, the only network code
    ui/           MainActivity with its back stack, one file per screen, theme
  src/test/kotlin/            Recognizer tests (plain JVM, no device)
scripts/          Release build, version and hook helpers
docs/             This file and the platform investigations
.github/          CI workflows
```

## Toolchain

Builds run on the host. You need:

- JDK 21, through `JAVA_HOME` or `java` on `PATH`
- The Android SDK with platform 37 and build-tools 37.0.0, found through `ANDROID_HOME` or the `sdk.dir` line Android Studio writes into `local.properties`
- Nothing else: `./gradlew` downloads the Gradle version the wrapper pins

Android Studio is convenient but not required. The emulator suites also need `/dev/kvm` and an AVD named `hypergesture`, which `avdmanager create avd -n hypergesture -k "system-images;android-36;google_apis;x86_64" -d pixel_7` creates once the system image is installed.

## Commands

Gradle is the only task runner for the app.

```sh
./gradlew assembleDebug   # build
./gradlew test            # unit tests (GestureTracker decision logic)
./gradlew lint            # Android lint
./gradlew jacocoTestReport
shellcheck -o all scripts/*.sh scripts/hooks/*
./e2e/run.sh              # gesture tests against a headless emulator
./scripts/build-apk.sh    # signed release APK in the repo root
```

## Screens

Home shows the status and the rows that lead to every other screen, each one level deep under a Material 3 top app bar with a back arrow. Its Features group holds App switching, Circle to Search and Updates, each a split row whose label opens the screen and whose switch turns the feature on or off in place. About holds the version, the links and the credits. The back stack is a list of `Screen` values in `MainActivity`, saved with `rememberSaveable` and popped by `BackHandler`, without a navigation library. Navigation 3 is the upgrade if a screen ever needs a second level or a deep link. `AppModel` holds what the screens show and reads it again on every resume.

## Testing

Two layers, because they catch different things.

**Unit tests** (`./gradlew test`) cover `GestureTracker`, the pure decision logic. They do not need a device or Robolectric, because `GestureTracker` has no `android.*` imports at all. The hold timer is driven by calling `onHoldElapsed()` directly, so no clock is involved. `LandscapeConfirm`, which decides whether a landscape bottom swipe goes ahead or shows the pill first, takes each timestamp as an argument for the same reason.

**End-to-end** (`./e2e/run.sh`) drives a headless emulator through the whole path:

- boots the emulator and installs the APK
- enables the accessibility service
- injects real swipes with `adb shell input`
- rotates the display to landscape for the pill, and back to portrait afterwards

It then asserts on two independent signals: logcat (did *our* service decide correctly) and `dumpsys` (did the system state actually change). It needs `/dev/kvm`, an AVD named `hypergesture`, and the debug APK from `./gradlew assembleDebug`:

```sh
./e2e/run.sh
```

Suite names after the flags run only those suites, such as `./e2e/run.sh navigation back-revert`, and with none it runs all six. CI builds the debug APK once and splits the suites across three jobs, each booting its own emulator, and the `Gesture end-to-end` check passes only when all three do. `-k` leaves the emulator running for interactive poking. `-p hypergesture.apk` runs the suite against a release build. Run it before every release, because R8 obfuscation and shrinking can break things no JVM test sees. It has already caught one such bug: log and diagnostics strings built from `::class.simpleName` came out as `a`/`b`/`c` once minified, so `GestureAction` has an explicit `id`. R8 also strips every `Log.d` and `Log.v` call from a release build, so a line the suite greps for logs at info or above.

A release build ignores the debug overrides that `e2e/device.sh` writes, so for a release APK the script turns app switching on through the app's own toggle, the way a user would, and the app-switch suite runs in full. That keeps an R8 break in the toggle, its DataStore write or the recognizer visible as a failing case, where a skip would hide all three. The cost is a dependency on the screen. The app turns its Compose test tags into resource ids. The script finds the switch by its `switch_app_switch` tag in a `uiautomator` dump and taps it. A layout that drops the tag fails the run before any gesture is tried. Every suite selects rows by tag through `find_id`, `tap_id`, `text_of_id` and `set_toggle_id` in `e2e/lib`, with the tags in `ui/TestTags.kt`. Moving a row or rewording its label leaves the suites working. The Recents hold also keeps its 100 ms default, since its override is debug-only as well. Installing over a build signed with another key uninstalls it first, so the release and debug runs can share one emulator, and on a phone that wipes the installed copy and its settings. The app opens on whichever screen was last showing, so `open_home` taps the back arrow until home, and `open_screen <row tag>` goes from home to a nested screen.

The update check has JVM tests of its own. A saved Releases API response in `app/src/test/resources`, captured from the real endpoint, feeds the parser, and fakes of the network, store and installer interfaces drive the controller through each outcome.

Every bug found on a real device so far was in the Android integration, not the recognizer:

- overlay windows not receiving touches
- strips left permanently inert after a gesture fired
- coordinates measured against the wrong display

None of that is reachable from a JVM test.

## Releasing

Signing material lives outside git:

- `android.keystore` at the repository root
- `KEYSTORE_PASSWORD` in `.env`, gitignored and sourced by `scripts/build-apk.sh`

```sh
./scripts/build-apk.sh 1.0.0   # produces ./hypergesture.apk
```

The script sources `.env`, exports the keystore settings, stamps `versionName` from its argument, and runs `./gradlew assembleRelease`. With either piece missing, the release build degrades to unsigned rather than failing, so CI can still verify that `assembleRelease` compiles.

**Back up the keystore somewhere off this machine.** Android identifies an app by its signing certificate, so an update signed with a different key is refused by every device that already has HyperGesture on it, and the only recovery is a new package name. `android.keystore` and `.env` are gitignored and must never be committed, which also means git is not a backup: keep the key and its password in a password manager, `base64` the keystore if the manager only takes text.

A push to `main` that changes the app runs the same build in CI and attaches `hypergesture.apk` to a `v*` GitHub release. That needs three repository secrets, `ANDROID_KEYSTORE_B64` (`base64 -w0 android.keystore`), `KEYSTORE_PASSWORD` and `KEYSTORE_ALIAS`. Unlike a local build, CI stops with an error when the keystore or password is missing rather than publishing an unsigned APK, and it runs `apksigner verify --print-certs` on the result so the certificate is visible in the job log. `versionCode` is stamped from the workflow run number, so it rises with every release.

## Update check

The update check under `update/` is off by default and is the only code that opens a connection. The activity's view model runs it on app open at most once a day, plus **Check now** on the Updates screen, and home's Updates row reads **Update available** with the version once it finds a newer release. The accessibility service never calls it, and nothing runs it in the background. It asks `https://api.github.com/repos/Rohith-E-S/HyperGesture/releases/latest` and compares the tag, `v1.2.3`, with the installed `versionName`, `1.2.3`, which `scripts/version.sh` writes from that same tag. The parser also accepts the `fork-` prefix that releases published from the old branch used. `versionCode` plays no part, since the API doesn't publish it.

Redirects are followed by hand, so every hop has to be HTTPS to `api.github.com`, `github.com` or a `*.githubusercontent.com` asset host. `res/xml/network_security_config.xml` refuses cleartext and user-added certificates. The APK is written to `noBackupFilesDir` and has to match the asset's `digest` when GitHub publishes one. It then goes to `PackageInstaller` as a session that requires the user's confirmation. Android refuses that session when the APK is signed with another key, so a debug build cannot install a release. A debug build usually meets the earlier refusal, `INSTALL_FAILED_VERSION_DOWNGRADE`, because released builds carry a `versionCode` above 100000 while the source tree keeps the small one.

A debuggable build reads its version from a global setting when one is set, so an emulator can test both answers without a rebuild:

```sh
adb shell settings put global hypergesture_version_name 0.9.0   # offered the latest release
adb shell settings put global hypergesture_version_name 99.0.0  # reads as up to date
adb shell settings delete global hypergesture_version_name
```

The last check time and answer sit in the `update_check` DataStore, so a second open on the same day doesn't send a request. **Check now** skips that limit.
