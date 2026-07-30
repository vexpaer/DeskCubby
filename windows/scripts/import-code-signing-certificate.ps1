[CmdletBinding()]
param(
    [switch]$WriteGitHubEnvironment
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$encodedCertificate = [Environment]::GetEnvironmentVariable(
    "WINDOWS_CERTIFICATE_BASE64"
)
$certificatePassword = [Environment]::GetEnvironmentVariable(
    "WINDOWS_CERTIFICATE_PASSWORD"
)
if ([string]::IsNullOrWhiteSpace($encodedCertificate)) {
    throw "WINDOWS_CERTIFICATE_BASE64 is missing."
}
if ([string]::IsNullOrEmpty($certificatePassword)) {
    throw "WINDOWS_CERTIFICATE_PASSWORD is missing."
}

$temporaryRoot = [Environment]::GetEnvironmentVariable("RUNNER_TEMP")
if ([string]::IsNullOrWhiteSpace($temporaryRoot)) {
    $temporaryRoot = [IO.Path]::GetTempPath()
}
$temporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
if (-not [IO.Directory]::Exists($temporaryRoot)) {
    throw "The temporary directory does not exist."
}
$pfxPath = [IO.Path]::Combine(
    $temporaryRoot,
    "deskcubby-codesign-$([Guid]::NewGuid().ToString('N')).pfx"
)

$certificateBytes = $null
$imported = @()
$newlyImportedThumbprints = @()
$existingThumbprints = $null
$completed = $false
try {
    $existingThumbprints = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::OrdinalIgnoreCase
    )
    Get-ChildItem -LiteralPath "Cert:\CurrentUser\My" |
        ForEach-Object {
            [void]$existingThumbprints.Add(
                ($_.Thumbprint -replace "\s", "").ToUpperInvariant()
            )
        }

    $certificateBytes = [Convert]::FromBase64String($encodedCertificate)
    [IO.File]::WriteAllBytes($pfxPath, $certificateBytes)
    $securePassword = ConvertTo-SecureString `
        -String $certificatePassword `
        -AsPlainText `
        -Force
    $imported = @(
        Import-PfxCertificate `
            -FilePath $pfxPath `
            -CertStoreLocation "Cert:\CurrentUser\My" `
            -Password $securePassword `
            -Exportable:$false
    )
    $newlyImportedThumbprints = @(
        $imported |
            ForEach-Object {
                ($_.Thumbprint -replace "\s", "").ToUpperInvariant()
            } |
            Where-Object {
                $_ -match "^[0-9A-F]{40}$" -and
                -not $existingThumbprints.Contains($_)
            } |
            Sort-Object -Unique
    )
    $codeSigningOid = "1.3.6.1.5.5.7.3.3"
    $certificate = $imported |
        Where-Object {
            $ekuOids = @(
                $_.EnhancedKeyUsageList |
                    ForEach-Object { $_.ObjectId.Value }
            )
            $_.HasPrivateKey -and $ekuOids -contains $codeSigningOid
        } |
        Select-Object -First 1
    if ($null -eq $certificate) {
        throw "The PFX does not contain a private-key certificate for code signing."
    }
    $now = [DateTime]::UtcNow
    if (
        $certificate.NotBefore.ToUniversalTime() -gt $now -or
        $certificate.NotAfter.ToUniversalTime() -le $now
    ) {
        throw "The imported code-signing certificate is outside its validity period."
    }

    $thumbprint = ($certificate.Thumbprint -replace "\s", "").ToUpperInvariant()
    $env:DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT = $thumbprint
    $env:DESKCUBBY_WINDOWS_IMPORTED_CERTIFICATE_THUMBPRINTS = (
        $newlyImportedThumbprints -join ","
    )
    if ($WriteGitHubEnvironment) {
        $githubEnvironment = [Environment]::GetEnvironmentVariable("GITHUB_ENV")
        if ([string]::IsNullOrWhiteSpace($githubEnvironment)) {
            throw "GITHUB_ENV is unavailable."
        }
        [IO.File]::AppendAllText(
            $githubEnvironment,
            (
                "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT=$thumbprint" +
                "$([Environment]::NewLine)" +
                "DESKCUBBY_WINDOWS_IMPORTED_CERTIFICATE_THUMBPRINTS=" +
                "$($newlyImportedThumbprints -join ',')" +
                "$([Environment]::NewLine)"
            ),
            [Text.UTF8Encoding]::new($false)
        )
    }
    $completed = $true
    Write-Output $thumbprint
}
finally {
    if (-not $completed) {
        if ($null -ne $existingThumbprints) {
            $newlyImportedThumbprints = @(
                Get-ChildItem -LiteralPath "Cert:\CurrentUser\My" |
                    ForEach-Object {
                        ($_.Thumbprint -replace "\s", "").ToUpperInvariant()
                    } |
                    Where-Object {
                        $_ -match "^[0-9A-F]{40}$" -and
                        -not $existingThumbprints.Contains($_)
                    } |
                    Sort-Object -Unique
            )
        }
        foreach ($thumbprint in $newlyImportedThumbprints) {
            $certificatePath = "Cert:\CurrentUser\My\$thumbprint"
            if (Test-Path -LiteralPath $certificatePath -PathType Leaf) {
                Remove-Item -LiteralPath $certificatePath -Force
            }
        }
    }
    if ($null -ne $certificateBytes) {
        [Array]::Clear($certificateBytes, 0, $certificateBytes.Length)
    }
    if ([IO.File]::Exists($pfxPath)) {
        Remove-Item -LiteralPath $pfxPath -Force
    }
}
