[CmdletBinding()]
param(
    [string] $BundleDirectory = (Join-Path $PSScriptRoot 'bundle'),

    [string] $PackageCache = (Join-Path ([System.IO.Path]::GetTempPath()) 'tavern-termux-arm64')
)

$ErrorActionPreference = 'Stop'
$repo = 'https://packages-cf.termux.dev/apt/termux-main'
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'termux-arm64.lock.json') -Raw | ConvertFrom-Json
$build = Join-Path ([System.IO.Path]::GetTempPath()) ("tavern-node-" + [guid]::NewGuid())
$assets = Join-Path $BundleDirectory 'assets/st-engine'
$libraries = Join-Path $BundleDirectory 'jniLibs/arm64-v8a'
$licenses = Join-Path $assets 'licenses'
$prefix = './data/data/com.termux/files/usr'

$entries = @(
    @{ Package = 'nodejs-lts'; Path = 'bin/node'; Name = 'libtavern_node.so' },
    @{ Package = 'c-ares'; Path = 'lib/libcares.so'; Name = 'libtavern_cares.so'; Alias = @('libcares.so') },
    @{ Package = 'libc++'; Path = 'lib/libc++_shared.so'; Name = 'libtavern_cpp.so'; Alias = @('libc++_shared.so') },
    @{ Package = 'libicu'; Path = 'lib/libicudata.so.79.1'; Name = 'libtavern_icudata.so'; Alias = @('libicudata.so.79') },
    @{ Package = 'libicu'; Path = 'lib/libicui18n.so.79.1'; Name = 'libtavern_icui18n.so'; Alias = @('libicui18n.so.79') },
    @{ Package = 'libicu'; Path = 'lib/libicuuc.so.79.1'; Name = 'libtavern_icuuc.so'; Alias = @('libicuuc.so.79') },
    @{ Package = 'libsqlite'; Path = 'lib/libsqlite3.so.3.54.1'; Name = 'libtavern_sqlite.so'; Alias = @('libsqlite3.so', 'libsqlite3.so.0') },
    @{ Package = 'openssl'; Path = 'lib/libcrypto.so.3'; Name = 'libtavern_crypto.so'; Alias = @('libcrypto.so.3') },
    @{ Package = 'openssl'; Path = 'lib/libssl.so.3'; Name = 'libtavern_ssl.so'; Alias = @('libssl.so.3') },
    @{ Package = 'zlib'; Path = 'lib/libz.so.1.4'; Name = 'libtavern_z.so'; Alias = @('libz.so.1') },
    @{ Package = 'ca-certificates'; Path = 'etc/tls/cert.pem'; Name = 'cert.pem'; IsAsset = $true },
    @{ Package = 'termux-licenses'; Path = 'share/LICENSES/MPL-2.0.txt'; Name = 'licenses/MPL-2.0.txt'; IsAsset = $true },
    @{ Package = 'termux-licenses'; Path = 'share/LICENSES/NCSA.txt'; Name = 'licenses/NCSA.txt'; IsAsset = $true },
    @{ Package = 'termux-licenses'; Path = 'share/LICENSES/Public Domain.txt'; Name = 'licenses/Public Domain.txt'; IsAsset = $true },
    @{ Package = 'termux-licenses'; Path = 'share/LICENSES/Apache-2.0.txt'; Name = 'licenses/Apache-2.0.txt'; IsAsset = $true }
)

function Invoke-Tar {
    & tar @args
    if ($LASTEXITCODE -ne 0) { throw "tar failed with code $LASTEXITCODE" }
}

New-Item -ItemType Directory -Path $PackageCache, $build, $assets, $libraries, $licenses -Force | Out-Null
$aliases = [ordered]@{}
foreach ($package in $lock) {
    $cache = Join-Path $PackageCache ($package.name + '.deb')
    if (!(Test-Path -LiteralPath $cache) -or (Get-FileHash -Algorithm SHA256 $cache).Hash -ne $package.sha256) {
        & curl --fail --silent --show-error --location --retry 2 --max-time 300 "$repo/$($package.path)" --output $cache
        if ($LASTEXITCODE -ne 0) { throw "Could not download $($package.name)." }
    }
    if ((Get-FileHash -Algorithm SHA256 $cache).Hash -ne $package.sha256) {
        throw "Checksum mismatch for $($package.name)."
    }

    $packageDir = Join-Path $build $package.name
    New-Item -ItemType Directory -Path $packageDir -Force | Out-Null
    Invoke-Tar -xf $cache -C $packageDir
    $data = Get-ChildItem -LiteralPath $packageDir -Filter 'data.tar.*' | Select-Object -First 1
    if ($null -eq $data) { throw "Missing runtime payload in $($package.name)." }

    $packageEntries = @($entries | Where-Object Package -eq $package.name)
    $members = @($packageEntries | ForEach-Object { "$prefix/$($_.Path)" })
    $licenseMember = "$prefix/share/doc/$($package.name)/copyright"
    $hasLicense = (Invoke-Tar -tvf $data.FullName) | Where-Object { $_.StartsWith('-') -and $_.EndsWith(" $licenseMember") }
    if ($hasLicense) { $members += $licenseMember }
    Invoke-Tar -xf $data.FullName -C $packageDir @members

    foreach ($entry in $packageEntries) {
        $directory = if ($entry.IsAsset) { $assets } else { $libraries }
        Copy-Item -LiteralPath (Join-Path $packageDir "$prefix/$($entry.Path)") -Destination (Join-Path $directory $entry.Name) -Force
        foreach ($name in $entry.Alias) { $aliases[$name] = $entry.Name }
    }
    if ($hasLicense) {
        Copy-Item -LiteralPath (Join-Path $packageDir $licenseMember) -Destination (Join-Path $licenses ($package.name + '.txt')) -Force
    }
}

$node = $lock | Where-Object name -eq 'nodejs-lts'
@{
    name = 'Termux Node.js LTS'
    version = $node.version
    abi = 'arm64-v8a'
    executable = 'libtavern_node.so'
    aliases = $aliases
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $assets 'runtime.json') -Encoding utf8NoBOM
Get-ChildItem -LiteralPath $libraries -File | Select-Object Name,Length
