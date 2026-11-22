
$PROJECT_ID = "first-agent-512806"
$AGENT_ID = "retail-stock-investigator"

$Token = gcloud auth print-access-token
$InteractionId = "ChBhN2EwNWQwN2U2NjVkZTU2EAgaAmQ3KgRtYWlu"

$GetInteractionUrl = "https://aiplatform.googleapis.com/v1beta1/projects/$PROJECT_ID/locations/global/interactions/$InteractionId"

$GetHeaders = @{
    Authorization = "Bearer $(gcloud auth print-access-token)"
}

$Result = Invoke-RestMethod `
    -Method GET `
    -Uri $GetInteractionUrl `
    -Headers $GetHeaders

$Result | ConvertTo-Json -Depth 30