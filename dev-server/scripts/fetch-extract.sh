#!/bin/sh
# Download the Geofabrik extracts listed in HANSEL_REGIONS into data/osm/, verify their .md5,
# and merge them into data/osm/$HANSEL_PBF with `osmium merge` (run via Docker).
# Idempotent: re-running only re-downloads when the checksum does not match and only re-merges
# when an input is newer than the merged file. Windows: see fetch-extract.ps1.
#
# Requires: curl, docker, md5sum (or `md5` on macOS). POSIX sh.

set -eu

DEV_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$DEV_DIR"

# shellcheck disable=SC1091
[ -f .env ] && . ./.env
: "${HANSEL_REGIONS:=north-america/us/mississippi north-america/us/louisiana north-america/us/alabama}"
: "${HANSEL_PBF:=gulf-south.osm.pbf}"
: "${OSMIUM_IMAGE:=iboates/osmium:latest}"
GEOFABRIK=https://download.geofabrik.de

OSM_DIR="$DEV_DIR/data/osm"
mkdir -p "$OSM_DIR"

human() { # bytes -> MiB
    awk -v b="$1" 'BEGIN { printf "%.1f MiB", b / 1048576 }'
}
size_of() {
    wc -c < "$1" | tr -d ' '
}
md5_of() {
    if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | cut -d' ' -f1
    elif command -v md5 >/dev/null 2>&1; then md5 -q "$1"
    else echo "WARN: no md5sum/md5 found; skipping checksum verification" >&2; echo skip
    fi
}

inputs=""
for region in $HANSEL_REGIONS; do
    name=$(basename "$region")
    pbf="$OSM_DIR/$name-latest.osm.pbf"
    url="$GEOFABRIK/$region-latest.osm.pbf"

    # Geofabrik publishes "<md5>  <file>" next to each extract.
    echo "== $name"
    curl -fsSL -o "$pbf.md5" "$url.md5"
    expected=$(cut -d' ' -f1 "$pbf.md5")

    if [ -f "$pbf" ]; then
        actual=$(md5_of "$pbf")
        if [ "$actual" = "$expected" ] || [ "$actual" = skip ]; then
            echo "   up to date: $pbf ($(human "$(size_of "$pbf")"))"
            inputs="$inputs $name-latest.osm.pbf"
            continue
        fi
        echo "   checksum changed upstream; re-downloading"
    fi

    echo "   downloading $url"
    curl -fL --retry 3 --retry-delay 5 -C - -o "$pbf.part" "$url"
    actual=$(md5_of "$pbf.part")
    if [ "$actual" != "$expected" ] && [ "$actual" != skip ]; then
        echo "ERROR: md5 mismatch for $name (expected $expected, got $actual)" >&2
        exit 1
    fi
    mv "$pbf.part" "$pbf"
    echo "   ok: $pbf ($(human "$(size_of "$pbf")"))"
    inputs="$inputs $name-latest.osm.pbf"
done

merged="$OSM_DIR/$HANSEL_PBF"
need_merge=0
if [ ! -f "$merged" ]; then
    need_merge=1
else
    for f in $inputs; do
        [ "$OSM_DIR/$f" -nt "$merged" ] && need_merge=1
    done
fi

if [ "$need_merge" -eq 1 ]; then
    echo "== merging into $HANSEL_PBF with $OSMIUM_IMAGE"
    # shellcheck disable=SC2086
    set -- $inputs
    args=""
    for f in "$@"; do args="$args /data/$f"; done
    # shellcheck disable=SC2086
    docker run --rm -v "$OSM_DIR:/data" "$OSMIUM_IMAGE" \
        merge $args -o "/data/$HANSEL_PBF" --overwrite
else
    echo "== merged file is newer than all inputs; skipping merge"
fi

echo
echo "== data/osm contents"
for f in $inputs $HANSEL_PBF; do
    printf '   %-32s %s\n' "$f" "$(human "$(size_of "$OSM_DIR/$f")")"
done
echo
echo "Next: scripts/build-graphs.sh"
