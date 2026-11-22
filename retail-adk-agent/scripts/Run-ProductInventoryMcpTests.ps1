#Requires -Version 7.0
param(
    [string]$ServerJar = 'C:\agent-AI-workspace\retail-stock-investigator\retail-inventory-mcp\target\retail-inventory-mcp-1.0.0.jar',
    [string]$ServerJdk = 'C:\Program Files\Java\jdk-25.0.5.0.3',
    [string]$AdkJdk = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$jar = (Resolve-Path -LiteralPath $ServerJar).Path
$fixture = Join-Path $project ('target/product-mcp-live-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$settingNames = 'JAVA_HOME', 'MCP_SERVER_URL', 'MCP_DELEGATION_KEY', 'MCP_AUDIT_FILE'
$savedSettings = @{}
foreach ($name in $settingNames) { $savedSettings[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$server = $null
$outputFile = $null
$errorFile = $null
$outputCopy = $null
$errorCopy = $null
try {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $random = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $secret = [Convert]::ToBase64String($random)
    [Array]::Clear($random)

    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $ServerJdk 'bin/java.exe'
    $start.WorkingDirectory = $fixture
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($arg in @('-jar', $jar, '--server.address=127.0.0.1', "--server.port=$port")) {
        $start.ArgumentList.Add($arg)
    }
    $start.Environment.Clear()
    foreach ($name in 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE') {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ($value) { $start.Environment[$name] = $value }
    }
    $start.Environment['MCP_DELEGATION_KEY'] = $secret
    $server = [Diagnostics.Process]::Start($start)
    $outputFile = [IO.File]::Create((Join-Path $fixture 'server.log'))
    $errorFile = [IO.File]::Create((Join-Path $fixture 'server-errors.log'))
    $outputCopy = $server.StandardOutput.BaseStream.CopyToAsync($outputFile)
    $errorCopy = $server.StandardError.BaseStream.CopyToAsync($errorFile)
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($server.HasExited) { throw "Spring MCP server exited. Inspect logs under $fixture" }
        $socket = [Net.Sockets.TcpClient]::new()
        try {
            if ($socket.ConnectAsync('127.0.0.1', $port).Wait(250) -and $socket.Connected) {
                $ready = $true
                break
            }
        } catch {
            # The process may still be starting.
        } finally {
            $socket.Dispose()
        }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw "Spring MCP server did not start. Inspect logs under $fixture" }

    $env:JAVA_HOME = $AdkJdk
    $env:MCP_SERVER_URL = "http://127.0.0.1:$port/mcp"
    $env:MCP_DELEGATION_KEY = $secret
    $env:MCP_AUDIT_FILE = Join-Path $fixture 'audit.jsonl'
    $testLog = Join-Path $fixture 'maven.log'
    Push-Location $project
    try {
        & mvn -B -ntp '-Dtest=ProductInventoryLiveTest' '-DrunProductLiveMcpTests=true' test *> $testLog
        $code = $LASTEXITCODE
        Get-Content -LiteralPath $testLog -Tail 24
        if ($code -ne 0) { throw "Product live MCP tests failed. Inspect $testLog" }
    } finally {
        Pop-Location
    }
} finally {
    if ($null -ne $server) {
        if (!$server.HasExited) { $server.Kill($true) }
        $server.WaitForExit()
        if ($null -ne $outputCopy) { $outputCopy.GetAwaiter().GetResult() }
        if ($null -ne $errorCopy) { $errorCopy.GetAwaiter().GetResult() }
        $server.Dispose()
    }
    if ($null -ne $outputFile) { $outputFile.Dispose() }
    if ($null -ne $errorFile) { $errorFile.Dispose() }
    foreach ($name in $settingNames) { [Environment]::SetEnvironmentVariable($name, $savedSettings[$name], 'Process') }
}
