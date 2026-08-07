<#
.SYNOPSIS
Installs the Visual Studio-cached Windows SDK 10.0.26100 package at
E:\Windows Kits\10 and verifies the x64 desktop/linking/signing tools.

.DESCRIPTION
Run this script from an Administrator PowerShell session. It deliberately does
not request elevation itself. The Microsoft bootstrapper may download SDK
payloads because Visual Studio's local package cache contains only the signed
bootstrapper in a default installation.

.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\windows\scripts\install-windows-sdk-26100.ps1

.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\windows\scripts\install-windows-sdk-26100.ps1 -VerifyOnly
#>
[CmdletBinding()]
param(
    [switch]$VerifyOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$sdkPackageId = "Win11SDK_10.0.26100"
$sdkPackageVersion = "10.0.26100.10"
$sdkDirectoryVersionPrefix = "10.0.26100"
$destination = [IO.Path]::GetFullPath("E:\Windows Kits\10")
$commonApplicationData = [Environment]::GetFolderPath(
    [Environment+SpecialFolder]::CommonApplicationData
)
$packageRoot = Join-Path (
    Join-Path $commonApplicationData "Microsoft\VisualStudio\Packages"
) "$sdkPackageId,version=$sdkPackageVersion,productarch=neutral"
$metadataPath = Join-Path $packageRoot "_package.json"
$wrapperPath = Join-Path $packageRoot "WinSdkInstaller.exe"
$bootstrapperPath = Join-Path $packageRoot "winsdksetup.exe"

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator
    )
}

function Get-KitsRoot10Values {
    # WinSdkInstaller.exe is a 32-bit process and consults the 32-bit registry
    # view. Check both views explicitly so a stale hidden C: value cannot make
    # the installer silently ignore the requested E: /InstallPath.
    $subKeyPath = "SOFTWARE\Microsoft\Windows Kits\Installed Roots"
    $values = @()
    foreach ($view in @(
        [Microsoft.Win32.RegistryView]::Registry64,
        [Microsoft.Win32.RegistryView]::Registry32
    )) {
        $baseKey = [Microsoft.Win32.RegistryKey]::OpenBaseKey(
            [Microsoft.Win32.RegistryHive]::LocalMachine,
            $view
        )
        try {
            $key = $baseKey.OpenSubKey($subKeyPath, $false)
            if ($null -eq $key) {
                continue
            }
            try {
                $value = $key.GetValue("KitsRoot10", $null)
                if (-not [string]::IsNullOrWhiteSpace([string]$value)) {
                    $values += [pscustomobject]@{
                        View = $view.ToString()
                        Value = [string]$value
                    }
                }
            }
            finally {
                $key.Dispose()
            }
        }
        finally {
            $baseKey.Dispose()
        }
    }
    return $values
}

function Assert-PathIsNotReparsePoint {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "SDK destination must not be a junction or symbolic link: $Path"
    }
}

