<#
.SYNOPSIS
Starts, resets or stops the local exchange sandbox of the Profit Basetool.

.DESCRIPTION
Runs the sandbox compose profile with the committed throwaway values in docker/sandbox/sandbox.env.
"up" starts and seeds it, "reset" removes it with all its data and starts it again, "down" stops it
and removes all its data. It checks that host.docker.internal resolves to the loopback, which the
issuer http://host.docker.internal:18080/auth/realms/basetool needs on this machine.

.PARAMETER Action
up, reset or down.

.PARAMETER Build
Build the images from this checkout instead of pulling the published sandbox images.

.EXAMPLE
./scripts/sandbox.ps1 up

.EXAMPLE
./scripts/sandbox.ps1 reset -Build

.LINK
docs/exchange/sandbox.md
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('up', 'reset', 'down')]
    [string] $Action,

    [switch] $Build
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$files = @('-f', 'docker-compose.yml', '-f', 'docker-compose.test.yml', '-f', 'docker-compose.sandbox.yml')
if ($Build) {
    $files += @('-f', 'docker-compose.sandbox-build.yml')
}
$compose = @('compose', '--env-file', 'docker/sandbox/sandbox.env') + $files + @('--profile', 'sandbox')
$services = @('redis-dev', 'db-backend-dev', 'db-keycloak-dev', 'keycloak-dev', 'backend-dev', 'frontend-dev', 'ingest-dev')

function Invoke-Compose {
    <#
    .SYNOPSIS
    Runs docker compose with the sandbox files and fails on a non-zero exit code only.

    .DESCRIPTION
    Docker writes its progress to the error stream. Windows PowerShell 5.1 turns each such line into
    an error record once the caller redirects that stream, which the script's 'Stop' preference would
    make fatal, so the call runs under 'Continue' and every line is passed on as plain text.

    .PARAMETER Arguments
    The compose command and its arguments.
    #>
    param([string[]] $Arguments)
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    try {
        & docker @compose @Arguments 2>&1 | ForEach-Object { "$_" }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($exitCode -ne 0) {
        throw "docker $($compose + $Arguments -join ' ') failed with exit code $exitCode"
    }
}

function Test-IssuerHost {
    $addresses = @()
    try {
        $addresses = @([System.Net.Dns]::GetHostAddresses('host.docker.internal') | ForEach-Object { $_.ToString() })
    } catch {
        $addresses = @()
    }
    if ($addresses.Count -gt 0 -and ($addresses[0] -like '127.*' -or $addresses[0] -eq '::1')) {
        return
    }
    $found = if ($addresses.Count -gt 0) { $addresses[0] } else { 'nothing' }
    Write-Warning "host.docker.internal resolves to $found, not to the loopback."
    Write-Warning 'The sandbox listens on 127.0.0.1 only, so a client or browser on this machine cannot reach the issuer.'
    Write-Warning 'Put "127.0.0.1 host.docker.internal" above the Docker Desktop block in C:\Windows\System32\drivers\etc\hosts (see docs/exchange/sandbox.md).'
}

function Start-Sandbox {
    if ($Build) {
        Invoke-Compose @('build')
    } else {
        Invoke-Compose @('pull', '--ignore-buildable')
    }
    Invoke-Compose (@('up', '-d', '--wait', '--wait-timeout', '600') + $services)
    Invoke-Compose @('run', '--rm', 'sandbox-seed')
    Write-Host 'sandbox: up. Issuer http://host.docker.internal:18080/auth/realms/basetool,'
    Write-Host 'sandbox: gateway https://localhost:11262/exchange/v1, web https://localhost:18081'
    Write-Host 'sandbox: the gateway answers 503 EXCHANGE_DISABLED for up to about 15 s until it sees the switch'
}

function Stop-Sandbox {
    Invoke-Compose @('down', '--volumes', '--remove-orphans')
}

switch ($Action) {
    'up' {
        Test-IssuerHost
        Start-Sandbox
    }
    'reset' {
        Test-IssuerHost
        Stop-Sandbox
        Start-Sandbox
    }
    'down' {
        Stop-Sandbox
    }
}
