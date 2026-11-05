Set-StrictMode -Version Latest

function Assert-PostgresVolumeInvocation([string]$Project, [string]$Id) {
    if ($Id -cnotmatch '^[a-f0-9]{32}$' -or $Project -cne "educore-volume-$Id") {
        throw "Only this invocation's generated disposable Compose project is permitted."
    }
}

function Assert-PostgresVolumeWorkspace([string]$Base) {
    # Unix dot directories are hidden to Get-Item unless -Force is explicit.
    # Other gates may already have created the shared ignored temporary parent.
    if ((Test-Path -LiteralPath $Base) -and ((Get-Item -LiteralPath $Base -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Refusing a linked temporary workspace.'
    }
}

function Get-PostgresVolumeProjection($Model, [string]$Project, [string]$Id, [string]$Password) {
    Assert-PostgresVolumeInvocation $Project $Id
    $database = "educore_volume_$Id"
    $service = $Model['services']['postgres']
    $volume = $Model['volumes']['postgres-data']
    if ($null -eq $service -or $null -eq $volume -or $service['image'] -cne 'postgres:18.4-alpine' -or
        $volume['name'] -cne "${Project}_postgres-data" -or $volume['external']) {
        throw 'The actual PostgreSQL service or generated named volume is not the approved isolated model.'
    }
    $environment = $service['environment']
    if ($null -eq $environment -or $environment.Count -ne 4 -or
        $environment['PGDATA'] -cne '/var/lib/postgresql/18/docker' -or
        $environment['POSTGRES_DB'] -cne $database -or $environment['POSTGRES_USER'] -cne $database -or
        [string]::IsNullOrWhiteSpace($Password) -or $environment['POSTGRES_PASSWORD'] -cne $Password) {
        throw "The service must use versioned PGDATA and this invocation's synthetic identity only."
    }
    $mounts = @($service['volumes'])
    if ($mounts.Count -ne 1 -or $mounts[0]['type'] -cne 'volume' -or
        $mounts[0]['source'] -cne 'postgres-data' -or $mounts[0]['target'] -cne '/var/lib/postgresql' -or
        $mounts[0]['read_only'] -or $null -eq $service['healthcheck'] -or
        $service['restart'] -cne 'unless-stopped' -or
        'no-new-privileges:true' -cnotin @($service['security_opt'])) {
        throw 'The versioned data must be covered by the actual named volume and existing service controls.'
    }
    # Never project an unknown command, host bind, secret, dependency or privilege
    # from a changed stack into this synthetic gate. Other services are omitted.
    # Compose emits null command/entrypoint fields for the image defaults. Null
    # alone is safe; even an empty non-null override would change startup.
    $allowed = @('image','environment','ports','volumes','healthcheck','restart','security_opt','networks','command','entrypoint')
    if ($null -ne $service['command'] -or $null -ne $service['entrypoint'] -or
        @($service.Keys | Where-Object { $_ -cnotin $allowed }).Count -ne 0 -or
        @($service['networks'].Keys | Where-Object { $_ -cne 'default' }).Count -ne 0) {
        throw 'Unexpected service override or external network; refusing to run the projection.'
    }
    $copy = $service | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
    $copy['ports'] = @() # Docker exec uses private TCP; no existing host port is used.
    return @{
        name = $Project
        services = @{ postgres = $copy }
        volumes = @{ 'postgres-data' = @{ name = "${Project}_postgres-data" } }
        networks = @{ default = @{ name = "${Project}_default" } }
    }
}

function Assert-OwnedPostgresVolume([string]$Name, $Labels, [string]$Project, [string]$Id, [string]$Suffix) {
    Assert-PostgresVolumeInvocation $Project $Id
    if ($Suffix -cnotin @('postgres-data','old-data','legacy-data') -or
        $Name -cne "${Project}_$Suffix" -or $Labels['com.docker.compose.project'] -cne $Project -or
        $Labels['com.docker.compose.volume'] -cne 'postgres-data') {
        throw 'Refusing to change or remove a volume not owned by this generated test invocation.'
    }
}

function Assert-OwnedPostgresContainer([string]$Container, $Labels, [string]$Project, [string]$Id) {
    Assert-PostgresVolumeInvocation $Project $Id
    if ($Container -cnotmatch '^[a-f0-9]{64}$' -or
        $Labels['com.docker.compose.project'] -cne $Project -or
        $Labels['com.docker.compose.service'] -cnotin @('postgres','fixture-helper')) {
        throw 'Refusing a container outside this generated persistence test.'
    }
}

function ConvertFrom-PostgresVolumeReport([string]$Json) {
    $parameters = @{ AsHashtable = $true }
    # PowerShell 7.5+ otherwise converts ISO strings into DateTime automatically.
    # Preserve the wire format for strict UTC/freshness validation; 7.4 already does.
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $parameters['DateKind'] = 'String' }
    return $Json | ConvertFrom-Json @parameters
}

function Assert-PostgresVolumeReport($Report, [string]$ComposeSha256, [datetime]$NotBefore) {
    $required = @('realPinnedImageRejectsOldDataMount','realNamedVolumePersistsAcrossContainerRecreation',
        'realLegacyDataRefusedAndPreserved','ownedResourcesCleanedUp')
    if ($null -eq $Report -or $Report['completedAt'] -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{7}Z$' -or
        @($Report.Keys | Where-Object { $_ -cnotin @('format','postgresMajor','image','pgdata','composeSha256','completedAt','scenarios') }).Count -ne 0) {
        throw 'Persistence report must contain a UTC timestamp and sanitized summary fields only.'
    }
    $completed = [datetime]::Parse($Report['completedAt'], [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind)
    if ($Report['format'] -ne 1 -or $Report['postgresMajor'] -ne 18 -or
        $Report['image'] -cne 'postgres:18.4-alpine' -or $Report['pgdata'] -cne '/var/lib/postgresql/18/docker' -or
        $ComposeSha256 -cnotmatch '^[a-f0-9]{64}$' -or $Report['composeSha256'] -cne $ComposeSha256 -or
        $completed -lt $NotBefore.ToUniversalTime() -or $completed -gt [datetime]::UtcNow.AddMinutes(1) -or
        @($Report['scenarios']).Count -ne $required.Count -or
        @($Report['scenarios'] | Select-Object -Unique).Count -ne $required.Count -or
        @($required | Where-Object { $_ -cnotin @($Report['scenarios']) }).Count -ne 0) {
        throw 'PostgreSQL persistence evidence is missing, stale, failed or incomplete.'
    }
}
