#Requires -Version 7.0
param(
    [string]$PgRoot='C:\Program Files\PostgreSQL\18',
    [switch]$BuildPgvector,
    [switch]$Gemini,
    [switch]$RealEmbeddings,
    [string]$ServerProject='C:\agent-AI-workspace\retail-stock-investigator\retail-inventory-mcp',
    [string]$MsvcSetup='C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat'
)
$ErrorActionPreference='Stop'
if($RealEmbeddings -and -not $Gemini){throw 'RealEmbeddings requires explicit -Gemini opt-in.'}
$pgHome=[IO.Path]::GetFullPath($PgRoot);$pgBin=Join-Path $pgHome 'bin';$temporaryBuild=$null
if(-not(Test-Path -LiteralPath (Join-Path $pgBin 'initdb.exe'))){throw 'PostgreSQL installation not found.'}
try{
    if($BuildPgvector){
        if(-not(Test-Path -LiteralPath $MsvcSetup)){throw 'Reviewed Visual C++ tools required for the private pgvector build.'}
        $temporaryBuild=Join-Path $env:TEMP ('retail-enterprise-pg-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temporaryBuild|Out-Null
        Invoke-WebRequest 'https://github.com/pgvector/pgvector/archive/refs/tags/v0.8.7.zip' -OutFile (Join-Path $temporaryBuild 'vector.zip')
        Expand-Archive -LiteralPath (Join-Path $temporaryBuild 'vector.zip') -DestinationPath $temporaryBuild
        $source=Join-Path $temporaryBuild 'pgvector-0.8.7'
        $batch=@('@echo off',('call "'+$MsvcSetup+'"'),('set "PGROOT='+$pgHome+'"'),'nmake /NOLOGO /F Makefile.win') -join "`r`n"
        Set-Content -LiteralPath (Join-Path $source 'enterprise-build.cmd') -Value $batch
        Push-Location $source
        try{& cmd.exe /d /c enterprise-build.cmd *> (Join-Path $temporaryBuild 'compile.log');if($LASTEXITCODE -ne 0){throw 'Private pgvector build failed.'}}finally{Pop-Location}
        $distribution=Join-Path $temporaryBuild 'pgsql';New-Item -ItemType Directory -Path $distribution|Out-Null
        foreach($part in @('bin','lib','share')){Copy-Item -LiteralPath (Join-Path $pgHome $part) -Destination $distribution -Recurse}
        Copy-Item -LiteralPath (Join-Path $source 'vector.dll') -Destination (Join-Path $distribution 'lib')
        Copy-Item -LiteralPath (Join-Path $source 'vector.control') -Destination (Join-Path $distribution 'share\extension')
        Get-ChildItem -LiteralPath (Join-Path $source 'sql') -Filter 'vector--*.sql' | Copy-Item -Destination (Join-Path $distribution 'share\extension')
        $pgBin=Join-Path $distribution 'bin'
    }elseif(-not(Test-Path -LiteralPath (Join-Path $pgHome 'lib\vector.dll'))){throw 'pgvector is absent. Use -BuildPgvector for an isolated test distribution.'}
    & (Join-Path $PSScriptRoot 'Run-SpringMcpIntegrationTests.ps1') -ServerProject $ServerProject -Enterprise -PgBin $pgBin -Gemini:$Gemini -RealEmbeddings:$RealEmbeddings
}finally{
    if($temporaryBuild){
        $target=[IO.Path]::GetFullPath($temporaryBuild);$tempRoot=[IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\'
        if(-not $target.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or -not(Split-Path $target -Leaf).StartsWith('retail-enterprise-pg-')){throw 'Unexpected temporary cleanup target.'}
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}
