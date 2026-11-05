# Pure report guards; no Docker, download, application data or credentials here.
function ConvertFrom-ImageSecurityJson([string]$Json) {
    $parameters = @{ AsHashtable = $true }
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $parameters.DateKind = 'String' }
    return $Json | ConvertFrom-Json @parameters
}

function Assert-ImageSecurityIdentity([string]$Service, [string]$Image, [string]$ImageId, [string]$Commit) {
    if ($Service -cnotin @('web','api') -or $Image -cnotmatch "^educore-$Service-smoke:[a-f0-9]{32}$" -or
        $ImageId -cnotmatch '^sha256:[a-f0-9]{64}$' -or $Commit -cnotmatch '^[a-f0-9]{40}$') {
        throw 'Only an existing invocation-owned application image and exact commit may be scanned.'
    }
}

function Assert-ImageSecurityText($Value, [int]$Limit = 300) {
    if ($Value -isnot [string] -or $Value.Length -eq 0 -or $Value.Length -gt $Limit -or $Value -match '[\x00-\x1f\x7f]') {
        throw 'Invalid bounded package/advisory metadata; no raw report is published.'
    }
}

function ConvertTo-ImageSecurityReport($Raw, [int]$ExitCode, [string]$Service, [string]$ImageId,
                                     [string]$Commit, [string]$DockerfileSha256) {
    $database = $Raw.descriptor.db.status
    if ($null -eq $Raw -or $Raw.descriptor.name -cne 'grype' -or $Raw.descriptor.version -cne '0.120.0' -or
        $Raw.source.type -cne 'image' -or $Raw.source.target.imageID -cne $ImageId -or
        $Raw.distro.name -cne 'alpine' -or $Raw.distro.version -cnotmatch '^3\.[0-9]+(\.[0-9]+)?$' -or
        $Raw.matches -isnot [array] -or @($Raw.ignoredMatches | Where-Object { $null -ne $_ }).Count -ne 0 -or
        $database.valid -isnot [bool] -or -not $database.valid -or
        $database.error -or $database.schemaVersion -cnotmatch '^v6\.[0-9]+\.[0-9]+$' -or
        $Raw.descriptor.configuration.search.scope -cne 'squashed') {
        throw 'Scan identity, complete unfiltered matches, supported distribution or database validation is missing.'
    }
    $built = [datetimeoffset]::Parse($database.built, [Globalization.CultureInfo]::InvariantCulture)
    if ($built -lt [datetimeoffset]::UtcNow.AddHours(-120) -or $built -gt [datetimeoffset]::UtcNow.AddMinutes(5)) {
        throw 'The vulnerability database is stale or future-dated.'
    }
    $findings = @($Raw.matches | ForEach-Object {
        $vulnerability = $_.vulnerability
        $package = $_.artifact
        foreach ($value in @($vulnerability.id, $vulnerability.namespace, $package.name, $package.version, $package.type)) {
            Assert-ImageSecurityText $value
        }
        Assert-ImageSecurityText $vulnerability.severity
        # Grype's severity parser is case-insensitive; individual feeds emit
        # title/lowercase values. Preserve the same severity, not a weaker one.
        $severity = @('Unknown','Negligible','Low','Medium','High','Critical') |
            Where-Object { $_ -ieq $vulnerability.severity } | Select-Object -First 1
        # The pinned presenter emits an empty fix state for missing metadata.
        # This means unknown, never fixed or exempt from the severity gate.
        $fixState = if ($null -eq $vulnerability.fix.state -or $vulnerability.fix.state -ceq '') { 'unknown' } else { $vulnerability.fix.state }
        if (-not $severity -or $fixState -cnotin @('fixed','not-fixed','wont-fix','unknown')) {
            throw 'Unknown scan severity/fix state requires review, not silent filtering.'
        }
        $versions = @($vulnerability.fix.versions | Where-Object { $null -ne $_ })
        foreach ($version in $versions) { Assert-ImageSecurityText $version }
        $types = @($_.matchDetails | ForEach-Object { $_.type } | Sort-Object -Unique)
        if ($types.Count -eq 0) { throw 'A finding is missing its matching evidence.' }
        foreach ($type in $types) { Assert-ImageSecurityText $type }
        @{ id = $vulnerability.id; namespace = $vulnerability.namespace; severity = $severity
           package = $package.name; version = $package.version; ecosystem = $package.type
           fixState = $fixState; fixedVersions = $versions; matchTypes = $types }
    })
    $high = @($findings | Where-Object { $_.severity -cin @('High','Critical') }).Count
    # --fail-on high returns 2 for findings, 0 for a completed below-threshold scan.
    # Operational failures (including missing DB) may never become a clean report.
    if (($ExitCode -ne 0 -and $ExitCode -ne 2) -or ($ExitCode -eq 2) -ne ($high -gt 0)) {
        throw 'Scanner exit status does not agree with the complete findings.'
    }
    return @{ format = 1; service = $Service; commit = $Commit; imageId = $ImageId
        dockerfileSha256 = $DockerfileSha256; scanner = 'grype'; scannerVersion = '0.120.0'
        scannerArchiveSha256 = 'a5a1218dce63acdac152a6b3b5bb366e7267e36f4069848cf455543b3fa5700e'
        scope = 'squashed'; completedAt = [datetime]::UtcNow.ToString('o')
        databaseBuiltAt = $built.UtcDateTime.ToString('o'); databaseSchema = $database.schemaVersion
        distro = "alpine:$($Raw.distro.version)"; scanExitCode = $ExitCode
        distributionAlerts = @($Raw.alertsByPackage | Where-Object { $null -ne $_ }).Count; findings = $findings }
}

