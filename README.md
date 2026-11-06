# Hansel

Personal Android navigation that remembers how *you* actually drive. Single user, privacy-first, two routing engines
side by side, routes you can drag into shape, and an Android Auto screen driven by the same session.

> Hansel marked his own trail to find the way home. The app keeps the breadcrumbs — every trip, as it was actually driven.

**Status:** first implementation of Phases 0–2 of [`DESIGN.md`](DESIGN.md). It compiles to a debug APK and has
88 unit tests over the routing/editing/navigation logic, but it has **not yet run on the Galaxy S25+ or a head unit**.
Routing today is PC-hosted on your LAN (Valhalla + GraphHopper in Docker); that is online routing, not the Phase 3
offline capability.

## What's built

| Design section | Where | State |
|---|---|---|
| §3 architecture, module layout | Gradle modules below, `build-logic/` convention plugins | done |
| §5 alternatives, route shaping, corridor locks | `core-routing-api` (`RouteEditor`, `CorridorLockController`, `AlternativesEvaluator`) | done, tested |
| §5.2 engine mapping | `routing-valhalla`, `routing-graphhopper` | done, tested against doc fixtures |
| §8 navigation session (one state, two projections) | `core-navigation` (`NavigationSession`, off-route detection, guidance) | done, tested |
| §8 Android Auto | `app-car` — `NavigationTemplate` + Canvas map on the car surface | compiles; needs DHU / head-unit run |
| §9 A/B shadow logging + scorecard | `benchmark`, Engine Lab screen | done |
| §10 screens | `app-phone` — Home, Search, Route preview, Route editor, Navigation, Engine lab, Settings | done; Downloads / Traffic lab / Learning lab are labelled placeholders |
| §11 data model (subset) | `storage` — Room: saved routes, recents, trips + raw GPS samples, A/B records; DataStore settings | done |
| §6.1 raw trip recording (opt-in) | `storage/TripRecorder` — never clamps speed | done; off by default |
| §4.3 on-device engine spike | `spikes/graphhopper-ondevice` (opt-in Gradle property) | compiles; unmeasured |
| Dev server | `dev-server/` — Docker Compose: Valhalla, GraphHopper, Nominatim on MS+LA+AL | written, not executed here |

Not built yet: Phase 3 offline packages, Phase 4 HERE/TomTom traffic + hazard reports, Phase 5 learning model,
Phase 6 crossover trials, Phase 7 Android Auto hardening. The app says so on the corresponding screens.

## Modules

```
core-model          LatLng/geometry math, polyline codec, RouteRequest/Route/Maneuver, errors      (JVM)
core-routing-api    RoutingEngine interface, orchestrator (single/shadow/side-by-side), evaluator,
                    corridor-lock controller + validator, RouteEditor with undo/redo, fake engine   (JVM)
routing-valhalla    Valhalla /route adapter: break/through/via, alternates, exclude_polygons          (JVM)
routing-graphhopper GraphHopper /route adapter: alternative_route, custom_model, areas               (JVM)
core-navigation     NavigationSession, RouteTracker, OffRouteDetector, GuidanceScheduler, units      (JVM)
search              Nominatim geocoder                                                               (JVM)
benchmark           AbRecord / AbShadowLogger / Scorecard                                            (JVM)
storage             Room database, repositories, TripRecorder, DataStore SettingsStore               (Android)
core-location       FusedLocationSource, NavigationForegroundService                                 (Android)
map-ui              MapLibre in Compose: route layers, puck, drag-to-shape interceptor               (Android)
app-car             Android Auto CarAppService, templates, Canvas surface renderer                   (Android)
app-phone           The app: Application graph, MainViewModel, Compose screens                       (Android)
spikes/graphhopper-ondevice  GraphHopper core loaded on the phone, behind the same interface         (Android, opt-in)
dev-server          docker-compose + scripts for the LAN routing/search stack
```

JVM modules have no Android dependency, so `./gradlew test` runs the interesting logic in seconds.

## Build

Requirements: JDK 17+, Android SDK with platform 37 and build-tools 37 (`sdk.dir` in `local.properties`), Gradle via
the wrapper (9.8), AGP 9.5 with built-in Kotlin, Kotlin 2.4.

```sh
./gradlew test testDebugUnitTest          # all unit tests
./gradlew :app-phone:assembleDebug        # app-phone/build/outputs/apk/debug/app-phone-debug.apk
./gradlew -Phansel.spike.graphhopperOnDevice=true :spikes:graphhopper-ondevice:assembleDebug
```

Install: `adb install -r app-phone/build/outputs/apk/debug/app-phone-debug.apk`.

## First run

1. **Dev server.** On your PC: `cd dev-server && ./scripts/fetch-extract.sh && ./scripts/build-graphs.sh`, then
   `./scripts/smoke.sh`. See `dev-server/README.md` for disk/RAM expectations (Nominatim import is the slow one).
2. **Phone.** Open Hansel → ⋮ → Settings → Servers: enter `http://<pc-ip>:8002`, `:8989`, `:8080`. The Protomaps key
   is pre-filled with the temporary key; replace it with your own from protomaps.com when it expires.
   No server yet? Flip **Demo mode** — the fake engine draws plausible routes so every screen is usable.
3. Grant location (and notifications, for the guidance notification). Search a destination or long-press the map.
4. Route preview shows up to three cards with engine/latency/shadow-overlap provenance. **Edit route** to shape it:
   long-press = route through here, drag the route = shaping point, tap the route twice = select a stretch, then
   *Prefer this road* (corridor lock), *Avoid*, or *Keep up to here*. Undo/redo, save as a template.
5. **Start**. Guidance runs in a foreground service so it survives the screen turning off; Android Auto shows the
   same session. Reroutes keep your stops, locks and un-passed shaping points (DESIGN §15 Phase 2).
6. Engine lab: engine health, SINGLE / SHADOW / SIDE_BY_SIDE, last discrepancy, and the scorecard once trips complete
   with *Record trips* on.

## Verification status (be honest with yourself)

- Unit tests: geometry, polyline codec, editor ordering/undo/drag/locks/avoid/reroute, orchestrator shadow and
  side-by-side, stitching for engines without through-points, lock validation, navigation progress/arrival/off-route
  reroute/snapshot restore, guidance stages, both adapters' request shaping and response parsing, Nominatim, sampling
  policy, GPX, car maneuver mapping and projection.
- Not verified: anything that needs a device — MapLibre rendering with Protomaps, the drag interceptor's feel,
  the FGS start restriction on Android 17, TTS, the car surface on a DHU, GraphHopper core on ART, and the Docker stack.
  Expect first-run fixes in those places; the design doc's Phase 0 exit criteria are the checklist.
- The adapters were written against the Valhalla and GraphHopper docs on 2026-11-05 and tested with fixtures, not
  against a live server.

## Licensing / attribution

Map data © OpenStreetMap contributors (ODbL). Tiles: Protomaps hosted API (non-commercial key). Routing: Valhalla
(MIT), GraphHopper (Apache-2.0). Search: Nominatim (GPL-2.0, self-hosted). MapLibre Native (BSD-2). The public
`tile.openstreetmap.org` service is not used. HERE/TomTom are not integrated yet; see DESIGN §7.2 before adding keys.
