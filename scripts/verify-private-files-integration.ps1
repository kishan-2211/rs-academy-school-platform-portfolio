param(
    [ValidateRange(1024, 65535)][int]$S3Port = 19000,
    [ValidateRange(1024, 65535)][int]$ScannerPort = 13310,
    [switch]$PairedRecovery,
    [ValidateRange(1024, 65535)][int]$DatabasePort = 15432
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot 'private-files-cleanup-common.ps1')
. (Join-Path $PSScriptRoot 'clamav-freshness-common.ps1')
. (Join-Path $PSScriptRoot 'clamav-update-common.ps1')
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composePath = Join-Path $projectRoot "infra/compose.private-files-test.yaml"
$pomPath = Join-Path $projectRoot "apps/api/pom.xml"
$reportPath = Join-Path $projectRoot "apps/api/target/failsafe-reports/TEST-com.educore.api.files.PrivateFilesIT.xml"
$pairedReportPath = Join-Path $projectRoot "apps/api/target/failsafe-reports/TEST-com.educore.api.config.OfficialDocumentRecoveryIT.xml"
$freshnessReportPath = Join-Path $projectRoot 'apps/api/target/clamav-freshness-report.json'
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
if ($null -eq $dockerCommand) { throw "Docker CLI is unavailable. This real-service gate cannot run; use its GitHub CI job or install/start Docker Desktop with Linux containers." }
if ($null -eq $mavenCommand) { throw "Maven 3.9+ and Java 21+ are required." }
& $dockerCommand.Source version *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker engine is unavailable; no test services were started." }
& $dockerCommand.Source compose version *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker Compose v2 is required." }
if ($S3Port -eq $ScannerPort) { throw "The two test ports must differ." }
if ($PairedRecovery -and $DatabasePort -in @($S3Port, $ScannerPort)) { throw "The three isolated test ports must differ." }

