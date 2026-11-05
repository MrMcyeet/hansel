#!/bin/sh
# Smoke-test the three dev-server services with curl.
#   scripts/smoke.sh [host]        default host: localhost
# Example from another machine on the LAN:  scripts/smoke.sh 192.168.1.50
# Needs curl plus jq or python3 for pretty output (falls back to raw JSON otherwise).
# Windows: run from Git Bash or WSL.

set -u

DEV_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$DEV_DIR"
# shellcheck disable=SC1091
[ -f .env ] && . ./.env

HOST=${1:-localhost}
VALHALLA="http://$HOST:${VALHALLA_PORT:-8002}"
GRAPHHOPPER="http://$HOST:${GRAPHHOPPER_PORT:-8989}"
NOMINATIM="http://$HOST:${NOMINATIM_PORT:-8080}"

# Jackson, MS -> New Orleans, LA (~265 km great-circle; GeoMathTest uses the same pair)
A_LAT=32.2988; A_LON=-90.1848
B_LAT=29.9511; B_LON=-90.0715

fails=0
fail() { echo "   FAIL: $*"; fails=$((fails + 1)); }
pass() { echo "   ok:   $*"; }

# json_get <file> <jq-expr> <python-expr-on-d>
json_get() {
    if command -v jq >/dev/null 2>&1; then
        jq -r "$2" "$1" 2>/dev/null
    elif command -v python3 >/dev/null 2>&1; then
        python3 -I -c "import json,sys
d=json.load(open(sys.argv[1]))
try: print($3)
except Exception as e: print('?', e)" "$1" 2>/dev/null
    else
        echo "(install jq or python3 for parsed output)"
    fi
}

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
CURL="curl -sS --max-time 90"

# ---------------------------------------------------------------------------------------
echo "== Valhalla  $VALHALLA"
if $CURL -o "$TMP/vstatus.json" -w '%{http_code}' "$VALHALLA/status?verbose=true" | grep -q '^200$'; then
    pass "/status  version=$(json_get "$TMP/vstatus.json" '.version' 'd.get("version")')  tileset_last_modified=$(json_get "$TMP/vstatus.json" '.tileset_last_modified' 'd.get("tileset_last_modified")')"
else
    fail "/status unreachable or non-200"
fi

cat > "$TMP/vroute.json" <<EOF
{"locations":[{"lat":$A_LAT,"lon":$A_LON},{"lat":$B_LAT,"lon":$B_LON}],
 "costing":"auto","alternates":2,"units":"kilometers"}
EOF
code=$($CURL -o "$TMP/vresp.json" -w '%{http_code}' -H 'Content-Type: application/json' \
    --data @"$TMP/vroute.json" "$VALHALLA/route")
if [ "$code" = 200 ]; then
    pass "/route Jackson->New Orleans, alternates=2"
    echo "         primary: $(json_get "$TMP/vresp.json" \
        '"\(.trip.summary.time/60|floor) min, \(.trip.summary.length) km"' \
        "'%.0f min, %s km' % (d['trip']['summary']['time']/60, d['trip']['summary']['length'])")"
    json_get "$TMP/vresp.json" \
        '.alternates[]? | "         alt:     \(.trip.summary.time/60|floor) min, \(.trip.summary.length) km"' \
        "chr(10).join('         alt:     %.0f min, %s km' % (a['trip']['summary']['time']/60, a['trip']['summary']['length']) for a in d.get('alternates', []))"
else
    fail "/route returned HTTP $code: $(head -c 300 "$TMP/vresp.json")"
fi

# ---------------------------------------------------------------------------------------
echo "== GraphHopper  $GRAPHHOPPER"
if $CURL -o /dev/null -w '%{http_code}' "$GRAPHHOPPER/health" | grep -q '^200$'; then
    pass "/health"
else
    fail "/health unreachable or non-200"
fi
if $CURL -o "$TMP/ghinfo.json" "$GRAPHHOPPER/info" 2>/dev/null; then
    echo "         profiles: $(json_get "$TMP/ghinfo.json" '[.profiles[].name]|join(",")' "','.join(p['name'] for p in d['profiles'])")"
