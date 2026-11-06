# Hansel — Technical Design Specification

> **Hansel** marked his own trail so he could find the way home. The app keeps the breadcrumbs: every trip you actually drive, as you actually drove it.

**Version:** 2026-11-05_1520 (named; Phase 0–2 implementation started — see `README.md` for what is built)  
**Status:** Proposed architecture; Phases 0–2 implemented as a first cut, unverified on hardware  
**Platform:** Android 17, Kotlin, Galaxy S25+; Android Auto companion interface  
**Scope:** Single-user, privacy-conscious navigation with customizable routing and offline operation  
**Prepared:** November 5, 2026

> **Design principle:** The app optimizes for the owner's observed road behavior and preferences, not a generic driver's profile. The observation layer preserves real measurements, including speeds above posted limits. Predictive routing may be configurable, with a legal-speed-constrained profile available as a separate mode; routing should never misrepresent recorded data as speed-limit-compliant. The driver remains responsible for lawful, safe operation.

## 1. Executive summary

Build a native Android navigation application with full control of map rendering, online/offline navigation, selectable alternative routes, drag-and-drop route shaping, driver-submitted hazard alerts, separate HERE and TomTom live-traffic integrations, and road-by-road learning from recorded trips. Valhalla and GraphHopper will both be supported as interchangeable routing backends, with objective A/B evaluation using the same map revision, traffic snapshot and driving-history predictions. Android Auto is a first-class client of the same navigation session.

A crucial engineering separation is **map display data**, **routable road graph**, **search index**, and **live traffic/incident feeds**. Each has its own storage, update cadence, coverage and licensing. Online traffic feeds are enhancements, not a hard dependency. The offline app must be able to display maps, locate the vehicle, compute/recompute routes, give turn-by-turn directions, and search downloaded destinations even in airplane mode.

### 1.1 User outcomes

1. Choose a destination, see up to three meaningful alternative routes with ETAs, and select one.
2. Touch or drag a route to force it through a desired road or junction; pin a route segment to preserve the choice across rerouting.
3. Download and maintain maps and routing/search data for selected regions or corridors for offline use.
4. Switch between Valhalla and GraphHopper, or run both concurrently in comparison mode.
5. Choose HERE, TomTom, automatic, or offline-only traffic data; diagnose the age and coverage of each feed.
6. Learn direction-specific road traversals, intersection waits, time-of-day effects, and route preferences, using those observations in personalized ETAs and route costs.
7. See relevant traffic, crashes, debris, stopped vehicles, closures and other hazards; support first-party reports, confirmations and expiration.
8. Use a simplified but custom-rendered map and guidance through Android Auto.

### 1.2 Non-goals for the first release

Multi-user crowdsourcing scale; recreating Google's/Waze's private crowdsourced database; automatic publication to Google Play; guaranteed police-report data; claiming offline traffic is live; fully independent nationwide offline downloads in the first prototype; or fully arbitrary Android Auto UI outside its supported templates.

## 2. Functional requirements

