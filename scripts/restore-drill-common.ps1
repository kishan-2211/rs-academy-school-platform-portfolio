Set-StrictMode -Version Latest

function Assert-RestoreDrillSource([string]$Database, [string]$User, [bool]$SyntheticConfirmed) {
    if (-not $SyntheticConfirmed) { throw "Explicit synthetic-only confirmation is required." }
    if ($Database -notmatch '^(educore_acceptance_[0-9]{8}|educore_integration)$') {
        throw "Only the named local synthetic acceptance/CI database is permitted."
    }
    if ($User -cne $Database) { throw "The dedicated synthetic database user must match its database." }
}

function Assert-RestoreDrillTarget([string]$Target, [string]$Source, [string]$InvocationId) {
    if ($InvocationId -notmatch '^[a-f0-9]{32}$' -or
        $Target -cne "educore_restore_drill_$InvocationId" -or $Target -ceq $Source) {
        throw "The target must be this invocation's uniquely generated disposable database."
    }
}

function Assert-NativeRestoreDirectory([string]$Directory, [string]$ProjectRoot, [string]$InvocationId) {
    if ($InvocationId -notmatch '^[a-f0-9]{32}$') { throw 'Invalid isolated cluster invocation.' }
    $expected = [IO.Path]::GetFullPath((Join-Path $ProjectRoot ".build-tmp/native-restore-$InvocationId"))
    if ([IO.Path]::GetFullPath($Directory) -cne $expected) { throw 'Refusing to operate on a non-generated cluster directory.' }
}

function Assert-RestoreDrillSchemaReference($Source, $Reference) {
    if ($Reference.format -ne 1 -or $Reference.migrations -ne 0 -or $Reference.failedMigrations -ne 0 -or
        $Reference.catalogSha256 -notmatch '^[a-f0-9]{64}$' -or
        $Source.catalogTopologySha256 -notmatch '^[a-f0-9]{64}$' -or
        $Reference.catalogTopologySha256 -cne $Source.catalogTopologySha256) {
        throw 'Independent schema reference does not preserve the source topology/security.'
    }
    foreach ($field in @('rlsTables', 'policies', 'immutableTriggers', 'locale')) {
        if ((ConvertTo-Json -InputObject $Source.$field -Depth 30 -Compress) -cne
            (ConvertTo-Json -InputObject $Reference.$field -Depth 30 -Compress)) {
            throw "Independent schema reference changed $field."
        }
    }
    $sourceNames = @($Source.tables | ForEach-Object { "$($_.schema).$($_.name)" })
    $referenceNames = @($Reference.tables | ForEach-Object { "$($_.schema).$($_.name)" })
    if (($sourceNames | ConvertTo-Json -Compress) -cne ($referenceNames | ConvertTo-Json -Compress) -or
        @($Reference.tables | Where-Object { $_.rows -ne 0 }).Count -ne 0) {
        throw 'Independent schema reference is incomplete or unexpectedly contains row data.'
    }
}

function Assert-RestoreDrillInventory($Source, $Restored, [int]$ExpectedMigrations, $SchemaReference = $null) {
    if ($null -ne $SchemaReference) { Assert-RestoreDrillSchemaReference $Source $SchemaReference }
    foreach ($inventory in @($Source, $Restored)) {
        if ($null -eq $inventory -or $inventory.format -ne 1 -or
            $inventory.migrations -ne $ExpectedMigrations -or $inventory.failedMigrations -ne 0 -or
            $inventory.tables.Count -lt 20 -or $inventory.rlsTables -lt 1 -or
            $inventory.policies -lt 1 -or $inventory.immutableTriggers -lt 1 -or
            $inventory.catalogSha256 -notmatch '^[a-f0-9]{64}$') {
            throw "Missing, incomplete or stale schema/migration/security inventory."
        }
        foreach ($table in $inventory.tables) {
            if ($table.rows -lt 0 -or $table.sha256 -notmatch '^[a-f0-9]{64}$') {
                throw "Invalid table row count or digest."
            }
        }
    }
    foreach ($field in @('catalogSha256', 'migrations', 'failedMigrations', 'rlsTables',
                         'policies', 'immutableTriggers', 'tables', 'sequences', 'locale')) {
        $expected = if ($field -eq 'catalogSha256' -and $null -ne $SchemaReference) { $SchemaReference } else { $Source }
        $before = ConvertTo-Json -InputObject $expected.$field -Depth 30 -Compress
        $after = ConvertTo-Json -InputObject $Restored.$field -Depth 30 -Compress
        if ($before -cne $after) {
            $kinds = @()
            if ($field -eq 'catalogSha256' -and $expected.PSObject.Properties['catalogComponents']) {
                foreach ($part in $expected.catalogComponents.PSObject.Properties) {
                    if ($part.Value -cne $Restored.catalogComponents.($part.Name)) { $kinds += $part.Name }
                }
            }
            throw "Restored $field differs from the consistent source snapshot. Changed schema categories: $($kinds -join ', ')."
        }
    }
}

function Start-RestoreDrillProcess([string]$Executable, [string[]]$Arguments) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) { throw "Could not start the PostgreSQL verification utility." }
    return $process
}

function Invoke-RestoreDrillProcess([string]$Executable, [string[]]$Arguments,
                                   [string]$InputText = '', [int]$TimeoutMs = 180000,
                                   [switch]$BackgroundLauncher) {
    $process = Start-RestoreDrillProcess $Executable $Arguments
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($InputText) { $process.StandardInput.WriteLine($InputText) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutMs)) {
            $process.Kill($true)
            throw "PostgreSQL verification exceeded its bounded timeout."
        }
        # A Windows background server can retain launcher pipe handles after
        # pg_ctl exits. Its exit code/--wait readiness remain authoritative;
        # do not wait indefinitely for a daemon-owned pipe to reach EOF.
        $outputReady = $stdout.Wait(5000)
        $errorReady = $stderr.Wait(5000)
        if ((-not $outputReady -or -not $errorReady) -and -not $BackgroundLauncher) {
            throw 'PostgreSQL verification output did not close inside the bounded timeout.'
        }
        return @{ exitCode = $process.ExitCode;
                  output = $(if ($outputReady) { $stdout.GetAwaiter().GetResult().Trim() } else { '' });
                  errorText = $(if ($errorReady) { $stderr.GetAwaiter().GetResult() } else { '' }) }
    } finally { $process.Dispose() }
}