function Assert-ImageSecurityReport($Report, [string]$Service, [string]$Commit, [string]$DockerfileSha256,
                                   [switch]$RequireClean) {
    $keys = @('format','service','commit','imageId','dockerfileSha256','scanner','scannerVersion',
        'scannerArchiveSha256','scope','completedAt','databaseBuiltAt','databaseSchema','distro',
        'scanExitCode','distributionAlerts','findings')
    if ($null -eq $Report -or @($Report.Keys).Count -ne $keys.Count -or
        @($Report.Keys | Where-Object { $_ -cnotin $keys }).Count -gt 0 -or
        $Service -cnotin @('web','api') -or $Report.service -cne $Service -or $Report.format -ne 1 -or
        $Commit -cnotmatch '^[a-f0-9]{40}$' -or $Report.commit -cne $Commit -or
        $DockerfileSha256 -cnotmatch '^[a-f0-9]{64}$' -or $Report.dockerfileSha256 -cne $DockerfileSha256 -or
        $Report.imageId -cnotmatch '^sha256:[a-f0-9]{64}$' -or $Report.scanner -cne 'grype' -or
        $Report.scannerVersion -cne '0.120.0' -or $Report.scope -cne 'squashed' -or
        $Report.scannerArchiveSha256 -cne 'a5a1218dce63acdac152a6b3b5bb366e7267e36f4069848cf455543b3fa5700e' -or
        $Report.databaseSchema -cnotmatch '^v6\.[0-9]+\.[0-9]+$' -or $Report.distro -cnotmatch '^alpine:3\.[0-9]+(\.[0-9]+)?$' -or
        $Report.findings -isnot [array] -or ($Report.distributionAlerts -isnot [int] -and $Report.distributionAlerts -isnot [long]) -or $Report.distributionAlerts -lt 0) {
        throw 'Image security report is incomplete, wrong-source or contains non-summary fields.'
    }
    foreach ($field in @('completedAt','databaseBuiltAt')) {
        if ($Report[$field] -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{7}Z$') { throw 'A UTC scan timestamp is required.' }
    }
    $completed = [datetime]::Parse($Report.completedAt, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
    $built = [datetime]::Parse($Report.databaseBuiltAt, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
    if ($completed -lt [datetime]::UtcNow.AddHours(-6) -or $completed -gt [datetime]::UtcNow.AddMinutes(1) -or
        $built -lt [datetime]::UtcNow.AddHours(-120) -or $built -gt $completed.AddMinutes(5)) { throw 'Stale or future image/advisory evidence.' }
    foreach ($finding in $Report.findings) {
        $fields = @('id','namespace','severity','package','version','ecosystem','fixState','fixedVersions','matchTypes')
        if (@($finding.Keys).Count -ne $fields.Count -or @($finding.Keys | Where-Object { $_ -cnotin $fields }).Count -gt 0 -or
            $finding.severity -cnotin @('Unknown','Negligible','Low','Medium','High','Critical') -or
            $finding.fixState -cnotin @('fixed','not-fixed','wont-fix','unknown') -or
            $finding.fixedVersions -isnot [array] -or $finding.matchTypes -isnot [array] -or $finding.matchTypes.Count -eq 0) {
            throw 'A sanitized finding is incomplete or contains extra/private fields.'
        }
        foreach ($field in @('id','namespace','package','version','ecosystem')) { Assert-ImageSecurityText $finding[$field] }
        foreach ($value in @($finding.fixedVersions) + @($finding.matchTypes)) { Assert-ImageSecurityText $value }
    }
    $high = @($Report.findings | Where-Object { $_.severity -cin @('High','Critical') }).Count
    if ($Report.scanExitCode -notin @(0,2) -or ($Report.scanExitCode -eq 2) -ne ($high -gt 0)) { throw 'Inconsistent scanner completion status.' }
    if ($RequireClean -and ($high -gt 0 -or $Report.distributionAlerts -gt 0 -or
        @($Report.findings | Where-Object { $_.severity -ceq 'Unknown' }).Count -gt 0)) {
        throw 'Runtime image security gate is blocked by HIGH/CRITICAL, unknown-severity or distribution-lifecycle findings; no suppression is applied.'
    }
}
