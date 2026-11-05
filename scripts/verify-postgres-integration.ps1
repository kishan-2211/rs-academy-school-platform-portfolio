$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composePath = Join-Path $projectRoot "infra\compose.integration-test.yaml"
$pomPath = Join-Path $projectRoot "apps\api\pom.xml"
$reportPath = Join-Path $projectRoot "apps\api\target\failsafe-reports\TEST-com.educore.api.PostgresIntegrationTest.xml"
$testPort = if ($env:EDUCORE_TEST_POSTGRES_PORT) { $env:EDUCORE_TEST_POSTGRES_PORT } else { "55432" }

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $dockerCommand) {
    throw "Docker CLI was not found. Install Docker Desktop, enable Linux containers, start the Docker engine, and rerun this script."
}

$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
if ($null -eq $mavenCommand) {
    throw "Maven was not found. Install Maven 3.9 or newer and rerun this script."
}

& $dockerCommand.Source version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker CLI is installed, but the Docker engine is unavailable. Start Docker Desktop with Linux containers enabled."
}

& $dockerCommand.Source compose version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose v2 is unavailable. Install or enable the 'docker compose' plugin."
}

if ($testPort -notmatch '^\d{1,5}$' -or [int]$testPort -lt 1 -or [int]$testPort -gt 65535) {
    throw "EDUCORE_TEST_POSTGRES_PORT must be a TCP port from 1 to 65535."
}

$previousUrl = $env:TEST_DATABASE_URL
$previousUser = $env:TEST_DATABASE_USER
$previousPassword = $env:TEST_DATABASE_PASSWORD
$composeAttempted = $false

try {
    $composeAttempted = $true
    & $dockerCommand.Source compose -f $composePath up -d --wait postgres-integration
    if ($LASTEXITCODE -ne 0) {
        throw "The PostgreSQL integration container did not become healthy."
    }

    $env:TEST_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$testPort/educore_integration"
    $env:TEST_DATABASE_USER = "educore_integration"
    $env:TEST_DATABASE_PASSWORD = "educore-integration-only"

    & $mavenCommand.Source -B -ntp -f $pomPath verify
    if ($LASTEXITCODE -ne 0) {
        throw "Maven verification failed."
    }

    & (Join-Path $PSScriptRoot "assert-postgres-integration-report.ps1") -ReportPath $reportPath
} finally {
    $env:TEST_DATABASE_URL = $previousUrl
    $env:TEST_DATABASE_USER = $previousUser
    $env:TEST_DATABASE_PASSWORD = $previousPassword
    if ($composeAttempted) {
        & $dockerCommand.Source compose -f $composePath down --volumes --remove-orphans
    }
}