| ID | Priority | Requirement | Acceptance criterion |
|---|---|---|---|
| NAV-01 | P0 | Select destination and navigate | Phone presents map, maneuver guidance, ETA, remaining distance, rerouting. |
| NAV-02 | P0 | Alternatives | Display up to 3 route geometries with individual ETA, distance and major differences; allow explicit choice. |
| NAV-03 | P0 | Route shaping | Add/move/remove `via`/`through` points by map tap/long press or dragging a route; user can lock a corridor or road segment. |
| NAV-04 | P0 | Offline core | In airplane mode, map display, GPS positioning, route calculation, reroute, instructions and downloaded address/POI search work in covered region. |
| NAV-05 | P0 | Offline management | Download region, route corridor, and optional custom rectangle; show size, components, progress, version, integrity, available storage, update/delete. |
| NAV-06 | P0 | Dual engines | Identical route requests can be translated to Valhalla and GraphHopper; show source and discrepancies. |
| NAV-07 | P0 | Driving history | Record time-stamped GPS traces (opt-in passive or active), map-match directionally, aggregate per-edge traversal and turn observations. |
| NAV-08 | P0 | Personalization | Toggle independent effects for ETA and for route selection; show unpersonalized comparison and confidence. |
| NAV-09 | P1 | HERE and TomTom | Provider adapters for traffic flow, incidents and metadata; support manual provider choice and testing. |
| NAV-10 | P1 | Incident reports | Display relevant incident markers and approach alerts, including source, age, confidence and expiry. |
| NAV-11 | P1 | Android Auto | Custom map on approved car template, route previews, turn cards, ETA and limited safe controls. |
| NAV-12 | P1 | A/B testing | Store both engine predictions, chosen/actually traversed route, observed outcome, coverage, routing latency and test context. |
| NAV-13 | P1 | Routing preference controls | Avoid tolls, ferries, unpaved roads; preferences for highways; route locks; avoid zones; saved favored roads/corridors. |
| NAV-14 | P1 | Offline resilience | Cache last-known route and traffic snapshot; show stale badge; never label cached reports “live.” |
| NAV-15 | P2 | Predictive models | Learn context-specific road/turn costs; calibrate ETAs and quantify uncertainty. |
| NAV-16 | P2 | Import/export | Import/export GPX, route shaping preferences and history backups. |

## 3. System architecture

```text
Android app (Kotlin / Jetpack Compose)
  ├─ MapLibre renderer (phone) and Android for Cars map surface (Android Auto)
  ├─ Navigation session / GPS / voice / maneuver state / incident UI
  ├─ Route editor (alternatives, via/through pins, locked corridors)
  ├─ Offline package manager (map tiles + routing graphs + search index + assets)
  ├─ Personal history recorder, map matcher, predictor, encrypted SQLite
  ├─ Routing orchestrator
  │    ├─ Valhalla adapter (local runtime OR local/server endpoint)
  │    ├─ GraphHopper adapter (local runtime OR local/server endpoint)
  │    └─ Baseline mode + parallel A/B controller
  ├─ Traffic normalization
  │    ├─ HERE traffic adapter
  │    ├─ TomTom traffic adapter
  │    └─ offline cached / unavailable adapter
  └─ Incidents
       ├─ provider incidents + optional approved partner/public feeds
       └─ personal reports / confirmations / TTL + deduplication

Optional development PC / LAN service:
  graph import + preprocessing, routing backends, prediction training,
  offline package generation, traffic road matching diagnostics.
```

**Architecture invariant:** map view, guidance UI, traffic sources and learning storage do not depend on the selected routing engine. Engine-specific directed-edge identifiers must never be the canonical key for the personalized driving history.

### 3.1 Suggested Kotlin module layout

`app-phone`, `app-car`, `core-model`, `core-navigation`, `core-location`, `core-guidance`, `core-routing-api`, `routing-valhalla`, `routing-graphhopper`, `traffic-api`, `traffic-here`, `traffic-tomtom`, `incidents`, `learning`, `offline`, `map-ui`, `search`, `storage`, `benchmark`, `settings`.

Interfaces (`RoutingEngine`, `TrafficSource`, `MapPackageSource`, `MapMatcher`, `PersonalCostModel`) support independent development and test fakes. Use coroutines/Flow, Room/SQLite, WorkManager, Android foreground location services and clearly scoped permissions.

## 4. Offline downloads and navigation

### 4.1 Offline datasets

| Dataset | Purpose | Suggested packaging | Required offline? |
|---|---|---|---|
| Vector map tiles | Road visualizations, labels, terrain style | PMTiles/MBTiles or approved provider pack | Yes |
| Routing graph | Connectivity, turns, road restrictions, cost attributes | Engine-specific Valhalla tiles and GraphHopper graph | Yes |
| Search index | Address/POI autocomplete and reverse geocoding | Regional searchable database | Yes for useful offline search |
| Fonts/styles/icons | Consistent styling without network | Bundled assets | Yes |
| Elevation / voice | Optional contours, speech guidance | Optional region/voice packs | Guidance voice or Android TTS available |
| Traffic and hazards | Recent observations only | Timestamped short-lived cache | Optional, explicitly stale |
| Learned profile | Personalized travel/turn histories | Local encrypted database independent of map version | Yes |

