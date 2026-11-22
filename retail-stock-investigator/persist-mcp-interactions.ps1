$PROJECT_ID = "first-agent-512806"
$AGENT_ID = "retail-stock-investigator"
$LOCATION = "global"

$Token = gcloud auth print-access-token

$Headers = @{
    Authorization = "Bearer $Token"
    "Content-Type" = "application/json"
}

$AgentUrl = "https://aiplatform.googleapis.com/v1beta1/projects/$PROJECT_ID/locations/$LOCATION/agents/$AGENT_ID"

$AgentResponse = Invoke-RestMethod `
    -Method GET `
    -Uri $AgentUrl `
    -Headers $Headers

Write-Host "`n===== EXISTING AGENT TOOLS ====="

$AgentResponse.tools | ConvertTo-Json -Depth 10


$SecureKey = Read-Host "Enter your MCP API key" -AsSecureString

$BSTR = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureKey)

try {
    $MCP_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($BSTR)
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR)
}

# Preserve all existing agent tools
$ExistingTools = @($AgentResponse.tools)

# Create the MCP server configuration
$McpTool = [PSCustomObject]@{
    type = "mcp_server"
    name = "retail-inventory-tools"
    url = "https://sizably-slot-passerby.ngrok-free.dev/mcp"

    headers = [PSCustomObject]@{
        "X-MCP-API-Key" = $MCP_API_KEY
    }
}

# Combine existing tools and the new MCP server
$UpdatedTools = @($ExistingTools) + @($McpTool)

# Prepare PATCH request
$PatchBodyObject = @{
    name = $AgentResponse.name
    tools = $UpdatedTools
}

$PatchBody = $PatchBodyObject | ConvertTo-Json -Depth 30

# Update only the tools field
$PatchUrl = "$AgentUrl" + "?update_mask=tools"

Write-Host "`nUpdating Google Managed Agent..."
try {
$PatchResponse = Invoke-RestMethod `
    -Method PATCH `
    -Uri $PatchUrl `
    -Headers $Headers `
    -Body $PatchBody

Write-Host "`n===== PATCH OPERATION RESPONSE ====="

$PatchResponse | ConvertTo-Json -Depth 10
}
catch {
    Write-Host "`n===== GOOGLE API ERROR =====" -ForegroundColor Red
    Write-Host $_.Exception.Message

    if ($_.Exception.Response -ne $null) {
        $ErrorResponse = $_.Exception.Response
        Write-Host "HTTP Status: $([int]$ErrorResponse.StatusCode)"

        $Reader = New-Object System.IO.StreamReader(
            $ErrorResponse.GetResponseStream()
        )

        $ErrorBody = $Reader.ReadToEnd()
        $Reader.Close()

        Write-Host "`n===== ERROR RESPONSE BODY ====="
        Write-Host $ErrorBody
    }
}

Start-Sleep -Seconds 5

$VerifyResponse = Invoke-RestMethod `
    -Method GET `
    -Uri $AgentUrl `
    -Headers $Headers

Write-Host "`n===== VERIFIED AGENT TOOLS ====="

$VerifyResponse.tools | ForEach-Object {
    [PSCustomObject]@{
        type = $_.type
        name = $_.name
        url  = $_.url
    }
} | Format-Table -AutoSize