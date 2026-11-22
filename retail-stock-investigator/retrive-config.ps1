$PROJECT_ID = "first-agent-512806"
$AGENT_ID = "retail-stock-investigator"

$AgentUrl = "https://aiplatform.googleapis.com/v1beta1/projects/$PROJECT_ID/locations/global/agents/$AGENT_ID"

$AccessToken = gcloud auth print-access-token

$Headers = @{
    Authorization = "Bearer $AccessToken"
}

$AgentResponse = Invoke-RestMethod `
    -Method GET `
    -Uri $AgentUrl `
    -Headers $Headers

$AgentResponse | ConvertTo-Json -Depth 30
$SecureKey = Read-Host "Enter your MCP API key" -AsSecureString

$BSTR = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureKey)

try {
    $MCP_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($BSTR)
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR)
}

Write-Host "MCP API key loaded:" ([bool]$MCP_API_KEY)

$RegistryResource = "projects/824007662818/locations/global/mcpServers/agentregistry-00000000-0000-0000-e908-eba77b84e8fe"

$McpTool = @{
    type = "mcp_server"
    name = $RegistryResource
    url = "https://sizably-slot-passerby.ngrok-free.dev/mcp"
    headers = @{
        "X-MCP-API-Key" = $MCP_API_KEY
    }
}

$UpdatedTools = @(
    @{ type = "code_execution" },
    @{ type = "filesystem" },
    $McpTool
)

$PatchBodyObject = @{
    name = $AgentResponse.name
    tools = $UpdatedTools
}

$PatchBody = $PatchBodyObject | ConvertTo-Json -Depth 30

$PatchUrl = "$AgentUrl" + "?update_mask=tools"
try {
    $PatchResponse = Invoke-RestMethod `
        -Method PATCH `
        -Uri $PatchUrl `
        -Headers $Headers `
        -ContentType "application/json" `
        -Body $PatchBody

    Write-Host "PATCH successful" -ForegroundColor Green
    $PatchResponse | ConvertTo-Json -Depth 30
}
catch {
    Write-Host "`nHTTP error:" $_.Exception.Message -ForegroundColor Red

    $Response = $_.Exception.Response

    if ($Response -ne $null) {
        Write-Host "HTTP Status:" ([int]$Response.StatusCode)

        $Reader = New-Object System.IO.StreamReader(
            $Response.GetResponseStream()
        )

        $ErrorBody = $Reader.ReadToEnd()
        $Reader.Close()

        Write-Host "`nGoogle API error details:"
        Write-Host $ErrorBody
    }
}