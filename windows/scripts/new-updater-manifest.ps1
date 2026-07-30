[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArtifactDirectory,
    [Parameter(Mandatory)]
    [string]$Version,
    [Parameter(Mandatory)]
    [string]$Tag,
    [string]$Repository = "vexpaer/DeskCubby",
    [string]$Notes = "See the DeskCubby GitHub Release for details.",
    [string]$OutputPath
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
if ($Notes.Length -gt 65536) {
    throw "Release notes exceed the 64 KiB updater display limit."
}

$resolvedArtifacts = [IO.Path]::GetFullPath($ArtifactDirectory)
if (-not [IO.Directory]::Exists($resolvedArtifacts)) {
    throw "Artifact directory does not exist."
}
$installerName = "DeskCubby-$Version-windows-x64-setup.exe"
$installerPath = [IO.Path]::Combine($resolvedArtifacts, $installerName)
$signaturePath = "$installerPath.sig"
foreach ($path in @($installerPath, $signaturePath)) {
    if (-not [IO.File]::Exists($path) -or (Get-Item -LiteralPath $path).Length -le 0) {
        throw "Required updater artifact is missing or empty: $path"
    }
}
$signature = [IO.File]::ReadAllText(
    $signaturePath,
    [Text.Encoding]::UTF8
).Trim()
if ([string]::IsNullOrWhiteSpace($signature) -or $signature.Length -gt 65536) {
    throw "The updater signature is empty or unexpectedly large."
}
if (
    $signature.Length % 4 -ne 0 -or
    $signature -notmatch "^[A-Za-z0-9+/]+={0,2}$"
) {
    throw "The updater signature is not canonical base64."
}
try {
    $decodedSignature = [Text.UTF8Encoding]::new(
        $false,
        $true
    ).GetString([Convert]::FromBase64String($signature))
}
catch {
    throw "The updater signature is not a UTF-8 Minisign signature."
}
$signatureLines = @(
    $decodedSignature -split "\r?\n" |
        Where-Object { $_.Length -gt 0 }
)
if (
    $signatureLines.Count -ne 4 -or
    $signatureLines[0] -notmatch "^untrusted comment: " -or
    $signatureLines[2] -notmatch "^trusted comment: "
) {
    throw "The updater signature has an invalid Minisign envelope."
}
try {
    $rawSignature = [Convert]::FromBase64String($signatureLines[1])
    $rawGlobalSignature = [Convert]::FromBase64String($signatureLines[3])
}
catch {
    throw "The updater signature contains invalid Minisign data."
}
if ($rawSignature.Length -ne 74 -or $rawGlobalSignature.Length -ne 64) {
    throw "The updater signature contains invalid Minisign data."
}

$escapedTag = [Uri]::EscapeDataString($Tag)
$escapedInstaller = [Uri]::EscapeDataString($installerName)
$downloadUrl = (
    "https://github.com/$Repository/releases/download/" +
    "$escapedTag/$escapedInstaller"
)
$platform = [ordered]@{
    signature = $signature
    url = $downloadUrl
}
$manifest = [ordered]@{
    version = $Version
    notes = $Notes
    pub_date = [DateTimeOffset]::UtcNow.ToString("o")
    platforms = [ordered]@{
        # Updater 2.10+ can select an installer-specific target. Keep the base
        # target as well for clients whose detected bundle type is unavailable.
        "windows-x86_64" = $platform
        "windows-x86_64-nsis" = $platform
    }
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = [IO.Path]::Combine($resolvedArtifacts, "latest.json")
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [IO.Path]::GetDirectoryName($resolvedOutput)
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
[IO.File]::WriteAllText(
    $resolvedOutput,
    ($manifest | ConvertTo-Json -Depth 8),
    [Text.UTF8Encoding]::new($false)
)
Write-Host "Created updater manifest for $Tag."
