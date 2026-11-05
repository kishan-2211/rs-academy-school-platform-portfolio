$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'restore-drill-common.ps1')
. (Join-Path $PSScriptRoot 'postgres-volume-common.ps1')
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) { throw 'Docker CLI is unavailable. Real PostgreSQL volume verification cannot run; use the existing draft-PR CI gate.' }
$engine = Invoke-RestoreDrillProcess $docker.Source @('info','--format','{{.OSType}}') -TimeoutMs 30000
if ($engine.exitCode -ne 0 -or $engine.output -cne 'linux') { throw 'An available Linux Docker engine is required; no services started.' }
$compose = Invoke-RestoreDrillProcess $docker.Source @('compose','version','--short') -TimeoutMs 30000
if ($compose.exitCode -ne 0) { throw 'Docker Compose v2 is required.' }
$repository = Split-Path $PSScriptRoot -Parent
$source = Join-Path $repository 'infra/compose.yaml'
$sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$id = [guid]::NewGuid().ToString('N')
$project = "educore-volume-$id"
Assert-PostgresVolumeInvocation $project $id
$root = [IO.Path]::GetFullPath((Join-Path $repository ".build-tmp/postgres-volume-$id"))
$base = Split-Path $root -Parent
Assert-PostgresVolumeWorkspace $base
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$bindings = @{
    POSTGRES_DB = "educore_volume_$id"; POSTGRES_USER = "educore_volume_$id"; POSTGRES_PASSWORD = $password
    PGPASSWORD = $password; REDIS_PASSWORD = $password; MINIO_ROOT_USER = "synthetic-$id"; MINIO_ROOT_PASSWORD = $password
    DATABASE_USER = "synthetic-runtime-$id"; DATABASE_PASSWORD = $password; APP_ENCRYPTION_KEY = $password
    APP_PUBLIC_ORGANISATION_ID = '10000000-0000-4000-8000-000000000001'
    APP_PUBLIC_SCHOOL_ID = '10000000-0000-4000-8000-000000000002'
    S3_ACCESS_KEY = "synthetic-storage-$id"; S3_SECRET_KEY = $password
}
$previous = @{}
$ownedContainers = [Collections.Generic.HashSet[string]]::new()
$createdVolumes = [Collections.Generic.HashSet[string]]::new()
$emptyEnv = Join-Path $root 'empty.env'
$currentFile = Join-Path $root 'current.json'
$image = 'postgres:18.4-alpine'
$started = [datetime]::UtcNow
$scenarios = [Collections.Generic.List[string]]::new()
$cleanupPassed = $false
$resourcesPermitted = $false

