[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$environmentNames = @(
    "DESKCUBBY_UPDATER_PUBLIC_KEY",
    "DESKCUBBY_UPDATER_ENDPOINT",
    "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT",
    "DESKCUBBY_WINDOWS_SIGN_COMMAND",
    "DESKCUBBY_WINDOWS_SIGNER_SUBJECT",
    "DESKCUBBY_WINDOWS_TIMESTAMP_URL",
    "DESKCUBBY_WINDOWS_TIMESTAMP_TSP"
)
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}

function Set-TestEnvironmentValue {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [AllowNull()]
        [string]$Value
    )
    [Environment]::SetEnvironmentVariable($Name, $Value)
}

function Assert-Condition {
    param(
        [Parameter(Mandatory)]
        [bool]$Condition,
        [Parameter(Mandatory)]
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Throws {
    param(
        [Parameter(Mandatory)]
        [scriptblock]$Action,
        [Parameter(Mandatory)]
        [string]$Message
    )
    $didThrow = $false
    try {
        & $Action
    }
    catch {
        $didThrow = $true
    }
    if (-not $didThrow) {
        throw $Message
    }
}

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryDirectory = [IO.Path]::Combine(
    $temporaryRoot,
    "deskcubby-release-policy-$([Guid]::NewGuid().ToString('N'))"
)
$releaseConfigScript = Join-Path $PSScriptRoot "new-release-config.ps1"

try {
    [IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null

    $rawPublicKey = [byte[]]::new(42)
    $rawPublicKey[0] = 0x45
    $rawPublicKey[1] = 0x64
    $minisignEnvelope = (
        "untrusted comment: DeskCubby release policy test key`n" +
        [Convert]::ToBase64String($rawPublicKey) +
        "`n"
    )
    $encodedPublicKey = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($minisignEnvelope)
    )
    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_UPDATER_PUBLIC_KEY" `
        -Value $encodedPublicKey
    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_UPDATER_ENDPOINT" `
        -Value "https://example.invalid/releases/download/windows-stable/latest.json"
    foreach ($name in $environmentNames | Select-Object -Skip 2) {
        Set-TestEnvironmentValue -Name $name -Value $null
    }

    $unsignedConfigPath = Join-Path $temporaryDirectory "unsigned.json"
    & $releaseConfigScript -OutputPath $unsignedConfigPath
    $unsignedConfig = Get-Content -Raw -LiteralPath $unsignedConfigPath |
        ConvertFrom-Json
    Assert-Condition `
        -Condition ([bool]$unsignedConfig.bundle.createUpdaterArtifacts) `
        -Message "The production config must create updater artifacts."
    $unsignedWindowsProperties = @(
        $unsignedConfig.bundle.windows.PSObject.Properties |
            ForEach-Object { $_.Name }
    )
    Assert-Condition `
        -Condition ($unsignedWindowsProperties -notcontains "certificateThumbprint") `
        -Message "Unsigned Authenticode policy unexpectedly set a thumbprint."
    Assert-Condition `
        -Condition ($unsignedWindowsProperties -notcontains "signCommand") `
        -Message "Unsigned Authenticode policy unexpectedly set a sign command."
    Assert-Condition `
        -Condition (
            [string]$unsignedConfig.plugins.updater.pubkey -eq $encodedPublicKey
        ) `
        -Message "The updater public key was not preserved exactly."
    Assert-Condition `
        -Condition (
            [string]$unsignedConfig.plugins.updater.endpoints[0] -like "https://*"
        ) `
        -Message "The updater endpoint must remain HTTPS."

    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_WINDOWS_SIGN_COMMAND" `
        -Value 'mock-signer "%1"'
    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_WINDOWS_SIGNER_SUBJECT" `
        -Value "CN=DeskCubby release policy test"
    $customSigningConfigPath = Join-Path $temporaryDirectory "custom-signing.json"
    & $releaseConfigScript -OutputPath $customSigningConfigPath
    $customSigningConfig = Get-Content `
        -Raw `
        -LiteralPath $customSigningConfigPath |
        ConvertFrom-Json
    Assert-Condition `
        -Condition (
            [string]$customSigningConfig.bundle.windows.signCommand -eq
            'mock-signer "%1"'
        ) `
        -Message "A configured custom Authenticode command was not preserved."

    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT" `
        -Value ("A" * 40)
    Assert-Throws `
        -Action { & $releaseConfigScript -OutputPath (Join-Path $temporaryDirectory "both.json") } `
        -Message "The release config accepted two Authenticode mechanisms."

    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_WINDOWS_CERTIFICATE_THUMBPRINT" `
        -Value $null
    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_WINDOWS_SIGN_COMMAND" `
        -Value $null
    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_WINDOWS_SIGNER_SUBJECT" `
        -Value $null
    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_UPDATER_PUBLIC_KEY" `
        -Value $null
    Assert-Throws `
        -Action { & $releaseConfigScript -OutputPath (Join-Path $temporaryDirectory "no-updater-key.json") } `
        -Message "Production config accepted a missing updater public key."

    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_UPDATER_PUBLIC_KEY" `
        -Value $encodedPublicKey
    Set-TestEnvironmentValue `
        -Name "DESKCUBBY_UPDATER_ENDPOINT" `
        -Value "http://example.invalid/latest.json"
    Assert-Throws `
        -Action { & $releaseConfigScript -OutputPath (Join-Path $temporaryDirectory "http-endpoint.json") } `
        -Message "Production config accepted a non-HTTPS updater endpoint."

    Write-Host "Windows release policy script tests passed."
}
finally {
    foreach ($name in $environmentNames) {
        Set-TestEnvironmentValue -Name $name -Value $originalEnvironment[$name]
    }
    if ([IO.Directory]::Exists($temporaryDirectory)) {
        $resolvedTemporaryDirectory = [IO.Path]::GetFullPath($temporaryDirectory)
        $temporaryPrefix = $temporaryRoot.TrimEnd(
            [IO.Path]::DirectorySeparatorChar,
            [IO.Path]::AltDirectorySeparatorChar
        ) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedTemporaryDirectory.StartsWith(
            $temporaryPrefix,
            [StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Refusing to remove a release-policy directory outside the temp root."
        }
        [IO.Directory]::Delete($resolvedTemporaryDirectory, $true)
    }
}
