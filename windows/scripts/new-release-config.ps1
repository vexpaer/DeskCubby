[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-RequiredEnvironmentValue {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [int]$MaximumLength = 65536
    )

    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required release environment value '$Name' is missing."
    }
    if ($value.Length -gt $MaximumLength) {
        throw "Release environment value '$Name' is too long."
    }
    return $value
}

function Test-AbsoluteWebUri {
    param(
        [Parameter(Mandatory)]
        [string]$Value,
        [Parameter(Mandatory)]
        [string[]]$AllowedSchemes,
        [Parameter(Mandatory)]
        [string]$Name
    )

    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri)) {
        throw "'$Name' must be an absolute URI."
    }
    if ($AllowedSchemes -notcontains $uri.Scheme.ToLowerInvariant()) {
        throw "'$Name' uses an unsupported URI scheme."
    }
    if ([string]::IsNullOrWhiteSpace($uri.Host) -or -not [string]::IsNullOrEmpty($uri.UserInfo)) {
        throw "'$Name' must have a host and must not contain credentials."
    }
    return $uri.AbsoluteUri
}

$publicKey = (
    Get-RequiredEnvironmentValue `
        -Name "DESKCUBBY_UPDATER_PUBLIC_KEY" `
        -MaximumLength (16 * 1024)
).Trim()
if (
    $publicKey.Length % 4 -ne 0 -or
    $publicKey -notmatch "^[A-Za-z0-9+/]+={0,2}$"
) {
    throw "DESKCUBBY_UPDATER_PUBLIC_KEY is not canonical base64."
}
try {
    $decodedPublicKey = [Text.UTF8Encoding]::new(
        $false,
        $true
    ).GetString([Convert]::FromBase64String($publicKey))
}
catch {
    throw "DESKCUBBY_UPDATER_PUBLIC_KEY is not a UTF-8 Minisign public key."
}
$publicKeyLines = @(
    $decodedPublicKey -split "\r?\n" |
        Where-Object { $_.Length -gt 0 }
)
if (
    $publicKeyLines.Count -ne 2 -or
    $publicKeyLines[0] -notmatch "^untrusted comment: " -or
    $publicKeyLines[1] -notmatch "^[A-Za-z0-9+/]+={0,2}$"
) {
    throw "DESKCUBBY_UPDATER_PUBLIC_KEY has an invalid Minisign envelope."
}
try {
    $rawPublicKey = [Convert]::FromBase64String($publicKeyLines[1])
}
catch {
    throw "DESKCUBBY_UPDATER_PUBLIC_KEY contains an invalid Minisign key."
}
if (
    $rawPublicKey.Length -ne 42 -or
    $rawPublicKey[0] -ne 0x45 -or
    ($rawPublicKey[1] -ne 0x44 -and $rawPublicKey[1] -ne 0x64)
) {
    throw "DESKCUBBY_UPDATER_PUBLIC_KEY contains an unsupported Minisign key."
}
$endpoint = Test-AbsoluteWebUri `
    -Value (Get-RequiredEnvironmentValue -Name "DESKCUBBY_UPDATER_ENDPOINT" -MaximumLength 2048) `
    -AllowedSchemes @("https") `
    -Name "DESKCUBBY_UPDATER_ENDPOINT"
$thumbprint = [Environment]::GetEnvironmentVariable(
    "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT"
)
$signCommand = [Environment]::GetEnvironmentVariable(
    "DESKCUBBY_WINDOWS_SIGN_COMMAND"
)
$hasThumbprint = -not [string]::IsNullOrWhiteSpace($thumbprint)
$hasSignCommand = -not [string]::IsNullOrWhiteSpace($signCommand)
if ($hasThumbprint -and $hasSignCommand) {
    throw (
        "SignedRelease accepts at most one Authenticode identity: " +
        "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT or " +
        "DESKCUBBY_WINDOWS_SIGN_COMMAND."
    )
}

$windowsConfig = [ordered]@{
    allowDowngrades = $false
}
if ($hasThumbprint) {
    $normalizedThumbprint = ($thumbprint -replace "\s", "").ToUpperInvariant()
    if ($normalizedThumbprint -notmatch "^[0-9A-F]{40}$") {
        throw "The Authenticode certificate thumbprint must be 40 hexadecimal characters."
    }
    $certificatePath = "Cert:\CurrentUser\My\$normalizedThumbprint"
    $certificate = Get-Item -LiteralPath $certificatePath -ErrorAction SilentlyContinue
    if ($null -eq $certificate -or -not $certificate.HasPrivateKey) {
        throw "The requested Authenticode certificate is not available with its private key."
    }
    $now = [DateTime]::UtcNow
    if (
        $certificate.NotBefore.ToUniversalTime() -gt $now -or
        $certificate.NotAfter.ToUniversalTime() -le $now
    ) {
        throw "The requested Authenticode certificate is outside its validity period."
    }
    $codeSigningOid = "1.3.6.1.5.5.7.3.3"
    $ekuOids = @(
        $certificate.EnhancedKeyUsageList |
            ForEach-Object { $_.ObjectId.Value }
    )
    if ($ekuOids -notcontains $codeSigningOid) {
        throw "The requested certificate is not valid for code signing."
    }

    $timestampUrl = Test-AbsoluteWebUri `
        -Value (Get-RequiredEnvironmentValue `
            -Name "DESKCUBBY_WINDOWS_TIMESTAMP_URL" `
            -MaximumLength 2048) `
        -AllowedSchemes @("http", "https") `
        -Name "DESKCUBBY_WINDOWS_TIMESTAMP_URL"
    $tspRaw = [Environment]::GetEnvironmentVariable("DESKCUBBY_WINDOWS_TIMESTAMP_TSP")
    $useTsp = $true
    if (-not [string]::IsNullOrWhiteSpace($tspRaw)) {
        if (-not [bool]::TryParse($tspRaw, [ref]$useTsp)) {
            throw "DESKCUBBY_WINDOWS_TIMESTAMP_TSP must be true or false."
        }
    }

    $windowsConfig.certificateThumbprint = $normalizedThumbprint
    $windowsConfig.digestAlgorithm = "sha256"
    $windowsConfig.timestampUrl = $timestampUrl
    $windowsConfig.tsp = $useTsp
}
elseif ($hasSignCommand) {
    if ($signCommand.Length -gt 4096 -or $signCommand -match "[`r`n]") {
        throw "DESKCUBBY_WINDOWS_SIGN_COMMAND must be one line and at most 4096 characters."
    }
    if (-not $signCommand.Contains("%1")) {
        throw "DESKCUBBY_WINDOWS_SIGN_COMMAND must contain the Tauri %1 file placeholder."
    }
    Get-RequiredEnvironmentValue `
        -Name "DESKCUBBY_WINDOWS_SIGNER_SUBJECT" `
        -MaximumLength 256 |
        Out-Null
    $windowsConfig.signCommand = $signCommand
}
else {
    Write-Warning (
        "Authenticode is not configured. The production artifacts will be " +
        "Tauri-updater-signed but Windows may display an unknown publisher."
    )
}

$configuration = [ordered]@{
    bundle = [ordered]@{
        createUpdaterArtifacts = $true
        windows = $windowsConfig
    }
    plugins = [ordered]@{
        updater = [ordered]@{
            pubkey = $publicKey
            endpoints = @($endpoint)
            dangerousInsecureTransportProtocol = $false
            windows = [ordered]@{
                installMode = "passive"
            }
        }
    }
}

$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [IO.Path]::GetDirectoryName($resolvedOutput)
if ([string]::IsNullOrWhiteSpace($outputDirectory)) {
    throw "The release config output path has no parent directory."
}
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$json = $configuration | ConvertTo-Json -Depth 10
[IO.File]::WriteAllText(
    $resolvedOutput,
    $json,
    [Text.UTF8Encoding]::new($false)
)
Write-Host "Created temporary production-release Tauri configuration."
