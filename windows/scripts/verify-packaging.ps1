[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

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
foreach ($requiredDirective in @(
    "default-src 'self'",
    "object-src 'none'",
    "img-src 'self' data: blob: media: http://media.localhost",
    "connect-src ipc: http://ipc.localhost"
)) {
    if (-not $csp.Contains($requiredDirective)) {
        throw "Required CSP directive is missing: $requiredDirective"
    }
}
if ($csp -match "https:\s*[; ]" -or $csp -match "http:\s*[; ]") {
    throw "CSP must not grant a wildcard HTTP or HTTPS source."
}

$toolchain = Get-Content -Raw -LiteralPath $toolchainPath
if (-not $toolchain.Contains("stable-x86_64-pc-windows-msvc")) {
    throw "The project Rust toolchain must target stable MSVC x64."
}

foreach ($icon in @(
    "32x32.png",
    "128x128.png",
    "128x128@2x.png",
    "icon.ico",
    "icon.png"
)) {
    $path = Join-Path $windowsRoot "src-tauri\icons\$icon"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required Windows icon is missing: $path"
    }
    if ((Get-Item -LiteralPath $path).Length -le 0) {
        throw "Windows icon is empty: $path"
    }
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

Write-Host "Tauri packaging configuration, capabilities, and icons are valid."
