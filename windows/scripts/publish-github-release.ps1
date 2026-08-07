[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArtifactDirectory,
    [Parameter(Mandatory)]
    [string]$Version,
    [Parameter(Mandatory)]
    [string]$Tag,
    [Parameter(Mandatory)]
    [string]$ExpectedCommit,
    [string]$Repository = "vexpaer/DeskCubby"
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
if ($ExpectedCommit -notmatch "^[0-9A-Fa-f]{40}([0-9A-Fa-f]{24})?$") {
    throw "ExpectedCommit must be a full Git object ID."
}
if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN)) {
    throw "GH_TOKEN is required to create the draft release."
}
if ($null -eq (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "The official GitHub CLI (gh) is required."
}

function Invoke-GhCapture {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& gh @Arguments 2>&1 | ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output -join [Environment]::NewLine).Trim()
    }
}

function Invoke-GhRequired {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments,
        [Parameter(Mandatory)]
        [string]$Operation
    )

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $result = Invoke-GhCapture -Arguments $Arguments
        if ($result.ExitCode -eq 0) {
            return $result.Output
        }
        if ($attempt -lt 3) {
            Start-Sleep -Seconds $attempt
        }
    }
    throw "$Operation could not be confirmed after three attempts."
}

function Test-ReleaseNotFound {
    param([Parameter(Mandatory)][string]$Message)
    return (
        $Message -match "(?i)release not found" -or
        $Message -match "(?i)HTTP 404(?:\D|$)" -or
        $Message -match '"status"\s*:\s*"?404"?'
    )
}

function Get-ReleaseView {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $result = Invoke-GhCapture -Arguments @(
            "release",
            "view",
            $Tag,
            "--repo",
            $Repository,
            "--json",
            "databaseId,isDraft,isPrerelease,tagName"
        )
        if ($result.ExitCode -eq 0) {
            try {
                return $result.Output | ConvertFrom-Json
            }
            catch {
                throw "GitHub returned malformed release metadata."
            }
        }
        if (Test-ReleaseNotFound -Message $result.Output) {
            return $null
        }
        if ($attempt -lt 3) {
            Start-Sleep -Seconds $attempt
        }
    }
    throw "The existing GitHub Release state could not be determined."
}

function Get-ReleaseAssets {
    param([Parameter(Mandatory)][long]$ReleaseId)
    $json = Invoke-GhRequired `
        -Arguments @(
            "api",
            "-X",
            "GET",
            "repos/$Repository/releases/$ReleaseId/assets?per_page=100"
        ) `
        -Operation "GitHub Release asset query"
    try {
        return @($json | ConvertFrom-Json)
    }
    catch {
        throw "GitHub returned malformed release asset metadata."
    }
}

function Assert-DraftRelease {
    param([Parameter(Mandatory)]$Release)
    if ([string]$Release.tagName -ne $Tag) {
        throw "GitHub returned a release for an unexpected tag."
    }
    if (-not [bool]$Release.isDraft) {
        throw "Release $Tag is already published; refusing to modify it."
    }
    if ([bool]$Release.isPrerelease) {
        throw "The production draft unexpectedly has prerelease state enabled."
    }
    if ([long]$Release.databaseId -le 0) {
        throw "GitHub returned an invalid release identifier."
    }
}