function Invoke-Docker([string[]]$Arguments, [string]$InputText = '', [int]$TimeoutMs = 120000) {
    return Invoke-RestoreDrillProcess $docker.Source $Arguments $InputText -TimeoutMs $TimeoutMs
}
function Invoke-Compose([string]$File, [string[]]$Arguments) {
    return Invoke-Docker (@('compose','--project-name',$project,'--env-file',$emptyEnv,'-f',$File) + $Arguments)
}
function Require-Success($Result, [string]$Message) {
    # Never print raw Compose config, generated credentials or utility diagnostics.
    if ($Result.exitCode -ne 0) { throw $Message }
    return $Result.output
}
function Register-Volume([string]$Suffix) {
    $name = "${project}_$Suffix"
    $result = Invoke-Docker @('volume','inspect','--format','{{json .Labels}}',$name)
    $labels = (Require-Success $result 'Generated volume ownership could not be inspected.') | ConvertFrom-Json -AsHashtable
    Assert-OwnedPostgresVolume $name $labels $project $id $Suffix
    [void]$createdVolumes.Add($name)
}
function Register-Container([string]$Container) {
    $labels = (Require-Success (Invoke-Docker @('inspect','--format','{{json .Config.Labels}}',$Container)) 'Container labels unavailable.') | ConvertFrom-Json -AsHashtable
    Assert-OwnedPostgresContainer $Container $labels $project $id
    [void]$ownedContainers.Add($Container)
}
function Get-OwnedContainer([string]$File) {
    $container = Require-Success (Invoke-Compose $File @('ps','--all','--quiet','postgres')) 'Could not find the synthetic PostgreSQL container.'
    if ($container -cnotmatch '^[a-f0-9]{64}$') { throw 'Exactly one generated PostgreSQL container is required.' }
    Register-Container $container
    return $container
}
function Remove-OwnedContainer([string]$Container) {
    if (-not $ownedContainers.Contains($Container)) { throw 'Unknown container cleanup target.' }
    Register-Container $Container # Recheck labels immediately before deletion.
    [void](Require-Success (Invoke-Docker @('rm','--force','--volumes',$Container)) 'Generated container cleanup failed.')
    [void]$ownedContainers.Remove($Container)
}
function Assert-RejectedContainer([string]$File, [string]$Suffix, [string]$ExpectedError) {
    [void](Require-Success (Invoke-Compose $File @('up','--detach','--no-deps','postgres')) 'Negative fixture could not be created.')
    $container = Get-OwnedContainer $File
    Register-Volume $Suffix
    $deadline = [datetime]::UtcNow.AddSeconds(30)
    do {
        $state = Require-Success (Invoke-Docker @('inspect','--format','{{.State.Status}}|{{.State.ExitCode}}',$container)) 'Negative fixture state unavailable.'
        if ($state -match '^exited\|[1-9][0-9]*$') { break }
        Start-Sleep -Milliseconds 500
    } while ([datetime]::UtcNow -lt $deadline)
    # Docker sends container stderr separately; the bounded helper retains both.
    $logResult = Invoke-Docker @('logs',$container)
    $diagnostics = (Require-Success $logResult 'Negative fixture diagnostics unavailable.') + $logResult.errorText
    if ($state -notmatch '^exited\|[1-9][0-9]*$' -or $diagnostics -notmatch $ExpectedError) {
        throw 'The real pinned PostgreSQL entry point did not reject the intended unsafe layout.'
    }
    Remove-OwnedContainer $container
}
function Query-Synthetic([string]$Container, [string]$Sql) {
    return Require-Success (Invoke-Docker @('exec','--interactive','--env','PGPASSWORD',$Container,
        'psql','-X','--no-password','--set=ON_ERROR_STOP=1','--host=127.0.0.1',
        "--username=educore_volume_$id","--dbname=educore_volume_$id",'-t','-A') $Sql) 'Synthetic persistence SQL failed.'
}
try {
    foreach ($key in $bindings.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $bindings[$key], 'Process')
    }
    if ($IsWindows) {
        [void][IO.Directory]::CreateDirectory($root)
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $root '/inheritance:r' '/grant:r' "*$($sid):(OI)(CI)(F)" *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Generated test configuration directory could not be protected.' }
    } else {
        [void][IO.Directory]::CreateDirectory($root, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute)
    }
    [IO.File]::WriteAllText($emptyEnv, '')
    $compiled = Require-Success (Invoke-Compose $source @('config','--format','json')) 'The actual stack could not be compiled with isolated synthetic values.'
    $model = $compiled | ConvertFrom-Json -AsHashtable
    $projection = Get-PostgresVolumeProjection $model $project $id $password
    foreach ($suffix in @('postgres-data','old-data','legacy-data')) {
        if ((Invoke-Docker @('volume','inspect',"${project}_$suffix")).exitCode -eq 0) {
            throw 'Generated volume unexpectedly already exists; nothing will be reused.'
        }
    }
    $existingContainers = Require-Success (Invoke-Docker @('ps','--all','--quiet','--no-trunc','--filter',"label=com.docker.compose.project=$project")) 'Generated project preflight failed.'
    if ($existingContainers -or (Invoke-Docker @('network','inspect',"${project}_default")).exitCode -eq 0) {
        throw 'Generated project unexpectedly already exists; nothing will be reused.'
    }
    $resourcesPermitted = $true
    [IO.File]::WriteAllText($currentFile, ($projection | ConvertTo-Json -Depth 30))
    $old = $projection | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
    $old.services.postgres.restart = 'no'
    $old.services.postgres.volumes[0].target = '/var/lib/postgresql/data'
    $old.volumes['postgres-data'].name = "${project}_old-data"
    $oldFile = Join-Path $root 'old.json'
    [IO.File]::WriteAllText($oldFile, ($old | ConvertTo-Json -Depth 30))
    Assert-RejectedContainer $oldFile 'old-data' 'unused mount/volume'
    $scenarios.Add('realPinnedImageRejectsOldDataMount')

    [void](Require-Success (Invoke-Compose $currentFile @('up','--detach','--no-deps','--wait','--wait-timeout','60','postgres')) 'Actual projected PostgreSQL service did not become healthy.')
    $firstContainer = Get-OwnedContainer $currentFile
    Register-Volume 'postgres-data'
    $mounts = @((Require-Success (Invoke-Docker @('inspect','--format','{{json .Mounts}}',$firstContainer)) 'Actual mount inspection failed.') | ConvertFrom-Json -AsHashtable)
    if ($mounts.Count -ne 1 -or $mounts[0].Type -cne 'volume' -or
        $mounts[0].Name -cne "${project}_postgres-data" -or $mounts[0].Destination -cne '/var/lib/postgresql') {
        throw 'Actual data mount differs from the named persistence boundary.'
    }
    $marker = [guid]::NewGuid().ToString()
    [void](Query-Synthetic $firstContainer "CREATE TABLE synthetic_persistence(id uuid PRIMARY KEY,payload text NOT NULL); INSERT INTO synthetic_persistence VALUES('$marker','Synthetic persistence marker only');")
    $inventorySql = @'