Use licensed/self-hosted map tiles or a provider explicitly permitting downloads. The public `tile.openstreetmap.org` service prohibits bulk/offline tile prefetch; OSM **source data** can still be used with proper licensing and attribution.

### 4.2 Map package UX

Map Downloads screen: search region, choose state/county, draw rectangle, or save a corridor around planned trip. Preview component sizes: display / routing graph(s) / search / voice; download on Wi-Fi option; pause/resume, checksum verification, and atomic activation. Show coverage map, last-updated date, incomplete components, disk budget and actionable repair. Package versions stored with source OSM extract timestamp, graph build configuration, and normalized-road-ID mapping. Never remove a currently used package until replacement passes integrity checks.

**Important:** Both engines need separately built graph assets. A downloaded map tile package cannot be passed to either engine as a routing graph. A/B offline doubles some graph storage; allow user to install one or both routing backends per region.

### 4.3 Offline runtime decisions

GraphHopper's repository explicitly states that offline mobile routing is **no longer officially supported**, although Java-on-Android integration may still work. Valhalla's Android/on-device packaging also requires a measured native build and resource test. This is a high-risk prototype item, not a guaranteed capability. Initial prototype may use PC-hosted engines, but offline release **must** pass airplane-mode tests on the phone; do not label a PC-hosted route as offline phone support.

Fallback if one/both engine ports are impractical: maintain identical shared API and offer a proven offline SDK (e.g. HERE Navigate, subject to licensing) as a third backend for production reliability, while retaining Valhalla/GraphHopper for comparison when available. HERE Navigate itself includes downloadable regional maps, offline search, routing and guidance, but those maps are proprietary and cannot automatically be reused for an OSM-based Valhalla/GraphHopper graph.

### 4.4 Airplane-mode acceptance test

On downloaded region and with Wi-Fi/cellular disabled: cold-start, search a preindexed address, calculate three routes if available, insert/remove a shaping point, start navigation, deliberately deviate and reroute, restore after process death, switch car display, and announce turns. Indicate unavailable or stale live traffic without blocking navigation.

## 5. Route alternatives, route editing and nudging

### 5.1 Route controls

- At route preview, draw primary and up to three sufficiently distinct alternatives using differentiated line styles. Present time, length, tolls, predicted confidence, traffic exposure, major highway/road differences.
- Tap a route to select. Tap a nonselected road or long-press any map coordinate to add a **through point**; drag a segment of the selected polyline onto the preferred road to insert a shaping point. Visualize the resulting route instantly, preserve Undo/Redo and a visible “custom route” indication.
- Distinguish **stop** (`break`, arrival instructions), **through** (no stop/guidance arrival), and **corridor/road lock** (preserve a specified sequence of edges). Avoid accidental U-turns or overconstraint; allow reordering and deletion.
- For a chosen stretch, offer “Prefer this road,” “Avoid this road,” “Route through here,” “Keep this portion,” and “Recalculate remaining route.” Segment-level selection must work mid-route.
- Offer manual short detour around a reported blocked segment, and save favorite route templates for common trips.

### 5.2 Engine mapping

Valhalla explicitly supports `break`, `through`, `via` and `break_through` location types. GraphHopper supports waypoint ordering, pass-through behavior and alternative-route modes; the adapter may need to translate or approximate semantics. For truly locked road-edge sequences, build a corridor-lock controller above both engines: expand the desired path into ordered anchors, validate continuity, issue subroute requests, and concatenate maneuver legs without duplicated arrivals. Check for one-way/turn restrictions and fail visibly rather than silently ignoring an anchor.

### 5.3 Alternatives generation

