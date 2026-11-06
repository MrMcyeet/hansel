# Spike: GraphHopper on-device

**Status:** compiles (see root README for the exact command); **not yet measured on the Galaxy S25+.** Treat as unproven
until the airplane-mode test in DESIGN §4.4 passes.

## Why this exists
DESIGN §4.3: GraphHopper's repo says offline mobile routing is no longer officially supported, though Java-on-Android
may still work. §14 rates "both engines truly offline on Android" as the top risk. This module is the measurement
harness: it wraps `graphhopper-core` in the same `RoutingEngine` interface the app already uses, so the Engine Lab can
compare it with the server engines on identical requests.

## How to try it
1. Build a graph on the PC with the **same GraphHopper version** (11.x) and the dev-server config
   (`dev-server/graphhopper/config.yml`): `docker compose run graphhopper --import` produces `dev-server/data/graphhopper/`.
   The on-device loader needs the graph folder (`nodes`, `edges`, `geometry`, `properties`, `location_index`, …).
2. Push it: `adb push dev-server/data/graphhopper /sdcard/Android/data/dev.hansel/files/graphhopper/gulf-south`
   (Mississippi+Louisiana+Alabama graph is on the order of a few hundred MB; expect RAM use of 1–2 GB on load — this is
   exactly what the spike measures).
3. Enable the module: add `hansel.spike.graphhopperOnDevice=true` to `gradle.properties` and wire
   `OnDeviceGraphHopperEngine(File(context.getExternalFilesDir(null), "graphhopper/gulf-south"))` into
   `AppGraph.engines()` under a new `EngineId` (requires adding `GRAPHHOPPER_LOCAL` to `EngineId`).
4. Record: load time, heap after load, p50/p95 route time for a 200 km trip, battery over 30 min of rerouting.

## Known limitations of the spike
- No avoid polygons / custom model yet (`capabilities.avoidPolygons = false`); the server adapter has them.
- Alternatives require `ch.disable=true` (flexible mode) which is the slow path on a phone — measure separately.
- `graphhopper-core` pulls Jackson, HPPC, JTS and Janino. Janino compiles custom-model expressions at runtime; if
  that fails on ART, custom models must be pre-compiled or the profile kept to encoded-value-only rules.
- If the Java core proves impractical, DESIGN §4.3's fallback is a proven offline SDK (e.g. HERE Navigate) behind the
  same `RoutingEngine` interface.
