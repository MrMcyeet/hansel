# Hansel

Personal Android navigation that remembers how *you* actually drive. Single user, privacy-first, two routing engines
side by side, routes you can drag into shape, and an Android Auto screen driven by the same session.

> Hansel marked his own trail to find the way home. The app keeps the breadcrumbs — every trip, as it was actually driven.

**Status:** second cut. Self-contained by design: the phone downloads OpenStreetMap extracts for the states you pick
(your own state is pre-selected from GPS), then builds the routing graph, the address/POI search index and the map
tiles **on the phone**. Nothing is served from a PC or any server of yours. 139 unit tests; the on-device pipeline has
been validated piecewise against the live data sources from a workstation but **not yet run end-to-end on the phone**.

## How it works offline

| Need | Source (download once) | Built on the phone | Used by |
|---|---|---|---|
| Roads | Geofabrik state extracts (`.osm.pbf`, MD5-verified, resumable) | merged across states → GraphHopper graph with CH (`offline-osm`, `routing-graphhopper`) | routing, rerouting, alternatives, avoid zones |
| Search | same extracts | SQLite FTS index of places, streets, POIs, addresses (`offline-osm` → `offline/SearchIndexDb`) | the search screen |
| Map | Protomaps' public daily planet PMTiles, **range requests for your regions' bbox only** (`offline-tiles`) | a local `.pmtiles` file + fonts/sprites + localized style | MapLibre (`pmtiles://file://…`) |

The build runs in `PackageBuildService` in its own `:build` process with a progress notification; phases are
resumable and the result is swapped in atomically (`OfflinePipeline`). GraphHopper's Java core runs inside the app:
two Android blockers were removed — its Janino code generation for cost models (replaced by an interpreter,
`InterpretedCustomModel`) and a `javax.lang.model` reference (shimmed). Both are covered by JVM tests that run with
code generation disabled.

Sizes for Mississippi + Louisiana + Alabama, measured 2026-11-05: extracts ≈ 350 MB; tiles ≈ 490 MB at z14 (default)
or ≈ 950 MB at z15; graph roughly the size of the extracts; fonts 3.5 MB. Expect the first build to take a good while
on Wi-Fi — keep the phone plugged in; it continues in the background.

## What's built

| Design section | Where | State |
|---|---|---|
| §3 architecture, module layout | Gradle modules below, `build-logic/` convention plugins | done |
| §4 offline datasets, package UX, airplane-mode operation | `offline`, `offline-osm`, `offline-tiles`, Offline data screen | built; needs the first on-phone run |
| §4.3 on-device engine | `routing-graphhopper` (GraphHopper core + interpreter + importer) | built, JVM-tested; measure on the phone |
| §5 alternatives, route shaping, corridor locks | `core-routing-api` (`RouteEditor`, `CorridorLockController`, `AlternativesEvaluator`) | done, tested |
| §8 navigation session (one state, two projections) | `core-navigation` | done, tested |
| §8 Android Auto | `app-car` — `NavigationTemplate` + Canvas map on the car surface | compiles; needs DHU / head-unit run |
| §9 A/B logging + scorecard | `benchmark`, Engine lab | done (one engine, so no shadow yet) |
| §10 screens | `app-phone` — Home/setup, Search, Route preview, Route editor, Navigation, Offline data, Engine lab, Settings | done; Traffic/Learning labs are labelled placeholders |
| §11 data model (subset) | `storage` — Room: saved routes, recents, trips + raw GPS samples, A/B records; DataStore settings | done |
| §6.1 raw trip recording (opt-in) | `storage/TripRecorder` — never clamps speed | done; off by default |

Not built yet: Phase 4 HERE/TomTom traffic + hazard reports, Phase 5 learning model, Phase 6 crossover trials,
Phase 7 Android Auto hardening, a second on-device engine (native Valhalla) for the A/B lab.

## Modules

```
core-model          LatLng/geometry math, polyline codec, RouteRequest/Route/Maneuver, errors      (JVM)
core-routing-api    RoutingEngine interface, orchestrator, evaluator, corridor locks, RouteEditor   (JVM)
core-navigation     NavigationSession, RouteTracker, OffRouteDetector, GuidanceScheduler, units      (JVM)
benchmark           AbRecord / AbShadowLogger / Scorecard                                            (JVM)
offline-osm         PBF reader/writer/merger, search-feature extractor, US state catalog, Geofabrik  (JVM)
offline-tiles       PMTiles v3 reader/writer, bbox extractor over HTTP ranges, offline style/assets  (JVM)
routing-graphhopper GraphHopper core in-process: interpreter for custom models, importer, engine     (Android)
offline             Package pipeline + build service (own process), manager, Room FTS search        (Android)
storage             Room database, repositories, TripRecorder, DataStore SettingsStore               (Android)
core-location       FusedLocationSource, NavigationForegroundService                                 (Android)
map-ui              MapLibre in Compose: route layers, puck, drag-to-shape interceptor               (Android)
app-car             Android Auto CarAppService, templates, Canvas surface renderer                   (Android)
app-phone           The app: Application graph, MainViewModel, Compose screens                       (Android)
```

