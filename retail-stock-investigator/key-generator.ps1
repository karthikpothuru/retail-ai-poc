$bytes = New-Object byte[] 32

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()

$env:MCP_API_KEY = [Convert]::ToBase64String($bytes)

$env:APP_MCP_API_KEY = $env:MCP_API_KEY

write-host "APP_MCP_API_KEY:" $env:APP_MCP_API_KEY