First ask each backend for native alternatives where supported; then generate edge-penalized, waypoint-guided and corridor-diverse candidates as needed. Reject trivially identical routes using overlap and geometric dissimilarity thresholds. Re-score all candidates through a shared evaluator to expose engine ETA differences without pretending each engine's raw ETA is directly comparable. Avoid hard guarantees about “fastest” without known road conditions.

## 6. Learning model and personalized routing

### 6.1 Data recording and truth model

Record opt-in trip traces sampled adaptively (e.g., every few seconds or significant movement): timestamp, location accuracy, speed, bearing, motion state and GPS source. Map-match batches with confidence; discard noisy jumps, tunnels with ambiguous parallel roads and low-quality matching. Maintain source trace plus derived traversals. Store directional segment ID, start/end crossing times, moving time, stopped time, traffic context, weekday/time bucket, weather where permitted, and path turns.

**Preserve real measurements.** A 25-mph posted road driven at 50 mph remains a measured 50-mph observation. Do not cap or falsify data in storage. A separate routing policy controls the use of those observations. This allows an explicit *observed-behavior ETA* and a *constrained routing profile*, with transparent assumptions and no prediction that implies certainty.

### 6.2 Stable road identity

Canonical IDs should use an internal normalized directed-road-segment catalog derived from OSM way references plus geometry/road topology and revision metadata. OSM way IDs alone are insufficient (ways split/merge, direction matters). Maintain `canonicalSegmentId -> ValhallaEdgeRef[]`, `canonicalSegmentId -> GraphHopperEdgeRef[]`, and provider-specific link references with match confidence and version. Reconcile on map refresh; never blindly reuse stale IDs.

### 6.3 Travel-time estimator

For each directed segment and context bucket, learn robust observed traversal times and moving-speed distribution, excluding nontraffic stops where confidently identifiable. For intersections, learn turn transitions and delay separately. Estimate with a hierarchical blend:

`expectedCost = w_personal * personalEstimate + w_live * liveFlow + w_historical * sharedHistorical + w_base * graphBaseline + turnPenalty`

Weights are nonnegative, sum to one and depend on sample count, recency, match confidence, traffic freshness and context. This is a conceptual model; actual implementation must avoid counting correlated signals twice and consider that some sources already encode turn delays. Track p50/p80 arrival estimates and calibration. Personal ETA and personal route selection must have separate toggles.

### 6.4 Model behavior

- Direction-specific and time-of-week predictions; smooth adjacent time buckets and decay old observations.
- Cold start uses map baseline or licensed provider speed; small sample sizes shrink to general prior.
- Learn intersection waits, traffic-signal patterns, turns, start/parking delays, and favored paths.
- Segment observations and turn costs may influence actual search edge weights **only if backend integration supports those weights**; post-hoc ETA rescore alone cannot guarantee personalized routes.
- Store confidence, count, age, range, data provenance and explanation such as “frequently slower in evening.”
- Include controls: record history, pause, exclude trip, delete trip, clear all data, import/export, personalization strength, ETA personalization, routing personalization.

### 6.5 Backend integration

**Valhalla:** documented live traffic overlays plus five-minute weekly predicted speed profiles. Build translator to Valhalla directed edges, feed external live traffic and predicted profiles in supported tile formats. Evaluate update latency, rebuild requirements and conflicts when using mixed personal/provider speeds. Historical predicted speed profiles may be baked into tiles; immediate per-trip personalization may require a live-style overlay or engine modification.

**GraphHopper:** custom models can alter encoded-value-dependent speed/priority and turn penalties; arbitrary per-directed-edge, frequently changing personalized costs are not a simple request-level JSON feature. Prototype a custom weighting / encoded value, flexible routing profile and incremental graph updating strategy. CH preprocessing can invalidate cost changes; flexible search may have latency and battery penalties. Do not claim a final solution until its on-device performance is measured.

### 6.6 Model evaluation

Keep three configurations per engine: baseline, personalized ETA only, personalized ETA+routing. Metrics include MAE / median absolute ETA error, p90 error, calibration, reroute stability, route-distance regret proxies and actual trip-time comparison on comparable recurring journeys. Log all feature and dataset versions.

