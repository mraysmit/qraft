# Qraft Docker starter: the clusters to start by hand
param(
    [string]$Service = "help"
)

switch ($Service) {
    "cluster" {
        & (Join-Path $PSScriptRoot "build-runtime.ps1")
        Write-Host "Starting Qraft single-server development environment..." -ForegroundColor Green
        docker compose -f compose/docker-compose-single-server.yml up -d
        Write-Host "Server with embedded HTTP API available at http://localhost:8080" -ForegroundColor Cyan
    }
    "multinode" {
        & (Join-Path $PSScriptRoot "build-runtime.ps1")
        Write-Host "Starting Qraft multi-node cluster..." -ForegroundColor Green
        docker compose -f compose/docker-compose-cluster.yml up -d
        Write-Host "Multi-node cluster available at:" -ForegroundColor Cyan
        Write-Host "  - API Node 1: http://localhost:8081" -ForegroundColor White
        Write-Host "  - API Node 2: http://localhost:8082" -ForegroundColor White
        Write-Host "  - API Node 3: http://localhost:8083" -ForegroundColor White
    }
    "stop" {
        Write-Host "Stopping services..." -ForegroundColor Yellow
        docker compose -f compose/docker-compose-single-server.yml down 2>$null
        docker compose -f compose/docker-compose-cluster.yml down 2>$null
        docker compose -f compose/docker-compose-5node.yml down 2>$null
        docker compose -f compose/docker-compose-network-test.yml down 2>$null
        Write-Host "Services stopped." -ForegroundColor Green
    }
    "status" {
        Write-Host "Qraft Services:" -ForegroundColor Green
        docker ps --filter "name=qraft-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
    }
    default {
        Write-Host "Qraft Docker Starter" -ForegroundColor Green
        Write-Host "Usage: .\start.ps1 <service>" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Clusters:" -ForegroundColor Cyan
        Write-Host "  cluster      - Start one server with a client, for development"
        Write-Host "  multinode    - Start three servers"
        Write-Host ""
        Write-Host "Management:" -ForegroundColor Cyan
        Write-Host "  stop         - Stop all services"
        Write-Host "  status       - Show service status"
        Write-Host ""
        Write-Host "Five servers and the network-partition cluster: .\start-quick.ps1 cluster 5node"
        Write-Host "The observability stack: .\start-observability.ps1"
        Write-Host ""
        Write-Host "Examples:" -ForegroundColor Green
        Write-Host "  .\start.ps1 cluster      # Development"
        Write-Host "  .\start.ps1 multinode    # Three servers"
    }
}
