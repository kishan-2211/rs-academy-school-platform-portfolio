param(
    [Parameter(Mandatory)][string]$PostgresBinDirectory,
    [switch]$FocusedFixture,
    [switch]$SchemaDiagnostics
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'restore-drill-common.ps1')
if (-not $IsWindows) { throw 'This native local wrapper is for Windows; CI uses its existing disposable PostgreSQL service.' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$id = [guid]::NewGuid().ToString('N')
$clusterRoot = Join-Path $projectRoot ".build-tmp/native-restore-$id"
Assert-NativeRestoreDirectory $clusterRoot $projectRoot $id
$dataPath = Join-Path $clusterRoot 'data'
$passwordFile = Join-Path $clusterRoot 'bootstrap-password'
$tools = @{}
foreach ($tool in @('initdb', 'pg_ctl', 'psql', 'pg_dump', 'pg_restore')) {
    $path = Join-Path $PostgresBinDirectory "$tool.exe"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Native PostgreSQL utility missing: $tool" }
    $tools[$tool] = (Resolve-Path -LiteralPath $path).Path
}
$maven = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $maven) { throw 'Maven and Java 21 are required for the real migration/integration fixture.' }
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
try { $listener.Start(); $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
# Keep the JVM's temporary selector socket path short and workspace-owned. This
# process-only Windows workaround changes neither the app nor OS protections.
$socketDirectory = Join-Path $projectRoot '.build-tmp/sockets'
$bindings = @{
    TEST_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$port/educore_integration"
    TEST_DATABASE_USER = 'educore_integration'; TEST_DATABASE_PASSWORD = $password
    EDUCORE_RESTORE_DRILL_USER = 'educore_integration'; EDUCORE_RESTORE_DRILL_PASSWORD = $password
    PGPASSWORD = $password; PGCONNECT_TIMEOUT = '5'
    JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir="' + $socketDirectory.Replace('\', '/') + '"'
}
$previous = @{}
$clusterAttempted = $false
$clusterStopped = $true
$verificationStarted = [datetime]::UtcNow
try {
    foreach ($name in $bindings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $bindings[$name], 'Process')
    }
    New-Item -ItemType Directory -Path $clusterRoot | Out-Null
    New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
    # Generated credentials are never a command-line argument, Git artifact or report field.
    [IO.File]::WriteAllText($passwordFile, $password)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls $passwordFile '/inheritance:r' '/grant:r' "*$($sid):(F)" *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the temporary bootstrap credential file.' }
    $init = Invoke-RestoreDrillProcess $tools.initdb @('--pgdata', $dataPath, '--username=educore_integration',
        '--encoding=UTF8', '--locale=C', '--auth-local=scram-sha-256', '--auth-host=scram-sha-256', '--pwfile', $passwordFile)
    if ($init.exitCode -ne 0) {
        $safeError = (($init.errorText -split '\r?\n' | Where-Object { $_ -match '^initdb: error:' }) -join ' ').Replace($password, '[redacted]')
        throw "Isolated PostgreSQL initialization failed; existing clusters were not touched. $safeError"
    }
    Remove-Item -LiteralPath $passwordFile
    $clusterAttempted = $true
    $clusterStopped = $false
    $start = Invoke-RestoreDrillProcess $tools.pg_ctl @('--pgdata', $dataPath, '--log', (Join-Path $clusterRoot 'postgres.log'),
        '--options', "-h 127.0.0.1 -p $port", '--wait', '--timeout=30', 'start') -BackgroundLauncher
    if ($start.exitCode -ne 0) { throw 'Isolated PostgreSQL could not start; no existing listener was stopped.' }
    $connection = "host=127.0.0.1 hostaddr=127.0.0.1 port=$port dbname=postgres user=educore_integration"
    $create = Invoke-RestoreDrillProcess $tools.psql @('-X', '--no-password', '--set=ON_ERROR_STOP=1',
        '--dbname', $connection, '--command', 'CREATE DATABASE educore_integration WITH TEMPLATE template0;')
    if ($create.exitCode -ne 0) { throw 'Could not create the isolated synthetic migration fixture.' }
    Write-Host 'Separate authenticated loopback PostgreSQL cluster started; running the real migrations and integration fixture.'
    if ($FocusedFixture) {
        # Surefire's explicit method selector runs a real Spring/Flyway/PostgreSQL
        # test, not a mock or a fabricated migration history. The full gate is unchanged.
        & $maven.Source -B -ntp -f (Join-Path $projectRoot 'apps/api/pom.xml') '-Dtest=PostgresIntegrationTest#appliesThePlatformKernelMigration' test
        if ($LASTEXITCODE -ne 0) { throw 'The focused real migration fixture failed.' }
        $testPath = Join-Path $projectRoot 'apps/api/target/surefire-reports/TEST-com.educore.api.PostgresIntegrationTest.xml'
        if (-not (Test-Path -LiteralPath $testPath)) { throw 'The focused real migration report is missing.' }
        [xml]$evidence = Get-Content -LiteralPath $testPath -Raw
        $suite = $evidence.testsuite
        if ([int]$suite.tests -ne 1 -or [int]$suite.skipped -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.failures -ne 0 -or
            $suite.testcase.name -cne 'appliesThePlatformKernelMigration') { throw 'The focused migration scenario did not execute successfully.' }
        $fixture = Invoke-RestoreDrillProcess $tools.psql @('-X', '--quiet', '--no-password', '--set=ON_ERROR_STOP=1',
            '--dbname', "host=127.0.0.1 hostaddr=127.0.0.1 port=$port dbname=educore_integration user=educore_integration") @'
            INSERT INTO audit_events(id,occurred_at,request_id,action,outcome,event_hash)
            VALUES(gen_random_uuid(),now(),'synthetic-restore-fixture','restore.synthetic.fixture','SUCCEEDED',repeat('0',64));
'@
        if ($fixture.exitCode -ne 0) { throw 'The isolated synthetic audit fixture failed.' }
        Write-Host 'Focused real migration fixture passed (one scenario, no skips); this is not the full regression gate.'
    } else {
        & $maven.Source -B -ntp -f (Join-Path $projectRoot 'apps/api/pom.xml') verify
        if ($LASTEXITCODE -ne 0) { throw 'The actual migration/integration fixture failed; restore acceptance cannot proceed.' }
        & (Join-Path $PSScriptRoot 'assert-postgres-integration-report.ps1')
    }
    & (Join-Path $PSScriptRoot 'verify-database-restore.ps1') -SourceDatabase educore_integration -SyntheticSourceConfirmed -Port $port -PostgresBinDirectory $PostgresBinDirectory -SchemaDiagnostics:$SchemaDiagnostics
    & (Join-Path $PSScriptRoot 'assert-database-restore-report.ps1') -NotBefore $verificationStarted
} finally {
    try {
        Assert-NativeRestoreDirectory $clusterRoot $projectRoot $id
        if ($clusterAttempted) {
            $pidPath = Join-Path $dataPath 'postmaster.pid'
            if (Test-Path -LiteralPath $pidPath) {
                # The unique data-directory PID file must identify this exact cluster and port.
                $pidLines = Get-Content -LiteralPath $pidPath
                if ($pidLines.Count -lt 4 -or [IO.Path]::GetFullPath($pidLines[1]) -cne [IO.Path]::GetFullPath($dataPath) -or
                    $pidLines[3] -cne "$port") { throw 'Cluster identity mismatch; refusing to stop another server.' }
                $stop = Invoke-RestoreDrillProcess $tools.pg_ctl @('--pgdata', $dataPath, '--wait', '--timeout=30', '--mode=fast', 'stop')
                if ($stop.exitCode -ne 0) { throw 'Generated test cluster could not stop; its files were retained.' }
            }
            $clusterStopped = $true
        }
        if ($clusterStopped -and (Test-Path -LiteralPath $clusterRoot)) {
            # Verified absolute invocation directory only; never a shared data/workspace root.
            $reparse = @(Get-ChildItem -LiteralPath $clusterRoot -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })
            if ($reparse.Count -ne 0) { throw 'Unexpected link in test cluster; refusing recursive cleanup.' }
            Remove-Item -LiteralPath $clusterRoot -Recurse -Force
        }
    } finally {
        foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    }
}
Write-Host "Only this invocation's synthetic cluster was stopped/removed. Existing application, database and credentials are unchanged; fixtures can be regenerated from migrations/tests."
