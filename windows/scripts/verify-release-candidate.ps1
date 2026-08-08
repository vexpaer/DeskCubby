[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArtifactDirectory,
    [Parameter(Mandatory)]
    [string]$Version,
    [Parameter(Mandatory)]
    [string]$Tag,
    [string]$Repository = "vexpaer/DeskCubby",
    [ValidateSet("Required", "Absent")]
    [string]$AuthenticodePolicy = "Absent",
    [string]$ExpectedCertificateThumbprint,
    [string]$ExpectedSignerSubject
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Version -notmatch "^\d+\.\d+\.\d+([+-][0-9A-Za-z.-]+)?$") {
    throw "Version must be valid release SemVer."
}
if ($Tag -ne "windows-v$Version") {
    throw "The release tag must exactly match windows-v$Version."
}
if ($Repository -notmatch "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$") {
    throw "Repository must use the OWNER/REPO form."
}
if ([string]::IsNullOrWhiteSpace($env:DESKCUBBY_UPDATER_PUBLIC_KEY)) {
    throw "DESKCUBBY_UPDATER_PUBLIC_KEY is required for release verification."
}

$windowsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$resolvedArtifacts = [IO.Path]::GetFullPath($ArtifactDirectory)
if (-not [IO.Directory]::Exists($resolvedArtifacts)) {
    throw "Artifact directory does not exist."
}

$portableName = "DeskCubby-$Version-windows-x64-portable.exe"
$installerName = "DeskCubby-$Version-windows-x64-setup.exe"
$signatureName = "$installerName.sig"
$checksumName = "SHA256SUMS.txt"
$manifestName = "latest.json"
$requiredNames = @(
    $portableName,
    $installerName,
    $signatureName,
    $checksumName,
    $manifestName
)
$paths = @{}
foreach ($name in $requiredNames) {
    $path = [IO.Path]::Combine($resolvedArtifacts, $name)
    if (-not [IO.File]::Exists($path) -or (Get-Item -LiteralPath $path).Length -le 0) {
        throw "Required release asset is missing or empty: $path"
    }
    $paths[$name] = $path
}

& (Join-Path $PSScriptRoot "inspect-artifacts.ps1") `
    -ArtifactDirectory $resolvedArtifacts `
    -Version $Version `
    -Mode SignedRelease `
    -AuthenticodePolicy $AuthenticodePolicy `
    -ExpectedCertificateThumbprint $ExpectedCertificateThumbprint `
    -ExpectedSignerSubject $ExpectedSignerSubject

Push-Location $windowsRoot
try {
    & cargo run `
        --locked `
        --quiet `
        --manifest-path .\src-tauri\Cargo.toml `
        --example verify_updater_signature `
        --release `
        --target x86_64-pc-windows-msvc `
        -- `
        $paths[$installerName] `
        $paths[$signatureName]
    if ($LASTEXITCODE -ne 0) {
        throw "Tauri updater signature verification failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$checksumFile = Get-Item -LiteralPath $paths[$checksumName]
if ($checksumFile.Length -gt (32 * 1024)) {
    throw "SHA256SUMS.txt is unexpectedly large."
}
$checksumLines = @(
    [IO.File]::ReadAllLines($checksumFile.FullName) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
$hashedNames = @($portableName, $installerName, $signatureName)
if ($checksumLines.Count -ne $hashedNames.Count) {
    throw "SHA256SUMS.txt must contain exactly the three release binary entries."
}
$checksums = @{}
foreach ($line in $checksumLines) {
    $match = [regex]::Match($line, "^([0-9a-f]{64})  ([A-Za-z0-9.+_-]+)$")
    if (-not $match.Success) {
        throw "SHA256SUMS.txt contains a malformed entry."
    }
    $name = $match.Groups[2].Value
    if ($hashedNames -notcontains $name -or $checksums.ContainsKey($name)) {
        throw "SHA256SUMS.txt contains an unexpected or duplicate entry."
    }
    $checksums[$name] = $match.Groups[1].Value
}
foreach ($name in $hashedNames) {
    $actual = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $paths[$name]
    ).Hash.ToLowerInvariant()
    if ($checksums[$name] -ne $actual) {
        throw "SHA256SUMS.txt does not match $name."
    }
}

$manifestFile = Get-Item -LiteralPath $paths[$manifestName]
if ($manifestFile.Length -gt (256 * 1024)) {
    throw "latest.json is unexpectedly large."
}
$manifest = Get-Content -Raw -LiteralPath $manifestFile.FullName |
    ConvertFrom-Json
if ([string]$manifest.version -ne $Version) {
    throw "latest.json version does not match the release version."
}
$publishedAt = [DateTimeOffset]::MinValue
if (-not [DateTimeOffset]::TryParse([string]$manifest.pub_date, [ref]$publishedAt)) {
    throw "latest.json contains an invalid publication date."
}
if ($null -eq $manifest.platforms) {
    throw "latest.json is missing its platform map."
}
$platformNames = @(
    $manifest.platforms.PSObject.Properties |
        ForEach-Object { $_.Name }
)
$expectedPlatforms = @("windows-x86_64", "windows-x86_64-nsis")
if (
    $platformNames.Count -ne $expectedPlatforms.Count -or
    @($platformNames | Where-Object { $expectedPlatforms -notcontains $_ }).Count -ne 0
) {
    throw "latest.json contains an unexpected updater platform set."
}
$encodedSignature = [IO.File]::ReadAllText(
    $paths[$signatureName],
    [Text.Encoding]::UTF8
).Trim()
$downloadUrl = (
    "https://github.com/$Repository/releases/download/" +
    "$([Uri]::EscapeDataString($Tag))/" +
    "$([Uri]::EscapeDataString($installerName))"
)
foreach ($platformName in $expectedPlatforms) {
    $platform = $manifest.platforms.PSObject.Properties[$platformName].Value
    $properties = @($platform.PSObject.Properties | ForEach-Object { $_.Name })
    if (
        $properties.Count -ne 2 -or
        $properties -notcontains "signature" -or
        $properties -notcontains "url"
    ) {
        throw "latest.json contains an invalid platform entry."
    }
    if ([string]$platform.signature -ne $encodedSignature) {
        throw "latest.json does not contain the verified updater signature."
    }
    if ([string]$platform.url -ne $downloadUrl) {
        throw "latest.json does not point to the immutable versioned installer."
    }
}

Write-Host (
    "Production release candidate verification passed for $Tag " +
    "(Authenticode policy: $AuthenticodePolicy)."
)
