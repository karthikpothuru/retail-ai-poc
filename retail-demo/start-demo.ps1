$ErrorActionPreference = "Stop"

$demoRoot = $PSScriptRoot

Write-Host "Retail AI POC - Demo Startup" -ForegroundColor Cyan

& (Join-Path $demoRoot "load-demo-env.ps1")

function Test-LocalPort {
    param([int]$Port)

    $client = [System.Net.Sockets.TcpClient]::new()

    try {
        $result = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        $connected = $result.AsyncWaitHandle.WaitOne(1000, $false)

        if ($connected) {
            $client.EndConnect($result)
        }

        $result.AsyncWaitHandle.Close()
        return $connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

if (-not (Test-LocalPort 8091)) {
    Write-Host "Starting Java ADK Bridge..."

    $agentDir = "C:\agent-AI-workspace\retail-adk-agent"

# Start ADK Bridge in a separate PowerShell window
$agentCommand = 'mvn exec:java "-Dexec.mainClass=com.retail.agent.context.RetailChatHttpServer"'

$encodedCommand = [Convert]::ToBase64String(
    [System.Text.Encoding]::Unicode.GetBytes($agentCommand)
)

Start-Process -FilePath "powershell.exe" `
    -WorkingDirectory $agentDir `
    -ArgumentList @(
        "-NoExit",
        "-EncodedCommand",
        $encodedCommand
    )

    $started = $false

    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2

        if (Test-LocalPort 8091) {
            $started = $true
            break
        }
    }

    if (-not $started) {
        throw "ADK Bridge did not start within 60 seconds."
    }
}
else {
    Write-Host "ADK Bridge is already listening."
}

# --------------------------------------------------
# Customer Service - Spring Boot (Port 8081)
# --------------------------------------------------

if (-not (Test-LocalPort 8081)) {

    Write-Host "Starting Customer Service..."

    $customerDir = "C:\agent-AI-workspace\retail-customer-service"

    if (-not (Test-Path $customerDir)) {
        throw "Customer Service directory not found: $customerDir"
    }

    Start-Process -FilePath "powershell.exe" `
        -WorkingDirectory $customerDir `
        -ArgumentList @(
            "-NoExit",
            "-Command",
            "mvn spring-boot:run"
        )

    $started = $false

    for ($i = 0; $i -lt 30; $i++) {

        Start-Sleep -Seconds 2

        if (Test-LocalPort 8081) {
            $started = $true
            break
        }
    }

    if (-not $started) {
        throw "Customer Service did not start within 60 seconds."
    }

    Write-Host "Customer Service is listening on port 8081."
}
else {
    Write-Host "Customer Service is already listening."
}

# --------------------------------------------------
# Inventory MCP - Spring Boot (Port 8080)
# --------------------------------------------------

if (-not (Test-LocalPort 8080)) {

    Write-Host "Starting Inventory MCP..."

    $inventoryDir = "C:\agent-AI-workspace\retail-stock-investigator\retail-inventory-mcp"

    if (-not (Test-Path $inventoryDir)) {
        throw "Inventory MCP directory not found: $inventoryDir"
    }

    $inventoryCommand = 'mvn spring-boot:run'

    $encodedInventoryCommand = [Convert]::ToBase64String(
        [System.Text.Encoding]::Unicode.GetBytes($inventoryCommand)
    )

    Start-Process -FilePath "powershell.exe" `
        -WorkingDirectory $inventoryDir `
        -ArgumentList @(
            "-NoExit",
            "-EncodedCommand",
            $encodedInventoryCommand
        )

    $started = $false

    for ($i = 0; $i -lt 30; $i++) {

        Start-Sleep -Seconds 2

        if (Test-LocalPort 8080) {
            $started = $true
            break
        }
    }

    if (-not $started) {
        throw "Inventory MCP did not start within 60 seconds."
    }

    Write-Host "Inventory MCP is listening on port 8080."
}
else {
    Write-Host "Inventory MCP is already listening."
}

# --------------------------------------------------
# React Portal - Vite (Port 5173)
# --------------------------------------------------

if (-not (Test-LocalPort 5173)) {

    Write-Host "Starting React Portal..."

    $reactDir = "C:\agent-AI-workspace\retail-customer-web"

    if (-not (Test-Path $reactDir)) {
        throw "React Portal directory not found: $reactDir"
    }

    $reactCommand = 'npm run dev -- --host 127.0.0.1 --port 5173 --strictPort'

    $encodedReactCommand = [Convert]::ToBase64String(
        [System.Text.Encoding]::Unicode.GetBytes($reactCommand)
    )

    Start-Process -FilePath "powershell.exe" `
        -WorkingDirectory $reactDir `
        -ArgumentList @(
            "-NoExit",
            "-EncodedCommand",
            $encodedReactCommand
        )

    $started = $false

    for ($i = 0; $i -lt 30; $i++) {

        Start-Sleep -Seconds 2

        if (Test-LocalPort 5173) {
            $started = $true
            break
        }
    }

    if (-not $started) {
        throw "React Portal did not start within 60 seconds."
    }

    Write-Host "React Portal is listening on port 5173."
}
else {
    Write-Host "React Portal is already listening."
}

Write-Host "`nRunning demo readiness checks..."

& (Join-Path $demoRoot "check-demo.ps1")
