#Requires -Version 7.0
param(
    [string]$CustomerProject = 'C:\agent-AI-workspace\retail-customer-service',
    [string]$PgBin = 'C:\Program Files\PostgreSQL\18\bin',
    [string]$CustomerJdk = 'C:\Program Files\Java\jdk-25.0.5.0.3',
    [string]$AdkJdk = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
$adkProject = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $env:TEMP ('retail-chat-it-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$data = Join-Path $fixture 'data'
$passwordFile = Join-Path $fixture 'init-password'
$api = $null
$settings = 'JAVA_HOME','RETAIL_TEST_DB_URL','RETAIL_TEST_DB_USER','RETAIL_TEST_DB_PASSWORD',
    'RETAIL_DB_URL','RETAIL_DB_USER','RETAIL_DB_PASSWORD','SPRING_PROFILES_ACTIVE','RETAIL_CHAT_ENABLED',
    'RETAIL_CHAT_ADK_URL','RETAIL_CHAT_ADK_PORT','RETAIL_CHAT_BRIDGE_TOKEN','RETAIL_CHAT_PORTAL_ORIGIN',
    'RETAIL_CUSTOMER_CONTEXT_URL'
$saved = @{}
foreach ($name in $settings) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

try {
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    [IO.File]::WriteAllText($passwordFile, $password)
    & (Join-Path $PgBin 'initdb.exe') -D $data -U retail_customer_app -A scram-sha-256 --no-locale -E UTF8 "--pwfile=$passwordFile" *> (Join-Path $fixture 'init.log')
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL initialization failed. Inspect $fixture." }
    Remove-Item -LiteralPath $passwordFile
    $pgPort = Get-FreePort
    [IO.File]::AppendAllText((Join-Path $data 'postgresql.conf'), "`nlisten_addresses='127.0.0.1'`nport=$pgPort`n")
    $pgArgs = @('-D', ('"' + $data + '"'), '-l', ('"' + (Join-Path $fixture 'postgres.log') + '"'), '-w', 'start')
    $pg = Start-Process (Join-Path $PgBin 'pg_ctl.exe') -WindowStyle Hidden -PassThru -ArgumentList $pgArgs -RedirectStandardOutput (Join-Path $fixture 'pg.out') -RedirectStandardError (Join-Path $fixture 'pg.err')
    if (!$pg.WaitForExit(30000) -or $pg.ExitCode -ne 0) { throw "PostgreSQL startup failed. Inspect $fixture." }

    $env:RETAIL_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$pgPort/postgres"
    $env:RETAIL_TEST_DB_USER = 'retail_customer_app'
    $env:RETAIL_TEST_DB_PASSWORD = $password
    $env:RETAIL_CHAT_ENABLED = 'false'
    $env:JAVA_HOME = $CustomerJdk
    Push-Location $CustomerProject
    try {
        $customerBuild = Join-Path $fixture 'customer-maven.log'
        mvn -B -ntp verify *> $customerBuild
        $code = $LASTEXITCODE
        Select-String -Path $customerBuild -Pattern 'Tests run:|BUILD SUCCESS|BUILD FAILURE' | Select-Object -Last 6 | ForEach-Object Line
        if ($code -ne 0) {
            Get-Content -LiteralPath $customerBuild -Tail 24
            throw "Customer verification failed. Inspect $fixture."
        }
        $jar = (Resolve-Path 'target/retail-customer-service-0.1.0-SNAPSHOT.jar').Path
    } finally { Pop-Location }

    $env:RETAIL_DB_URL = $env:RETAIL_TEST_DB_URL
    $env:RETAIL_DB_USER = $env:RETAIL_TEST_DB_USER
    $env:RETAIL_DB_PASSWORD = $password
    $env:SPRING_PROFILES_ACTIVE = 'poc'
    $env:RETAIL_CHAT_ENABLED = 'true'
    $env:RETAIL_CHAT_ADK_PORT = [string](Get-FreePort)
    $env:RETAIL_CHAT_ADK_URL = "http://127.0.0.1:$env:RETAIL_CHAT_ADK_PORT"
    $env:RETAIL_CHAT_PORTAL_ORIGIN = 'http://127.0.0.1:5173'
    $env:RETAIL_CHAT_BRIDGE_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')
    $apiLog = Join-Path $fixture 'customer.out'
    $javaArgs = @('-jar', ('"' + $jar + '"'), '--server.port=0')
    $api = Start-Process (Join-Path $CustomerJdk 'bin/java.exe') -WindowStyle Hidden -PassThru -ArgumentList $javaArgs -RedirectStandardOutput $apiLog -RedirectStandardError (Join-Path $fixture 'customer.err')
    Remove-Item Env:RETAIL_DB_PASSWORD,Env:RETAIL_TEST_DB_PASSWORD
    $ready = $null
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($api.HasExited) { break }
        $ready = Select-String -LiteralPath $apiLog -Pattern 'Tomcat started on port ([0-9]+)' | Select-Object -Last 1
        if ($null -ne $ready) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($null -eq $ready) { throw "Customer HTTP fixture failed. Inspect $fixture." }

    $env:RETAIL_CUSTOMER_CONTEXT_URL = 'http://127.0.0.1:' + $ready.Matches[0].Groups[1].Value
    $env:JAVA_HOME = $AdkJdk
    Push-Location $adkProject
    try {
        $liveLog = Join-Path $fixture 'adk-http-maven.log'
        mvn -B -ntp '-Dtest=RetailChatEndToEndTest' '-DrunChatAdkIntegrationTests=true' test *> $liveLog
        $code = $LASTEXITCODE
        Get-Content -LiteralPath $liveLog -Tail 23
        if ($code -ne 0) { throw "ADK HTTP integration failed. Inspect $fixture." }
    } finally { Pop-Location }
} finally {
    if ($null -ne $api -and !$api.HasExited) { Stop-Process -Id $api.Id }
    if (Test-Path -LiteralPath (Join-Path $data 'postmaster.pid')) {
        & (Join-Path $PgBin 'pg_ctl.exe') -D $data -m fast -w stop
    }
    if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile }
    foreach ($name in $settings) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
