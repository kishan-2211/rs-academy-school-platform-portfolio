# These are safety/report-validator fixtures, not real Docker verification.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'postgres-volume-common.ps1')
$fixtureId = [guid]::NewGuid().ToString('N')
$fixture = [IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot -Parent) ".build-tmp/.volume-base-guard-$fixtureId"))
if (Test-Path -LiteralPath $fixture) { throw 'Refusing an existing hidden-directory fixture.' }
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
try {
    if ($IsWindows) { [IO.File]::SetAttributes($fixture, [IO.FileAttributes]::Directory -bor [IO.FileAttributes]::Hidden) }
    Assert-PostgresVolumeWorkspace $fixture
    if (-not ((Get-Item -LiteralPath $fixture -Force).Attributes -band [IO.FileAttributes]::Hidden)) { throw 'The actual hidden-directory fixture is not hidden.' }
} finally {
    $expected = [IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot -Parent) ".build-tmp/.volume-base-guard-$fixtureId"))
    if ((Resolve-Path -LiteralPath $fixture).Path -cne $expected) { throw 'Unsafe hidden-directory fixture cleanup target.' }
    Remove-Item -LiteralPath $fixture -Force # Only the newly created empty directory, not its parent.
}
Write-Output 'Actual existing hidden workspace preflight passed; temporary empty fixture removed.'
$id = '0123456789abcdef0123456789abcdef'
$project = "educore-volume-$id"
$password = 'synthetic-guard-fixture-only'
function New-Model {
    return @{
        services = @{ postgres = @{
            image = 'postgres:18.4-alpine'; restart = 'unless-stopped'
            command = $null; entrypoint = $null # Actual Compose default serialization.
            environment = @{ PGDATA = '/var/lib/postgresql/18/docker'; POSTGRES_DB = "educore_volume_$id"
                POSTGRES_USER = "educore_volume_$id"; POSTGRES_PASSWORD = $password }
            volumes = @(@{ type = 'volume'; source = 'postgres-data'; target = '/var/lib/postgresql' })
            healthcheck = @{ test = @('CMD-SHELL','pg_isready') }
            security_opt = @('no-new-privileges:true'); networks = @{ default = $null }
            ports = @(@{ host_ip = '127.0.0.1'; published = '5432'; target = 5432 })
        } }
        volumes = @{ 'postgres-data' = @{ name = "${project}_postgres-data" } }
    }
}
$rejected = 0
function Assert-Rejected([scriptblock]$Action) {
    $denied = $false
    try { & $Action } catch { $denied = $true }
    if (-not $denied) { throw 'Unsafe persistence fixture was accepted.' }
    $script:rejected++
}
$model = New-Model
$projection = Get-PostgresVolumeProjection $model $project $id $password
if ($projection.services.Count -ne 1 -or @($projection.services.postgres.ports).Count -ne 0 -or
    $projection.volumes['postgres-data'].name -cne "${project}_postgres-data") { throw 'Projection is not isolated.' }
