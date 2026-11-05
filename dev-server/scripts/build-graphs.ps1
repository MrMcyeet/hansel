# Windows twin of build-graphs.sh. Requires Docker Desktop running.
#   powershell -ExecutionPolicy Bypass -File scripts\build-graphs.ps1

$ErrorActionPreference = 'Stop'
$DevDir = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $DevDir

$env:HANSEL_PBF = 'gulf-south.osm.pbf'
if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            Set-Item -Path "env:$($Matches[1])" -Value $Matches[2].Trim('"', "'")
        }
    }
} else {
    Write-Host 'NOTE: no .env found; using defaults from docker-compose.yml (copy .env.example to .env to customise).'
}

$pbf = Join-Path $DevDir "data\osm\$($env:HANSEL_PBF)"
if (-not (Test-Path $pbf)) { throw "$pbf not found. Run scripts\fetch-extract.ps1 first." }

New-Item -ItemType Directory -Force -Path data\valhalla, data\graphhopper\logs | Out-Null

function Size-Of($path) {
    if (-not (Test-Path $path)) { return 'n/a' }
    $bytes = (Get-ChildItem -Recurse -File $path -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    if (-not $bytes) { $bytes = (Get-Item $path).Length }
    '{0:N1} MiB' -f ($bytes / 1MB)
}
function Run-Compose([string[]]$args) {
    & docker compose @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($args -join ' ') failed" }
}

# 1. Valhalla config seed
if (-not (Test-Path data\valhalla\valhalla.json)) {
    Copy-Item valhalla\valhalla.seed.json data\valhalla\valhalla.json
    Write-Host '== seeded data\valhalla\valhalla.json (service_limits: max_alternates=3, auto.max_locations=50)'
} else {
    Write-Host '== data\valhalla\valhalla.json exists; not touching it'
}

# 2. Valhalla tiles
if (Test-Path data\valhalla\valhalla_tiles.tar) {
    Write-Host "== Valhalla tiles exist ($(Size-Of data\valhalla\valhalla_tiles.tar)); skipping build."
} else {
    Write-Host "== Building Valhalla tiles from $($env:HANSEL_PBF) ..."
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Run-Compose @('run', '--rm', '-e', 'serve_tiles=False', 'valhalla')
    Write-Host "   Valhalla build: $($sw.Elapsed.ToString('hh\:mm\:ss'))"
    Write-Host "   valhalla_tiles.tar: $(Size-Of data\valhalla\valhalla_tiles.tar)"
}

# 3. GraphHopper import
if ((Test-Path data\graphhopper\graph-cache) -and (Get-ChildItem data\graphhopper\graph-cache | Select-Object -First 1)) {
    Write-Host "== GraphHopper graph-cache exists ($(Size-Of data\graphhopper\graph-cache)); skipping import."
} else {
    Write-Host "== Importing GraphHopper graph from $($env:HANSEL_PBF) (OSM parse + CH + LM prep) ..."
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Run-Compose @('run', '--rm', '--no-deps', 'graphhopper', '--import',
        '-i', "/data/osm/$($env:HANSEL_PBF)", '-c', '/data/config.yml', '-o', '/data/graph-cache')
    Write-Host "   GraphHopper import: $($sw.Elapsed.ToString('hh\:mm\:ss'))"
    Write-Host "   graph-cache: $(Size-Of data\graphhopper\graph-cache)"
}

# 4. Up
Write-Host '== docker compose up -d'
Run-Compose @('up', '-d')
Write-Host ''
Write-Host 'Valhalla and GraphHopper serve once their health checks pass (~1-2 min).'
Write-Host 'Nominatim imports the PBF on its FIRST start (tens of minutes to hours). Follow with:'
Write-Host '    docker compose logs -f nominatim'
Write-Host 'Then run scripts\smoke.sh from Git Bash / WSL (curl + jq or python3).'
