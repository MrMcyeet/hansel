#!/bin/sh
# One-shot graph build for the Hansel dev server:
#   1. seed data/valhalla/valhalla.json with our service_limits (first run only)
#   2. build Valhalla tiles        (docker compose run, serve_tiles=False -> exits when done)
#   3. import GraphHopper graph    (docker compose run graphhopper --import)
#   4. docker compose up -d        (Nominatim starts its own import on first start - long!)
# Prints elapsed time and output sizes per step. Safe to re-run: steps whose output exists are skipped.
# Windows: see build-graphs.ps1 (or run this from Git Bash / WSL with Docker Desktop).

set -eu

DEV_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$DEV_DIR"

# shellcheck disable=SC1091
[ -f .env ] && . ./.env
: "${HANSEL_PBF:=gulf-south.osm.pbf}"

PBF="$DEV_DIR/data/osm/$HANSEL_PBF"
if [ ! -f "$PBF" ]; then
    echo "ERROR: $PBF not found. Run scripts/fetch-extract.sh first." >&2
    exit 1
fi
if [ ! -f .env ]; then
    echo "NOTE: no .env found; using defaults from docker-compose.yml (cp .env.example .env to customise)."
fi

mkdir -p data/valhalla data/graphhopper/logs

now() { date +%s; }
elapsed() { # seconds -> "Xm Ys"
    awk -v s="$1" 'BEGIN { printf "%dm %02ds", s / 60, s % 60 }'
}
size_of() { # human-readable size of a file or directory
    du -sh "$1" 2>/dev/null | cut -f1
}

# --- 1. Valhalla config seed -------------------------------------------------------------
if [ ! -f data/valhalla/valhalla.json ]; then
    cp valhalla/valhalla.seed.json data/valhalla/valhalla.json
    echo "== seeded data/valhalla/valhalla.json (service_limits: max_alternates=3, auto.max_locations=50)"
else
    echo "== data/valhalla/valhalla.json exists; not touching it"
fi

# --- 2. Valhalla tiles -------------------------------------------------------------------
if [ -f data/valhalla/valhalla_tiles.tar ]; then
    echo "== Valhalla tiles exist ($(size_of data/valhalla/valhalla_tiles.tar)); skipping build."
    echo "   To rebuild after a new extract: docker compose run --rm -e serve_tiles=False -e force_rebuild=True valhalla"
else
    echo "== Building Valhalla tiles from $HANSEL_PBF ..."
    t0=$(now)
    docker compose run --rm -e serve_tiles=False valhalla
    echo "   Valhalla build: $(elapsed $(( $(now) - t0 )))"
    echo "   valhalla_tiles.tar: $(size_of data/valhalla/valhalla_tiles.tar)   admins.sqlite: $(size_of data/valhalla/admins.sqlite)"
fi

# --- 3. GraphHopper import ----------------------------------------------------------------
if [ -d data/graphhopper/graph-cache ] && [ -n "$(ls -A data/graphhopper/graph-cache 2>/dev/null)" ]; then
    echo "== GraphHopper graph-cache exists ($(size_of data/graphhopper/graph-cache)); skipping import."
    echo "   To rebuild: rm -rf data/graphhopper/graph-cache && scripts/build-graphs.sh"
else
    echo "== Importing GraphHopper graph from $HANSEL_PBF (OSM parse + CH + LM prep) ..."
    t0=$(now)
    docker compose run --rm --no-deps graphhopper \
        --import -i "/data/osm/$HANSEL_PBF" -c /data/config.yml -o /data/graph-cache
    echo "   GraphHopper import: $(elapsed $(( $(now) - t0 )))"
    echo "   graph-cache: $(size_of data/graphhopper/graph-cache)"
fi

# --- 4. Bring everything up ---------------------------------------------------------------
echo "== docker compose up -d"
docker compose up -d
echo
echo "Valhalla and GraphHopper serve as soon as their health checks pass (~1-2 min)."
echo "Nominatim imports the PBF into Postgres on its FIRST start; expect tens of minutes to a few"
echo "hours for a 3-state extract. Follow it with:   docker compose logs -f nominatim"
echo "Then run:   scripts/smoke.sh"
