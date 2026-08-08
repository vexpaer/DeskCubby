[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("SignedRelease", "AllowUnsignedTestBuild")]
    [string]$Mode,
    [switch]$SkipChecks,
    [string]$ReleaseTag,
    [string]$Repository = "vexpaer/DeskCubby"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$windowsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$targetTriple = "x86_64-pc-windows-msvc"
$configurationPath = Join-Path $windowsRoot "src-tauri\tauri.conf.json"
$configuration = Get-Content -Raw -LiteralPath $configurationPath |
    ConvertFrom-Json
$version = [string]$configuration.version
$releaseDirectory = Join-Path (
    $windowsRoot
) "src-tauri\target\$targetTriple\release"
$nsisDirectory = Join-Path $releaseDirectory "bundle\nsis"
$bundledInstaller = Join-Path $nsisDirectory "DeskCubby_${version}_x64-setup.exe"
$bundledInstallerSignature = "$bundledInstaller.sig"
$artifactDirectory = Join-Path $windowsRoot "artifacts"
$temporaryReleaseConfig = $null
$authenticodePolicy = "Absent"

function Assert-LastExitCode {
    param([string]$Step)
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

function Invoke-Pnpm {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Arguments)
    if (Get-Command pnpm -ErrorAction SilentlyContinue) {
        & pnpm @Arguments
    }
    else {
        & corepack pnpm @Arguments
    }
}

function Assert-SignedReleaseInputs {
    $privateKey = [Environment]::GetEnvironmentVariable(
        "TAURI_SIGNING_PRIVATE_KEY"
    )
    $privateKeyPath = [Environment]::GetEnvironmentVariable(
        "TAURI_SIGNING_PRIVATE_KEY_PATH"
    )
    $hasPrivateKey = -not [string]::IsNullOrWhiteSpace($privateKey)
    $hasPrivateKeyPath = -not [string]::IsNullOrWhiteSpace($privateKeyPath)
    if ($hasPrivateKey -eq $hasPrivateKeyPath) {
        throw "SignedRelease requires exactly one updater private-key source."
    }
    if ($hasPrivateKey -and $privateKey.Length -gt (1024 * 1024)) {
        throw "The updater private key is unexpectedly large."
    }
    if ($hasPrivateKeyPath) {
        $resolvedKeyPath = [IO.Path]::GetFullPath($privateKeyPath)
        if (-not [IO.File]::Exists($resolvedKeyPath)) {
            throw "The updater private-key path does not identify a file."
        }
        $repositoryRoot = [IO.Path]::GetFullPath(
            [IO.Path]::Combine($windowsRoot, "..")
        ).TrimEnd(
            [IO.Path]::DirectorySeparatorChar,
            [IO.Path]::AltDirectorySeparatorChar
        )
        $repositoryPrefix = "$repositoryRoot$([IO.Path]::DirectorySeparatorChar)"
        if (
            $resolvedKeyPath.Equals(
                $repositoryRoot,
                [StringComparison]::OrdinalIgnoreCase
            ) -or
            $resolvedKeyPath.StartsWith(
                $repositoryPrefix,
                [StringComparison]::OrdinalIgnoreCase
            )
        ) {
            throw "The updater private key must be stored outside the repository."
        }
        # Tauri's CLI consumes the canonical TAURI_SIGNING_PRIVATE_KEY
        # variable and accepts either key text or a filesystem path. Keep the
        # repository-specific *_PATH input out of command-line arguments while
        # forwarding the validated external path to the CLI.
        $env:TAURI_SIGNING_PRIVATE_KEY = $resolvedKeyPath
        $env:TAURI_SIGNING_PRIVATE_KEY_PATH = $resolvedKeyPath
    }
    $privateKeyPassword = [Environment]::GetEnvironmentVariable(
        "TAURI_SIGNING_PRIVATE_KEY_PASSWORD"
    )
    if ([string]::IsNullOrEmpty($privateKeyPassword)) {
        throw "SignedRelease requires a non-empty updater signing-key password."
    }
    if ($privateKeyPassword.Length -gt 4096) {
        throw "The updater signing-key password is unexpectedly large."
    }
    if ([string]::IsNullOrWhiteSpace($ReleaseTag)) {
        throw "SignedRelease requires -ReleaseTag."
    }
    if ($ReleaseTag -ne "windows-v$version") {
        throw "ReleaseTag must exactly match windows-v$version."
    }
}

function Get-AuthenticodePolicy {
    $thumbprint = [Environment]::GetEnvironmentVariable(
        "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT"
    )
    $signCommand = [Environment]::GetEnvironmentVariable(
        "DESKCUBBY_WINDOWS_SIGN_COMMAND"
    )
    $hasThumbprint = -not [string]::IsNullOrWhiteSpace($thumbprint)
    $hasSignCommand = -not [string]::IsNullOrWhiteSpace($signCommand)
    if ($hasThumbprint -and $hasSignCommand) {
        throw "Configure at most one Authenticode signing mechanism."
    }
    if ($hasThumbprint -or $hasSignCommand) {
        return "Required"
    }
    return "Absent"
}

