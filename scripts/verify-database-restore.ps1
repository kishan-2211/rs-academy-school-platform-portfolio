param(
    [Parameter(Mandatory)][string]$SourceDatabase,
    [switch]$SyntheticSourceConfirmed,
    [ValidateRange(1024, 65535)][int]$Port = 5432,
    [string]$PostgresBinDirectory,
    [string]$DockerContainer,
    [switch]$SchemaDiagnostics
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'restore-drill-common.ps1')
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$user = $env:EDUCORE_RESTORE_DRILL_USER
$password = $env:EDUCORE_RESTORE_DRILL_PASSWORD
Assert-RestoreDrillSource $SourceDatabase $user $SyntheticSourceConfirmed.IsPresent
if ([string]::IsNullOrWhiteSpace($password)) { throw 'A process-only synthetic password is required.' }
if ($DockerContainer -and $PostgresBinDirectory) { throw 'Select native tools or the isolated CI container, not both.' }
if ($DockerContainer -and $DockerContainer -notmatch '^[a-f0-9]{64}$') { throw 'Invalid CI service-container identifier.' }
$id = [guid]::NewGuid().ToString('N')
$target = "educore_restore_drill_$id"
$referenceId = [guid]::NewGuid().ToString('N')
$referenceTarget = "educore_restore_drill_$referenceId"
$reader = "educore_restore_reader_$id"
Assert-RestoreDrillTarget $target $SourceDatabase $id
Assert-RestoreDrillTarget $referenceTarget $SourceDatabase $referenceId
$folder = Join-Path $projectRoot ".build-tmp/restore-drill-$id"
$archive = if ($DockerContainer) { "/tmp/educore-restore-drill-$id.dump" } else { Join-Path $folder 'snapshot.dump' }
$schemaArchive = if ($DockerContainer) { "/tmp/educore-restore-drill-$id.schema" } else { Join-Path $folder 'schema.dump' }
$invalidArchive = if ($DockerContainer) { "/tmp/educore-restore-drill-$id.empty" } else { Join-Path $folder 'invalid.dump' }
$partialArchive = if ($DockerContainer) { "/tmp/educore-restore-drill-$id.partial" } else { Join-Path $folder 'denied.dump' }
$tools = @{}
if ($DockerContainer) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) { throw 'Docker is unavailable; no database or evidence was changed.' }
    foreach ($name in @('psql', 'pg_dump', 'pg_restore')) { $tools[$name] = $docker.Source }
} else {
    foreach ($name in @('psql', 'pg_dump', 'pg_restore')) {
        $candidate = if ($PostgresBinDirectory) {
            Join-Path $PostgresBinDirectory ($name + $(if ($IsWindows) { '.exe' } else { '' }))
        } else { (Get-Command $name -ErrorAction SilentlyContinue)?.Source }
        if (-not $candidate -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "Required native PostgreSQL tool is unavailable: $name"
        }
        $tools[$name] = (Resolve-Path -LiteralPath $candidate).Path
    }
}
function Get-Arguments([string]$Tool, [string[]]$ToolArguments) {
    if ($DockerContainer) {
        return @('exec', '--interactive', '--env', 'PGPASSWORD', '--env', 'PGOPTIONS',
                 '--env', 'PGCONNECT_TIMEOUT', $DockerContainer, $Tool) + $ToolArguments
    }
    return $ToolArguments
}
function Get-Connection([string]$Database) {
    # Explicit host AND hostaddr prevent inherited libpq options redirecting this drill.
    return "host=127.0.0.1 hostaddr=127.0.0.1 port=$Port dbname=$Database user=$user"
}
function Invoke-Tool([string]$Tool, [string[]]$ToolArguments, [string]$InputText = '', [switch]$AllowFailure) {
    $result = Invoke-RestoreDrillProcess $tools[$Tool] (Get-Arguments $Tool $ToolArguments) $InputText
    if ($result.exitCode -ne 0 -and -not $AllowFailure) {
        # Never print PostgreSQL's potentially data-bearing error text or credential bindings.
        if ($SchemaDiagnostics -and -not $DockerContainer) {
            $errorPath = Join-Path $projectRoot ".build-tmp/restore-tool-error-$id.txt"
            [IO.File]::WriteAllText($errorPath, "$verificationStage`n$($result.errorText)")
            if ($IsWindows) {
                $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
                & icacls $errorPath '/inheritance:r' '/grant:r' "*$($sid):(F)" *> $null
                if ($LASTEXITCODE -ne 0) { Remove-Item -LiteralPath $errorPath; throw 'Could not protect local synthetic utility diagnostics.' }
            }
        }
        throw "$verificationStage : $Tool verification failed (exit $($result.exitCode)); no success evidence will be written."
    }
    return $result
}
function Invoke-Sql([string]$Database, [string]$Sql) {
    return (Invoke-Tool 'psql' @('-X', '--quiet', '--no-password', '--tuples-only', '--no-align',
        '--set=ON_ERROR_STOP=1', '--dbname', (Get-Connection $Database)) $Sql).output
}
function Save-LocalSchemaDiagnostics($Before, $After, [string]$Comparison) {
    if (-not $SchemaDiagnostics -or $DockerContainer) { return }
    $beforeItems = @($Before.catalogDiagnostics | ForEach-Object { ConvertTo-Json -InputObject $_ -Depth 30 -Compress })
    $afterItems = @($After.catalogDiagnostics | ForEach-Object { ConvertTo-Json -InputObject $_ -Depth 30 -Compress })
    $differences = @(Compare-Object $beforeItems $afterItems -CaseSensitive)
    $diagnosticPath = Join-Path $projectRoot ".build-tmp/restore-schema-$Comparison-diff-$id.json"
    $differences | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $diagnosticPath -Encoding utf8
    if ($IsWindows) {
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $diagnosticPath '/inheritance:r' '/grant:r' "*$($sid):(F)" *> $null
        if ($LASTEXITCODE -ne 0) { Remove-Item -LiteralPath $diagnosticPath; throw 'Could not protect local synthetic schema diagnostics.' }
    }
    Write-Host 'Local-only synthetic schema diagnostics retained under ignored .build-tmp; no row values or credentials included.'
}
function New-DrillDatabase([string]$Name, [string]$Invocation) {
    Assert-RestoreDrillTarget $Name $SourceDatabase $Invocation
    Invoke-Sql $SourceDatabase "CREATE DATABASE `"$Name`" WITH TEMPLATE template0 OWNER `"$user`";" | Out-Null
    $identity = [pscustomobject]@{ name = $Name; invocation = $Invocation; oid = $null }
    $createdTargets.Add($identity)
    $identity.oid = Invoke-Sql $SourceDatabase "SELECT oid FROM pg_database WHERE datname='$Name' AND datdba=(SELECT oid FROM pg_roles WHERE rolname='$user');"
    if ($identity.oid -notmatch '^[0-9]+$') { throw 'Could not identify the newly created disposable target.' }
}
$previous = @{}
$bindings = @{ PGPASSWORD = $password; PGOPTIONS = '-c timezone=UTC -c statement_timeout=120000 -c lock_timeout=5000'; PGCONNECT_TIMEOUT = '5' }
$snapshotProcess = $null
$snapshotErrorTask = $null
$createdTargets = [Collections.Generic.List[object]]::new()
$roleCreated = $false
$started = [datetime]::UtcNow
$stopwatch = [Diagnostics.Stopwatch]::StartNew()
$report = $null
$cleanupSucceeded = $true
$verificationStage = 'source preflight'
try {
    foreach ($name in $bindings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $bindings[$name], 'Process')
    }
    New-Item -ItemType Directory -Path $folder | Out-Null
    $preflight = (Invoke-Sql $SourceDatabase @'
        SELECT json_build_object('database', current_database(), 'user', current_user,
            'major', current_setting('server_version_num')::int / 10000,
            'superuser', (SELECT rolsuper FROM pg_roles WHERE rolname=current_user),
            'unsupported', (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_%'
                AND c.relkind IN ('f','m')) + (SELECT count(*) FROM pg_largeobject_metadata));
'@) | ConvertFrom-Json
    if ($preflight.database -cne $SourceDatabase -or $preflight.user -cne $user -or
        -not $preflight.superuser -or $preflight.major -ne 18 -or $preflight.unsupported -ne 0) {
        throw 'This bounded PostgreSQL 18 synthetic drill requires its existing test administrator and no foreign/materialized/large-object sources.'
    }
    foreach ($name in @('psql', 'pg_dump', 'pg_restore')) {
        $version = (Invoke-Tool $name @('--version')).output
        if ($version -notmatch '\(PostgreSQL\) 18\.') { throw 'Use matching PostgreSQL 18 utilities; no version substitution is accepted.' }
    }
    $verificationStage = 'source snapshot and backup'
    $inventorySql = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sql/restore-drill-inventory.sql') -Raw
    if ($SchemaDiagnostics) {
        if ($DockerContainer) { throw 'Schema diagnostics are local-only and never a CI artifact.' }
        $inventorySql = "SET LOCAL educore.restore_diagnostics=on; $inventorySql"
    }
    $migrations = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'apps/api/src/main/resources/db/migration') -File -Filter 'V*__*.sql').Count
    $snapshotProcess = Start-RestoreDrillProcess $tools.psql (Get-Arguments 'psql' @('-X', '--quiet', '--no-password',
        '--tuples-only', '--no-align', '--set=ON_ERROR_STOP=1', '--dbname', (Get-Connection $SourceDatabase)))
    $snapshotErrorTask = $snapshotProcess.StandardError.ReadToEndAsync()
    $snapshotProcess.StandardInput.WriteLine('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();')
    $snapshotProcess.StandardInput.Flush()
    $lineTask = $snapshotProcess.StandardOutput.ReadLineAsync()
    if (-not $lineTask.Wait(15000)) { throw 'The read-only source snapshot could not be obtained in time.' }
    $snapshot = $lineTask.GetAwaiter().GetResult()
    if ($snapshot -notmatch '^[A-Fa-f0-9]+-[A-Fa-f0-9]+-[0-9]+$') { throw 'The source did not return a valid exported transaction snapshot.' }
    $source = (Invoke-Sql $SourceDatabase "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '$snapshot'; SET row_security=off; $inventorySql COMMIT;") | ConvertFrom-Json
    Assert-RestoreDrillInventory $source $source $migrations
    Write-Host 'Consistent, read-only synthetic source inventory captured; taking a real custom-format backup.'
    Invoke-Tool 'pg_dump' @('--no-password', '--dbname', (Get-Connection $SourceDatabase), '--format=custom',
        '--snapshot', $snapshot, '--lock-wait-timeout=5s', '--file', $archive) | Out-Null
    # Independent schema-only archive from exactly the same source snapshot.
    # Never infer equivalence by stripping casts, predicates or parentheses.
    Invoke-Tool 'pg_dump' @('--no-password', '--dbname', (Get-Connection $SourceDatabase), '--format=custom',
        '--schema-only', '--snapshot', $snapshot, '--lock-wait-timeout=5s', '--file', $schemaArchive) | Out-Null
    $snapshotProcess.StandardInput.WriteLine('ROLLBACK;')
    $snapshotProcess.StandardInput.Close()
    if (-not $snapshotProcess.WaitForExit(15000) -or $snapshotProcess.ExitCode -ne 0) { throw 'Source snapshot release failed.' }
    $snapshotProcess.Dispose(); $snapshotProcess = $null
    if ($DockerContainer) {
        $hashResult = Invoke-RestoreDrillProcess $docker.Source @('exec', $DockerContainer, 'sha256sum', $archive)
        if ($hashResult.exitCode -ne 0) { throw 'Backup checksum could not be obtained.' }
        $archiveSha256 = ($hashResult.output -split '\s+')[0]
    } else { $archiveSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() }
    if ($archiveSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Missing archive integrity digest.' }
    # No --create, --clean, --no-owner, --no-acl or trigger/row-security bypass flags.
    $verificationStage = 'independent schema reference'
    New-DrillDatabase $referenceTarget $referenceId
    Invoke-Tool 'pg_restore' @('--no-password', '--dbname', (Get-Connection $referenceTarget), '--single-transaction',
        '--exit-on-error', $schemaArchive) | Out-Null
    $reference = (Invoke-Sql $referenceTarget "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET row_security=off; $inventorySql COMMIT;") | ConvertFrom-Json
    try { Assert-RestoreDrillSchemaReference $source $reference } catch {
        Save-LocalSchemaDiagnostics $source $reference 'reference'
        throw
    }
    $verificationStage = 'full database restore'
    New-DrillDatabase $target $id
    Invoke-Tool 'pg_restore' @('--no-password', '--dbname', (Get-Connection $target), '--single-transaction',
        '--exit-on-error', $archive) | Out-Null
    $restored = (Invoke-Sql $target "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET row_security=off; $inventorySql COMMIT;") | ConvertFrom-Json
    try { Assert-RestoreDrillInventory $source $restored $migrations $reference } catch {
        if ($SchemaDiagnostics -and $reference.catalogSha256 -cne $restored.catalogSha256) {
            Save-LocalSchemaDiagnostics $reference $restored 'restored'
        }
        throw
    }
    Write-Host 'Restored rows/digests, schema/security, all migrations and sequence positions match the snapshot.'
    # Real negative controls are rolled back inside PostgreSQL. In particular a
    # changed WHEN must fail the FULL reference/restored definition comparison,
    # not a presence-only topology check or an inferred SQL normalization.
    $verificationStage = 'changed-schema rejection'
    $schemaControls = @(
        @{ name='changedSchemaRejected'; categories=@('constraint'); ddl='ALTER TABLE public.organisations DROP CONSTRAINT organisations_status_valid; ALTER TABLE public.organisations ADD CONSTRAINT organisations_status_valid CHECK (true);' },
        @{ name='weakenedTriggerWhenRejected'; categories=@('trigger'); ddl="DROP TRIGGER fee_payments_manual_collection_fence ON public.fee_payments; CREATE TRIGGER fee_payments_manual_collection_fence BEFORE INSERT ON public.fee_payments FOR EACH ROW WHEN (NEW.gateway_capture_id IS NULL AND NEW.payment_method='CASH') EXECUTE FUNCTION public.fence_manual_collection_receipt();" },
        @{ name='removedTriggerWhenRejected'; categories=@('trigger'); ddl='DROP TRIGGER fee_payments_manual_collection_fence ON public.fee_payments; CREATE TRIGGER fee_payments_manual_collection_fence BEFORE INSERT ON public.fee_payments FOR EACH ROW EXECUTE FUNCTION public.fence_manual_collection_receipt();' },
        @{ name='changedTriggerFunctionRejected'; categories=@('trigger'); ddl="DROP TRIGGER fee_payments_manual_collection_fence ON public.fee_payments; CREATE TRIGGER fee_payments_manual_collection_fence BEFORE INSERT ON public.fee_payments FOR EACH ROW WHEN (NEW.gateway_capture_id IS NULL AND NEW.payment_method IN ('CASH','BANK_TRANSFER','SCHOOL_UPI')) EXECUTE FUNCTION public.prevent_finance_evidence_mutation();" },
        @{ name='disabledTriggerRejected'; categories=@('trigger'); ddl='ALTER TABLE public.fee_payments DISABLE TRIGGER fee_payments_manual_collection_fence;' }
    )
    $rejectedSchemaControls = @()
    foreach ($control in $schemaControls) {
        $changed = (Invoke-Sql $target "BEGIN; $($control.ddl) SET row_security=off; $inventorySql ROLLBACK;") | ConvertFrom-Json
        $schemaDefectRejected = $false
        try { Assert-RestoreDrillInventory $source $changed $migrations $reference } catch {
            if ($_.Exception.Message -notlike 'Restored catalogSha256 differs*') { throw }
            $changedCategories = @($reference.catalogComponents.PSObject.Properties | Where-Object {
                $_.Value -cne $changed.catalogComponents.($_.Name)
            } | ForEach-Object { $_.Name })
            if ($changedCategories.Count -ne $control.categories.Count -or
                @($control.categories | Where-Object { $_ -cnotin $changedCategories }).Count -ne 0) {
                throw 'The real schema negative control changed unexpected catalog categories.'
            }
            $schemaDefectRejected = $true
        }
        if (-not $schemaDefectRejected) { throw "The actual $($control.name) schema defect was not detected." }
        $rejectedSchemaControls += $control.name
        Write-Host "Actual $($control.name) rejected by full reference/restored catalogSha256 equality (categories: $($control.categories -join ', ')); transaction rolled back."
    }
    $verificationStage = 'restored audit immutability'
    $auditProbe = Invoke-Sql $target @'
        BEGIN;
        DO $$
        DECLARE denied boolean := false;
        BEGIN
            IF NOT EXISTS (SELECT 1 FROM audit_events) THEN
                RAISE EXCEPTION 'Synthetic audit evidence is missing; immutability was not exercised';
            END IF;
            BEGIN
                UPDATE audit_events SET action=action WHERE id=(SELECT id FROM audit_events LIMIT 1);
            EXCEPTION WHEN SQLSTATE 'P0001' THEN
                IF SQLERRM <> 'audit_events is append-only' THEN RAISE; END IF;
                denied := true;
            END;
            IF NOT denied THEN RAISE EXCEPTION 'Restored audit mutation was incorrectly accepted'; END IF;
        END $$;
        ROLLBACK;
        SELECT 'audit-mutation-denied';
'@
    if ($auditProbe -cne 'audit-mutation-denied') { throw 'Missing actual restored-audit denial evidence.' }
    Write-Host 'Restored append-only audit guard denied an actual update.'
    $verificationStage = 'corrupt-archive rejection'
    if ($DockerContainer) {
        $touch = Invoke-RestoreDrillProcess $docker.Source @('exec', $DockerContainer, 'touch', '--', $invalidArchive)
        if ($touch.exitCode -ne 0) { throw 'Could not prepare the isolated invalid-archive fixture.' }
    } else { [IO.File]::WriteAllBytes($invalidArchive, [byte[]]::new(0)) }
    $invalid = Invoke-Tool 'pg_restore' @('--no-password', '--dbname', (Get-Connection $target),
        '--single-transaction', '--exit-on-error', $invalidArchive) -AllowFailure
    if ($invalid.exitCode -eq 0) { throw 'A corrupt/empty backup was incorrectly accepted.' }
    $verificationStage = 'restored tenant isolation'
    # This temporary NOLOGIN/NOBYPASSRLS role has grants ONLY in the new target.
    # It receives no membership, password, source grants or access to real school data.
    Invoke-Sql $target "CREATE ROLE `"$reader`" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;" | Out-Null
    $roleCreated = $true
    Invoke-Sql $target "GRANT USAGE ON SCHEMA public TO `"$reader`"; GRANT SELECT ON ALL TABLES IN SCHEMA public TO `"$reader`"; GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO `"$reader`";" | Out-Null
    $tenantProbeSql = @'
        BEGIN;
        DO $setup$
        DECLARE first_org uuid := gen_random_uuid(); second_org uuid := gen_random_uuid();
            first_school uuid := gen_random_uuid(); second_school uuid := gen_random_uuid();
        BEGIN
            INSERT INTO organisations(id,slug,legal_name,display_name,created_at,updated_at)
            VALUES(first_org,'restore-probe-'||first_org,'Synthetic restore organisation A','Synthetic restore A',now(),now()),
                  (second_org,'restore-probe-'||second_org,'Synthetic restore organisation B','Synthetic restore B',now(),now());
            INSERT INTO schools(id,organisation_id,slug,legal_name,display_name,created_at,updated_at)
            VALUES(first_school,first_org,'restore-probe','Synthetic restore school A','Synthetic restore A',now(),now()),
                  (second_school,second_org,'restore-probe','Synthetic restore school B','Synthetic restore B',now(),now());
            INSERT INTO campuses(id,organisation_id,school_id,code,display_name,created_at,updated_at)
            VALUES(gen_random_uuid(),first_org,first_school,'RESTORE','Synthetic restore campus A',now(),now()),
                  (gen_random_uuid(),second_org,second_school,'RESTORE','Synthetic restore campus B',now(),now());
            PERFORM set_config('app.current_organisation_id',first_org::text,true);
            PERFORM set_config('educore.restore_second_org',second_org::text,true);
        END $setup$;
        -- Switch role before entering the assertion block; PL/pgSQL statements
        -- must be planned in the actual reader context, not the setup's owner.
        SET LOCAL row_security=on;
        SET LOCAL ROLE "__READER__";
        DO $probe$
        DECLARE visible integer;
        BEGIN
            IF current_user <> '__READER__' OR
                (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user) THEN
                RAISE EXCEPTION 'Probe did not acquire the unprivileged reader context';
            END IF;
            -- School public identity intentionally has an ACTIVE public-read
            -- policy. Campuses are private operational records with tenant-only RLS.
            SELECT count(*) INTO visible FROM campuses;
            IF visible <> 1 OR EXISTS(SELECT 1 FROM campuses WHERE organisation_id=current_setting('educore.restore_second_org')::uuid) THEN
                RAISE EXCEPTION 'Restored cross-tenant row security did not preserve isolation (visible=%)', visible;
            END IF;
            PERFORM set_config('app.current_organisation_id','',true);
            SELECT count(*) INTO visible FROM campuses;
            IF visible <> 0 THEN RAISE EXCEPTION 'Missing organisation scope did not deny access'; END IF;
        END $probe$;
        RESET ROLE;
        ROLLBACK;
        SELECT 'restored-tenant-isolation-verified';
'@
    $tenantProbe = Invoke-Sql $target ($tenantProbeSql.Replace('__READER__', $reader))
    if ($tenantProbe -cne 'restored-tenant-isolation-verified') { throw 'Missing actual restored tenant-isolation evidence.' }
    $verificationStage = 'underprivileged partial-backup rejection'
    $denied = Invoke-Tool 'pg_dump' @('--no-password', '--dbname', (Get-Connection $target), '--format=custom',
        '--role', $reader, '--lock-wait-timeout=5s', '--file', $partialArchive) -AllowFailure
    if ($denied.exitCode -eq 0 -or $denied.errorText -notmatch 'row-level security') {
        throw 'The real underprivileged backup did not demonstrate fail-closed row-security denial.'
    }
    $report = [ordered]@{
        format = 1; invocationId = $id; startedAt = $started.ToString('O'); finishedAt = [datetime]::UtcNow.ToString('O')
        scope = 'synthetic-database-only'; postgresMajor = 18; migrations = $migrations
        tables = $source.tables.Count; rows = ($source.tables | Measure-Object -Property rows -Sum).Sum
        sequences = $source.sequences.Count; rlsTables = $source.rlsTables; policies = $source.policies
        immutableTriggers = $source.immutableTriggers; archiveSha256 = $archiveSha256
        schemaEvidence = [ordered]@{
            sourceCatalogSha256=$source.catalogSha256; referenceCatalogSha256=$reference.catalogSha256; restoredCatalogSha256=$restored.catalogSha256
            sourceTopologySha256=$source.catalogTopologySha256; referenceTopologySha256=$reference.catalogTopologySha256; restoredTopologySha256=$restored.catalogTopologySha256
            sourceTriggerSha256=$source.catalogComponents.trigger; referenceTriggerSha256=$reference.catalogComponents.trigger; restoredTriggerSha256=$restored.catalogComponents.trigger
        }
        elapsedSeconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
        scenarios = @('consistentDataRestore', 'schemaSecurityRestore', 'migrationHistoryRestore',
                      'sequencePositionRestore', 'auditMutationDenied', 'corruptArchiveRejected',
                      'restoredTenantIsolation', 'partialRlsBackupDenied') + $rejectedSchemaControls
        productionRpoRtoVerified = $false; objectStorageRestoreVerified = $false
    }
} finally {
    if ($snapshotProcess) {
        try { $snapshotProcess.StandardInput.Close(); if (-not $snapshotProcess.WaitForExit(5000)) { $snapshotProcess.Kill($true) } }
        finally { $snapshotProcess.Dispose() }
    }
    foreach ($identity in $createdTargets) {
        try {
            Assert-RestoreDrillTarget $identity.name $SourceDatabase $identity.invocation
            $currentOid = Invoke-Sql $SourceDatabase "SELECT oid FROM pg_database WHERE datname='$($identity.name)' AND datdba=(SELECT oid FROM pg_roles WHERE rolname='$user');"
            if ($null -eq $identity.oid -or $currentOid -cne $identity.oid) { throw 'Disposable target identity changed; refusing cleanup.' }
            Invoke-Sql $SourceDatabase "DROP DATABASE `"$($identity.name)`";" | Out-Null
        } catch { $cleanupSucceeded = $false; Write-Warning 'Disposable database cleanup failed; no passing report will be written.' }
    }
    try {
        if ($roleCreated) { Invoke-Sql $SourceDatabase "DROP ROLE `"$reader`";" | Out-Null }
    } catch { $cleanupSucceeded = $false; Write-Warning 'Disposable database/role cleanup failed; no passing report will be written.' }
    try {
        if ($DockerContainer) {
            $removed = Invoke-RestoreDrillProcess $docker.Source @('exec', $DockerContainer, 'rm', '-f', '--', $archive, $schemaArchive, $invalidArchive, $partialArchive)
            if ($removed.exitCode -ne 0) { throw 'Disposable archive cleanup failed.' }
        } elseif (Test-Path -LiteralPath $folder) {
            # Exact generated files; no recursive deletion, shared-folder removal or glob.
            foreach ($path in @($archive, $schemaArchive, $invalidArchive, $partialArchive)) {
                $resolved = [IO.Path]::GetFullPath($path)
                if ([IO.Path]::GetDirectoryName($resolved) -cne [IO.Path]::GetFullPath($folder)) { throw 'Unsafe archive cleanup path.' }
                if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved }
            }
            Remove-Item -LiteralPath $folder
        }
    } catch { $cleanupSucceeded = $false; Write-Warning 'Disposable archive cleanup failed; no passing report will be written.' }
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
if (-not $cleanupSucceeded -or $null -eq $report) { throw 'Restore verification or cleanup did not complete.' }
$report.cleanupSucceeded = $true
$reportDirectory = Join-Path $projectRoot 'apps/api/target'
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportDirectory 'restore-drill-report.json') -Encoding utf8
Write-Host "Real synthetic restore passed: $($report.migrations) migrations, $($report.tables) tables, $($report.scenarios.Count) scenarios; only generated databases/role/archives removed. Existing databases preserved."
