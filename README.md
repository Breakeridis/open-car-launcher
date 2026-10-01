# Minimal Car Launcher

A distraction-free Android **home launcher** for automotive head units. Kotlin + XML views,
`minSdk 29`, no Google Play Services, no Compose.

**Target unit:** Allwinner T507 / A133 SoC (Nowada / NWD K2401P platform), 1080p landscape
in-dash panel. It runs on any landscape Android 10+ head unit; see
[Head-unit notes](#head-unit-notes-allwinner-t507--a133-nwd-k2401p) for what is specific to this one.

```
┌──────────────────┬───────────────────────────┬──────────────────┐
│  12:47 09        │        ╭── N ──╮          │   ┌──────────┐   │
│  TUESDAY         │      ╭─  NE 43°  ─╮       │   │  ZLink   │   │
│  16 Sept 2026    │     W  [🔍]  ▲  [◎] E     │   └──────────┘   │
├──────────────────┤      │   map    │        ├──────────────────┤
│ [−]  88.6  [+]   │      ╰─  62 KM/H ─╯       │ [ Maps ][ Music ]│
│  FM  RADIO NOVA  │        ╰── S ──╯          │                  │
│ [88.6][95.2][..] │       ● GPS lock          ├──────────────────┤
├──────────────────┤                           │  [◐]  [⚙]  [⇩•]  │
│ [▦][app][app][+] │                           │                  │
└──────────────────┴───────────────────────────┴──────────────────┘
```

## Features

| # | Area | Behaviour |
|---|---|---|
| 1 | **Clock & calendar** | Large HH:mm with a running seconds counter, localized weekday and date. Tap opens the clock / alarm app. |
| 2 | **FM/AM radio** | Live frequency, band and RDS station name / radio text read from the native tuner. Tap `−`/`+` to seek, long-press to step one channel. Four presets: tap to tune, long-press to store the current station (persisted). Tap the frequency to open the radio app, long-press to choose which app that is. |
| 3 | **Map portal** | Circular OpenStreetMap view following the car, rotating compass bezel, heading-up or north-up (tap the heading chip), pan / pinch-zoom, recenter. Address search (Nominatim) in a full-screen panel, or long-press the map to drop a pin. **Navigate** routes on this map (OSRM): route line, next-turn card with distance, remaining distance / time / ETA, automatic rerouting, and a trip that survives switching the ignition off. Night mode darkens the tiles. Snaps back to the car 20 s after you stop panning. |
| 4 | **Speed HUD** | Large GPS speed inside the portal; tap to toggle KM/H ↔ MPH. GPS lock indicator underneath. |
| 5 | **Phone projection** | Auto-detects ZLink / AutoKit / EasyConnection / Headunit Reloaded; tap launches, long-press re-assigns. |
| 6 | **Navigation & music** | Tap launches, long-press binds any installed app. |
| 7 | **App dock** | All Apps plus four user-assignable slots (long-press: replace / remove, `+` to add). |
| 8 | **System dock** | Day/night toggle, vehicle settings (long-press → launcher settings), updater with a badge. |
| – | **App drawer** | Full-screen grid, type-to-filter search, long-press to pin. Close button, HOME, BACK, a tap on the margins or a downward fling dismisses it. |
| – | **Updater** | Polls the GitHub releases API, downloads with a progress bar and hands the APK to the system installer. |

The clock, radio, speedometer, docks and any map area already in the tile cache work with
**zero connectivity**; new map tiles, address search and the updater need a connection
(typically the phone's hotspot).

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

## Head-unit notes (Allwinner T507 / A133, NWD K2401P)

These units typically run a regular (not Android Automotive OS) Android 10-class ROM with vendor apps for the
radio, CAN/vehicle settings and phone projection. That shapes four things:

### Screen density decides the layout bucket

A "1080p" panel can come out as very different dp sizes depending on the ROM's
`lcd_density`. Check once:

```bash
adb shell wm size        # e.g. Physical size: 1920x1080
adb shell wm density     # e.g. Physical density: 240
```

| Result | dp size | Resources used |
|---|---|---|
| 1920×1080 @ 240 | 1280×720 dp | `values-sw720dp` (the expected case) |
| 1920×1080 @ 160 | 1920×1080 dp | `values-sw1000dp` |
| anything smaller | — | `values/` (fallback, ~1024×600 dp) |

The map portal is always a circle the height of the screen; the side columns share the rest.

### Radio sync needs notification access

A third-party app cannot drive the tuner hardware (`RadioManager` is a privileged system API),
so the widget talks to the **native radio app through its media session**: it reads the
frequency and RDS from the session metadata, seeks with `skipToNext/Previous`, and tunes presets
with `playFromSearch("88.6 FM")`. Reading another app's media session requires granting this
launcher *notification access* — tap *"Tap to sync with the car radio"* on the widget. Vendor
ROMs often hide that screen; grant it over adb instead:

```bash
adb shell cmd notification allow_listener com.minimal.carlauncher/com.minimal.carlauncher.radio.MediaSessionListener
```

Then confirm the stock radio publishes a session and see which fields it fills:

```bash
adb shell pm list packages | grep -iE "radio|fm|tuner"
adb shell dumpsys media_session      # play the radio first; look for its package and metadata
```

If the radio's package name does not contain "radio"/"fm", long-press the frequency on the
widget and pick it. What still depends on the vendor app:

- **No media session at all** → the frequency and station are read from the radio app's ongoing
  notification instead (standard text and custom layouts), and seek presses that notification's
  previous / next buttons. Without a notification either, seek falls back to media key events
  (the same path as the steering-wheel buttons), and presets open the radio app with a
  play-from-search intent.

**When the widget stays empty:** long-press ⚙ → *Radio diagnostics*. It lists the access state,
every media session with its metadata, and the text and buttons of ongoing notifications
(other notifications by package name only). A screenshot of it is enough to adapt the parser to
a new tuner app.
- **Session without `playFromSearch`** → presets cannot tune directly. The widget checks the
  frequency the tuner reports afterwards and says so (*"The radio app did not accept direct
  tuning"*) rather than pretending it worked.

**When the radio publishes nothing at all** — the case for the NWD K2401P's stock tuner,
`com.nwd.radio`, which has no media session and no notification — Radio diagnostics →
*Inspect radio app* looks inside the tuner instead. It reads the tuner APK's manifest (exported
receivers, services and providers, and their intent-filter actions), the vendor names in its
code, and any radio-related `Settings` values. Change station and tap *Refresh*: whatever changes
is the channel the frequency travels on. No adb needed.

### Vehicle settings and projection apps are vendor apps

Long-press the system dock's ⚙ → *Vehicle settings button* to point it at the factory car-settings
app (EQ, steering-wheel learning, CAN); until then it opens Android Settings. ZLink is tried first
for projection, including Zjinnova's `com.zjinnova.zlink`; verify with the `pm list packages`
command under [Verify the projection package names](#3-verify-the-projection-package-names).

### Map tiles, GPU and connectivity

- Tiles come from OpenStreetMap (osmdroid, no Google Play Services) and are cached in the app's
  cache directory, up to 300 MB, so routes you drive regularly keep working offline. The OSM
  [tile usage policy](https://operations.osmfoundation.org/policies/tiles/) applies; for a fleet,
  switch `TileSourceFactory.MAPNIK` in `MapPortalController` to a commercial tile source.
- The Mali-G31 / PowerVR GE8300 GPUs in these SoCs are modest: the map is clipped with an outline
  (not `clipPath`), only re-rotated when the heading moves by ≥ 2°, and only redrawn when
  something changed. Tile downloads are limited to two threads.
- Address search is disabled above ~5 km/h, like the update prompt. Long-press pins and the
  Navigate button work at any speed (one tap each).
- Routes come from the public OSRM demo server (`router.project-osrm.org`), which is fine for
  one car but has a fair-use policy; for a fleet, point `RoutingClient.ENDPOINT` at your own
  OSRM instance. Only routing and rerouting need a connection: following the route, the
  next-turn readout and arrival are computed on the unit.

## Verifying on a device

Emulator profile that matches the target unit: **1920×1080, 240 dpi, landscape, no-Google-APIs
system image** (which also proves the zero-GMS claim). Also try **1024×600 @ 160** for the
fallback layout.

| Feature | Check |
|---|---|
| Home registration | `adb shell dumpsys package preferred-activities`; press HOME from inside the drawer → returns to the dashboard with the search box cleared. |
| Speedometer | Emulator → Extended controls → Location, or `geo fix <lon> <lat> <alt> <sats> <velocity>`. Expect `--` before a fix, `0` at standstill. `adb shell pm revoke com.minimal.carlauncher android.permission.ACCESS_FINE_LOCATION` to retest the permission path. |
| GPS is released | Launch another app, then `adb shell dumpsys location \| grep carlauncher` → no active request. |
| Compass | Extended controls → Virtual sensors → rotate. `adb shell dumpsys sensorservice` to confirm `TYPE_ROTATION_VECTOR` exists; the gauge must degrade to `--` where it does not. |
| Map portal | Feed a moving route (Extended controls → Location → Routes). The car arrow must follow, the bezel N must rotate with the map in heading-up mode, panning must stop following and the map must snap back after 20 s. Toggle night mode: tiles darken. |
| Radio | With notification access granted and the stock radio playing, the frequency must match the radio app; seek, a preset tap and a preset long-press must all work. Revoke access (`adb shell cmd notification disallow_listener …`) → the widget shows the last station dimmed and asks for access. |
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
- **osmdroid, not Google Maps.** Google's SDK needs Play Services; OpenStreetMap tiles plus
  Nominatim search need no key and no Google stack.
- **Media session, not a vendor radio SDK.** It is the one radio interface that is public and
  identical across firmware; vendor intents differ per ROM and are undocumented.
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