function Assert-MicrosoftExecutable {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ExpectedSha256
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required cached SDK installer is missing: $Path"
    }
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
    if (-not $actualHash.Equals($ExpectedSha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Cached SDK installer hash does not match Visual Studio metadata: $Path"
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    if (
        $signature.Status -ne [Management.Automation.SignatureStatus]::Valid -or
        $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch "(^|, )O=Microsoft Corporation(,|$)"
    ) {
        throw "Cached SDK installer does not have a valid Microsoft signature: $Path"
    }
}

function Get-InstalledSdkCandidate {
    $binRoot = Join-Path $destination "bin"
    if (-not (Test-Path -LiteralPath $binRoot -PathType Container)) {
        return $null
    }

    $versionPattern = "^$([regex]::Escape($sdkDirectoryVersionPrefix))(?:\.\d+)?$"
    $candidates = Get-ChildItem -LiteralPath $binRoot -Directory -ErrorAction Stop |
        Where-Object { $_.Name -match $versionPattern } |
        Sort-Object { [version]$_.Name } -Descending

    foreach ($candidate in $candidates) {
        $version = $candidate.Name
        $rcPath = Join-Path $destination "bin\$version\x64\rc.exe"
        $signToolPath = Join-Path $destination "bin\$version\x64\signtool.exe"
        $kernel32Path = Join-Path $destination "Lib\$version\um\x64\kernel32.lib"
        if (
            (Test-Path -LiteralPath $rcPath -PathType Leaf) -and
            (Test-Path -LiteralPath $signToolPath -PathType Leaf) -and
            (Test-Path -LiteralPath $kernel32Path -PathType Leaf)
        ) {
            return [pscustomobject]@{
                Version = $version
                Rc = $rcPath
                SignTool = $signToolPath
                Kernel32 = $kernel32Path
            }
        }
    }
    return $null
}

function Assert-SdkInstallation {
    Assert-PathIsNotReparsePoint -Path (Split-Path -Parent $destination)
    Assert-PathIsNotReparsePoint -Path $destination

    $candidate = Get-InstalledSdkCandidate
    if ($null -eq $candidate) {
        throw (
            "Windows SDK $sdkDirectoryVersionPrefix is incomplete under $destination. " +
            "Expected x64 rc.exe, signtool.exe, and kernel32.lib."
        )
    }

    foreach ($toolPath in @($candidate.Rc, $candidate.SignTool)) {
        $signature = Get-AuthenticodeSignature -LiteralPath $toolPath
        if (
            $signature.Status -ne [Management.Automation.SignatureStatus]::Valid -or
            $null -eq $signature.SignerCertificate -or
            $signature.SignerCertificate.Subject -notmatch "(^|, )O=Microsoft Corporation(,|$)"
        ) {
            throw "Installed SDK tool does not have a valid Microsoft signature: $toolPath"
        }
    }

    $installedRoots = @(Get-KitsRoot10Values)
    if ($installedRoots.Count -eq 0) {
        throw (
            "Windows SDK files were found under $destination, but the KitsRoot10 " +
            "registry value is missing. Do not use this incomplete installation."
        )
    }
    $normalizedDestination = $destination.TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    foreach ($installedRootRecord in $installedRoots) {
        $normalizedInstalledRoot = [IO.Path]::GetFullPath(
            [string]$installedRootRecord.Value
        ).TrimEnd(
            [IO.Path]::DirectorySeparatorChar,
            [IO.Path]::AltDirectorySeparatorChar
        )
        if (-not $normalizedInstalledRoot.Equals(
            $normalizedDestination,
            [StringComparison]::OrdinalIgnoreCase
        )) {
            throw (
                "KitsRoot10 ($($installedRootRecord.View)) points to " +
                "'$normalizedInstalledRoot', not the requested destination " +
                "'$normalizedDestination'."
            )
        }
    }

    return $candidate
}

if ($VerifyOnly) {
    $installed = Assert-SdkInstallation
    Write-Host "Windows SDK verification succeeded."
    Write-Host "Version: $($installed.Version)"
    Write-Host "Root: $destination"
    Write-Host "rc.exe: $($installed.Rc)"
    Write-Host "signtool.exe: $($installed.SignTool)"
    Write-Host "kernel32.lib: $($installed.Kernel32)"
    exit 0
}

if (-not (Test-IsAdministrator)) {
    throw (
        "Installation requires an Administrator PowerShell session. " +
        "This script deliberately does not self-elevate; right-click PowerShell, " +
        "choose 'Run as administrator', then run this script again."
    )
}

if (-not (Test-Path -LiteralPath "E:\" -PathType Container)) {
    throw "Drive E: is not available. The SDK destination is fixed at $destination."
}
Assert-PathIsNotReparsePoint -Path (Split-Path -Parent $destination)
Assert-PathIsNotReparsePoint -Path $destination

if (Test-Path -LiteralPath $metadataPath -PathType Leaf) {
    $metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
}
else {
    throw (
        "Visual Studio's cached Windows SDK package metadata is missing: " +
        $metadataPath
    )
}
if (
    [string]$metadata.id -ne $sdkPackageId -or
    [string]$metadata.version -ne $sdkPackageVersion
) {
    throw "Cached Windows SDK package identity is not the expected 10.0.26100 package."
}
$cachedInstallParameters = [string]$metadata.installParams.parameters
foreach ($requiredParameter in @(
    "ProgramFilesOrSharedDriveSdkPath",
    "OptionId.DesktopCPPx64",
    "OptionId.SigningTools"
)) {
    if ($cachedInstallParameters -notmatch [regex]::Escape($requiredParameter)) {
        throw "Cached Windows SDK metadata does not support $requiredParameter."
    }
}

$bootstrapperPayload = @($metadata.payloads | Where-Object {
    $_.fileName -eq "winsdksetup.exe"
})
$wrapperPayload = @($metadata.payloads | Where-Object {
    $_.fileName -eq "WinSdkInstaller.exe"
})
if ($bootstrapperPayload.Count -ne 1 -or $wrapperPayload.Count -ne 1) {
    throw "Cached Windows SDK metadata has unexpected installer payload entries."
}
Assert-MicrosoftExecutable `
    -Path $bootstrapperPath `
    -ExpectedSha256 ([string]$bootstrapperPayload[0].sha256)
Assert-MicrosoftExecutable `
    -Path $wrapperPath `
    -ExpectedSha256 ([string]$wrapperPayload[0].sha256)

$installedRoots = @(Get-KitsRoot10Values)
$normalizedDestination = $destination.TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
)
foreach ($installedRootRecord in $installedRoots) {
    $normalizedInstalledRoot = [IO.Path]::GetFullPath(
        [string]$installedRootRecord.Value
    ).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    if (-not $normalizedInstalledRoot.Equals(
        $normalizedDestination,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw (
            "A different Windows 10/11 SDK root is registered in the " +
            "$($installedRootRecord.View) registry view at '$normalizedInstalledRoot'. " +
            "Refusing to relocate or overwrite it automatically."
        )
    }
}

$existingCandidate = Get-InstalledSdkCandidate
if ($null -ne $existingCandidate) {
    $verified = Assert-SdkInstallation
    Write-Host "Windows SDK $($verified.Version) is already installed and verified at $destination."
    exit 0
}

$setupParameters = (
    "/features " +
    "OptionId.DesktopCPPx64 " +
    "OptionId.SigningTools " +
    "OptionId.MSIInstallTools " +
    "/quiet /norestart"
)
$logFolderName = "deskcubby-windows-sdk"
$logDirectory = Join-Path ([IO.Path]::GetTempPath()) $logFolderName
[IO.Directory]::CreateDirectory($logDirectory) | Out-Null
$logPath = Join-Path $logDirectory (
    "install-10.0.26100-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')).log"
)
$installerArguments = @(
    "SetupExe=winsdksetup.exe",
    "LogFile=`"$logPath`"",
    "SetupLogFolder=$logFolderName",
    "CeipSetting=off",
    "ProgramFilesOrSharedDriveSdkPath=`"$destination`"",
    "SetupParameters=`"$setupParameters`""
)

Write-Host "Installing Windows SDK 10.0.26100 to $destination ..."
Write-Host "Installer log: $logPath"
& $wrapperPath @installerArguments
$installerExitCode = $LASTEXITCODE
if ($installerExitCode -notin @(0, 3010)) {
    throw "Windows SDK installer failed with exit code $installerExitCode. Log: $logPath"
}

$verified = Assert-SdkInstallation
Write-Host "Windows SDK installation and verification succeeded."
Write-Host "Version: $($verified.Version)"
Write-Host "Root: $destination"
Write-Host "rc.exe: $($verified.Rc)"
Write-Host "signtool.exe: $($verified.SignTool)"
Write-Host "kernel32.lib: $($verified.Kernel32)"
if ($installerExitCode -eq 3010) {
    Write-Warning "The installer requested a restart (exit code 3010)."
}
