$response = Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/clients'
foreach ($client in $response) {
    Write-Host "Client: $($client.clientId)"
    Write-Host "  Status: $($client.status)"
    Write-Host "  Healthy: $($client.healthy)"
    Write-Host "  Available: $($client.available)"
    Write-Host "  Last Heartbeat: $($client.lastHeartbeat)"
    Write-Host ""
}
