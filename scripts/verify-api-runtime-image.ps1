$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-package-security-common.ps1')
if (-not $IsLinux) { throw 'Real API image gate requires the Linux Docker CI runner; no image verification is claimed.' }
$testUrl = [Environment]::GetEnvironmentVariable('TEST_DATABASE_URL', 'Process')
if ($testUrl -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost)(:[0-9]+)?/educore_integration$') {
    throw 'API image gate requires the isolated loopback integration cluster.'
}
$owner = [Environment]::GetEnvironmentVariable('TEST_DATABASE_USER', 'Process')
$ownerPassword = [Environment]::GetEnvironmentVariable('TEST_DATABASE_PASSWORD', 'Process')
if ([string]::IsNullOrWhiteSpace($owner) -or [string]::IsNullOrWhiteSpace($ownerPassword)) { throw 'Synthetic test credentials are required.' }
$invocation = [guid]::NewGuid().ToString('N')
$imageTag = "educore-api-smoke:$invocation"
$containerName = "educore-api-smoke-$invocation"
$imageRuntime = "synthetic_image_$invocation"
$repository = Split-Path $PSScriptRoot -Parent
$roleFile = Join-Path $repository "apps/api/target/api-image-role-$invocation.sql"
$uri = [uri]$testUrl.Substring(5)
$dbPort = if ($uri.Port -gt 0) { $uri.Port } else { 5432 }
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
try { $listener.Start(); $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
$bindings = @{
    PGPASSWORD = $ownerPassword
    DATABASE_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    APP_ENCRYPTION_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}
$previous = @{}
$containerId = $null
$built = $false
try {
    foreach ($key in $bindings.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $bindings[$key], 'Process')
    }
    $psqlArgs = @('-X', '--no-password', '--set=ON_ERROR_STOP=1', '--host', $uri.Host,
        '--port', "$dbPort", '--username', $owner, '--dbname', 'educore_integration', '-t', '-A')
    $roles = @(& psql @psqlArgs --command "SELECT rolname FROM pg_roles WHERE rolname ~ '^synthetic_runtime_[a-f0-9]{32}$'" |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($LASTEXITCODE -ne 0 -or $roles.Count -ne 1) { throw 'Exactly one real production-runtime gate login is required.' }
    $parentRole = $roles[0].Trim()
    $database = 'educore_runtime_' + $parentRole.Substring('synthetic_runtime_'.Length)
    $databaseUrl = $testUrl -replace '/educore_integration$', "/$database"
    # New synthetic service login only: never reset an existing account's password.
    # Initial file creation is owner-only; no credentials in arguments or artifacts.
    $options = [IO.FileStreamOptions]::new()
    $options.Mode = [IO.FileMode]::CreateNew
    $options.Access = [IO.FileAccess]::Write
    $options.UnixCreateMode = [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite
    $stream = [IO.FileStream]::new($roleFile, $options)
    $writer = [IO.StreamWriter]::new($stream)
    try {
        $writer.WriteLine("CREATE ROLE $imageRuntime LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$($bindings.DATABASE_PASSWORD)';")
        $writer.WriteLine("GRANT $parentRole TO $imageRuntime;")
    } finally { $writer.Dispose() }
    & psql @psqlArgs --file $roleFile
    if ($LASTEXITCODE -ne 0) { throw 'New isolated image-test login could not be provisioned.' }
    Remove-Item -LiteralPath $roleFile

    & docker build --file (Join-Path $repository 'apps/api/Dockerfile') --tag $imageTag $repository
    if ($LASTEXITCODE -ne 0) { throw 'Real API Docker image did not build.' }
    $built = $true
    $output = & docker run --detach --rm --network host --name $containerName `
        --env 'SPRING_PROFILES_ACTIVE=prod' --env "DATABASE_URL=$databaseUrl" --env "DATABASE_USER=$imageRuntime" `
        --env DATABASE_PASSWORD --env APP_ENCRYPTION_KEY --env "PORT=$port" --env 'SERVER_ADDRESS=127.0.0.1' `
        --env 'APP_PUBLIC_URL=https://synthetic.example.test' --env 'APP_ALLOWED_ORIGINS=https://synthetic.example.test' `
        --env 'APP_PUBLIC_ORGANISATION_ID=10000000-0000-4000-8000-000000000001' `
        --env 'APP_PUBLIC_SCHOOL_ID=10000000-0000-4000-8000-000000000002' `
        --env 'APP_BOOTSTRAP_ENABLED=false' --env 'APP_GUARDIAN_INVITATION_EXPIRY_ENABLED=false' `
        --env 'APP_CALENDAR_NOTIFICATIONS_ENABLED=false' --env 'APP_LEARNING_NOTIFICATIONS_ENABLED=false' `
        --env 'APP_PARENT_REQUEST_NOTIFICATIONS_ENABLED=false' `
        --env 'APP_STUDENT_LEAVE_NOTIFICATIONS_ENABLED=false' --env 'APP_PTM_REMINDERS_ENABLED=false' `
        --env 'APP_PUSH_REGISTRATION_ENABLED=false' --env 'APP_PUSH_DELIVERY_ENABLED=false' --env 'APP_PUSH_WORKER_ENABLED=false' `
        --env 'S3_AUTO_CREATE_BUCKET=false' --env 'S3_ACCESS_KEY=synthetic-private-storage' `
        --env 'S3_SECRET_KEY=synthetic-network-not-used' --env 'CLAMAV_HOST=synthetic-private-scanner.invalid' $imageTag
    if ($LASTEXITCODE -ne 0 -or "$output" -notmatch '^[a-f0-9]{64}$') { throw 'Owned test container could not start.' }
    $containerId = "$output".Trim()
    $live = $false
    $deadline = [datetime]::UtcNow.AddSeconds(120)
    while ([datetime]::UtcNow -lt $deadline) {
        $running = & docker inspect --format '{{.State.Running}}' $containerId 2>$null
        if ($LASTEXITCODE -ne 0 -or $running -cne 'true') { throw 'Real API container exited before liveness.' }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health/liveness" -TimeoutSec 2
            if ($health.status -ceq 'UP') { $live = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $live) { throw 'Actual API image liveness was not observed within the bound.' }
    $uid = & docker exec $containerId id -u
    if ($LASTEXITCODE -ne 0 -or "$uid".Trim() -cne '10001') { throw 'Actual API process must run as the existing non-root UID 10001.' }
    $installedPackages = @(& docker exec $containerId apk info -v)
    if ($LASTEXITCODE -ne 0) { throw 'Actual API image package inventory could not be read.' }
    Assert-RuntimePackageInventory 'api' $installedPackages
    $pruningProof = @(& docker exec $containerId sh -ec (Get-ApiPrunedRuntimeProbe))
    if ($LASTEXITCODE -ne 0 -or $pruningProof.Count -ne 1 -or $pruningProof[0] -cne 'API_PRUNED_TOOLS_ABSENT_FONTS_TRUST_PRESENT') {
        throw 'Actual API must prove complete GnuPG/SQLite command/file absence and preserved fonts/trust/native Java resources.'
    }
    Write-Output 'Actual non-root API has no build-only GnuPG/SQLite packages, commands, native helpers or SQLite libraries; DejaVu/fontconfig resolves and CA/trust/crypto/Java font resources remain present.'
    Write-Output 'Actual final API image has the approved aligned OpenSSL, zlib, libpng and libexpat patches and meets the aligned musl security floor; existing Java entry point and non-owner database startup remain live.'
    Write-Output 'Real API image built and started using its unchanged default entry point; production profile, non-owner PostgreSQL, loopback HTTP liveness and non-root UID 10001 passed. This is not private-file readiness or deployment approval.'
    & (Join-Path $PSScriptRoot 'verify-runtime-dependency-exposure.ps1') -Service api -Image $imageTag
    & (Join-Path $PSScriptRoot 'scan-runtime-image.ps1') -Service api -Image $imageTag
    & (Join-Path $PSScriptRoot 'verify-runtime-dependency-exposure.ps1') -Service api -Image $imageTag -InspectionTarget minimatch
} catch {
    if ($containerId) {
        & docker logs $containerId 2>&1 | Where-Object { $_ -notmatch '(?i)password|secret|credential|ciphertext|token' } | Select-Object -Last 35
    }
    throw
} finally {
    if ($containerId -match '^[a-f0-9]{64}$') { & docker rm --force $containerId 2>$null | Out-Null }
    if ($built) { & docker image rm $imageTag 2>$null | Out-Null }
    if (Test-Path -LiteralPath $roleFile) { Remove-Item -LiteralPath $roleFile }
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
