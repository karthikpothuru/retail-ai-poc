
# ============================================
# STEP 1: Google Cloud authentication
# ============================================

$PROJECT_ID = (gcloud config get-value project).Trim()
$LOCATION = "global"
$Token = (gcloud auth print-access-token).Trim()

$Headers = @{
    Authorization = "Bearer $Token"
    "Content-Type" = "application/json"
}

# ============================================
# STEP 2: Retrieve existing Google agent
# ============================================

$AgentUrl = "https://aiplatform.googleapis.com/v1beta1/projects/$PROJECT_ID/locations/$LOCATION/agents/retail-stock-investigator"

$AgentResponse = Invoke-RestMethod `
    -Method GET `
    -Uri $AgentUrl `
    -Headers $Headers

Write-Host "`n===== AGENT CONFIGURATION ====="

$AgentResponse | ConvertTo-Json -Depth 30

# ============================================
# STEP 3: Extract base_environment
# IMPORTANT: Use AgentResponse, not agentConfig
# ============================================

$baseEnvironment = $AgentResponse.base_environment

if ($null -eq $baseEnvironment) {
    throw "base_environment not found in Google agent response."
}

Write-Host "`n===== EXISTING BASE ENVIRONMENT ====="

$baseEnvironment | ConvertTo-Json -Depth 30

# ============================================
# STEP 4: Add network allowlist
# ============================================

$networkConfig = [PSCustomObject]@{
    allowlist = @(
        [PSCustomObject]@{
            domain = "*"
        }
    )
}

$baseEnvironment | Add-Member `
    -MemberType NoteProperty `
    -Name network `
    -Value $networkConfig `
    -Force

# ============================================
# STEP 5: Verify prepared configuration
# ============================================

Write-Host "`n===== UPDATED BASE ENVIRONMENT ====="

$baseEnvironment | ConvertTo-Json -Depth 30

Write-Host "`n===== NETWORK CONFIGURATION ====="

$baseEnvironment.network | ConvertTo-Json -Depth 30


# ============================================
# STEP 6: Prepare PATCH request body
# ============================================

$PatchBodyObject = @{
    name = $AgentResponse.name
    base_environment = $baseEnvironment
}

$PatchBody = $PatchBodyObject | ConvertTo-Json -Depth 30

Write-Host "`n===== PATCH REQUEST BODY ====="
Write-Host $PatchBody

# ============================================
# STEP 7: Update Google Managed Agent
# ============================================

# Use the documented update_mask parameter
$PatchUrl = "$AgentUrl" + "?update_mask=base_environment"

Write-Host "`nUpdating Google Managed Agent..."

try {
    $PatchResponse = Invoke-RestMethod `
        -Method PATCH `
        -Uri $PatchUrl `
        -Headers $Headers `
        -Body $PatchBody `
        -ErrorAction Stop

    Write-Host "`n===== PATCH RESPONSE ====="
    $PatchResponse | ConvertTo-Json -Depth 30
}
catch {
    Write-Host "`n===== PATCH ERROR DETAILS ====="

    Write-Host $_.Exception.Message

    if ($_.Exception.Response -ne $null) {
        $reader = New-Object System.IO.StreamReader(
            $_.Exception.Response.GetResponseStream()
        )

        $errorBody = $reader.ReadToEnd()
        $reader.Close()

        Write-Host "`nGoogle API error response:"
        Write-Host $errorBody
    }

    throw
}

# ============================================
# STEP 8: Verify configuration
# ============================================

Write-Host "`n===== VERIFYING AGENT CONFIGURATION ====="

$VerifyResponse = Invoke-RestMethod `
    -Method GET `
    -Uri $AgentUrl `
    -Headers $Headers

$VerifyResponse.base_environment | ConvertTo-Json -Depth 30