## 7. Traffic providers: HERE and TomTom

### 7.1 Required provider abstraction

`TrafficSource` returns `TrafficObservation(segmentGeometry, direction, speed, freeFlowSpeed, jamFactor?, timestamp, confidence, coverage, attribution, licenseTag)` and `Incident(id, geometry, type, start/end, severity, lane/road impact, source, updatedAt, confidence)`. Normalize units and timezone, classify closures separately, retain originals for provenance, and match each observation to canonical road segments with probability. Never simply average provider values for different roads or carriageways.

### 7.2 Provider-specific exploration

| Dimension | HERE | TomTom |
|---|---|---|
| Flow / congestion | Traffic API v7 flow; SDK traffic layers | Traffic Flow API / segment data / tiles |
| Incidents | Traffic API v7 incidents; route corridor querying | Traffic incidents and incident details |
| Native offline navigation | HERE Navigate SDK supports offline packages and guidance | TomTom Navigation SDK supports offline map workflows; verify license/SDK compatibility |
| Proposed initial test | Bounding box plus route corridor; incident categories and timestamps | Flow segment around coordinates, incident corridor/tile, freshness |
| Important uncertainty | API quota, permitted caching and using feed in independent OSM engine | API quota, redistribution/caching, map-matching terms and independent-engine use |

**Do not assume** traffic provider subscriptions permit converting and permanently storing live speed feeds or combining them with competitor datasets. Obtain current plan terms. For a single-user app, the price and contract can be more consequential than engineering effort. API selection is independent from the option to use HERE/TomTom's own SDK for offline maps.

### 7.3 Test plan / scoring

Run both sources simultaneously (within license and limits) over a defined familiar driving corridor and at least one unfamiliar corridor. Compare: road coverage, direction matching, freshness, sudden slowdowns, incident geolocation, closure fidelity, reporting lag, query volume, cost, offline-stale behavior, and agreement with actual GPS traversal. Provider mode: HERE / TomTom / automatic preferred / none; show provenance and last refresh. Automatic mode picks per-area source based on observed reliability, coverage and freshness; do not silently merge conflicting incidents.

### 7.4 Police / debris / user hazards

Commercial incident feeds can contain accidents, hazards, debris or closures but **police reports are not assured**. Waze for Cities data is permissioned/partner-restricted, not a general consumer API. No scraping or fabricated reports. Implement optional personal reports with quick categories (police, object, vehicle on shoulder, crash, roadwork, weather, closure), along-route filtering, short TTL by type, confirmation/negative confirmation, and collision-resistant IDs. Reports available only on the owner's device are useful as personal reminders but are not a substitute for community data. When reporting while driving, prioritize voice/one-tap actions and Android Auto's interaction restrictions.

## 8. Navigation session and Android Auto

One `NavigationSession` owns the active route, map matching, guidance progress, rerouting decisions, user overrides and destination; phone and Android Auto are projections of the same state. Compose renders phone controls; Android for Cars App Library (`androidx.car.app`) displays a permitted navigation template and custom map surface. Current documentation identifies `NavigationTemplate` and `MapWithContentTemplate`; the app must declare the navigation category and relevant template/map permissions and satisfy driving-distraction rules. MapLibre rendering on the car `Surface` requires a dedicated renderer adapter and proof-of-concept; phone Compose views cannot simply be mirrored.

Android Auto initial installation: first use official navigation app templates and Desktop Head Unit testing, then actual head unit. KingInstaller/Shizuku may affect installation provenance but **do not grant Android Auto navigation-template authorization or guarantee app visibility**. Keep private Play testing or another compliant distribution route as a fallback; test on the target Android Auto/head-unit version. Do not bypass driver distraction restrictions.

During active guidance, offer route overview, next maneuver, ETA, hazards, one-tap alternatives and constrained simple route edits. Full free-form drag editing belongs on the phone when stationary. Show lane guidance only if licensed data/graph supports it. Use offline TTS fallback; preserve guidance through connectivity transitions and reattachment of Android Auto.

