[CmdletBinding()]
param(
    [string]$ArtifactDirectory,
    [string]$Version = "0.2.0",
    [Parameter(Mandatory)]
    [ValidateSet("SignedRelease", "AllowUnsignedTestBuild")]
    [string]$Mode,
    [string]$ExpectedCertificateThumbprint,
    [string]$ExpectedSignerSubject
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$windowsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
if ([string]::IsNullOrWhiteSpace($ArtifactDirectory)) {
    $ArtifactDirectory = Join-Path $windowsRoot "artifacts"
}
$resolvedArtifacts = [System.IO.Path]::GetFullPath($ArtifactDirectory)
$portable = Join-Path (
    $resolvedArtifacts
) "DeskCubby-$Version-windows-x64-portable.exe"
$installer = Join-Path (
    $resolvedArtifacts
) "DeskCubby-$Version-windows-x64-setup.exe"
$updaterSignature = "$installer.sig"

foreach ($path in @($portable, $installer)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required artifact is missing: $path"
    }
    if ((Get-Item -LiteralPath $path).Length -le 0) {
        throw "Artifact is empty: $path"
    }
}

$bytes = [System.IO.File]::ReadAllBytes($portable)
if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) {
    throw "Portable artifact is not a valid PE executable."
}
$peOffset = [BitConverter]::ToUInt32($bytes, 0x3c)
if (
    $bytes[$peOffset] -ne 0x50 -or
    $bytes[$peOffset + 1] -ne 0x45 -or
    $bytes[$peOffset + 2] -ne 0 -or
    $bytes[$peOffset + 3] -ne 0
) {
    throw "Portable artifact has an invalid PE signature."
}
$machine = [BitConverter]::ToUInt16($bytes, $peOffset + 4)
if ($machine -ne 0x8664) {
    throw (
        "Portable artifact is not x64: expected machine 0x8664, " +
        ("found 0x{0:x4}." -f $machine)
    )
}

$results = foreach ($path in @($portable, $installer)) {
    $file = Get-Item -LiteralPath $path
    $signature = Get-AuthenticodeSignature -LiteralPath $path
    if ($Mode -eq "SignedRelease") {
        if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
            throw "Authenticode verification failed for $($file.Name): $($signature.Status)."
        }
        if ($null -eq $signature.TimeStamperCertificate) {
            throw "The signed artifact has no trusted timestamp: $($file.Name)."
        }
        if (-not [string]::IsNullOrWhiteSpace($ExpectedCertificateThumbprint)) {
            $expected = ($ExpectedCertificateThumbprint -replace "\s", "").ToUpperInvariant()
            $actual = ($signature.SignerCertificate.Thumbprint -replace "\s", "").ToUpperInvariant()
            if ($actual -ne $expected) {
                throw "The artifact signer certificate does not match the expected thumbprint."
            }
        }
        if (
            -not [string]::IsNullOrWhiteSpace($ExpectedSignerSubject) -and
            -not $signature.SignerCertificate.Subject.Equals(
                $ExpectedSignerSubject.Trim(),
                [StringComparison]::OrdinalIgnoreCase
            )
        ) {
            throw "The artifact signer subject does not match the expected publisher identity."
        }
    }
    [pscustomobject]@{
        Artifact = $file.Name
        Bytes = $file.Length
        Sha256 = (
            Get-FileHash -Algorithm SHA256 -LiteralPath $path
        ).Hash.ToLowerInvariant()
        Signature = $signature.Status
        Signer = if ($null -eq $signature.SignerCertificate) {
            ""
        }
        else {
            $signature.SignerCertificate.Subject
        }
        Timestamped = $null -ne $signature.TimeStamperCertificate
    }
}

$results | Format-Table -AutoSize
if ($Mode -eq "SignedRelease") {
    if (
        -not (Test-Path -LiteralPath $updaterSignature -PathType Leaf) -or
        (Get-Item -LiteralPath $updaterSignature).Length -le 0
    ) {
        throw "The Tauri updater signature is missing or empty."
    }
    if ((Get-Item -LiteralPath $updaterSignature).Length -gt 65536) {
        throw "The Tauri updater signature is unexpectedly large."
    }

    $signTool = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($null -ne $signTool) {
        foreach ($path in @($portable, $installer)) {
            & $signTool.Source verify /pa /all /v /tw $path
            if ($LASTEXITCODE -ne 0) {
                throw "SignTool verification failed for $path."
            }
        }
    }
    Write-Host "Authenticode and Tauri updater signature checks passed."
}
else {
    Write-Warning (
        "AllowUnsignedTestBuild was selected. These artifacts are not approved " +
        "for public distribution, even if a local certificate happened to sign them."
    )
}