SELECT current_setting('server_version_num')::integer/10000 || '|' || current_setting('data_directory') || '|' ||
       (SELECT system_identifier::text FROM pg_control_system()) || '|' ||
       (SELECT count(*)::text || '|' || encode(sha256(convert_to(string_agg(id::text || payload,'' ORDER BY id),'UTF8')),'hex') FROM synthetic_persistence);
'@
    $before = Query-Synthetic $firstContainer $inventorySql
    if ($before -cnotmatch '^18\|/var/lib/postgresql/18/docker\|[0-9]+\|1\|[a-f0-9]{64}$') { throw 'Initial real persisted synthetic inventory is invalid.' }
    Remove-OwnedContainer $firstContainer
    [void](Require-Success (Invoke-Compose $currentFile @('up','--detach','--no-deps','--wait','--wait-timeout','60','postgres')) 'Recreated PostgreSQL service did not become healthy.')
    $secondContainer = Get-OwnedContainer $currentFile
    if ($secondContainer -ceq $firstContainer -or (Query-Synthetic $secondContainer $inventorySql) -cne $before) {
        throw 'The recreated container lost or changed its real named-volume cluster/data.'
    }
    Remove-OwnedContainer $secondContainer
    $scenarios.Add('realNamedVolumePersistsAcrossContainerRecreation')

    $legacyName = "${project}_legacy-data"
    [void](Require-Success (Invoke-Docker @('volume','create','--label',"com.docker.compose.project=$project",'--label','com.docker.compose.volume=postgres-data',$legacyName)) 'New legacy fixture volume could not be created.')
    Register-Volume 'legacy-data'
    $legacyMarker = Join-Path $root 'legacy-marker'
    [IO.File]::WriteAllText($legacyMarker, "17`n")
    # Only this generated marker and volume. No legacy school cluster is mounted.
    [void](Require-Success (Invoke-Docker @('run','--rm','--name',"${project}_fixture-write",
        '--label',"com.docker.compose.project=$project",'--label','com.docker.compose.service=fixture-helper',
        '--network','none','--mount',"type=bind,source=$legacyMarker,target=/synthetic-marker,readonly",
        '--mount',"type=volume,source=$legacyName,target=/var/lib/postgresql",'--entrypoint','cp',$image,
        '/synthetic-marker','/var/lib/postgresql/PG_VERSION')) 'Synthetic legacy marker could not be copied.')
    $legacy = $projection | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
    $legacy.services.postgres.restart = 'no'
    $legacy.volumes['postgres-data'].name = $legacyName
    $legacyFile = Join-Path $root 'legacy.json'
    [IO.File]::WriteAllText($legacyFile, ($legacy | ConvertTo-Json -Depth 30))
    Assert-RejectedContainer $legacyFile 'legacy-data' 'appears to be PostgreSQL data in:'
    $preserved = Require-Success (Invoke-Docker @('run','--rm','--name',"${project}_fixture-read",
        '--label',"com.docker.compose.project=$project",'--label','com.docker.compose.service=fixture-helper',
        '--network','none','--mount',"type=volume,source=$legacyName,target=/var/lib/postgresql,readonly",
        '--entrypoint','cat',$image,'/var/lib/postgresql/PG_VERSION')) 'Legacy fixture preservation could not be read.'
    if ($preserved -cne '17') { throw 'Refused legacy-layout marker was changed.' }
    $scenarios.Add('realLegacyDataRefusedAndPreserved')
} finally {
    try {
        try {
            if ($resourcesPermitted) {
                # Discover partial starts and timed-out fixture helpers too. Only
                # this previously nonexistent project and inspected labels qualify.
                $remaining = Require-Success (Invoke-Docker @('ps','--all','--quiet','--no-trunc','--filter',"label=com.docker.compose.project=$project")) 'Disposable container discovery failed.'
                foreach ($container in @($remaining -split '\r?\n' | Where-Object { $_ })) { Register-Container $container }
                foreach ($container in @($ownedContainers)) { Remove-OwnedContainer $container }
                foreach ($suffix in @('postgres-data','old-data','legacy-data')) {
                    $name = "${project}_$suffix"
                    if ((Invoke-Docker @('volume','inspect',$name)).exitCode -eq 0) { Register-Volume $suffix }
                }
                foreach ($name in @($createdVolumes)) {
                    $suffix = $name.Substring($project.Length + 1)
                    Register-Volume $suffix
                    [void](Require-Success (Invoke-Docker @('volume','rm',$name)) 'Owned disposable volume cleanup failed.')
                }
                $network = "${project}_default"
                $networkResult = Invoke-Docker @('network','inspect','--format','{{json .Labels}}',$network)
                if ($networkResult.exitCode -eq 0) {
                    $labels = $networkResult.output | ConvertFrom-Json -AsHashtable
                    if ($labels['com.docker.compose.project'] -cne $project -or $labels['com.docker.compose.network'] -cne 'default') { throw 'Refusing an unowned network cleanup.' }
                    [void](Require-Success (Invoke-Docker @('network','rm',$network)) 'Owned test-network cleanup failed.')
                }
            }
            $cleanupPassed = $true
        } finally {
            if (Test-Path -LiteralPath $root) {
                $expectedRoot = [IO.Path]::GetFullPath((Join-Path $repository ".build-tmp/postgres-volume-$id"))
                if ($root -cne $expectedRoot -or @((Get-Item -LiteralPath $root) | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -or
                    @(Get-ChildItem -LiteralPath $root -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
                    throw 'Refusing unsafe generated-directory cleanup.'
                }
                Remove-Item -LiteralPath $root -Recurse -Force
            }
        }
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}
if (-not $cleanupPassed -or $scenarios.Count -ne 3) { throw 'Real persistence/cleanup scenarios are incomplete.' }
if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $sourceHash) {
    throw 'Compose source changed during real verification; evidence is not accepted.'
}
$scenarios.Add('ownedResourcesCleanedUp')
$report = @{ format = 1; postgresMajor = 18; image = $image; pgdata = '/var/lib/postgresql/18/docker'
    composeSha256 = $sourceHash
    completedAt = [datetime]::UtcNow.ToString('o'); scenarios = $scenarios.ToArray() }
Assert-PostgresVolumeReport $report $report.composeSha256 $started
$reportPath = Join-Path $repository 'apps/api/target/postgres-volume-report.json'
[void][IO.Directory]::CreateDirectory((Split-Path $reportPath -Parent))
[IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 10))
Write-Host 'Real pinned PostgreSQL 18 named-volume recreation, old-mount rejection, preserved legacy-layout refusal and owned cleanup passed. No existing database/volume, data migration, production restore or deployment claim.'
