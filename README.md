# Minimal Car Launcher

A distraction-free Android **home launcher** for automotive head units, dashboard tablets and
landscape screens. Kotlin + XML views, `minSdk 29`, no Google Play Services, no Compose.

```
┌──────────────────────────────────────────┐
│   62        12:47        NE              │
│  KM/H      TUESDAY       43°             │
│   GPS    16 SEPT 2026    GPS             │
├──────────────────────────────────────────┤
│ ┌────────┐ ┌────────┐ ┌────────┐         │
│ │ ZLink  │ │  Maps  │ │ Music  │         │
│ └────────┘ └────────┘ └────────┘         │
├──────────────────────────────────────────┤
│  [apps] │ [app][app][ +][ +] │ [⚙] [i•]  │
└──────────────────────────────────────────┘
```

## Features

| Area | Behaviour |
|---|---|
| **Dashboard** | GPS speedometer (tap to toggle KM/H ↔ MPH), compass heading as a cardinal point, large clock, day and date. |
| **Quick cards** | Phone projection (ZLink / AutoKit / EasyConnection / Headunit Reloaded), navigation, music. Tap launches, long-press re-assigns. |
| **Dock** | Four user-assignable slots plus three fixed actions: all-apps, system settings (long-press → launcher settings), about/update. |
| **App drawer** | Grid of every launchable app, type-to-filter search, long-press to pin to the dock. |
| **Updater** | Polls the GitHub releases API, compares semantic versions, badges the info icon, downloads with a progress bar and hands the APK to the system installer. |
| **Themes** | Dark cockpit, light cockpit, or follow system. |

Everything except the updater works with **zero connectivity**.

## Before you build

### 1. Point the updater at a repository

`app/build.gradle.kts`:

```kotlin
buildConfigField("String", "GITHUB_OWNER", "\"your-github-user\"")
buildConfigField("String", "GITHUB_REPO",  "\"your-repo\"")
```

While these still read `TODO_…` the updater stays in a `NotConfigured` state and never touches
the network.

### 2. Set up signing

> **The single most important rule.** The in-app updater can only install an APK signed with the
> **same key** as the build already on the device. A different key fails with
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only fix is uninstall/reinstall on every unit.
> Create the keystore once and never rotate it.

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias carlauncher
cp keystore.properties.sample keystore.properties   # then fill it in
```

Both files are git-ignored. For CI, set these repository secrets:
`KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Until those secrets exist a `v*` tag still publishes, but with a debug-signed APK that the
in-app updater cannot upgrade in place. Adding a real key later forces one reinstall.

### 3. Verify the projection package names

The ZLink/AutoKit family is rebranded per dongle vendor, so package ids vary by firmware. On the
target unit:

```bash
adb shell pm list packages | grep -iE "link|auto|carplay|easyconn"
```

Add whatever you find to `Constants.PROJECTION_TARGETS` **and** to the `<package>` entries in the
manifest's `<queries>` block — a package not named in `<queries>` is invisible to this app on
Android 11+, whatever it is actually called.

## Building

This repository ships no `gradle-wrapper.jar` (it is a binary). Either open the project once in
Android Studio, or generate the wrapper yourself:

```bash
gradle wrapper --gradle-version 8.9
```

Then:

```bash
./gradlew :app:testDebugUnitTest     # pure-logic tests, no device needed
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:assembleRelease       # needs keystore.properties
```

CI (`.github/workflows/build.yml`) runs the tests and assembles a debug APK on every push, and on
a `v*` tag publishes an APK to a GitHub release (signed when the keystore secrets exist, debug-signed otherwise) — which is exactly what
the in-app updater then finds.

## Installing it as the home screen

**Install a second launcher first.** Some stripped head-unit ROMs ship no other HOME activity at
all; uninstalling this one without a fallback leaves a black screen recoverable only over adb.

The activity also declares `CATEGORY_LAUNCHER`, so you can open it like a normal app and try it
before making it the default. When ready: **Settings → Apps → Default apps → Home app**, or
long-press the dock's settings icon → *Set as Home app*.

### Getting back out

```bash
adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
adb shell cmd package set-home-activity com.android.launcher3/com.android.launcher3.Launcher
adb shell am start -a android.settings.HOME_SETTINGS
adb shell pm uninstall com.minimal.carlauncher     # frees the default-home association
```

Booting into **Safe Mode** (long-press Power → long-press *Power off*) disables third-party
launchers on most ROMs.

## Verifying on a device

Emulator profile that matches a typical head unit: **1024×600, 160 dpi, landscape, no-Google-APIs
system image** (which also proves the zero-GMS claim).

| Feature | Check |
|---|---|
| Home registration | `adb shell dumpsys package preferred-activities`; press HOME from inside the drawer → returns to the dashboard with the search box cleared. |
| Speedometer | Emulator → Extended controls → Location, or `geo fix <lon> <lat> <alt> <sats> <velocity>`. Expect `--` before a fix, `0` at standstill. `adb shell pm revoke com.minimal.carlauncher android.permission.ACCESS_FINE_LOCATION` to retest the permission path. |
| GPS is released | Launch another app, then `adb shell dumpsys location \| grep carlauncher` → no active request. |
| Compass | Extended controls → Virtual sensors → rotate. `adb shell dumpsys sensorservice` to confirm `TYPE_ROTATION_VECTOR` exists; the gauge must degrade to `--` where it does not. |
| Drawer visibility | **Run on API 30+.** An empty or one-item drawer means the `<queries>` block is wrong. |
| Scroll smoothness | `adb shell dumpsys gfxinfo com.minimal.carlauncher framestats` while flinging. |
| Dock persistence | `adb shell run-as com.minimal.carlauncher cat /data/data/com.minimal.carlauncher/shared_prefs/car_launcher.xml` |
| Dock self-heal | Uninstall a docked app; the slot must clear itself. |
| Updater | Temporarily set `versionName = "0.0.1"` to force an update. Check the badge, the progress bar, reopening the dialog mid-download, airplane mode, and revoking *Install unknown apps*. |
| Crash resilience | `adb shell am crash com.minimal.carlauncher` while it is the default home. The stack trace lands in the About dialog's crash log. |

## Design notes

- **`LocationManager`, not `FusedLocationProviderClient`.** Many head units ship without Google
  Play Services, where the fused client silently never fires.
- **GPS bearing over the magnetometer while moving.** A head unit sits in a metal dash next to
  speaker magnets; the compass is the *stationary* fallback, and the gauge labels which source it
  is using.
- **`<queries>`, not `QUERY_ALL_PACKAGES`.** Same result, better hygiene, and some ROMs strip the
  broad permission.
- **`HttpURLConnection` + `org.json`.** Both are in `android.jar`; Retrofit/Moshi would add ~1 MB
  and R8 keep rules for one GET. `DownloadManager` was rejected because it offers no progress
  callback and is missing from some ROMs.
- **Nothing heavy or fallible in `onCreate`.** The app list loads asynchronously and every
  preference decode falls back to defaults — a crash-looping home screen is close to unrecoverable
  in a car.

## Licence

MIT — see [LICENSE](LICENSE).