# Unique Compose project and ephemeral credentials. Never read .env or reuse school services.
$project = "educore-files-test-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
# Compose otherwise discovers .env automatically. An empty, explicit temporary
# env file prevents loading any developer or school configuration.
$emptyEnvFile = [IO.Path]::GetTempFileName()
$bindings = @{
    PRIVATE_FILES_TEST_ROOT_USER = "root" + [guid]::NewGuid().ToString("N")
    PRIVATE_FILES_TEST_ROOT_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    PRIVATE_FILES_TEST_ACCESS_KEY = "test" + [guid]::NewGuid().ToString("N")
    PRIVATE_FILES_TEST_SECRET_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    PRIVATE_FILES_TEST_S3_PORT = "$S3Port"
    PRIVATE_FILES_TEST_CLAMAV_PORT = "$ScannerPort"
    TEST_PRIVATE_FILES_ENABLED = "true"
    TEST_S3_ENDPOINT = "http://127.0.0.1:$S3Port"
    TEST_S3_BUCKET = "educore-private-files-integration"
    TEST_CLAMAV_HOST = "127.0.0.1"
    TEST_CLAMAV_PORT = "$ScannerPort"
}
$profile = 'private-files-integration'
if ($PairedRecovery) {
    $profile = 'private-files-paired-recovery'
    $databasePassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $bindings.PRIVATE_FILES_TEST_DATABASE_PASSWORD = $databasePassword
    $bindings.PRIVATE_FILES_TEST_DATABASE_PORT = "$DatabasePort"
    $bindings.TEST_PAIRED_RECOVERY_ENABLED = 'true'
    $bindings.TEST_PAIRED_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$DatabasePort/educore_paired_integration"
    $bindings.TEST_PAIRED_DATABASE_USER = 'educore_paired_test'
    $bindings.TEST_PAIRED_DATABASE_PASSWORD = $databasePassword
    $bindings.TEST_PAIRED_POSTGRES_CONTAINER = ''
}
$composeArguments = @('compose', '--project-name', $project, '--env-file', $emptyEnvFile, '-f', $composePath)
$services = @('object-storage', 'malware-scanner')
if ($PairedRecovery) {
    $composeArguments += @('-f', (Join-Path $projectRoot 'infra/compose.paired-recovery-test.yaml'))
    $services += 'recovery-postgres'
}
$previous = @{}
$attempted = $false
$scannerBefore = $null; $scannerAfter = $null
$sourceHead = (& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceHead -cnotmatch '^[a-f0-9]{40}$') { throw 'Candidate Git identity is required before starting disposable services.' }
$freshnessSources = [ordered]@{}
foreach ($relative in @('scripts/clamav-freshness-common.ps1','scripts/test-clamav-freshness.ps1',
    'scripts/test-clamav-freshness-loopback.ps1','scripts/clamav-update-common.ps1','scripts/test-clamav-update.ps1',
    'scripts/verify-private-files-integration.ps1','infra/compose.private-files-test.yaml')) {
    $freshnessSources[$relative] = (Get-FileHash -LiteralPath (Join-Path $projectRoot $relative) -Algorithm SHA256).Hash
}
$verificationStartedAt = [datetime]::UtcNow
try {
    foreach ($name in $bindings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $bindings[$name], "Process")
    }
    $attempted = $true
    & $dockerCommand.Source @composeArguments up --build -d --wait --wait-timeout 600 @services
    if ($LASTEXITCODE -ne 0) { throw "Real storage/scanner services did not become healthy." }
    & $dockerCommand.Source @composeArguments run --build --rm object-storage-init
    if ($LASTEXITCODE -ne 0) { throw "The isolated private bucket could not be provisioned." }
    if ($PairedRecovery) {
        $container = & $dockerCommand.Source @composeArguments ps -q recovery-postgres
        if ($LASTEXITCODE -ne 0 -or @($container).Count -ne 1 -or $container -cnotmatch '^[a-f0-9]{64}$') {
            throw 'The exact invocation-owned PostgreSQL container could not be resolved.'
        }
        [Environment]::SetEnvironmentVariable('TEST_PAIRED_POSTGRES_CONTAINER', $container, 'Process')
    }
    # PING/health/EICAR alone do not prove recent LOADED definitions. Inspect only
    # this invocation's official scanner. One controlled update/notify completes
    # first; its exit alone never substitutes for the loaded-definition gate.
    Invoke-OwnedClamAvControlledUpdate $dockerCommand.Source $composeArguments $project $ScannerPort
    $scannerBefore = Assert-OwnedClamAvFreshness $dockerCommand.Source $composeArguments $project $ScannerPort
    & $mavenCommand.Source -B -ntp -f $pomPath "-P$profile" verify
    if ($LASTEXITCODE -ne 0) { throw "Real private-file verification failed." }
    & (Join-Path $PSScriptRoot "assert-private-files-integration-report.ps1") -ReportPath $reportPath -NotBefore $verificationStartedAt
    if ($PairedRecovery) {
        & (Join-Path $PSScriptRoot 'assert-paired-recovery-report.ps1') -ReportPath $pairedReportPath -NotBefore $verificationStartedAt
    }
    $scannerAfter = Assert-OwnedClamAvFreshness $dockerCommand.Source $composeArguments $project $ScannerPort
    if ($scannerAfter.containerId -cne $scannerBefore.containerId -or $scannerAfter.imageId -cne $scannerBefore.imageId -or
        $scannerAfter.dailyDatabaseVersion -lt $scannerBefore.dailyDatabaseVersion) {
        throw 'Scanner identity changed or loaded definitions regressed during the real service tests.'
    }
    if ((& git -C $projectRoot rev-parse HEAD).Trim() -cne $sourceHead -or $LASTEXITCODE -ne 0) { throw 'Candidate identity changed during private-file verification.' }
    foreach ($relative in $freshnessSources.Keys) {
        if ((Get-FileHash -LiteralPath (Join-Path $projectRoot $relative) -Algorithm SHA256).Hash -cne $freshnessSources[$relative]) {
            throw 'Scanner gate inputs changed during verification.'
        }
    }
} finally {
    try {
        if ($attempted) {
            # Only this invocation's unique disposable project; never the development stack.
            Assert-PrivateFilesTestProject $project
            & $dockerCommand.Source @composeArguments down --volumes
            Assert-PrivateFilesCleanupResult $LASTEXITCODE
        }
    } finally {
        foreach ($name in $previous.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], "Process")
        }
        Remove-Item -LiteralPath $emptyEnvFile
    }
}
# Reached only after real named Maven reports AND successful owned cleanup. An
# earlier failure cannot write a new successful aggregate freshness artifact.
[ordered]@{
    schemaVersion = 1; candidateHead = $sourceHead; sourceHashes = $freshnessSources
    startedAtUtc = $verificationStartedAt.ToString('o'); completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    beforeTests = $scannerBefore; afterTests = $scannerAfter
    privateFileScenarios = 9; pairedRecoveryScenarios = $(if ($PairedRecovery) { 3 } else { 0 })
    ownedCleanup = 'succeeded'; scope = 'Actual isolated service gate and loaded-definition freshness only; production and four-document browser acceptance remain separate'
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $freshnessReportPath -Encoding utf8
Write-Output 'Real private-file service tests, loaded daily definitions at most 48 hours old before/after tests, and owned cleanup passed. Sanitized freshness evidence retained; no production or full document-browser acceptance claimed.'