## 9. A/B testing design

For each request, capture identical starting point, destination, anchors, restrictions, map revision, traffic snapshot time and personalized model version. Send parallel requests to Valhalla and GraphHopper; retain raw routes and normalized route geometries. Record engine response time, path overlap, ETA and coverage. Let user explicitly select a route and record the actually driven path and arrival.

Two comparison modes:

1. **Shadow mode (default):** compute both, navigate one. Compare route outputs and ETA only for roads actually traversed; do not treat the unchosen route's predicted duration as its actual outcome.
2. **Alternating crossover:** on repeated comparable journeys, alternate selected engine or selected route with consent; stratify by departure window, weather and traffic. Compare observed travel time and ETA error across enough matched trips.

Add unpersonalized baseline for both engines. Show aggregate sample count, confidence interval/dispersion, predicted ETA bias and model version. Test rerouting in a controlled simulator as well as real trips. Avoid rewarding reckless speed or treating a single unusually fast trip as evidence of route quality.

## 10. UX and screens

**Design language:** uncluttered, modern, dark-first theme with legible high-contrast map and restrained accent colors. Distinguish maps/roads vs route overlays; avoid redundant control bars. Default to one-tap actions while driving.

- Home: search, recent/saved places, current position, quick resume, offline coverage status.
- Route preview: three candidate cards, tap-to-highlight map paths, source engine/traffic provider, personalized and baseline ETAs, confidence, tolls/closures.
- Route editor: waypoint list with ordering, via/stop toggle, add via tap/drag, segment lock, avoid polygon, undo/redo, saved route profile.
- Navigation: clear next turn, lane guidance when available, distance, ETA range, route alternatives, muted alerts, report action, instant reroute reason.
- Downloads: map region/corridor selector, estimated sizes, per-component downloads, pause/update/delete, checksum, version/coverage; offline readiness diagnostic.
- Traffic lab: HERE/TomTom A/B overlays, coverage, freshness and sample queries, incident filtering and source license notices.
- Learning lab: segment history, observed p50/p80 times, model confidence, mode selectors, explain route preference, privacy controls.
- Engine lab: Valhalla / GraphHopper / side-by-side, baseline versus personalized, route deviation metrics and benchmarks.
- Settings: storage, permissions, background tracking, alerts, voice, units, map styles, default routing and traffic behavior.

## 11. Data model (initial)

```text
RoadSegment(id, direction, geometryHash, lengthMeters, speedLimit?, graphRevision)
GraphEdgeMapping(canonicalSegmentId, engine, engineEdgeId, graphRevision, confidence)
Trip(id, startAt, endAt, source, selectedEngine, mapRevision, modelRevision)
GpsSample(tripId, at, lat, lon, accuracyM, speedMps?, bearing?, quality)
Traversal(tripId, segmentId, entryAt, exitAt, seconds, contextId, matchConfidence)
TurnTraversal(tripId, fromSegmentId, toSegmentId, seconds, contextId)
LearnedCost(segmentId, contextKey, samples, medianSeconds, p80Seconds, variance, updatedAt)
RouteRequest(id, origin, destination, viaPoints, locks, avoidZones, settings, issuedAt)
RouteCandidate(requestId, engine, geometry, edgeIds, etaSeconds, baseEta, confidence)
TrafficObservation(provider, externalId, segmentId?, speed, freeFlow, observedAt, expiresAt)
Incident(provider, externalId, category, geometry, confidence, updatedAt, expiry)
OfflinePackage(id, region, mapRevision, components, bytes, checksum, status)
Benchmark(id, requestId, chosenCandidate, actualSeconds?, routeOverlap, contextHash)
```

All GPS history stays local by default and is encrypted at rest. If optional synchronization/backend is added, minimize uploaded data and use explicit opt-in. Separate third-party data retention from personal observations to honor vendor agreements. Keep a record of all model/data provenance.

## 12. Performance and reliability targets

Targets are proposals to validate on the Galaxy S25+ with real packages, not promises.