function Assert-RemoteAssets {
    param(
        [Parameter(Mandatory)]
        [object[]]$Assets,
        [Parameter(Mandatory)]
        [hashtable]$LocalAssets
    )

    if ($Assets.Count -ne $LocalAssets.Count) {
        throw "The draft release does not contain the exact required asset set."
    }
    $seen = @{}
    foreach ($asset in $Assets) {
        $name = [string]$asset.name
        if (-not $LocalAssets.ContainsKey($name) -or $seen.ContainsKey($name)) {
            throw "The draft release contains an unexpected or duplicate asset."
        }
        $seen[$name] = $true
        $local = Get-Item -LiteralPath $LocalAssets[$name]
        if ([string]$asset.state -ne "uploaded" -or [long]$asset.size -ne $local.Length) {
            throw "A GitHub Release asset is incomplete or has the wrong size."
        }
        $expectedDigest = "sha256:$((Get-FileHash `
            -Algorithm SHA256 `
            -LiteralPath $local.FullName
        ).Hash.ToLowerInvariant())"
        if ([string]$asset.digest -ne $expectedDigest) {
            throw "A GitHub Release asset digest does not match the local candidate."
        }
    }
}

$resolvedArtifacts = [IO.Path]::GetFullPath($ArtifactDirectory)
$requiredNames = @(
    "DeskCubby-$Version-windows-x64-portable.exe",
    "DeskCubby-$Version-windows-x64-setup.exe",
    "DeskCubby-$Version-windows-x64-setup.exe.sig",
    "SHA256SUMS.txt",
    "latest.json"
)
$localAssets = @{}
foreach ($name in $requiredNames) {
    $path = [IO.Path]::Combine($resolvedArtifacts, $name)
    if (-not [IO.File]::Exists($path) -or (Get-Item -LiteralPath $path).Length -le 0) {
        throw "Required release asset is missing or empty: $path"
    }
    $localAssets[$name] = $path
}

& (Join-Path $PSScriptRoot "verify-release-candidate.ps1") `
    -ArtifactDirectory $resolvedArtifacts `
    -Version $Version `
    -Tag $Tag `
    -Repository $Repository `
    -ExpectedCertificateThumbprint $env:DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT `
    -ExpectedSignerSubject $env:DESKCUBBY_WINDOWS_SIGNER_SUBJECT

$escapedTag = [Uri]::EscapeDataString($Tag)
$remoteCommit = Invoke-GhRequired `
    -Arguments @(
        "api",
        "repos/$Repository/commits/$escapedTag",
        "--jq",
        ".sha"
    ) `
    -Operation "Remote release-tag resolution"
if (-not $remoteCommit.Trim().Equals($ExpectedCommit, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The remote release tag no longer points to the verified commit."
}

$release = Get-ReleaseView
if ($null -ne $release) {
    Assert-DraftRelease -Release $release
    $existingAssets = @(Get-ReleaseAssets -ReleaseId ([long]$release.databaseId))
    if ($existingAssets.Count -eq $localAssets.Count) {
        Assert-RemoteAssets -Assets $existingAssets -LocalAssets $localAssets
        Write-Host "Draft GitHub Release $Tag already contains the verified candidate."
        return
    }
    if ($existingAssets.Count -ne 0) {
        throw "The existing draft has a partial or unexpected asset set; refusing to overwrite it."
    }
}

$assetPaths = @($requiredNames | ForEach-Object { $localAssets[$_] })
if ($null -eq $release) {
    $createArguments = @(
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
            "Verify Authenticode, updater signatures, checksums, and latest.json before publishing."
        )
    )
    $creation = Invoke-GhCapture -Arguments $createArguments
    $release = Get-ReleaseView
    if ($null -eq $release) {
        throw "GitHub draft release creation failed and no draft was created."
    }
    Assert-DraftRelease -Release $release
    if ($creation.ExitCode -ne 0) {
        Write-Warning "The create request reported failure; validating actual remote draft state."
    }
}
else {
    $upload = Invoke-GhCapture -Arguments (
        @("release", "upload", $Tag) +
        $assetPaths +
        @("--repo", $Repository)
    )
    if ($upload.ExitCode -ne 0) {
        Write-Warning "The upload request reported failure; validating actual remote draft state."
    }
}

$release = Get-ReleaseView
if ($null -eq $release) {
    throw "The draft release disappeared before final verification."
}
Assert-DraftRelease -Release $release
$remoteAssets = @(Get-ReleaseAssets -ReleaseId ([long]$release.databaseId))
Assert-RemoteAssets -Assets $remoteAssets -LocalAssets $localAssets
Write-Host "Created and verified draft GitHub Release $Tag. Publish only after manual review."
