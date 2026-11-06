# app-car — Android Auto projection

Android for Cars App Library (`androidx.car.app` 1.7.0) module. It projects the single `NavigationSession` from
`NavigationHostRegistry` onto a head unit (DESIGN §8): phone and car are two views of the same state, the car never
owns a route of its own.

## What is here

| File | Role |
|---|---|
| `HanselCarAppService` | `CarAppService` the host binds to. `ALLOW_ALL_HOSTS_VALIDATOR` on debuggable builds (DHU, sideloaded hosts); release builds use `HostValidator.Builder(...).addAllowedHosts(R.array.hosts_allowlist_sample)`. |
| `HanselCarSession` | One head-unit connection. Registers `CarMapRenderer` as the `SurfaceCallback`, wires `NavigationManagerCallback` (`onStopNavigation` -> `session.stop()`, `onAutoDriveEnabled` -> `CarHooks.autoDriveRequested`), forwards dark-mode changes. |
| `CarNavigationScreen` | Root `Screen`. Collects `session.state` with `repeatOnLifecycle(STARTED)` and returns a `NavigationTemplate` per state (Idle / Previewing / Navigating / Arrived). Pushes `navigationStarted/Ended` and `updateTrip(Trip)` so the cluster / HUN get the same step and ETA. |
| `AlternativesScreen` | `ListTemplate` of routes; preview mode calls `session.select(i)`, guidance mode calls `session.switchTo(route)`. |
| `ReportScreen` | `ListTemplate` of `HazardCategory` (Police, Crash, Debris, Vehicle on shoulder, Roadwork, Closure); a tap calls `CarHooks.reportSink`. |
| `CarTemplates` | Builds `Step` / `Maneuver` / `RoutingInfo` / `TravelEstimate` / `Trip` from `RouteProgress`. |
| `CarManeuvers`, `CarDistances`, `MapProjection` | Pure Kotlin (no Android imports), covered by `src/test`. Maneuver-type mapping, `Distance` unit selection, Web Mercator fit/follow/pan/zoom. |
| `CarSurfaceRenderer` | Interface the map drawing sits behind (see "Swapping in MapLibre"). |
| `CanvasSurfaceRenderer` | v1 renderer: `android.graphics.Canvas` on the car `Surface` — dark background, active route (teal), alternatives (gray), lock/shaping points, destination, upcoming-maneuver ring, position puck with bearing wedge, scale bar. Follow mode (~1.5 km across, heading up via `Canvas.rotate`) while navigating, route-bounds fit otherwise. |
| `CarMapRenderer` | The `SurfaceCallback`: surface lifecycle, visible/stable areas, <=10 fps redraw coalescing, pan/zoom gestures with an 8 s return-to-follow timer. |
| `CarHooks` | Process-wide hooks the Application sets: `reportSink`, `rendererFactory`, `drivesOnLeft`, `autoDriveRequested`. |

## App-side wiring (the app module must do this)

1. Depend on `project(":app-car")`.
2. Set `NavigationHostRegistry.session`, `.units`, `.locationSource` at startup (the car service only reads them).
3. Optionally set `CarHooks.reportSink` (hazard reports) and `CarHooks.rendererFactory`.
4. Manifest: this library's manifest already merges the service, the three `androidx.car.app.*` permissions,
   `androidx.car.app.minCarApiLevel = 1` and
   `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc"/>`
   into the app. If the app declares its own `com.google.android.gms.car.application` meta-data it must point at an
   `automotive_app_desc.xml` containing `<uses name="template"/>` (identical to the one in this module), otherwise the
   manifest merger reports a conflict.
5. Keep guidance alive while the phone is backgrounded: the app's foreground location/navigation service, not this
   module, owns that (DESIGN §8 "preserve guidance through connectivity transitions").

