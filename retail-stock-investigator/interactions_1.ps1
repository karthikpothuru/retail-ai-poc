$PROJECT_ID = (gcloud config get-value project).Trim()
$LOCATION = "global"
$Token = gcloud auth print-access-token

$InteractionUrl = "https://aiplatform.googleapis.com/v1beta1/projects/$PROJECT_ID/locations/$LOCATION/interactions"

$Headers = @{
    Authorization = "Bearer $Token"
    "Content-Type" = "application/json"
    "Api-Revision" = "2026-05-20"
}

$Payload = @{
    stream = $true
    background = $true
    store = $true

    agent = "retail-stock-investigator"

    input = @(
        @{
            type = "user_input"
            content = @(
                @{
                    type = "text"
                    text = @"
Analyze the following retail inventory data.

Product: Wireless Mouse
Expected inventory: 120
Actual inventory: 105
Today's sales: 15
Reorder threshold: 30

Calculate the discrepancy and explain whether today's sales
fully explain it. Recommend three monitoring checks.

Follow your configured system instructions and response format.
Do not access external systems or modify inventory.
"@
                }
            )
        }
    )
}

$Json = $Payload | ConvertTo-Json -Depth 10

try {
    $Response = Invoke-RestMethod `
        -Method Post `
        -Uri $InteractionUrl `
        -Headers $Headers `
        -Body $Json `
        -TimeoutSec 180 `
        -ErrorAction Stop

  write-host "Interaction response :"
  $Response | ConvertTo-Json -Depth 20
}
catch {
    Write-Host "Request failed:"
    Write-Host $_.Exception.Message

    if ($_.ErrorDetails.Message) {
        Write-Host $_.ErrorDetails.Message
    }
}