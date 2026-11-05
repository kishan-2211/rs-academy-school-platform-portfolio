param([string]$ArtifactPath = 'apps/api/target/educore-api-0.1.0-SNAPSHOT.jar')
$ErrorActionPreference = 'Stop'
# Only the new CI/isolated synthetic database; this is not a production deploy command.
$testUrl = [Environment]::GetEnvironmentVariable('TEST_DATABASE_URL', 'Process')
if ($testUrl -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost)(:[0-9]+)?/educore_integration$') {
    throw 'Packaged migration verification requires the isolated loopback educore_integration database.'
}
$testUser = [Environment]::GetEnvironmentVariable('TEST_DATABASE_USER', 'Process')
$testPassword = [Environment]::GetEnvironmentVariable('TEST_DATABASE_PASSWORD', 'Process')
if ([string]::IsNullOrWhiteSpace($testUser) -or [string]::IsNullOrWhiteSpace($testPassword)) {
    throw 'Isolated test database credentials are required.'
}
if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) { throw 'Built API artifact is missing.' }
$testUri = [uri]$testUrl.Substring(5)
$testPort = if ($testUri.Port -gt 0) { $testUri.Port } else { 5432 }
$previous = @{}
$bindings = @{
    PGPASSWORD = $testPassword
    MIGRATION_DATABASE_URL = $testUrl
    MIGRATION_DATABASE_USER = $testUser
    MIGRATION_DATABASE_PASSWORD = $testPassword
    DATABASE_USER = ''
}
try {
    foreach ($key in $bindings.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $bindings[$key], 'Process')
    }
    $psqlArgs = @('-X', '--no-password', '--set=ON_ERROR_STOP=1', '--host', $testUri.Host,
        '--port', "$testPort", '--username', $testUser, '--dbname', 'educore_integration', '-t', '-A')
    $roles = @(& psql @psqlArgs --command "SELECT rolname FROM pg_roles WHERE rolname ~ '^synthetic_runtime_[a-f0-9]{32}$'" |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($LASTEXITCODE -ne 0 -or $roles.Count -ne 1) { throw 'Exactly one real integration-gate runtime login is required.' }
    [Environment]::SetEnvironmentVariable('DATABASE_USER', $roles[0].Trim(), 'Process')
    $runtimeRole = $roles[0].Trim()
    $runtimeDatabase = 'educore_runtime_' + $runtimeRole.Substring('synthetic_runtime_'.Length)
    $runtimeUrl = $testUrl -replace '/educore_integration$', "/$runtimeDatabase"
    [Environment]::SetEnvironmentVariable('MIGRATION_DATABASE_URL', $runtimeUrl, 'Process')
    $psqlArgs = @('-X', '--no-password', '--set=ON_ERROR_STOP=1', '--host', $testUri.Host,
        '--port', "$testPort", '--username', $testUser, '--dbname', $runtimeDatabase, '-t', '-A')
    $before = & psql @psqlArgs --command 'SELECT count(*) FROM flyway_schema_history WHERE success'
    if ($LASTEXITCODE -ne 0) { throw 'Could not read existing real migration evidence.' }
    & java '-Dloader.main=com.educore.api.config.DatabaseMigrationTool' -cp $ArtifactPath org.springframework.boot.loader.launch.PropertiesLauncher
    if ($LASTEXITCODE -ne 0) { throw 'Actual packaged migration entry point failed.' }
    $after = & psql @psqlArgs --command 'SELECT count(*) FROM flyway_schema_history WHERE success'
    if ($LASTEXITCODE -ne 0 -or [int]$before -ne [int]$after -or [int]$after -lt 62) {
        throw 'Packaged migration retry did not preserve the real committed migration history.'
    }
    Write-Output 'Actual built API migration entry point and runtime grants passed; no duplicate migrations or API server started.'
} finally {
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