Permissions declared here (verified against
<https://developer.android.com/training/cars/apps/library/draw-maps>):
`androidx.car.app.NAVIGATION_TEMPLATES` (required for `NavigationTemplate` / `MapWithContentTemplate` in a NAVIGATION
app), `androidx.car.app.MAP_TEMPLATES` (the POI/weather variant, harmless), `androidx.car.app.ACCESS_SURFACE`
(required to receive the map `Surface`).

## Testing with the Desktop Head Unit (DHU)

Current steps from <https://developer.android.com/training/cars/testing/dhu> and
<https://developer.android.com/training/cars/testing> (DHU 2.x):

1. Android Studio -> SDK Manager -> SDK Tools -> install **Android Auto Desktop Head Unit Emulator**. It lands in
   `$ANDROID_HOME/extras/google/auto/`. On Linux/macOS `chmod +x desktop-head-unit`; Linux also needs glibc >= 2.32
   plus `libc++1 libc++abi1`.
2. On the phone (Android 9+, developer options on, Play Store signed in, Android Auto updated): open the Android Auto
   settings (Settings -> Apps -> Android Auto -> Additional settings in the app), scroll to **About / Version**, tap
   **Version and permission info** 10 times, accept **Allow development settings**.
3. In the Android Auto overflow menu choose **Start head unit server** (a foreground notification appears). Under
   **Previously connected cars** make sure **Add new cars to Android Auto** is on.
4. Install the debug APK (`./gradlew :app-phone:installDebug`), connect the phone over USB, keep it unlocked.
5. Connect the DHU, either in accessory mode (recommended for 2.x):
   `$ANDROID_HOME/extras/google/auto/desktop-head-unit --usb`
   or through adb: `adb forward tcp:5277 tcp:5277` then `./desktop-head-unit`.
   Optional `~/.android/headunit.ini`, e.g. `resolution = 1280x720`, `instrumentcluster = true` (DHU 2.1+ can add a
   `[display:cluster]` section to see the `Trip` we push through `NavigationManager.updateTrip`).
6. Accept the terms on the phone on first connect, then open Hansel from the DHU launcher. Start a route on the phone;
   the car screen follows (`Previewing` -> "Start", `Navigating` -> turn card + ETA).
7. Auto-drive hook for Play review:
   `adb shell dumpsys activity service dev.hansel.car.HanselCarAppService AUTO_DRIVE`
   sets `CarHooks.autoDriveRequested`; the app's location pipeline is expected to simulate driving the route.

Caveats:

- **"Allow unknown sources"** in the Android Auto developer settings exists, but per the testing page it applies to
  media / messaging / parked apps and *does not apply to apps built with the Android for Cars App Library*. Sideloaded
  template apps run on the DHU (a developer host) regardless; on a real head unit the app must come from a trusted
  source — Play internal test track / internal app sharing — before the host will show it. Plan the private Play
  track early (DESIGN §8).
- **KingInstaller / Shizuku** only change how the APK is installed. They do *not* grant navigation-template
  authorization or make the app visible to the Android Auto host (DESIGN §8). Do not count on them for the head-unit
  test; use them, if at all, only for the phone-side install.
- `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` is used only when the app is debuggable. Release builds validate hosts
  against the library allowlist.
- Dark mode: the renderer honours `CarContext.isDarkMode()` (`Session.onCarConfigurationChanged`) with a light and a
  dark palette; the quality guidelines require the light default, so do not remove the light palette.

## Template / quota notes (androidx.car.app 1.7.0, checked against the library's constraint tables)

- `NavigationTemplate`: action strip max **4** actions, max 1 `FLAG_PRIMARY`, text+icon titles; the strip is mandatory.
  Navigating uses Stop / Reroute / Routes / Report (exactly 4; Routes is omitted when there are no alternatives).
- Map action strip (Car API >= 2): only `Action.PAN`; pan-mode exit resets the camera.
- `RoutingInfo` must have a current step + distance unless `setLoading(true)`; we use loading while rerouting or
  before the first fix. `TravelEstimate` remaining time must be >= 0.
- `ListTemplate` row count follows `ConstraintManager.getContentLimit(CONTENT_LIMIT_TYPE_LIST)` (typically 6); the
  report list has exactly 6 categories.
- Refreshing a template of the same type (progress updates) does not count against the template-depth quota.

## Swapping in MapLibre

`CarSurfaceRenderer` is the seam. The intended path (DESIGN §8, still a known risk that must be proven on a real head
unit, not just the DHU):

1. Implement `MapLibreSurfaceRenderer : CarSurfaceRenderer`. `attach(surface, w, h)` has no `MapView`/activity to
   lean on, so render off-screen: MapLibre Android's `MapSnapshotter` (style + camera -> bitmap, asynchronous) is the
   least invasive option — snapshot on every camera change / state update, then draw the bitmap into the `Surface`
   canvas and overlay the route, puck and maneuver ring exactly as `CanvasSurfaceRenderer` does today (reuse
   `MapProjection` so overlays line up with the snapshot's camera). The alternative is a `VirtualDisplay` +
   `Presentation` hosting a real `MapView` (the draw-maps guide documents this pattern for Views), which gives
   continuous rendering at the cost of a second GL context and more memory on the phone.
2. Register it: `CarHooks.rendererFactory = { MapLibreSurfaceRenderer(context, styleUri) }` before the head unit
   connects (Application `onCreate`).
3. Keep `CanvasSurfaceRenderer` as the fallback when the style/tiles are unavailable offline.
4. Measure on the target head unit: frame time at the DHU resolution, memory with the phone map open at the same time,
   behaviour on surface loss / reattach, and dark-mode style switching. Only then flip the default.