JVM modules have no Android dependency, so `./gradlew test` runs the interesting logic in seconds.

## Build

Requirements: JDK 17+, Android SDK with platform 37 and build-tools 37 (`sdk.dir` in `local.properties`), Gradle via
the wrapper (9.8), AGP 9.5 with built-in Kotlin, Kotlin 2.4.

```sh
./gradlew test testDebugUnitTest          # all unit tests
./gradlew :app-phone:assembleRelease      # arm64, R8-minified, debug-key signed: app-phone/build/outputs/apk/release/
./gradlew :app-phone:assembleDebug        # arm64 debug build (add -Phansel.universalApk=true for all ABIs)
```

Install: `adb install -r app-phone/build/outputs/apk/release/app-phone-arm64-v8a-release.apk`.

## First run

1. Grant location (and notifications). The home card suggests your state from GPS with its neighbours as checkboxes
   and shows an estimate (it HEADs Geofabrik and resolves Protomaps' directories for real numbers). **Download and build.**
2. Watch progress on the card or the notification; the phases are download → merge → index → graph → tiles → style.
   If anything fails, the Offline data screen shows the error and **Build now** resumes from what's already done.
3. No data yet? **Demo mode** (Settings) uses a fake engine so every screen is usable.
4. Search a destination or long-press the map. Route preview shows up to three cards with engine/latency provenance.
   **Edit route**: long-press = route through here, drag the route = shaping point, tap the route twice = select a
   stretch, then *Prefer this road* (corridor lock), *Avoid*, or *Keep up to here*. Undo/redo, save as a template.
5. **Start**. Guidance runs in a foreground service; Android Auto shows the same session.

## Verification status (be honest with yourself)

- Unit tests (139): geometry, polyline codec, editor, orchestrator, stitching, lock validation, navigation progress /
  arrival / off-route reroute / snapshot, guidance, PBF round-trips + merge (read back by GraphHopper's own reader),
  search extraction, state catalog + neighbours, Geofabrik index, PMTiles spec vectors / writer / extractor with resume,
  style localization, GraphHopper import + routing with code generation disabled, custom-model interpreter, car
  maneuver mapping and projection, sampling policy, GPX, settings.
- Validated against live sources from a workstation: a bbox extract from the real planet build (byte-identical tiles,
  readable by the official PMTiles tool); the Geofabrik index shape; the Protomaps style/asset URLs.
- Not verified: the full pipeline on the phone (download speeds, GraphHopper import memory/time on ART, MMAP graph
  loading, the `javax.lang.model` shim and R8 keep rules in the release build), MapLibre rendering the local PMTiles
  file and `file://` glyphs, the drag interceptor's feel, FGS rules on Android 17, TTS, the car surface on a DHU.
  Expect first-run fixes there; DESIGN §4.4 is the acceptance test.

## Licensing / attribution

Map data © OpenStreetMap contributors (ODbL), downloaded as Geofabrik extracts. Tiles: Protomaps' public daily
basemap build (ODbL data, tileset Apache-2.0/BSD) extracted per region; the hosted Protomaps API is only used for the
style JSON and as a fallback before any region is installed. Routing: GraphHopper core (Apache-2.0). MapLibre Native
(BSD-2). The public `tile.openstreetmap.org` service is not used. HERE/TomTom are not integrated; see DESIGN §7.2.

## Known gaps (carried from the first review)

- Guidance started from the Android Auto screen bypasses the phone's `startNavigation()` (no foreground service, trip recording, or TTS for that trip).
- `NavigationSession.snapshot()/restore()` exist but nothing persists them yet (DESIGN §12 crash recovery).
- `NavigationSession.onFix` vs. the reroute job is not thread-confined.
- Corridor-lock anchors are positioned on the straight chord through all waypoints; on out-and-back trips they can land in the wrong leg (surfaced as a `LockViolated` error, never silently).
- A loop route (origin ≈ destination) can "arrive" on the first fix.
- One region at a time is merged into a single graph: adding a state rebuilds the whole graph (by design — GraphHopper reads one file).
