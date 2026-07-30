[CmdletBinding()]
param(
    [string]$Thumbprints = (
        $env:DESKCUBBY_WINDOWS_IMPORTED_CERTIFICATE_THUMBPRINTS
    )
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($Thumbprints)) {
    Write-Host "No imported Authenticode certificate was recorded."
    return
}
$normalizedThumbprints = @(
    $Thumbprints.Split(
        ",",
        [StringSplitOptions]::RemoveEmptyEntries
    ) |
        ForEach-Object { ($_ -replace "\s", "").ToUpperInvariant() } |
        Sort-Object -Unique
)
foreach ($normalized in $normalizedThumbprints) {
    if ($normalized -notmatch "^[0-9A-F]{40}$") {
        throw "Refusing to remove a certificate with an invalid thumbprint."
    }
}
foreach ($normalized in $normalizedThumbprints) {
    $certificatePath = "Cert:\CurrentUser\My\$normalized"
    if (Test-Path -LiteralPath $certificatePath -PathType Leaf) {
        Remove-Item -LiteralPath $certificatePath -Force
        Write-Host (
            "Removed a temporary Authenticode certificate from CurrentUser\My."
        )
    }
}
