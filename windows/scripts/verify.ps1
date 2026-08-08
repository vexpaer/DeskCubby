[CmdletBinding()]
param(
    [switch]$IncludePackage
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$windowsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))

function Invoke-Checked {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [Parameter(Mandatory)]
        [scriptblock]$Command
    )

    Write-Host "`n==> $Name"
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
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

Push-Location $windowsRoot
try {
    Write-Host "`n==> Packaging configuration"
    & (Join-Path $PSScriptRoot "verify-packaging.ps1")
    Write-Host "`n==> Release policy scripts"
    & (Join-Path $PSScriptRoot "test-release-policy.ps1")
    Invoke-Checked -Name "Frontend lint" -Command { Invoke-Pnpm lint }
    Invoke-Checked -Name "Frontend typecheck" -Command { Invoke-Pnpm typecheck }
    Invoke-Checked -Name "Frontend tests" -Command { Invoke-Pnpm test }
    Invoke-Checked -Name "Rust formatting" -Command {
        cargo fmt --manifest-path .\src-tauri\Cargo.toml --all -- --check
    }
    Invoke-Checked -Name "Rust clippy" -Command {
        cargo clippy --locked --manifest-path .\src-tauri\Cargo.toml --all-targets -- -D warnings
    }
    Invoke-Checked -Name "Rust tests" -Command {
        cargo test --locked --manifest-path .\src-tauri\Cargo.toml
    }

    if ($IncludePackage) {
        Invoke-Checked -Name "Windows release and NSIS package" -Command {
            & (Join-Path $PSScriptRoot "build-release.ps1") `
                -Mode AllowUnsignedTestBuild `
                -SkipChecks
        }
    }
}
finally {
    Pop-Location
}
