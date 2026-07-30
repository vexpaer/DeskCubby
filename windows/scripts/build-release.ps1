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
$artifactDirectory = Join-Path $windowsRoot "artifacts"
$temporaryReleaseConfig = $null

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

Push-Location $windowsRoot
try {
    if (-not $SkipChecks) {
        & (Join-Path $PSScriptRoot "verify.ps1")
        Assert-LastExitCode "Verification"
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
        Assert-LastExitCode "Signed release configuration"
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

    Invoke-Pnpm @buildArguments
    Assert-LastExitCode "Tauri release build"

    node .\scripts\copy-portable.mjs
    Assert-LastExitCode "Portable artifact copy"

    $nsisDirectory = Join-Path $releaseDirectory "bundle\nsis"
    $installer = Get-ChildItem -LiteralPath $nsisDirectory -Filter *.exe -File |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $installer) {
        throw "No NSIS installer was produced in $nsisDirectory."
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
    Copy-Item -LiteralPath $installer.FullName -Destination $artifactInstaller

    if ($Mode -eq "SignedRelease") {
        $sourceSignature = "$($installer.FullName).sig"
        if (
            -not [IO.File]::Exists($sourceSignature) -or
            (Get-Item -LiteralPath $sourceSignature).Length -le 0
        ) {
            throw "Tauri did not produce the required updater signature."
        }
        Copy-Item -LiteralPath $sourceSignature -Destination $artifactSignature

        & cargo run `
            --locked `
            --quiet `
            --manifest-path .\src-tauri\Cargo.toml `
            --example verify_updater_signature `
            --release `
            --target $targetTriple `
            -- `
            $artifactInstaller `
            $artifactSignature
        Assert-LastExitCode "Tauri updater signature verification"
    }

    $releaseFiles = Get-ChildItem -LiteralPath $artifactDirectory -File |
        Where-Object {
            $_.Name -like "DeskCubby-$version-windows-x64-*.exe" -or
            $_.Name -like "DeskCubby-$version-windows-x64-*.exe.sig"
        } |
        Sort-Object Name
    $checksumLines = foreach ($file in $releaseFiles) {
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
        "$($hash.ToLowerInvariant())  $($file.Name)"
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
        Assert-LastExitCode "Updater manifest generation"
    }

    & (Join-Path $PSScriptRoot "inspect-artifacts.ps1") `
        -ArtifactDirectory $artifactDirectory `
        -Version $version `
        -Mode $Mode `
        -ExpectedCertificateThumbprint (
            [Environment]::GetEnvironmentVariable(
                "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT"
            )
        ) `
        -ExpectedSignerSubject (
            [Environment]::GetEnvironmentVariable(
                "DESKCUBBY_WINDOWS_SIGNER_SUBJECT"
            )
        )
    Assert-LastExitCode "Artifact inspection"

    if ($Mode -eq "SignedRelease") {
        Write-Host (
            "Signed release candidate created. Review latest.json and all " +
            "signatures before publishing."
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