- Warm navigation display: target <2 seconds; cold start target <5 seconds.
- Route calculation inside installed regional graph: p95 <5 seconds per engine for typical regional trips; measure separately for multi-anchor and alternatives.
- Reroute after confirmed off-route: target <5 seconds where supported; don't oscillate on GPS noise.
- Map render: target smooth 30–60 fps while guiding, adaptive under thermal pressure.
- Offline: zero network requests required for core navigation; verify in airplane mode.
- Recorder: adaptive GPS sampling to limit battery, bounded raw trace retention, thermal/battery monitoring.
- Traffic: explicit `fresh`, `stale`, `unavailable` state per observation; never present stale hazards as verified current.
- Storage: region package quota, checksum, resumable downloads, atomic swap and rollback.
- Crash recovery: restore destination, shaping anchors, selected engine and last route without corrupting learning data.

## 13. Security, privacy and licenses

GPS history can reveal sensitive home/work patterns. Use Android Keystore for encryption keys, no cloud upload by default, local backups opt-in, per-trip exclusions and deletion, and disable collection whenever requested. Keys stored in Android Keystore should be considered device-bound for backup design. Avoid persistent location collection absent a visible foreground-service notification and permission. Export files may be sensitive and should support encryption.

Track provider licensing separately: HERE, TomTom, MapLibre render styles/tiles, OSM/ODbL attribution, hosted geocoding and any public hazard feeds. Vendor API terms may restrict storing raw live traffic, merging across vendors or displaying on third-party maps. A private one-user project still requires applicable API and copyright license compliance. Do not use Google/Waze scraping as a foundation.

## 14. Engineering risks and decisions

| Risk | Severity | Mitigation / decision gate |
|---|---|---|
| Both routing engines truly offline on Android | Critical | Early on-device graph loading/routing benchmark for each; fallback architecture ready. |
| Personalized edge costs used *during search*, not merely post-ETA | Critical | Prototype engine-level edge integration before training elaborate model. |
| HERE/TomTom independent-routing data rights | Critical | Confirm license, storage, georeferencing and cost with provider before production integration. |
| Graph update changes road IDs | High | Canonical segment catalog and graph-version migration. |
| Android Auto private APK visibility | High | Early real-head-unit prototype; do not rely on KingInstaller alone. |
| Route locks produce impossible journey | High | Topological validation, explicit error and user-adjustable anchor placement. |
| Offline package size and RAM | High | Regional extraction, graph compression, per-engine optional install, measured storage estimates. |
| Live hazard feed lacks police reports | Medium | Clear data availability labels, personal reports, don't promise a Waze clone. |
| Conflicting traffic observations | Medium | Per-provider provenance, confidence arbitration, no double counting. |
| Battery drain from passive collection | Medium | Adaptive GPS, opt-in schedule and telemetry metrics. |

## 15. Implementation roadmap and validation gates

**Phase 0 — Feasibility spikes (do first).** Verify Android Auto custom map surface on actual head unit; route one OSM region with Valhalla and GraphHopper using a shared API; prototype offline graph loading on the phone; verify HERE/TomTom API plans and contractual use; build 1-mile-per-road-edge learned-cost injection for both engines. Exit only with measured compatibility and fallback choices.

**Phase 1 — Navigation foundation.** Kotlin Compose app, MapLibre rendering and licensed offline tiles, location tracking, typed route domain, route preview, maneuvers and TTS, initial regional search. Start with PC-hosted routing for development, explicitly online.

**Phase 2 — Route shaping / alternatives.** Native alternate routes, draggable through points, stops versus via, pinned segments/corridors, avoid zones, saved routes, rerouting with preserved constraints, route validation and regression suite.

**Phase 3 — True offline.** Region/corridor package build/download, both graph formats as feasible, search index, checksum/update UI, offline diagnostics, airplane-mode complete flow, power/recovery tests.

**Phase 4 — Traffic lab and incidents.** HERE/TomTom adapters, coverage benchmark, license-aware storage, traffic overlays, incident corridor filtering, confidence and last-updated badges, simple reports.

