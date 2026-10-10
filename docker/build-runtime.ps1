$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$pom = Join-Path $repositoryRoot "pom.xml"

$artifact = Join-Path $repositoryRoot "target/qraft.jar"

# A JAR newer than the POM and every production source is current. Packaging again would rewrite target/
# under a Maven build that is running in this tree.
if (Test-Path -LiteralPath $artifact -PathType Leaf) {
    $built = (Get-Item -LiteralPath $artifact).LastWriteTimeUtc
    $inputs = @(Get-Item -LiteralPath $pom) +
        @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "src/main") -Recurse -File)
    if (-not ($inputs | Where-Object { $_.LastWriteTimeUtc -gt $built } | Select-Object -First 1)) {
        Write-Host "Host-built artifact is current: $artifact" -ForegroundColor Cyan
        return
    }
}

Write-Host "Building the Qraft runtime JAR locally with Maven..." -ForegroundColor Green
& mvn -f $pom package "-DskipTests"
if ($LASTEXITCODE -ne 0) {
    throw "Local Maven build failed with exit code $LASTEXITCODE"
}

if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
    throw "Expected host-built runtime JAR was not created: $artifact"
}

Write-Host "Host-built artifact: $artifact" -ForegroundColor Cyan
