[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $UpstreamRepo,

    [string] $BundleDirectory = (Join-Path $PSScriptRoot 'bundle')
)

$ErrorActionPreference = 'Stop'
$version = '1.19.1'
$revision = '82b64a45c34facbc98ff4b9f33c067d3ccab6a17'
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("tavern-engine-" + [guid]::NewGuid())
$sourceArchive = Join-Path $stage 'source.tar'
$source = Join-Path $stage 'sillytavern'
$packageFiles = @(
    'server.js', 'src', 'public', 'default', 'plugins.js', 'webpack.config.js',
    'package.json', 'package-lock.json', 'LICENSE', '.npmrc', '.nomedia'
)

$output = Join-Path $BundleDirectory 'assets/st-engine'
New-Item -ItemType Directory -Path $source, $output -Force | Out-Null
$destination = (Resolve-Path -LiteralPath $output).Path
$archive = Join-Path $destination "sillytavern-$version.zip"
& git -C $UpstreamRepo archive --format=tar "--output=$sourceArchive" $revision -- @packageFiles
if ($LASTEXITCODE -ne 0) { throw "Unable to archive SillyTavern $revision." }

& tar -xf $sourceArchive -C $source
if ($LASTEXITCODE -ne 0) { throw 'Unable to unpack the upstream source.' }

Push-Location $source
try {
    & npm ci --omit=dev --ignore-scripts --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Unable to install the pinned server dependencies.' }
} finally {
    Pop-Location
}

# Use a sibling candidate so a failed archive cannot replace a previous bundle.
$candidate = Join-Path $destination ("sillytavern-" + [guid]::NewGuid() + ".zip")
& tar -a -cf $candidate -C $source .
if ($LASTEXITCODE -ne 0) { throw 'Unable to pack the server archive.' }
Move-Item -LiteralPath $candidate -Destination $archive -Force

@{
    package = 'SillyTavern'
    version = $version
    revision = $revision
    sha256 = (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant()
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'upstream.json') -Encoding utf8NoBOM
Get-Item -LiteralPath $archive