foreach ($mutate in @(
    { param($m) $m.services.postgres.volumes[0].target = '/var/lib/postgresql/data' },
    { param($m) $m.services.postgres.volumes[0].type = 'bind' },
    { param($m) $m.services.postgres.volumes[0].source = 'school-data' },
    { param($m) $m.volumes['postgres-data'].external = $true },
    { param($m) $m.volumes['postgres-data'].name = 'educore_postgres-data' },
    { param($m) $m.services.postgres.environment.POSTGRES_DB = 'school' },
    { param($m) $m.services.postgres.environment.POSTGRES_PASSWORD = 'existing-secret' },
    { param($m) $m.services.postgres.environment.PGDATA = '/var/lib/postgresql/data' },
    { param($m) $m.services.postgres.environment.POSTGRES_HOST_AUTH_METHOD = 'trust' },
    { param($m) $m.services.postgres.image = 'postgres:latest' },
    { param($m) $m.services.postgres.entrypoint = 'different' },
    { param($m) $m.services.postgres.command = @() },
    { param($m) $m.services.postgres.entrypoint = @() },
    { param($m) $m.services.postgres.command = 'different' },
    { param($m) $m.services.postgres.privileged = $true },
    { param($m) $m.services.postgres.networks.school = $null }
)) {
    $changed = New-Model
    & $mutate $changed
    Assert-Rejected { Get-PostgresVolumeProjection $changed $project $id $password | Out-Null }
}
Assert-Rejected { Assert-PostgresVolumeInvocation 'educore' $id }
Assert-Rejected { Assert-PostgresVolumeInvocation $project '../data' }
$labels = @{ 'com.docker.compose.project' = $project; 'com.docker.compose.volume' = 'postgres-data' }
Assert-OwnedPostgresVolume "${project}_postgres-data" $labels $project $id 'postgres-data'
Assert-Rejected { Assert-OwnedPostgresVolume 'educore_postgres-data' $labels $project $id 'postgres-data' }
Assert-Rejected { Assert-OwnedPostgresVolume "${project}_postgres-data" @{} $project $id 'postgres-data' }
Assert-Rejected { Assert-OwnedPostgresVolume "${project}_anything" $labels $project $id 'anything' }
$containerLabels = @{ 'com.docker.compose.project' = $project; 'com.docker.compose.service' = 'postgres' }
Assert-OwnedPostgresContainer ('a' * 64) $containerLabels $project $id
Assert-Rejected { Assert-OwnedPostgresContainer 'school-container' $containerLabels $project $id }
Assert-Rejected { Assert-OwnedPostgresContainer ('a' * 64) @{} $project $id }
Assert-Rejected { Assert-OwnedPostgresContainer ('a' * 64) @{ 'com.docker.compose.project' = $project; 'com.docker.compose.service' = 'api' } $project $id }
Assert-Rejected { Assert-OwnedPostgresContainer ('a' * 64) @{ 'com.docker.compose.project' = 'educore'; 'com.docker.compose.service' = 'postgres' } $project $id }
$hash = 'a' * 64
$report = @{ format = 1; postgresMajor = 18; image = 'postgres:18.4-alpine'; pgdata = '/var/lib/postgresql/18/docker'
    composeSha256 = $hash; completedAt = [datetime]::UtcNow.ToString('o')
    scenarios = @('realPinnedImageRejectsOldDataMount','realNamedVolumePersistsAcrossContainerRecreation',
        'realLegacyDataRefusedAndPreserved','ownedResourcesCleanedUp') }
Assert-PostgresVolumeReport $report $hash ([datetime]::MinValue)
$roundTrip = ConvertFrom-PostgresVolumeReport ($report | ConvertTo-Json -Depth 10)
if ($roundTrip['completedAt'] -isnot [string] -or $roundTrip['completedAt'] -cne $report['completedAt']) {
    throw 'Serialized report did not preserve its original UTC string.'
}
Assert-PostgresVolumeReport $roundTrip $hash ([datetime]::MinValue)
foreach ($mutate in @(
    { param($r) $r.format = 2 },
    { param($r) $r.postgresMajor = 17 },
    { param($r) $r.composeSha256 = 'b' * 64 },
    { param($r) $r.completedAt = [datetime]::UtcNow.AddHours(1).ToString('o') },
    { param($r) [void]$r.Remove('completedAt') },
    { param($r) $r.completedAt = 'not-a-date' },
    { param($r) $r.completedAt = '2026-99-01T00:00:00.0000000Z' },
    { param($r) $r.password = 'synthetic-sensitive-field-must-be-rejected' },
    { param($r) $r.image = 'postgres:latest' },
    { param($r) $r.pgdata = '/var/lib/postgresql/data' },
    { param($r) $r.scenarios = @($r.scenarios[0]) },
    { param($r) $r.scenarios[3] = $r.scenarios[0] }
)) {
    $changed = ConvertFrom-PostgresVolumeReport ($report | ConvertTo-Json -Depth 10)
    & $mutate $changed
    Assert-Rejected { Assert-PostgresVolumeReport $changed $hash ([datetime]::MinValue) }
}
Assert-Rejected { Assert-PostgresVolumeReport $report $hash ([datetime]::UtcNow.AddMinutes(1)) }
Write-Host "PostgreSQL volume guards passed: isolated projection accepted; $rejected unsafe/stale/incomplete fixtures rejected. No real container execution claimed."
