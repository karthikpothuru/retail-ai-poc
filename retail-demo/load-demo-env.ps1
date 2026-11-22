$ErrorActionPreference = "Stop"

$configDir = Join-Path $PSScriptRoot "config"
$envFile = Join-Path $configDir "demo.env.local"
$secretFile = Join-Path $configDir "demo-secrets.xml"

if (-not (Test-Path $envFile)) {
    throw "Demo configuration file missing: $envFile"
}

if (-not (Test-Path $secretFile)) {
    throw "Demo credential file missing: $secretFile"
}

$configCount = 0

foreach ($line in Get-Content $envFile) {
    $line = $line.Trim()

    if (-not $line -or $line.StartsWith("#")) {
        continue
    }

    $parts = $line -split "=", 2

    if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Invalid demo environment configuration entry."
    }

    [Environment]::SetEnvironmentVariable(
        $parts[0],
        $parts[1],
        "Process"
    )

    $configCount++
}

$secrets = Import-Clixml -Path $secretFile
$secretCount = 0

foreach ($name in $secrets.Keys) {
    $secureValue = $secrets[$name]

    $plainValue = [System.Net.NetworkCredential]::new(
        "",
        $secureValue
    ).Password

    [Environment]::SetEnvironmentVariable(
        $name,
        $plainValue,
        "Process"
    )

    $secretCount++
}

Write-Host "Retail AI POC environment loaded successfully."
Write-Host "Configuration variables: $configCount"
Write-Host "Encrypted credentials: $secretCount"
Write-Host "No credential values displayed."