Push-Location $windowsRoot
try {
    if (-not $SkipChecks) {
        & (Join-Path $PSScriptRoot "verify.ps1")
    }

    $buildArguments = @(
        "tauri",
        "build",
        "--target",
        $targetTriple,
        "--bundles",
        "nsis",
        "--ci"
    )
    if ($Mode -eq "SignedRelease") {
        Assert-SignedReleaseInputs
        $authenticodePolicy = Get-AuthenticodePolicy
        $temporaryRoot = [Environment]::GetEnvironmentVariable("RUNNER_TEMP")
        if ([string]::IsNullOrWhiteSpace($temporaryRoot)) {
            $temporaryRoot = [IO.Path]::GetTempPath()
        }
        $temporaryReleaseConfig = [IO.Path]::Combine(
            [IO.Path]::GetFullPath($temporaryRoot),
            "deskcubby-release-$([Guid]::NewGuid().ToString('N')).json"
        )
        & (Join-Path $PSScriptRoot "new-release-config.ps1") `
            -OutputPath $temporaryReleaseConfig
        $buildArguments += @("--config", $temporaryReleaseConfig)
    }
    else {
        $buildArguments += "--no-sign"
        Write-Warning (
            "Building an explicitly unsigned, updater-offline test package. " +
            "It must not be published as a DeskCubby release."
        )
    }
    # Forward Cargo's lockfile gate through Tauri's runner argument boundary.
    # Release builds must never silently resolve a different Rust dependency set.
    $buildArguments += @("--", "--locked")

    # Refuse to mistake a stale installer from an earlier attempt for this build.
    foreach ($staleBundle in @($bundledInstaller, $bundledInstallerSignature)) {
        if ([IO.File]::Exists($staleBundle)) {
            Remove-Item -LiteralPath $staleBundle -Force
        }
    }

    Invoke-Pnpm @buildArguments
    Assert-LastExitCode "Tauri release build"

    node .\scripts\copy-portable.mjs
    Assert-LastExitCode "Portable artifact copy"

    if (-not [IO.File]::Exists($bundledInstaller)) {
        throw "Tauri did not produce the expected versioned NSIS installer."
    }

    [IO.Directory]::CreateDirectory($artifactDirectory) | Out-Null
    $installerName = "DeskCubby-$version-windows-x64-setup.exe"
    $artifactInstaller = Join-Path $artifactDirectory $installerName
    $artifactSignature = "$artifactInstaller.sig"
    $artifactManifest = Join-Path $artifactDirectory "latest.json"
    foreach ($stalePath in @($artifactInstaller, $artifactSignature, $artifactManifest)) {
        if ([IO.File]::Exists($stalePath)) {
            Remove-Item -LiteralPath $stalePath -Force
        }
    }
    Copy-Item -LiteralPath $bundledInstaller -Destination $artifactInstaller

    if ($Mode -eq "SignedRelease") {
        if (
            -not [IO.File]::Exists($bundledInstallerSignature) -or
            (Get-Item -LiteralPath $bundledInstallerSignature).Length -le 0
        ) {
            throw "Tauri did not produce the required updater signature."
        }
        Copy-Item `
            -LiteralPath $bundledInstallerSignature `
            -Destination $artifactSignature
    }

    $portableName = "DeskCubby-$version-windows-x64-portable.exe"
    $portablePath = Join-Path $artifactDirectory $portableName
    $checksumPaths = @($portablePath, $artifactInstaller)
    if ($Mode -eq "SignedRelease") {
        $checksumPaths += $artifactSignature
    }
    $checksumLines = foreach ($path in ($checksumPaths | Sort-Object)) {
        if (-not [IO.File]::Exists($path) -or (Get-Item -LiteralPath $path).Length -le 0) {
            throw "A required checksum input is missing or empty."
        }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
        "$($hash.ToLowerInvariant())  $([IO.Path]::GetFileName($path))"
    }
    [IO.File]::WriteAllLines(
        (Join-Path $artifactDirectory "SHA256SUMS.txt"),
        $checksumLines,
        [Text.UTF8Encoding]::new($false)
    )

    if ($Mode -eq "SignedRelease") {
        & (Join-Path $PSScriptRoot "new-updater-manifest.ps1") `
            -ArtifactDirectory $artifactDirectory `
            -Version $version `
            -Tag $ReleaseTag `
            -Repository $Repository
    }

    $expectedThumbprint = [Environment]::GetEnvironmentVariable(
        "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT"
    )
    $expectedSubject = [Environment]::GetEnvironmentVariable(
        "DESKCUBBY_WINDOWS_SIGNER_SUBJECT"
    )
    if ($Mode -eq "SignedRelease") {
        & (Join-Path $PSScriptRoot "verify-release-candidate.ps1") `
            -ArtifactDirectory $artifactDirectory `
            -Version $version `
            -Tag $ReleaseTag `
            -Repository $Repository `
            -AuthenticodePolicy $authenticodePolicy `
            -ExpectedCertificateThumbprint $expectedThumbprint `
            -ExpectedSignerSubject $expectedSubject
    }
    else {
        & (Join-Path $PSScriptRoot "inspect-artifacts.ps1") `
            -ArtifactDirectory $artifactDirectory `
            -Version $version `
            -Mode $Mode
    }

    if ($Mode -eq "SignedRelease") {
        Write-Host (
            "Production release candidate created. Tauri updater signing was " +
            "verified; Authenticode policy: $authenticodePolicy."
        )
    }
    else {
        Write-Warning (
            "AllowUnsignedTestBuild completed. SmartScreen may report an " +
            "unknown publisher; these files are not release-approved."
        )
    }
}
finally {
    Pop-Location
    if (
        $null -ne $temporaryReleaseConfig -and
        [IO.File]::Exists($temporaryReleaseConfig)
    ) {
        Remove-Item -LiteralPath $temporaryReleaseConfig -Force
    }
}
