param([string]$PgBin = 'C:\Program Files\PostgreSQL\18\bin', [switch]$Semantic, [switch]$Governance, [switch]$Runtime, [switch]$Documents, [switch]$Enterprise, [switch]$Gemini, [switch]$RealEmbeddings)
$ErrorActionPreference = 'Stop'
if($RealEmbeddings -and (-not $Enterprise -or -not $Gemini)){throw 'Real embeddings require explicit Enterprise and Gemini flags.'}
$temporaryRoot = [IO.Path]::GetFullPath($env:TEMP)
$testDirectory = Join-Path $temporaryRoot ('retail-memory-it-' + [guid]::NewGuid().ToString('N'))
$dataDirectory = Join-Path $testDirectory 'data'
$passwordFile = Join-Path $testDirectory 'init-password'
$started = $false
$savedEnvironment = @{}
$environmentKeys = @('MEMORY_POSTGRES_MIGRATION_URL','MEMORY_POSTGRES_MIGRATION_USER','MEMORY_POSTGRES_MIGRATION_PASSWORD','MEMORY_POSTGRES_TEST_DISPOSABLE','PGPASSWORD','JAVA_HOME')
foreach ($key in $environmentKeys) { $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key,'Process') }
try {
    foreach ($binary in @('initdb.exe','pg_ctl.exe','psql.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PgBin $binary))) { throw "PostgreSQL binary not found: $binary" }
    }
    New-Item -ItemType Directory -Path $testDirectory | Out-Null
    $randomBytes = New-Object byte[] 32
    $randomSource = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $randomSource.GetBytes($randomBytes) } finally { $randomSource.Dispose() }
    $temporaryPassword = [Convert]::ToBase64String($randomBytes)
    [IO.File]::WriteAllText($passwordFile,$temporaryPassword)
    & (Join-Path $PgBin 'initdb.exe') -D $dataDirectory -U phase62_admin -A scram-sha-256 --no-locale --encoding=UTF8 "--pwfile=$passwordFile" *> (Join-Path $testDirectory 'init.log')
    if ($LASTEXITCODE -ne 0) { throw 'Temporary PostgreSQL initialization failed.' }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start(); $testPort = $listener.LocalEndpoint.Port; $listener.Stop()
    [IO.File]::AppendAllText((Join-Path $dataDirectory 'postgresql.conf'), "`nlisten_addresses='127.0.0.1'`nport=$testPort`nmax_connections=30`n")
    $startProcess = Start-Process -FilePath (Join-Path $PgBin 'pg_ctl.exe') -WindowStyle Hidden -PassThru -ArgumentList @('-D',('"' + $dataDirectory + '"'),'-l',('"' + (Join-Path $testDirectory 'server.log') + '"'),'-w','start') -RedirectStandardOutput (Join-Path $testDirectory 'start.log') -RedirectStandardError (Join-Path $testDirectory 'start-error.log')
    # Wait on pg_ctl only. Start-Process -Wait also waits for the long-lived server child on Windows.
    if (-not $startProcess.WaitForExit(30000)) { throw 'Temporary PostgreSQL startup timed out.' }
    if ($startProcess.ExitCode -ne 0) { throw 'Temporary PostgreSQL startup failed.' }
    $started = $true
    $env:PGPASSWORD = $temporaryPassword
    & (Join-Path $PgBin 'psql.exe') -X -h 127.0.0.1 -p $testPort -U phase62_admin -d postgres -v ON_ERROR_STOP=1 -c 'CREATE DATABASE retail_memory_it' *> (Join-Path $testDirectory 'create.log')
    if ($LASTEXITCODE -ne 0) { throw 'Temporary test database creation failed.' }
    $env:MEMORY_POSTGRES_MIGRATION_URL = "jdbc:postgresql://127.0.0.1:$testPort/retail_memory_it"
    $env:MEMORY_POSTGRES_MIGRATION_USER = 'phase62_admin'
    $env:MEMORY_POSTGRES_MIGRATION_PASSWORD = $temporaryPassword
    $env:MEMORY_POSTGRES_TEST_DISPOSABLE = 'true'
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
    Push-Location (Split-Path $PSScriptRoot -Parent)
    try {
        if ($Enterprise) {
            $mavenArguments=@('test','-Dtest=EnterpriseEndToEndPostgresTest','-DrunPostgresMemoryTests=true','-DrunSemanticMemoryTests=true','-DrunGovernanceMemoryTests=true','-DrunContextRuntimePostgresTests=true','-DrunDocumentPostgresTests=true','-DrunEnterpriseE2eTests=true')
            if($Gemini){$mavenArguments+='-DrunEnterpriseGeminiTests=true'}
            if($RealEmbeddings){$mavenArguments+='-DrunEnterpriseRealEmbeddings=true'}
            $resultLog=if($Gemini){'phase6-6c-live.log'}else{'phase6-6c-enterprise.log'}
            & mvn @mavenArguments *> $resultLog
        } elseif ($Documents) {
            & mvn test '-Dtest=EnterpriseDocumentPostgresTest' '-DrunPostgresMemoryTests=true' '-DrunSemanticMemoryTests=true' '-DrunGovernanceMemoryTests=true' '-DrunContextRuntimePostgresTests=true' '-DrunDocumentPostgresTests=true' *> phase6-6b-postgres.log
            $resultLog = 'phase6-6b-postgres.log'
        } elseif ($Runtime) {
            & mvn test '-Dtest=ContextAwareRuntimePostgresTest' '-DrunPostgresMemoryTests=true' '-DrunSemanticMemoryTests=true' '-DrunGovernanceMemoryTests=true' '-DrunContextRuntimePostgresTests=true' *> phase6-5-postgres.log
            $resultLog = 'phase6-5-postgres.log'
        } elseif ($Governance) {
            & mvn test '-Dtest=GovernanceMemoryIntegrationTest' '-DrunPostgresMemoryTests=true' '-DrunSemanticMemoryTests=true' '-DrunGovernanceMemoryTests=true' *> phase6-4-postgres.log
            $resultLog = 'phase6-4-postgres.log'
        } elseif ($Semantic) {
            & mvn test '-Dtest=SemanticMemoryIntegrationTest' '-DrunPostgresMemoryTests=true' '-DrunSemanticMemoryTests=true' *> phase6-3-postgres.log
            $resultLog = 'phase6-3-postgres.log'
        } else {
            & mvn test '-Dtest=PostgresMemoryIntegrationTest' '-DrunPostgresMemoryTests=true' *> phase6-2-postgres.log
            $resultLog = 'phase6-2-postgres.log'
        }
        $testExitCode = $LASTEXITCODE
        if($Enterprise){Select-String -Path $resultLog -Pattern 'ENTERPRISE E2E:|ENTERPRISE SECURITY:' | ForEach-Object {$_.Line}}
        Get-Content $resultLog -Tail 20
        if ($testExitCode -ne 0) { throw "PostgreSQL integration tests failed; inspect $resultLog." }
    } finally { Pop-Location }
} finally {
    foreach ($key in $environmentKeys) { [Environment]::SetEnvironmentVariable($key,$savedEnvironment[$key],'Process') }
    if (Test-Path -LiteralPath (Join-Path $dataDirectory 'postmaster.pid')) {
        & (Join-Path $PgBin 'pg_ctl.exe') -D $dataDirectory -m fast -w stop *> (Join-Path $testDirectory 'stop.log')
        if ($LASTEXITCODE -ne 0) { throw "Temporary PostgreSQL could not stop; data retained at $testDirectory" }
    }
    # Only delete this script's verified, unique temporary directory after its own server stopped.
    $resolvedTestDirectory = [IO.Path]::GetFullPath($testDirectory)
    if ($resolvedTestDirectory.StartsWith($temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolvedTestDirectory -Leaf).StartsWith('retail-memory-it-') -and (Test-Path -LiteralPath $resolvedTestDirectory)) {
        Remove-Item -LiteralPath $resolvedTestDirectory -Recurse -Force
    }
}
