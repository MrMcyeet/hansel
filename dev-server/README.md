# Hansel dev server (PC-hosted routing + geocoding)

Runs **Valhalla**, **GraphHopper** and **Nominatim** in Docker on the owner's PC for the
Mississippi + Louisiana + Alabama extract, reachable from the phone over Wi-Fi.

> **This is online, PC-hosted routing for development only** (DESIGN.md §3 "Optional
> development PC / LAN service", §4.3, §15 Phase 1: "Start with PC-hosted routing for
> development, explicitly online"). It is **not** offline phone support. The app must not
> label a route from these services as offline, and the Phase 3 airplane-mode test (§4.4)
> cannot be passed with this setup.

## Prerequisites

- Docker Desktop (Windows or Linux; Docker Engine + Compose v2 on Linux is fine).
  Give it at least **8 GB RAM** and the disk below in Settings → Resources if on Windows/macOS.
- `curl`, plus `jq` or `python3` for `smoke.sh` output. `md5sum` (Linux/Git Bash) or `md5` (macOS).
- Windows: either run the `.sh` scripts from **Git Bash** or **WSL**, or use the `.ps1` twins
  (`fetch-extract.ps1`, `build-graphs.ps1`). `smoke.sh` has no `.ps1` twin; run it from Git Bash/WSL.
- Phone and PC on the same Wi-Fi network, and Windows Firewall allowing inbound TCP 8002/8989/8080
  on the private network profile (Docker Desktop normally prompts for this on first `up`).

## Disk and RAM expectations

Input (Geofabrik, 2026-11-05): Mississippi 87 MB, Louisiana 134 MB, Alabama 131 MB → merged
~350 MB PBF.

| Service | Disk (estimate) | RAM while building | RAM while serving | First build time (estimate) |
|---|---|---|---|---|
| Valhalla tiles + admins/timezones | ~1–2 GB | 2–4 GB | <1 GB | 5–15 min |
| GraphHopper graph-cache (car, CH+LM, turn costs) | ~1 GB | ≤4 GB heap (`GRAPHHOPPER_JAVA_OPTS`) | ~1–2 GB | 5–20 min |
| Nominatim Postgres (`full` style) | ~15–30 GB | 4–8 GB | 1–2 GB | 30 min – 3 h |
| Docker images | ~4 GB | | | |

These are **extrapolations, not measurements** — nothing here has been executed yet (see
"Verified against"). Anchors used:

- GraphHopper's deploy guide gives ~60 GB RAM / ~3 h for the full planet import (~80 GB PBF) and
  says CH/LM preparation needs more heap than serving; a 350 MB extract is ~1/200 of that
  (<https://github.com/graphhopper/graphhopper/blob/master/docs/core/deploy.md>).
- Nominatim's install docs: 2 GB RAM minimum, full planet ~2.5 days and ≥1 TB; they give no
  per-country numbers (<https://nominatim.org/release-docs/latest/admin/Installation/>).
  Postgres' maintenance work is the slow part, so NVMe helps more than CPU.
- The mediagis image defaults to `maintenance_work_mem=10GB` / `effective_cache_size=24GB`;
  `.env.example` lowers these for a 16 GB desktop.

## Step by step

```sh
cd dev-server
cp .env.example .env            # edit ports / threads / heap if needed
scripts/fetch-extract.sh        # downloads 3 PBFs, verifies .md5, osmium-merges -> data/osm/gulf-south.osm.pbf
scripts/build-graphs.sh         # Valhalla tiles, GraphHopper import, then docker compose up -d
docker compose logs -f nominatim   # watch the Nominatim import (long); Ctrl-C does not stop it
scripts/smoke.sh                # once all three answer
```

Windows (PowerShell): `scripts\fetch-extract.ps1`, `scripts\build-graphs.ps1`, then
`scripts/smoke.sh` from Git Bash or WSL.

What the scripts do:

1. **fetch-extract** – idempotent. Re-downloads only when Geofabrik's `.md5` changes; re-merges only
   when an input is newer than the merged file. Merge uses `osmium merge` from the
   `iboates/osmium` image (the widely used `stefda/osmium-tool` image was last pushed in 2017).
2. **build-graphs**
   - Copies `valhalla/valhalla.seed.json` → `data/valhalla/valhalla.json` (first run only). The
     Valhalla container merges upstream defaults into it (`update_existing_config=True`) while
     keeping our `service_limits`: `max_alternates: 3` (upstream default 2), `auto.max_locations: 50`
     (default 20), `max_timedep_distance: 500 km`, `status.allow_verbose: true`.
   - `docker compose run --rm -e serve_tiles=False valhalla` builds tiles, admin DB and timezone DB,
     then exits. Later `up`s find `valhalla_tiles.tar` and skip the build (`use_tiles_ignore_pbf=True`).
     After refreshing the extract: `docker compose run --rm -e serve_tiles=False -e force_rebuild=True valhalla`.
   - `docker compose run --rm --no-deps graphhopper --import ...` parses OSM, prepares CH and LM for
     the `car` profile, writes `data/graphhopper/graph-cache`. To rebuild, delete that directory.
   - `docker compose up -d`. Nominatim starts importing on its first start and persists the result in
     the `nominatim-pg` volume; later starts are quick.
3. **smoke** – curl checks (Valhalla `/status`, `/route` with `alternates: 2`; GraphHopper `/health`,
   POST `/route` with `algorithm=alternative_route` + `ch.disable=true`, GET `/route` on CH;
   Nominatim `/status`, `/search?q=Hattiesburg`, `/reverse`) for Jackson, MS → New Orleans, LA and
   prints time/distance of every returned path. `scripts/smoke.sh 192.168.1.50` tests from another
   machine.

## Find the PC's LAN IP and configure the app

- Windows: `ipconfig` → "IPv4 Address" of the Wi-Fi or Ethernet adapter (not the
  `vEthernet (WSL)` / Docker adapters, which are 172.x).
- Linux: `ip -4 addr show scope global` or `hostname -I`.
- Prefer a DHCP reservation on the router so the address does not change.

Enter in the app's **Settings** screen (no auth, plain HTTP, LAN only):

| Setting | Value | Notes |
|---|---|---|
| Valhalla base URL | `http://<pc-ip>:8002` | endpoints `/route`, `/status`, `/trace_route`, `/locate`; JSON POST or `?json=` |
| GraphHopper base URL | `http://<pc-ip>:8989` | endpoints `/route` (GET or POST JSON), `/health`, `/info`; profile name `car` |
| Nominatim base URL | `http://<pc-ip>:8080` | endpoints `/search`, `/reverse`, `/status`; `format=jsonv2` |

Ports are overridable in `.env` (`VALHALLA_PORT`, `GRAPHHOPPER_PORT`, `NOMINATIM_PORT`).
The app's cleartext-traffic policy must allow plain `http://` to private addresses for this to work.

What the app can rely on from this configuration:

- **Valhalla**: `costing: auto`, up to **3 `alternates`**, up to **50 locations** per request,
  `break`/`through`/`via`/`break_through` location types, `date_time` routing up to 500 km
  (timezone DB built), admin info (admin DB built), default-speeds enhancement on. No elevation,
  no transit, no live traffic archive.
- **GraphHopper**: profile **`car`** with turn restrictions and U-turn cost 60 s; CH (fast default)
  **and** LM; `ch.disable=true` plus a request `custom_model` works (LM is used if the model only
  raises weights, else send `lm.disable=true` too); `algorithm=alternative_route` with
  `alternative_route.max_paths` up to 3; `routing.max_visited_nodes = 5,000,000`;
  `routing.non_ch.max_waypoint_distance = 1,000 km`. Encoded values available to custom models:
  `car_access, car_average_speed, road_access, road_class, road_class_link, road_environment,
  roundabout, toll, surface, max_speed, ferry_speed`. Non-motorized ways are excluded from the graph.
- **Nominatim**: `full` import style (POIs included), no Wikipedia importance, no US postcode or
  TIGER house-number data (set `IMPORT_US_POSTCODES` / `IMPORT_TIGER_ADDRESSES` in
  `docker-compose.yml` before the first start to add them), no replication. Debug output is off.

## Files

```
dev-server/
├─ docker-compose.yml          valhalla, graphhopper, nominatim (+ photon, commented out)
├─ .env.example                regions, PBF name, ports, threads, heap, Postgres tuning
├─ valhalla/valhalla.seed.json partial valhalla.json with our service_limits
├─ graphhopper/config.yml      car profile (custom model + CH + LM), encoded values, limits
├─ scripts/fetch-extract.sh|.ps1
├─ scripts/build-graphs.sh|.ps1
├─ scripts/smoke.sh
└─ data/                       generated; git-ignore it
   ├─ osm/                     *-latest.osm.pbf, *.md5, gulf-south.osm.pbf
   ├─ valhalla/                valhalla.json, valhalla_tiles.tar, admins.sqlite, timezones.sqlite, file_hashes.txt
   └─ graphhopper/             graph-cache/, logs/
```

Nominatim's Postgres lives in the named volumes `hansel-dev_nominatim-pg` and
`hansel-dev_nominatim-flatnode`; `docker compose down -v` deletes them (full re-import next time).

## Licensing and attribution

- Map data is © OpenStreetMap contributors, **ODbL 1.0**. Anything derived from it (tiles, graphs,
  the geocoder database, and any exported routes/places) is a derivative database; the app UI must
  show "© OpenStreetMap contributors" wherever map data or search results are displayed
  (<https://www.openstreetmap.org/copyright>). DESIGN.md §13 tracks this with the other licenses.
- Geofabrik extracts are free to download; their server is rate-limited, so the fetch script keeps
  the three files and only re-downloads on checksum change.
- The **Nominatim usage policy** (1 request/s, identifying User-Agent, no autocomplete) applies to
  the public `nominatim.openstreetmap.org` service only. It does **not** apply to this self-hosted
  instance; the `search` module still sends a `Hansel/0.1 (personal)` User-Agent by default so it
  would behave if ever pointed at a public server.
- Software: Valhalla (MIT), GraphHopper (Apache-2.0), Nominatim (GPL-2.0, server-side only; the app
  only talks HTTP), osmium-tool (GPL-3.0, build-time only).

## Verified against (2026-11-05)

Config was **checked against the documents below, not executed** — the machine this was written on
has no Docker daemon. `docker compose config` (schema validation) and a YAML/JSON parse pass;
shell scripts pass `sh -n`. First real run should expect to fix small things (e.g. the
`--import`/`-o` handling of `graphhopper.sh`, Nominatim's first-start timing).

- Valhalla scripted image, env vars (`use_tiles_ignore_pbf`, `force_rebuild`, `build_admins`,
  `build_time_zones`, `serve_tiles`, `server_threads`, `update_existing_config`, `use_default_speeds_config`),
  `/custom_files` layout: <https://github.com/valhalla/valhalla/blob/master/docker/README.md>,
  <https://github.com/valhalla/valhalla/blob/master/docker/scripts/configure_valhalla.sh>,
  <https://github.com/valhalla/valhalla/blob/master/docker/scripts/helpers.sh>.
  Image: `ghcr.io/valhalla/valhalla-scripted:latest`. The previous home of this image,
  <https://github.com/gis-ops/docker-valhalla> (`ghcr.io/nilsnolde/docker-valhalla/valhalla`), was
  archived 2026-03-03 and points upstream.
- Valhalla default `service_limits` (`max_alternates: 2`, `auto.max_locations: 20`) and the
  `--merge-config` behaviour (existing values preserved, missing keys added):
  <https://github.com/valhalla/valhalla/blob/master/scripts/valhalla_build_config>.
- GraphHopper image `israelhikingmap/graphhopper` (tags `11.0` 2026-04-03, `12.0-pre3`, nightly
  `latest`; Java 25; `JAVA_OPTS` default `-Xmx1g -Xms1g`; `/data` volume; entrypoint
  `./graphhopper.sh -c config-example.yml`):
  <https://github.com/IsraelHikingMap/graphhopper-docker-image-push>,
  <https://hub.docker.com/r/israelhikingmap/graphhopper/tags>,
  Dockerfile and `graphhopper.sh` (`-i/-c/-o/--host/--port/--import` flags) in that repo.
- GraphHopper config keys, `profiles_ch`/`profiles_lm`, `graph.encoded_values`,
  `routing.max_visited_nodes`, `routing.non_ch.max_waypoint_distance`:
  <https://github.com/graphhopper/graphhopper/blob/master/config-example.yml>.
  Request params `ch.disable`, `algorithm=alternative_route`, `alternative_route.max_paths`,
  `custom_model` (POST only): <https://github.com/graphhopper/graphhopper/blob/master/docs/web/api-doc.md>.
  Encoded value names `toll`, `surface`, `road_access`, `road_class`, `road_environment`:
  <https://github.com/graphhopper/graphhopper/blob/master/docs/core/custom-models.md>.
  Built-in `car.json` and the encoded values it needs:
  <https://github.com/graphhopper/graphhopper/blob/master/core/src/main/resources/com/graphhopper/custom_models/car.json>.
- Nominatim image `mediagis/nominatim:5.3` (5.3.3, 2026-10-26), env vars (`PBF_PATH`, `REPLICATION_URL`,
  `UPDATE_MODE`, `IMPORT_WIKIPEDIA`, `IMPORT_US_POSTCODES`, `IMPORT_TIGER_ADDRESSES`, `IMPORT_STYLE`,
  `FREEZE`, `THREADS`, `POSTGRES_*`), persistent volume `/var/lib/postgresql/16/main`, flatnode mount,
  `--shm-size`: <https://github.com/mediagis/nominatim-docker> (README.md and howto.md),
  <https://hub.docker.com/r/mediagis/nominatim/tags>.
- Nominatim hardware notes: <https://nominatim.org/release-docs/latest/admin/Installation/>.
- osmium image `iboates/osmium` (1.19.0, 2026-11-02; `/data` mount, `osmium` entrypoint):
  <https://hub.docker.com/r/iboates/osmium>. `stefda/osmium-tool` last pushed 2017-12-19.
- Geofabrik file names, sizes and `.md5` sidecars: <https://download.geofabrik.de/north-america/us.html>,
  <https://download.geofabrik.de/north-america/us/alabama.html>.