fi

cat > "$TMP/ghroute.json" <<EOF
{"points":[[$A_LON,$A_LAT],[$B_LON,$B_LAT]],
 "profile":"car","algorithm":"alternative_route","alternative_route.max_paths":3,
 "ch.disable":true,"points_encoded":false,"instructions":false}
EOF
code=$($CURL -o "$TMP/ghresp.json" -w '%{http_code}' -H 'Content-Type: application/json' \
    --data @"$TMP/ghroute.json" "$GRAPHHOPPER/route")
if [ "$code" = 200 ]; then
    pass "POST /route Jackson->New Orleans, algorithm=alternative_route, ch.disable=true"
    json_get "$TMP/ghresp.json" \
        '.paths[] | "         path:    \(.time/60000|floor) min, \(.distance/1000|.*10|round/10) km"' \
        "chr(10).join('         path:    %.0f min, %.1f km' % (p['time']/60000, p['distance']/1000) for p in d['paths'])"
else
    fail "POST /route returned HTTP $code: $(head -c 300 "$TMP/ghresp.json")"
fi

# CH (fast path) sanity: same pair without ch.disable
code=$($CURL -o "$TMP/ghch.json" -w '%{http_code}' \
    "$GRAPHHOPPER/route?point=$A_LAT,$A_LON&point=$B_LAT,$B_LON&profile=car&points_encoded=false&instructions=false")
if [ "$code" = 200 ]; then
    pass "GET /route (CH) $(json_get "$TMP/ghch.json" '.paths[0] | "\(.time/60000|floor) min, \(.distance/1000|.*10|round/10) km"' "'%.0f min, %.1f km' % (d['paths'][0]['time']/60000, d['paths'][0]['distance']/1000)")"
else
    fail "GET /route (CH) returned HTTP $code"
fi

# ---------------------------------------------------------------------------------------
echo "== Nominatim  $NOMINATIM"
if $CURL -o "$TMP/nstatus.txt" -w '%{http_code}' "$NOMINATIM/status" | grep -q '^200$'; then
    pass "/status: $(head -c 80 "$TMP/nstatus.txt")"
else
    fail "/status unreachable or non-200 (import still running? docker compose logs -f nominatim)"
fi
code=$($CURL -o "$TMP/nsearch.json" -w '%{http_code}' "$NOMINATIM/search?q=Hattiesburg&format=jsonv2&limit=3")
if [ "$code" = 200 ]; then
    n=$(json_get "$TMP/nsearch.json" 'length' 'len(d)')
    if [ "${n:-0}" -gt 0 ] 2>/dev/null; then
        pass "/search?q=Hattiesburg -> $n result(s)"
        json_get "$TMP/nsearch.json" '.[] | "         \(.lat),\(.lon)  \(.category)/\(.type)  \(.display_name)"' \
            "chr(10).join('         %s,%s  %s/%s  %s' % (r['lat'], r['lon'], r.get('category'), r.get('type'), r['display_name']) for r in d)"
    else
        fail "/search?q=Hattiesburg returned no results"
    fi
else
    fail "/search returned HTTP $code"
fi
code=$($CURL -o "$TMP/nrev.json" -w '%{http_code}' "$NOMINATIM/reverse?lat=$A_LAT&lon=$A_LON&format=jsonv2")
if [ "$code" = 200 ]; then
    pass "/reverse Jackson -> $(json_get "$TMP/nrev.json" '.display_name' 'd.get("display_name")')"
else
    fail "/reverse returned HTTP $code"
fi

echo
if [ "$fails" -eq 0 ]; then
    echo "All checks passed. App Settings base URLs:"
    echo "   Valhalla     $VALHALLA"
    echo "   GraphHopper  $GRAPHHOPPER"
    echo "   Nominatim    $NOMINATIM"
else
    echo "$fails check(s) failed."
    exit 1
fi
