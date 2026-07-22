[CmdletBinding()]
param(
    [string]$Alias = "deskcubby",
    [string]$DistinguishedName = "CN=DeskCubby, OU=Release, O=DeskCubby, C=CN",
    [int]$ValidityDays = 36500
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$keystoreDirectory = Join-Path $repoRoot "release"
$keystorePath = Join-Path $keystoreDirectory "DeskCubby-release.jks"
$propertiesPath = Join-Path $repoRoot "keystore.properties"

if (Test-Path -LiteralPath $keystorePath) {
    throw "Release keystore already exists: $keystorePath"
}
if (Test-Path -LiteralPath $propertiesPath) {
    throw "Signing properties already exist: $propertiesPath"
}

$keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
if ($null -eq $keytoolCommand) {
    throw "keytool was not found. Install JDK 17+ or add its bin directory to PATH."
}

function New-ReleasePassword {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    # A fixed alphabetic prefix prevents keytool from interpreting a leading '-' as an option.
    return "DC_" + [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$password = New-ReleasePassword
New-Item -ItemType Directory -Path $keystoreDirectory -Force | Out-Null

$keytoolArguments = @(
    "-genkeypair",
    "-noprompt",
    "-keystore", $keystorePath,
    "-storetype", "PKCS12",
    "-storepass", $password,
    "-keypass", $password,
    "-alias", $Alias,
    "-keyalg", "RSA",
    "-keysize", "4096",
    "-sigalg", "SHA256withRSA",
    "-validity", $ValidityDays.ToString(),
    "-dname", $DistinguishedName
)

& $keytoolCommand.Source @keytoolArguments
if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE"
}

$propertyLines = @(
    "storeFile=release/DeskCubby-release.jks",
    "storePassword=$password",
    "keyAlias=$Alias",
    "keyPassword=$password"
)
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($propertiesPath, $propertyLines, $utf8WithoutBom)

Write-Host "Release signing created successfully."
Write-Host "Keystore: $keystorePath"
Write-Host "Credentials: $propertiesPath"
Write-Host "Back up both files securely. Losing the key prevents signing future app updates."
