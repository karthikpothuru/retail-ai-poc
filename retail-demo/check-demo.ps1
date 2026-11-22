$ErrorActionPreference = "Stop"

Write-Host "`nRetail AI POC - Demo Readiness Report" -ForegroundColor Cyan
Write-Host "====================================="

& (Join-Path $PSScriptRoot "load-demo-env.ps1")

$results = @()

function Add-Check {
    param(
        [string]$Category,
        [string]$Component,
        [bool]$Passed
    )

    $script:results += [PSCustomObject]@{
        Category  = $Category
        Component = $Component
        Status    = if ($Passed) { "PASS" } else { "FAIL" }
    }
}

$requiredVariables = @(
    "GOOGLE_CLOUD_PROJECT",
    "GOOGLE_CLOUD_LOCATION",
    "GOOGLE_GENAI_USE_VERTEXAI",
    "DOCUMENT_REPOSITORY",
    "DOCUMENT_POSTGRES_READER_URL",
    "DOCUMENT_POSTGRES_READER_USER",
    "DOCUMENT_POSTGRES_READER_PASSWORD",
    "MEMORY_REPOSITORY",
    "MEMORY_POSTGRES_URL",
    "RETAIL_CHAT_ADK_URL",
    "RETAIL_CHAT_BRIDGE_TOKEN",
	"RETAIL_DB_URL",
	"RETAIL_DB_USER",
	"RETAIL_DB_PASSWORD",
	"DOCUMENT_POSTGRES_INGEST_URL",
	"DOCUMENT_POSTGRES_INGEST_USER",
	"DOCUMENT_POSTGRES_INGEST_PASSWORD",
    "SPRING_PROFILES_ACTIVE",
	"MCP_DELEGATION_KEY",
	"MEMORY_POSTGRES_URL",
	"MEMORY_POSTGRES_USER",
	"MEMORY_POSTGRES_PASSWORD",
	"RETAIL_CHAT_ENABLED"
)

foreach ($name in $requiredVariables) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    Add-Check "Environment" $name (-not [string]::IsNullOrWhiteSpace($value))
}

foreach ($tool in @("java", "mvn", "node", "npm", "gcloud")) {
    $found = Get-Command $tool -ErrorAction SilentlyContinue
    Add-Check "Tool" $tool ($null -ne $found)
}

$folders = @{
    "ADK Agent"        = "C:\agent-AI-workspace\retail-adk-agent"
    "Customer Service" = "C:\agent-AI-workspace\retail-customer-service"
    "React Portal"     = "C:\agent-AI-workspace\retail-customer-web"
    "Inventory MCP"    = "C:\agent-AI-workspace\retail-stock-investigator\retail-inventory-mcp"
    "PostgreSQL"       = "C:\Program Files\PostgreSQL\18"
}

foreach ($name in $folders.Keys) {
    Add-Check "Directory" $name (Test-Path $folders[$name])
}

$services = @(
    @{ Name = "PostgreSQL"; Port = 5432 },
    @{ Name = "Inventory MCP"; Port = 8080 },
    @{ Name = "Customer Service"; Port = 8081 },
    @{ Name = "ADK Bridge"; Port = 8091 },
    @{ Name = "React Portal"; Port = 5173 }
)

foreach ($service in $services) {
    $client = [System.Net.Sockets.TcpClient]::new()
    $connected = $false

    try {
        $result = $client.BeginConnect("127.0.0.1", $service.Port, $null, $null)
        $connected = $result.AsyncWaitHandle.WaitOne(1000, $false)

        if ($connected) {
            $client.EndConnect($result)
        }

        $result.AsyncWaitHandle.Close()
    }
    catch {
        $connected = $false
    }
    finally {
        $client.Dispose()
    }

    Add-Check "TCP Port" "$($service.Name) : $($service.Port)" $connected
}

Write-Host ""
$results | Sort-Object Category, Component | Format-Table -AutoSize

$passed = @($results | Where-Object Status -eq "PASS").Count
$failed = @($results | Where-Object Status -eq "FAIL").Count

Write-Host "Passed: $passed"
Write-Host "Failed: $failed"

if ($failed -gt 0) {
    Write-Host "System is not fully demo-ready." -ForegroundColor Yellow
} else {
    Write-Host "Basic prerequisite checks passed." -ForegroundColor Green
}

Write-Host "Note: TCP checks do not validate application health, database schema, or credentials."
