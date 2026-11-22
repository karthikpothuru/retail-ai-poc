$PROJECT_ID = (gcloud config get-value project).Trim()
$LOCATION = "global"
$Token = gcloud auth print-access-token

$AgentUrl = "https://aiplatform.googleapis.com/v1beta1/projects/$PROJECT_ID/locations/$LOCATION/agents"

$AgentHeaders = @{
    Authorization = "Bearer $Token"
    "Content-Type" = "application/json"
}

Write-Host "Project: $PROJECT_ID"
Write-Host "Endpoint: $AgentUrl"

$AgentPayload = @{
    id = "retail-stock-investigator"

    base_agent = "antigravity-preview-05-2026"

    description = "Analyzes retail inventory discrepancies and recommends monitoring checks."

    system_instruction = @"
You are a Retail Stock Investigator for retail operations teams.

Your responsibilities:
1. Analyze expected inventory, actual inventory, sales, and reorder thresholds.
2. Calculate inventory discrepancies accurately.
3. Distinguish confirmed facts from possible explanations.
4. Never assume that sales have already been deducted from expected inventory.
5. Recommend practical monitoring and reconciliation checks.
6. Do not claim to have accessed business systems unless a tool actually provided that access.
7. Do not make inventory changes, place orders, or modify external systems.
8. Present findings in this format:
   - Inventory summary
   - Discrepancy calculation
   - Possible explanations
   - Reorder assessment
   - Recommended monitoring checks

If essential information is missing, state your assumptions and explain what needs verification.
"@

    tools = @(
        @{ type = "code_execution" }
        @{ type = "filesystem" }
    )

    base_environment = @{
        type = "remote"
    }
}

$AgentJson = $AgentPayload | ConvertTo-Json -Depth 10

$AgentJson

try {
    $CreateResponse = Invoke-RestMethod `
        -Method Post `
        -Uri $AgentUrl `
        -Headers $AgentHeaders `
        -Body $AgentJson `
        -TimeoutSec 180 `
        -ErrorAction Stop

    $CreateResponse | ConvertTo-Json -Depth 20
}
catch {
    Write-Host "Agent creation request failed:"
    Write-Host $_.Exception.Message

    if ($_.ErrorDetails.Message) {
        Write-Host $_.ErrorDetails.Message
    }
}