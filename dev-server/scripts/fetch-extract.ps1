# Windows twin of fetch-extract.sh. Run from PowerShell 5.1+ or pwsh with Docker Desktop running:
#   powershell -ExecutionPolicy Bypass -File scripts\fetch-extract.ps1
# Downloads the Geofabrik extracts into data\osm, verifies .md5, merges with osmium (via Docker).

$ErrorActionPreference = 'Stop'
$DevDir = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $DevDir

# Minimal .env loader (KEY=VALUE, optional quotes, # comments)
$env:HANSEL_REGIONS = 'north-america/us/mississippi north-america/us/louisiana north-america/us/alabama'
$env:HANSEL_PBF = 'gulf-south.osm.pbf'
$env:OSMIUM_IMAGE = 'iboates/osmium:latest'
if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $v = $Matches[2].Trim('"', "'")
            Set-Item -Path "env:$($Matches[1])" -Value $v
        }
    }
}

$Geofabrik = 'https://download.geofabrik.de'
$OsmDir = Join-Path $DevDir 'data\osm'
New-Item -ItemType Directory -Force -Path $OsmDir | Out-Null

function Format-MiB($bytes) { '{0:N1} MiB' -f ($bytes / 1MB) }

$inputs = @()
foreach ($region in ($env:HANSEL_REGIONS -split '\s+' | Where-Object { $_ })) {
    $name = Split-Path $region -Leaf
    $pbf = Join-Path $OsmDir "$name-latest.osm.pbf"
    $url = "$Geofabrik/$region-latest.osm.pbf"
    Write-Host "== $name"

    Invoke-WebRequest -Uri "$url.md5" -OutFile "$pbf.md5" -UseBasicParsing
    $expected = ((Get-Content "$pbf.md5" -Raw) -split '\s+')[0].ToLower()

    if (Test-Path $pbf) {
        $actual = (Get-FileHash -Algorithm MD5 -Path $pbf).Hash.ToLower()
        if ($actual -eq $expected) {
            Write-Host "   up to date: $pbf ($(Format-MiB (Get-Item $pbf).Length))"
            $inputs += "$name-latest.osm.pbf"
            continue
        }
        Write-Host '   checksum changed upstream; re-downloading'
    }

    Write-Host "   downloading $url"
    # curl.exe ships with Windows 10 1803+ and supports resume; Invoke-WebRequest is much slower for large files.
    & curl.exe -fL --retry 3 --retry-delay 5 -C - -o "$pbf.part" $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    $actual = (Get-FileHash -Algorithm MD5 -Path "$pbf.part").Hash.ToLower()
    if ($actual -ne $expected) { throw "md5 mismatch for $name (expected $expected, got $actual)" }
    Move-Item -Force "$pbf.part" $pbf
    Write-Host "   ok: $pbf ($(Format-MiB (Get-Item $pbf).Length))"
    $inputs += "$name-latest.osm.pbf"
}

$merged = Join-Path $OsmDir $env:HANSEL_PBF
$needMerge = -not (Test-Path $merged)
if (-not $needMerge) {
    $mergedTime = (Get-Item $merged).LastWriteTimeUtc
    foreach ($f in $inputs) {
        if ((Get-Item (Join-Path $OsmDir $f)).LastWriteTimeUtc -gt $mergedTime) { $needMerge = $true }
    }
}

if ($needMerge) {
    Write-Host "== merging into $($env:HANSEL_PBF) with $($env:OSMIUM_IMAGE)"
    $args = @('run', '--rm', '-v', "${OsmDir}:/data", $env:OSMIUM_IMAGE, 'merge')
    $args += $inputs | ForEach-Object { "/data/$_" }
    $args += @('-o', "/data/$($env:HANSEL_PBF)", '--overwrite')
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'osmium merge failed' }
} else {
    Write-Host '== merged file is newer than all inputs; skipping merge'
}

Write-Host ''
Write-Host '== data\osm contents'
foreach ($f in ($inputs + $env:HANSEL_PBF)) {
    '   {0,-32} {1}' -f $f, (Format-MiB (Get-Item (Join-Path $OsmDir $f)).Length) | Write-Host
}
Write-Host ''
Write-Host 'Next: scripts\build-graphs.ps1  (or run build-graphs.sh from Git Bash / WSL)'
