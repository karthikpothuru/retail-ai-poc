$PROJECT_ID = "first-agent-512806"
$AGENT_ID = "retail-stock-investigator"

$Token = gcloud auth print-access-token

$InteractionUrl = "https://aiplatform.googleapis.com/v1beta1/projects/$PROJECT_ID/locations/global/interactions"

$SecureKey = Read-Host "Enter your MCP API key " -AsSecureString
$BSTR = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureKey)

try {
    $MCP_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR)
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR)
}
$BodyObject = @{
    agent = $AGENT_ID
	background = $true

    input = @"
Investigate the inventory for product WM-101.

Use the available MCP tools to:
1. Retrieve the current inventory.
2. Retrieve the sales summary.
3. Check the reorder status.

Calculate the inventory discrepancy.
Explain whether sales could account for the difference.
Tell me whether the product needs reordering.

Use actual tool results. Do not invent inventory or sales values.
"@

    tools = @(
        @{
            type = "mcp_server"
            name = "retail-inventory-tools"
            url = "https://sizably-slot-passerby.ngrok-free.dev/mcp"
            headers = @{
                "X-MCP-API-Key" = $MCP_API_KEY
            }
        }
    )
}

$Body = $BodyObject | ConvertTo-Json -Depth 20
$Headers = @{
    Authorization = "Bearer $Token"
    "Content-Type" = "application/json"
}

try {
    $Response = Invoke-RestMethod `
        -Method POST `
        -Uri $InteractionUrl `
        -Headers $Headers `
        -Body $Body

    Write-Host "`n===== GOOGLE API RESPONSE =====" -ForegroundColor Green
    $Response | ConvertTo-Json -Depth 30
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
