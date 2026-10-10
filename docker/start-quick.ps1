# Qraft Docker Quick Start Script
# Provides easy commands for common Docker operations

param(
    [Parameter(Position=0)]
    [ValidateSet("cluster", "stop", "clean", "status", "help")]
    [string]$Action = "help",
    
    [Parameter(Position=1)]
    [ValidateSet("3node", "5node", "network-test")]
    [string]$ClusterType = "3node"
)

function Show-Help {
    Write-Host "Qraft Docker Quick Start" -ForegroundColor Green
    Write-Host "=========================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Usage: .\start-quick.ps1 <action> [cluster-type]" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Actions:" -ForegroundColor Yellow
    Write-Host "  cluster [3node|5node|network-test]  - Start Qraft cluster" -ForegroundColor White
    Write-Host "  stop                                - Stop all services" -ForegroundColor White
    Write-Host "  clean                               - Remove Qraft's containers and data volumes" -ForegroundColor White
    Write-Host "  status                              - Show service status" -ForegroundColor White
    Write-Host "  help                                - Show this help" -ForegroundColor White
    Write-Host ""
    Write-Host "Examples:" -ForegroundColor Yellow
    Write-Host "  .\start-quick.ps1 cluster           # Start 3-node cluster" -ForegroundColor Gray
    Write-Host "  .\start-quick.ps1 cluster 5node     # Start 5-node cluster" -ForegroundColor Gray
    Write-Host "  .\start-quick.ps1 status            # Check service status" -ForegroundColor Gray
    Write-Host ""
    Write-Host "The cluster types share their data volumes: a cluster starts with the data of the one before. clean removes it." -ForegroundColor Yellow
}

function Start-Cluster {
    param([string]$Type)

    & (Join-Path $PSScriptRoot "build-runtime.ps1")
    
    $composeFile = switch ($Type) {
        "3node" { "compose/docker-compose-cluster.yml" }
        "5node" { "compose/docker-compose-5node.yml" }
        "network-test" { "compose/docker-compose-network-test.yml" }
        default { "compose/docker-compose-cluster.yml" }
    }
    
    Write-Host "Starting $Type cluster..." -ForegroundColor Green
    docker compose -f $composeFile up -d
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Cluster started successfully!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Services available at:" -ForegroundColor Yellow
        Write-Host "  - Server 1: http://localhost:8081" -ForegroundColor White
        Write-Host "  - Server 2: http://localhost:8082" -ForegroundColor White
        Write-Host "  - Server 3: http://localhost:8083" -ForegroundColor White
        
        if ($Type -eq "5node" -or $Type -eq "network-test") {
            Write-Host "  - Server 4: http://localhost:8084" -ForegroundColor White
            Write-Host "  - Server 5: http://localhost:8085" -ForegroundColor White
        }
    } else {
        Write-Host "Failed to start cluster!" -ForegroundColor Red
    }
}

function Stop-Services {
    Write-Host "Stopping all Qraft services..." -ForegroundColor Yellow
    
    # Stop all possible compose configurations
    docker compose -f compose/docker-compose-single-server.yml down 2>$null
    docker compose -f compose/docker-compose-cluster.yml down 2>$null
    docker compose -f compose/docker-compose-5node.yml down 2>$null
    docker compose -f compose/docker-compose-network-test.yml down 2>$null
    
    Write-Host "All services stopped." -ForegroundColor Green
}

function Clean-Environment {
    Write-Host "Removing Qraft's containers, networks, and data volumes..." -ForegroundColor Yellow

    # Only what these compose files define. Other projects' volumes and networks are left alone.
    docker compose -f compose/docker-compose-single-server.yml down -v 2>$null
    docker compose -f compose/docker-compose-cluster.yml down -v 2>$null
    docker compose -f compose/docker-compose-5node.yml down -v 2>$null
    docker compose -f compose/docker-compose-network-test.yml down -v 2>$null

    Write-Host "Environment cleaned." -ForegroundColor Green
}

function Show-Status {
    Write-Host "Qraft Service Status" -ForegroundColor Green
    Write-Host "=====================" -ForegroundColor Green
    Write-Host ""
    
    # Check containers
    Write-Host "Running containers:" -ForegroundColor Yellow
    docker ps --filter "name=qraft-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
    
    Write-Host ""
    
    # Check networks
    Write-Host "Networks:" -ForegroundColor Yellow
    docker network ls --filter "name=qraft" --format "table {{.Name}}\t{{.Driver}}\t{{.Scope}}"
    
    Write-Host ""
    
    # Check volumes
    Write-Host "Volumes:" -ForegroundColor Yellow
    docker volume ls --filter "name=qraft" --format "table {{.Name}}\t{{.Driver}}"
}

# Main execution
switch ($Action) {
    "cluster" { Start-Cluster $ClusterType }
    "stop" { Stop-Services }
    "clean" { Clean-Environment }
    "status" { Show-Status }
    "help" { Show-Help }
    default { Show-Help }
}
