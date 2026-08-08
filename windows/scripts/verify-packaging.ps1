[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Drawing

$windowsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$tauriPath = Join-Path $windowsRoot "src-tauri\tauri.conf.json"
$packagePath = Join-Path $windowsRoot "package.json"
$cargoPath = Join-Path $windowsRoot "src-tauri\Cargo.toml"
$capabilityPath = Join-Path (
    $windowsRoot
) "src-tauri\capabilities\default.json"
$toolchainPath = Join-Path $windowsRoot "rust-toolchain.toml"

$tauri = Get-Content -Raw -LiteralPath $tauriPath | ConvertFrom-Json
$package = Get-Content -Raw -LiteralPath $packagePath | ConvertFrom-Json
$cargoManifest = Get-Content -Raw -LiteralPath $cargoPath
$capability = Get-Content -Raw -LiteralPath $capabilityPath | ConvertFrom-Json

if ($tauri.productName -ne "DeskCubby") {
    throw "Tauri productName must remain DeskCubby."
}
if ($tauri.version -notmatch "^\d+\.\d+\.\d+([+-][0-9A-Za-z.-]+)?$") {
    throw "The Windows Tauri version must be valid release SemVer."
}
if ($package.version -ne $tauri.version) {
    throw "package.json and tauri.conf.json versions do not match."
}
$cargoVersion = [regex]::Match(
    $cargoManifest,
    "(?ms)^\[package\].*?^version\s*=\s*`"([^`"]+)`""
)
if (-not $cargoVersion.Success -or $cargoVersion.Groups[1].Value -ne $tauri.version) {
    throw "Cargo.toml and tauri.conf.json versions do not match."
}
if ($tauri.identifier -ne "com.deskcubby.windows") {
    throw "Unexpected Tauri application identifier."
}
if (@($tauri.bundle.targets) -notcontains "nsis") {
    throw "The Windows bundle must include an NSIS target."
}
if ($tauri.bundle.createUpdaterArtifacts) {
    throw (
        "The checked-in configuration must not create updater artifacts. " +
        "SignedRelease injects its trust configuration through --config."
    )
}
if ($tauri.bundle.windows.allowDowngrades) {
    throw "Windows release packaging must block downgrade installation."
}
if (-not ($tauri.PSObject.Properties.Name -contains "plugins")) {
    throw (
        "The checked-in config must contain an offline updater object so the " +
        "plugin can initialize safely in local builds."
    )
}
$updaterProperty = $tauri.plugins.PSObject.Properties["updater"]
if ($null -eq $updaterProperty -or $null -eq $updaterProperty.Value) {
    throw (
        "The checked-in config must contain an offline updater object so the " +
        "plugin can initialize safely in local builds."
    )
}
$baseUpdaterProperties = @(
    $updaterProperty.Value.PSObject.Properties |
        ForEach-Object { $_.Name }
)
if (
    $baseUpdaterProperties.Count -ne 2 -or
    $baseUpdaterProperties -notcontains "pubkey" -or
    $baseUpdaterProperties -notcontains "endpoints" -or
    $updaterProperty.Value.pubkey -isnot [string] -or
    -not [string]::IsNullOrEmpty($updaterProperty.Value.pubkey) -or
    $null -eq $updaterProperty.Value.endpoints -or
    $updaterProperty.Value.endpoints -isnot [array] -or
    @($updaterProperty.Value.endpoints).Count -ne 0
) {
    throw (
        "The checked-in updater config must keep an empty public key and no " +
        "endpoints. SignedRelease injects both through --config."
    )
}
if ($tauri.app.security.assetProtocol.enable) {
    throw "The unrestricted Tauri asset protocol must remain disabled."
}

$allowedPermissions = @("core:default")
foreach ($permission in @($capability.permissions)) {
    if ($allowedPermissions -notcontains $permission) {
        throw "Unexpected capability permission: $permission"
    }
}
foreach ($permission in $allowedPermissions) {
    if (@($capability.permissions) -notcontains $permission) {
        throw "Required capability permission is missing: $permission"
    }
}

$csp = [string]$tauri.app.security.csp
$cspDirectives = @{}
foreach ($rawDirective in ($csp -split ";")) {
    $parts = @($rawDirective.Trim() -split "\s+" | Where-Object { $_.Length -gt 0 })
    if ($parts.Count -eq 0) {
        continue
    }
    if ($cspDirectives.ContainsKey($parts[0])) {
        throw "CSP contains a duplicate directive: $($parts[0])"
    }
    $cspDirectives[$parts[0]] = @($parts | Select-Object -Skip 1)
}
$requiredCspSources = @{
    "default-src" = @("'self'")
    "object-src" = @("'none'")
    "frame-src" = @("reader:", "http://reader.localhost")
    "img-src" = @(
        "'self'",
        "data:",
        "blob:",
        "background:",
        "http://background.localhost",
        "media:",
        "http://media.localhost"
    )
    "connect-src" = @("ipc:", "http://ipc.localhost")
}
foreach ($directive in $requiredCspSources.GetEnumerator()) {
    if (-not $cspDirectives.ContainsKey($directive.Key)) {
        throw "Required CSP directive is missing: $($directive.Key)"
    }
    foreach ($source in $directive.Value) {
        if ($cspDirectives[$directive.Key] -notcontains $source) {
            throw "Required CSP source is missing from $($directive.Key): $source"
        }
    }
}
if (
    $csp -match "https:\s*[; ]" -or
    $csp -match "http:\s*[; ]" -or
    @($cspDirectives.Values | ForEach-Object { $_ } | Where-Object { $_ -eq "*" }).Count -gt 0
) {
    throw "CSP must not grant a wildcard HTTP or HTTPS source."
}

$toolchain = Get-Content -Raw -LiteralPath $toolchainPath
if (-not $toolchain.Contains("stable-x86_64-pc-windows-msvc")) {
    throw "The project Rust toolchain must target stable MSVC x64."
}

$expectedPngs = [ordered]@{
    "app-icon.png" = 512
    "src-tauri\icons\32x32.png" = 32
    "src-tauri\icons\128x128.png" = 128
    "src-tauri\icons\128x128@2x.png" = 256
    "src-tauri\icons\icon.png" = 512
    "src\assets\deskcubby.png" = 128
}

$canonicalPalette = [System.Collections.Generic.HashSet[int]]::new()
$canonicalPath = Join-Path $windowsRoot "app-icon.png"
$canonicalImage = [System.Drawing.Bitmap]::FromFile($canonicalPath)
try {
    for ($y = 0; $y -lt $canonicalImage.Height; $y++) {
        for ($x = 0; $x -lt $canonicalImage.Width; $x++) {
            [void]$canonicalPalette.Add($canonicalImage.GetPixel($x, $y).ToArgb())
        }
    }
}
finally {
    $canonicalImage.Dispose()
}

foreach ($entry in $expectedPngs.GetEnumerator()) {
    $path = Join-Path $windowsRoot $entry.Key
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required Windows PNG icon is missing: $path"
    }
    if ((Get-Item -LiteralPath $path).Length -le 0) {
        throw "Windows PNG icon is empty: $path"
    }

    $image = [System.Drawing.Bitmap]::FromFile($path)
    try {
        if ($image.Width -ne $entry.Value -or $image.Height -ne $entry.Value) {
            throw (
                "Windows PNG icon has unexpected dimensions: $path " +
                "($($image.Width)x$($image.Height), expected " +
                "$($entry.Value)x$($entry.Value))."
            )
        }
        if (-not [System.Drawing.Image]::IsAlphaPixelFormat($image.PixelFormat)) {
            throw "Windows PNG icon must preserve an alpha channel: $path"
        }

        $hasTransparentPixel = $false
        $hasVisiblePixel = $false
        for ($y = 0; $y -lt $image.Height; $y++) {
            for ($x = 0; $x -lt $image.Width; $x++) {
                $color = $image.GetPixel($x, $y)
                if ($color.A -eq 0) {
                    $hasTransparentPixel = $true
                }
                elseif ($color.A -eq 255) {
                    $hasVisiblePixel = $true
                }
                else {
                    throw (
                        "Pixel-art PNG contains a smoothed partial-alpha pixel: " +
                        "$path"
                    )
                }
                if (-not $canonicalPalette.Contains($color.ToArgb())) {
                    throw "Pixel-art PNG contains a color outside the source palette: $path"
                }
            }
        }
        if (-not $hasTransparentPixel -or -not $hasVisiblePixel) {
            throw "Windows PNG icon must contain visible and transparent pixels: $path"
        }
    }
    finally {
        $image.Dispose()
    }
}

if (
    (Get-FileHash -Algorithm SHA256 -LiteralPath $canonicalPath).Hash -ne
    (Get-FileHash -Algorithm SHA256 -LiteralPath (
        Join-Path $windowsRoot "src-tauri\icons\icon.png"
    )).Hash
) {
    throw "The packaged 512 px icon must exactly match app-icon.png."
}
if (
    (Get-FileHash -Algorithm SHA256 -LiteralPath (
        Join-Path $windowsRoot "src-tauri\icons\128x128.png"
    )).Hash -ne
    (Get-FileHash -Algorithm SHA256 -LiteralPath (
        Join-Path $windowsRoot "src\assets\deskcubby.png"
    )).Hash
) {
    throw "The React brand icon must exactly match the packaged 128 px icon."
}

$icoPath = Join-Path $windowsRoot "src-tauri\icons\icon.ico"
$icoHeader = [System.IO.File]::ReadAllBytes($icoPath)
if (
    $icoHeader.Length -lt 6 -or
    [BitConverter]::ToUInt16($icoHeader, 0) -ne 0 -or
    [BitConverter]::ToUInt16($icoHeader, 2) -ne 1 -or
    [BitConverter]::ToUInt16($icoHeader, 4) -lt 1
) {
    throw "Windows ICO header is invalid."
}

$expectedIcoSizes = @(16, 24, 32, 48, 64, 128, 256)
$icoCount = [BitConverter]::ToUInt16($icoHeader, 4)
if ($icoCount -ne $expectedIcoSizes.Count) {
    throw "Windows ICO must contain all seven required size layers."
}
for ($index = 0; $index -lt $icoCount; $index++) {
    $directoryOffset = 6 + (16 * $index)
    $width = [int]$icoHeader[$directoryOffset]
    $height = [int]$icoHeader[$directoryOffset + 1]
    if ($width -eq 0) { $width = 256 }
    if ($height -eq 0) { $height = 256 }
    $expectedSize = $expectedIcoSizes[$index]
    if ($width -ne $expectedSize -or $height -ne $expectedSize) {
        throw "Windows ICO layer $index has unexpected dimensions."
    }
    if ([BitConverter]::ToUInt16($icoHeader, $directoryOffset + 6) -ne 32) {
        throw "Windows ICO layer $expectedSize must be 32-bit."
    }

    $frameLength = [BitConverter]::ToUInt32($icoHeader, $directoryOffset + 8)
    $frameOffset = [BitConverter]::ToUInt32($icoHeader, $directoryOffset + 12)
    if (
        $frameLength -lt 26 -or
        ([uint64]$frameOffset + [uint64]$frameLength) -gt $icoHeader.Length
    ) {
        throw "Windows ICO layer $expectedSize has an invalid data range."
    }
    $pngSignature = @(137, 80, 78, 71, 13, 10, 26, 10)
    for ($byteIndex = 0; $byteIndex -lt $pngSignature.Count; $byteIndex++) {
        if ($icoHeader[$frameOffset + $byteIndex] -ne $pngSignature[$byteIndex]) {
            throw "Windows ICO layer $expectedSize is not an embedded PNG."
        }
    }
    if ($icoHeader[$frameOffset + 25] -ne 6) {
        throw "Windows ICO layer $expectedSize must use RGBA PNG data."
    }
}

Write-Host "Tauri packaging configuration, capabilities, and icons are valid."
