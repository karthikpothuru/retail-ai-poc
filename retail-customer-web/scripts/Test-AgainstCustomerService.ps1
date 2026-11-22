#Requires -Version 7.0
param(
    [string]$CustomerJar = 'C:\agent-AI-workspace\retail-customer-service\target\retail-customer-service-0.1.0-SNAPSHOT.jar',
    [string]$PgBin = 'C:\Program Files\PostgreSQL\18\bin',
    [string]$Java = 'C:\Program Files\Java\jdk-25.0.5.0.3\bin\java.exe',
    [int]$CustomerPort = 0,
    [ValidatePattern('^http://127\.0\.0\.1:[0-9]+$')][string]$ChatAdkUrl
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$jar = (Resolve-Path -LiteralPath $CustomerJar).Path
$fixture = Join-Path $env:TEMP ('retail-portal-it-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$database = Join-Path $fixture 'pg'
$passwordFile = Join-Path $fixture 'init-password'
$server = $null
$names = 'RETAIL_DB_URL','RETAIL_DB_USER','RETAIL_DB_PASSWORD','SPRING_PROFILES_ACTIVE','RETAIL_CUSTOMER_API_PORT','RETAIL_PORTAL_PORT'
$names += 'RETAIL_CHAT_ENABLED','RETAIL_CHAT_ADK_URL','RETAIL_CHAT_PORTAL_ORIGIN','RETAIL_CHAT_E2E'
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Get-FreeLoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

try {
    if ($ChatAdkUrl -and (!$CustomerPort -or !$env:RETAIL_CHAT_BRIDGE_TOKEN)) {
        throw 'Supply the customer port and set RETAIL_CHAT_BRIDGE_TOKEN when testing against ADK.'
    }
    $secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    [IO.File]::WriteAllText($passwordFile, $secret)
    & (Join-Path $PgBin 'initdb.exe') -D $database -U retail_customer_app -A scram-sha-256 --no-locale -E UTF8 "--pwfile=$passwordFile" *> (Join-Path $fixture 'init.log')
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL initialization failed. Inspect $fixture." }
    Remove-Item -LiteralPath $passwordFile
    $pgPort = Get-FreeLoopbackPort
    [IO.File]::AppendAllText((Join-Path $database 'postgresql.conf'), "`nlisten_addresses='127.0.0.1'`nport=$pgPort`n")
    $pgArgs = @('-D', ('"' + $database + '"'), '-l', ('"' + (Join-Path $fixture 'postgres.log') + '"'), '-w', 'start')
    $start = Start-Process (Join-Path $PgBin 'pg_ctl.exe') -WindowStyle Hidden -PassThru -ArgumentList $pgArgs -RedirectStandardOutput (Join-Path $fixture 'pg-start.log') -RedirectStandardError (Join-Path $fixture 'pg-start-errors.log')
    if (!$start.WaitForExit(30000) -or $start.ExitCode -ne 0) { throw "PostgreSQL startup failed. Inspect $fixture." }

    $env:RETAIL_DB_URL = "jdbc:postgresql://127.0.0.1:$pgPort/postgres"
    $env:RETAIL_DB_USER = 'retail_customer_app'
    $env:RETAIL_DB_PASSWORD = $secret
    $env:SPRING_PROFILES_ACTIVE = 'poc'
    $customerLog = Join-Path $fixture 'customer.log'
    $env:RETAIL_PORTAL_PORT = [string](Get-FreeLoopbackPort)
    $env:RETAIL_CHAT_E2E = if ($ChatAdkUrl) { 'true' } else { 'false' }
    $env:RETAIL_CHAT_ENABLED = $env:RETAIL_CHAT_E2E
    if ($ChatAdkUrl) {
        $env:RETAIL_CHAT_ADK_URL = $ChatAdkUrl
        $env:RETAIL_CHAT_PORTAL_ORIGIN = "http://127.0.0.1:$env:RETAIL_PORTAL_PORT"
    }
    $bootArgs = @('-jar', ('"' + $jar + '"'), "--server.port=$CustomerPort")
    $server = Start-Process $Java -WindowStyle Hidden -PassThru -ArgumentList $bootArgs -RedirectStandardOutput $customerLog -RedirectStandardError (Join-Path $fixture 'customer-errors.log')
    [Environment]::SetEnvironmentVariable('RETAIL_DB_PASSWORD', $saved['RETAIL_DB_PASSWORD'], 'Process')
    $ready = $null
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($server.HasExited) { break }
        $ready = Select-String -LiteralPath $customerLog -Pattern 'Tomcat started on port ([0-9]+)' | Select-Object -Last 1
        if ($null -ne $ready) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($null -eq $ready) { throw "Customer service startup failed. Inspect $fixture." }
    $env:RETAIL_CUSTOMER_API_PORT = $ready.Matches[0].Groups[1].Value
    Push-Location $project
    try {
        npm run test:e2e
        if ($LASTEXITCODE -ne 0) { throw "Playwright failed. Spring fixture logs are in $fixture." }
    } finally { Pop-Location }
} finally {
    if ($null -ne $server -and !$server.HasExited) { Stop-Process -Id $server.Id }
    if (Test-Path -LiteralPath (Join-Path $database 'postmaster.pid')) {
        & (Join-Path $PgBin 'pg_ctl.exe') -D $database -m fast -w stop
    }
    if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
