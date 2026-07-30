[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArtifactDirectory,
    [Parameter(Mandatory)]
    [string]$Version,
    [Parameter(Mandatory)]
    [string]$Tag,
    [string]$Repository = "vexpaer/DeskCubby"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Tag -ne "windows-v$Version") {
    throw "The release tag must exactly match windows-v$Version."
}
if ($Repository -notmatch "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$") {
    throw "Repository must use the OWNER/REPO form."
}
if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN)) {
    throw "GH_TOKEN is required to create the draft release."
}
if ($null -eq (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "The official GitHub CLI (gh) is required."
}

$resolvedArtifacts = [IO.Path]::GetFullPath($ArtifactDirectory)
$requiredNames = @(
    "DeskCubby-$Version-windows-x64-portable.exe",
    "DeskCubby-$Version-windows-x64-setup.exe",
    "DeskCubby-$Version-windows-x64-setup.exe.sig",
    "SHA256SUMS.txt",
    "latest.json"
)
$assetPaths = foreach ($name in $requiredNames) {
    $path = [IO.Path]::Combine($resolvedArtifacts, $name)
    if (-not [IO.File]::Exists($path) -or (Get-Item -LiteralPath $path).Length -le 0) {
        throw "Required release asset is missing or empty: $path"
    }
    $path
}

& gh release view $Tag --repo $Repository --json id --jq ".id" 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
    throw "Release $Tag already exists; refusing to overwrite a release."
}

$arguments = @(
    "release",
    "create",
    $Tag
) + $assetPaths + @(
    "--repo",
    $Repository,
    "--verify-tag",
    "--draft",
    "--title",
    "DeskCubby Windows $Version",
    "--notes",
    (
        "Signed DeskCubby Windows $Version release candidate. " +
        "Verify Authenticode, updater signatures, and latest.json before publishing this draft."
    )
)
& gh @arguments
if ($LASTEXITCODE -ne 0) {
    throw "GitHub draft release creation failed with exit code $LASTEXITCODE."
}
Write-Host "Created draft GitHub Release $Tag. Publish it only after manual review."
