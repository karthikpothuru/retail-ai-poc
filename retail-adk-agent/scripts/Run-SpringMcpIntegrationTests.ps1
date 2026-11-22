#Requires -Version 7.0
param(
    [string]$ServerProject='C:\agent-AI-workspace\retail-stock-investigator\retail-inventory-mcp',
    [string]$ServerJdk='C:\Program Files\Java\jdk-25.0.5.0.3',
    [int]$Port=18080,
    [switch]$Gemini,
    [switch]$DocumentGrounding,
    [switch]$Enterprise,
    [string]$PgBin,
    [switch]$RealEmbeddings
)
$ErrorActionPreference='Stop'
if($DocumentGrounding -and -not $Gemini){throw 'DocumentGrounding requires explicit -Gemini configuration.'}
if($Enterprise -and ($DocumentGrounding -or [string]::IsNullOrWhiteSpace($PgBin))){throw 'Enterprise requires PgBin and cannot be combined with DocumentGrounding mode.'}
if($RealEmbeddings -and (-not $Enterprise -or -not $Gemini)){throw 'RealEmbeddings requires explicit Enterprise and Gemini modes.'}
$clientRoot=Split-Path $PSScriptRoot -Parent
$project=[IO.Path]::GetFullPath($ServerProject)
if(-not (Test-Path -LiteralPath (Join-Path $project 'src\main\java\com\retail\inventory\security\SecureMcpDelegationFilter.java'))){throw 'Install the reviewed security overlay before running this helper.'}
if($Port -lt 1024 -or $Port -gt 65535){throw 'Invalid isolated server port.'}
$probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
try{$probe.Start()}finally{$probe.Stop()}
$saved=@{};foreach($key in @('JAVA_HOME','PATH','MCP_SERVER_URL','MCP_DELEGATION_KEY','MCP_TOOL_PROFILE')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$process=$null;$outFile=$null;$errFile=$null;$outTask=$null;$errTask=$null
try{
    $env:JAVA_HOME=$ServerJdk;$env:PATH=$ServerJdk+'\bin;'+$env:PATH
    Push-Location $project
    try{& mvn package *> (Join-Path $clientRoot 'phase6-6a-server-build.log');if($LASTEXITCODE -ne 0){throw 'Spring server build failed; inspect the local build log.'}}finally{Pop-Location}
    $bytes=New-Object byte[] 32;$random=[Security.Cryptography.RandomNumberGenerator]::Create();try{$random.GetBytes($bytes)}finally{$random.Dispose()}
    $secret=[Convert]::ToBase64String($bytes);[Array]::Clear($bytes,0,$bytes.Length)
    $start=[Diagnostics.ProcessStartInfo]::new();$start.FileName=Join-Path $ServerJdk 'bin\java.exe';$start.WorkingDirectory=$project;$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.ArgumentList.Add('-jar');$start.ArgumentList.Add((Join-Path $project 'target\retail-inventory-mcp-1.0.0.jar'));$start.ArgumentList.Add('--server.port='+$Port);$start.ArgumentList.Add('--server.address=127.0.0.1')
    # Do not propagate Gemini/database credentials into the independent MCP server process.
    $start.Environment.Clear();foreach($key in @('SystemRoot','WINDIR','TEMP','TMP','USERPROFILE')){$value=[Environment]::GetEnvironmentVariable($key,'Process');if($value){$start.Environment[$key]=$value}}
    $start.Environment['MCP_DELEGATION_KEY']=$secret
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start;[void]$process.Start()
    $outFile=[IO.File]::Create((Join-Path $clientRoot 'phase6-6a-server-live.log'));$errFile=[IO.File]::Create((Join-Path $clientRoot 'phase6-6a-server-live-error.log'))
    $outTask=$process.StandardOutput.BaseStream.CopyToAsync($outFile);$errTask=$process.StandardError.BaseStream.CopyToAsync($errFile)
    $ready=$false
    for($i=0;$i -lt 60;$i++){
        if($process.HasExited){throw 'Secured Spring server exited during startup; inspect the local server logs.'}
        $socket=[Net.Sockets.TcpClient]::new();try{$connect=$socket.ConnectAsync('127.0.0.1',$Port);if($connect.Wait(250) -and $socket.Connected){$ready=$true;break}}catch{}finally{$socket.Dispose()};Start-Sleep -Milliseconds 500
    }
    if(-not $ready){throw 'Secured Spring server startup timed out.'}
    $env:JAVA_HOME='C:\Program Files\Java\jdk-17';$env:PATH=$env:JAVA_HOME+'\bin;'+$saved['PATH'];$env:MCP_SERVER_URL='http://127.0.0.1:'+$Port+'/mcp';$env:MCP_DELEGATION_KEY=$secret;$env:MCP_TOOL_PROFILE='retail-inventory'
    Push-Location $clientRoot
    try{
        if($Enterprise){
            & (Join-Path $PSScriptRoot 'Run-PostgresMemoryIntegrationTests.ps1') -PgBin $PgBin -Enterprise -Gemini:$Gemini -RealEmbeddings:$RealEmbeddings
        }else{
        $arguments=if($DocumentGrounding){@('test','-Dtest=DocumentGroundingLiveTest','-DrunLiveDocumentMcpTests=true')}else{@('test','-Dtest=RetailInventorySpringLiveTest','-DrunLiveRetailMcpTests=true')}
        if($Gemini -and -not $DocumentGrounding){$arguments+='-DrunLiveMcpGeminiTests=true'}
        $integrationLog=if($DocumentGrounding){'phase6-6b-spring-integration.log'}else{'phase6-6a-spring-integration.log'}
        & mvn @arguments *> $integrationLog;$code=$LASTEXITCODE
        Select-String -Path $integrationLog -Pattern 'SPRING LIVE MCP:|GEMINI \+ SPRING MCP LIVE:|LIVE DOCUMENT \+ SPRING MCP:|Tests run: [0-9]+,|BUILD SUCCESS|BUILD FAILURE' | ForEach-Object {$_.Line}
        if($code -ne 0){throw 'Spring MCP integration failed; inspect the local test log.'}
        }
    }finally{Pop-Location}
}finally{
    if($process){if(-not $process.HasExited){$process.Kill($true);[void]$process.WaitForExit(10000)};$process.Dispose()}
    if($outTask){[void]$outTask.Wait(5000)};if($errTask){[void]$errTask.Wait(5000)};if($outFile){$outFile.Dispose()};if($errFile){$errFile.Dispose()}
    foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
    $secret=$null
}
