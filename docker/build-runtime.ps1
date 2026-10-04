$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$pom = Join-Path $repositoryRoot "pom.xml"

Write-Host "Building the Qraft runtime JAR locally with Maven..." -ForegroundColor Green
& mvn -f $pom package "-DskipTests"
if ($LASTEXITCODE -ne 0) {
    throw "Local Maven build failed with exit code $LASTEXITCODE"
}

$artifact = Join-Path $repositoryRoot "target/qraft.jar"
if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
    throw "Expected host-built runtime JAR was not created: $artifact"
}

Write-Host "Host-built artifact: $artifact" -ForegroundColor Cyan