**Phase 5 — Personal learning.** Opt-in recorder, map matching, directional segment aggregation, context model, travel-time prediction; independently enable ETA and search-cost personalization; calibration and explanations.

**Phase 6 — Engine comparison.** Same-snapshot parallel routing, shadow testing, baseline controls, crossover trials, scorecard, data export, performance profiling.

**Phase 7 — Android Auto stabilization.** Template compliance, Surface rendering, same-session state, constraints on editing, guidance/audio behavior, real car display regression, private distribution strategy.

### 15.1 Proof-of-concept acceptance checklist

- [ ] An APK renders a custom navigation map in Android Auto on the target setup.
- [ ] Valhalla and GraphHopper both return correct maneuver lists for identical origin/destination and shaping points.
- [ ] Deliberate route-through anchor changes the chosen road for each backend.
- [ ] Offline display + offline routing + offline search + reroute all work in airplane mode for at least one chosen region.
- [ ] Sample personal segment cost demonstrably changes the search route on both engines (not just displayed ETA).
- [ ] HERE and TomTom sample observations are timestamped, correctly map-matched and compared over the same corridor.
- [ ] Offline test does not mislabel cached traffic as live; reports expire or show staleness.
- [ ] Parallel A/B logging distinguishes *predicted unchosen route* from *actually measured route*.
- [ ] Complete trip history can be disabled, exported and deleted locally.

## 16. Open decisions

1. On-device-first implementation vs a development server and subsequent mobile port (recommended: prove on-device early, use server for fast iteration).
2. Exact regional download scheme and tile licensing; choose source with authorized offline rights.
3. Allow a proprietary HERE Navigate SDK offline fallback if either open-source mobile routing runtime fails? (recommended yes, optional adapter subject to license).
4. Exact normalization of learned costs for roads with few observations, traffic already accounted for and changing conditions.
5. Whether to collect passive history when not navigating (recommended opt-in, visible status, aggressive battery controls).
6. How to expose historical *observed behavior* versus policy-constrained ETA/routing modes; maintain clear transparency about predictions.
7. Select default voice, visual theme, and route-alternative diversity threshold after prototypes.

## 17. Primary references (checked November 5, 2026)

1. Valhalla speed hierarchy, live overlays and predicted traffic: https://valhalla.github.io/valhalla/concepts/speeds/
2. Valhalla route locations, `break`/`through`/`via`: https://valhalla.github.io/valhalla/api/route/api-reference/
3. Valhalla map matching: https://valhalla.github.io/valhalla/api/map-matching/api-reference/
4. GraphHopper open-source repository and offline mobile support note: https://github.com/graphhopper/graphhopper
5. GraphHopper docs and custom models: https://docs.graphhopper.com/
6. HERE Android offline maps and region downloader: https://docs.here.com/here-sdk/docs/android-offline-maps
7. HERE Android traffic engine: https://docs.here.com/here-sdk/docs/android-traffic-engine
8. HERE Android SDK feature comparison: https://docs.here.com/here-sdk/docs/android-introduction-feature-list
9. TomTom Traffic Flow Segment Data: https://docs.tomtom.com/traffic-api/documentation/tomtom-maps/v1/traffic-flow/flow-segment-data
10. TomTom Android Navigation SDK traffic module: https://docs.tomtom.com/navigation/android/guides/map-data/traffic
11. MapLibre Compose offline packs: https://maplibre.org/maplibre-compose/offline/
12. Android for Cars navigation apps: https://developer.android.com/training/cars/apps/navigation
13. Android for Cars map surface: https://developer.android.com/training/cars/apps/library/draw-maps
14. OpenStreetMap Foundation standard tile usage policy: https://operations.osmfoundation.org/policies/tiles/

**Document status:** Proposed design, now with a Phase 0–2 implementation in this repository (`README.md` maps code to sections). API entitlement, pricing, permitted data transformations, full on-device engine builds, and actual Android Auto private-install compatibility still require hands-on validation. The APK builds but has not yet run on the target phone or head unit.
