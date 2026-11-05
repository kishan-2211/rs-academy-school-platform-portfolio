# Evidence validation only. Production dependency policy does not consume or
# authorize an exception from this report, including an ABSENT result.
function Assert-MinimatchExposureSourceIdentity($Before, $After) {
    $keys = @('commit','dockerfileSha256','lockfileSha256')
    foreach ($identity in @($Before,$After)) {
        if ($identity -isnot [System.Collections.IDictionary] -or $identity.Count -ne $keys.Count -or
            @($identity.Keys | Where-Object { $_ -cnotin $keys }).Count -gt 0 -or
            $identity.commit -cnotmatch '^[a-f0-9]{40}$' -or
            $identity.dockerfileSha256 -cnotmatch '^[a-f0-9]{64}$' -or
            $identity.lockfileSha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Incomplete committed source identity.' }
    }
    foreach ($key in $keys) {
        if ($Before[$key] -cne $After[$key]) { throw 'Source changed while final-image evidence was collected.' }
    }
}

function Assert-MinimatchExposureReport($Report, [string]$Service, [string]$Commit,
        [string]$DockerfileHash, [string]$LockfileHash, [string]$ImageId) {
    $keys = @('format','inspectionTarget','result','service','commit','imageId','dockerfileSha256',
        'lockfileSha256','source','inspectorVersion','completedAt','counts','affectedLibraryPresent','cleanupSucceeded')
    if ($Report -isnot [System.Collections.IDictionary] -or $Report.Count -ne $keys.Count -or
        @($Report.Keys | Where-Object { $_ -cnotin $keys }).Count -gt 0 -or
        $Service -cnotin @('web','api') -or $Report.service -cne $Service -or
        ($Report.format -isnot [int] -and $Report.format -isnot [long]) -or $Report.format -ne 1 -or
        $Report.inspectionTarget -cne 'minimatch' -or
        ($Report.inspectorVersion -isnot [int] -and $Report.inspectorVersion -isnot [long]) -or $Report.inspectorVersion -ne 1 -or
        $Commit -cnotmatch '^[a-f0-9]{40}$' -or $Report.commit -cne $Commit -or
        $DockerfileHash -cnotmatch '^[a-f0-9]{64}$' -or $Report.dockerfileSha256 -cne $DockerfileHash -or
        $LockfileHash -cnotmatch '^[a-f0-9]{64}$' -or $Report.lockfileSha256 -cne $LockfileHash -or
        $ImageId -cnotmatch '^sha256:[a-f0-9]{64}$' -or $Report.imageId -cne $ImageId -or
        $Report.source -cne 'final-image-docker-export' -or
        $Report.affectedLibraryPresent -isnot [bool] -or $Report.cleanupSucceeded -isnot [bool] -or -not $Report.cleanupSucceeded -or
        $Report.result -cnotin @('present','absent') -or
        ($Report.affectedLibraryPresent -and $Report.result -cne 'present') -or
        (-not $Report.affectedLibraryPresent -and $Report.result -cne 'absent') -or
        $Report.completedAt -isnot [string] -or $Report.completedAt -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{7}Z$') {
        throw 'Minimatch final-image evidence is incomplete, altered or not bound to the current source/image.'
    }
    $completed = [datetime]::Parse($Report.completedAt, [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind)
    if ($completed -lt [datetime]::UtcNow.AddHours(-6) -or $completed -gt [datetime]::UtcNow.AddMinutes(1)) {
        throw 'Stale or future minimatch image evidence.'
    }
    $countKeys = @('filesystemMembers','manifests','javascript','jars','archiveMembers','contentMembers')
    if ($Report.counts -isnot [System.Collections.IDictionary] -or $Report.counts.Count -ne $countKeys.Count -or
        @($Report.counts.Keys | Where-Object { $_ -cnotin $countKeys }).Count -gt 0) { throw 'Incomplete image inventory counts.' }
    foreach ($key in $countKeys) {
        $count = $Report.counts[$key]
        if (($count -isnot [int] -and $count -isnot [long]) -or $count -lt 0 -or $count -gt 200000) { throw 'Unbounded or invalid image inventory counts.' }
    }
    if ($Report.counts.filesystemMembers -eq 0 -or
        ($Service -ceq 'web' -and ($Report.counts.manifests -eq 0 -or $Report.counts.javascript -eq 0)) -or
        ($Service -ceq 'api' -and ($Report.counts.jars -eq 0 -or $Report.counts.archiveMembers -eq 0))) {
        throw 'Final image inventory is incomplete.'
    }
}

# A separate diagnostic sidecar preserves the existing exposure-report schema.
# It contains only bounded library locations and byte fingerprints, never source
# excerpts, environment values, archive exports or a release-policy decision.
function New-MinimatchDiagnosticReport($ExposureReport,[string]$ExposureReportSha256,[hashtable]$Diagnostics) {
    Assert-MinimatchDiagnosticState $Diagnostics
    return @{ format=1; inspectionTarget='minimatch'; service=$ExposureReport.service; commit=$ExposureReport.commit;
        imageId=$ExposureReport.imageId; dockerfileSha256=$ExposureReport.dockerfileSha256;
        lockfileSha256=$ExposureReport.lockfileSha256; exposureReportSha256=$ExposureReportSha256;
        source=$ExposureReport.source; inspectorVersion=1; completedAt=$ExposureReport.completedAt;
        affectedLibraryPresent=$ExposureReport.affectedLibraryPresent; cleanupSucceeded=$ExposureReport.cleanupSucceeded;
        totalFindings=$Diagnostics.totalFindings; truncated=$Diagnostics.truncated;
        unfingerprintedMatches=$Diagnostics.unfingerprintedMatches; findings=@($Diagnostics.findings.ToArray()) }
}
function Assert-MinimatchDiagnosticReport($Report,$ExposureReport,[string]$ExposureReportSha256) {
    Assert-MinimatchExposureReport $ExposureReport $ExposureReport.service $ExposureReport.commit `
        $ExposureReport.dockerfileSha256 $ExposureReport.lockfileSha256 $ExposureReport.imageId
    $keys=@('format','inspectionTarget','service','commit','imageId','dockerfileSha256','lockfileSha256',
        'exposureReportSha256','source','inspectorVersion','completedAt','affectedLibraryPresent','cleanupSucceeded',
        'totalFindings','truncated','unfingerprintedMatches','findings')
    if ($Report -isnot [System.Collections.IDictionary] -or $Report.Count -ne $keys.Count -or
        @($Report.Keys | Where-Object { $_ -cnotin $keys }).Count -gt 0 -or
        ($Report.format -isnot [int] -and $Report.format -isnot [long]) -or $Report.format -ne 1 -or
        ($Report.inspectorVersion -isnot [int] -and $Report.inspectorVersion -isnot [long]) -or $Report.inspectorVersion -ne 1 -or
        $Report.inspectionTarget -cne 'minimatch' -or $ExposureReport.inspectionTarget -cne 'minimatch' -or
        $ExposureReportSha256 -cnotmatch '^[a-f0-9]{64}$' -or $Report.exposureReportSha256 -cne $ExposureReportSha256 -or
        $Report.affectedLibraryPresent -isnot [bool] -or $Report.cleanupSucceeded -isnot [bool] -or -not $Report.cleanupSucceeded -or
        $Report.truncated -isnot [bool] -or $Report.findings -isnot [array] -or $Report.findings.Count -gt 32) {
        throw 'Malformed or unpaired minimatch diagnostic evidence.'
    }
    foreach ($key in @('service','commit','imageId','dockerfileSha256','lockfileSha256','source','completedAt','affectedLibraryPresent','cleanupSucceeded')) {
        if ($Report[$key] -cne $ExposureReport[$key]) { throw 'Minimatch diagnostic does not match its exposure report.' }
    }
    foreach ($key in @('totalFindings','unfingerprintedMatches')) {
        $count=$Report[$key]
        if (($count -isnot [int] -and $count -isnot [long]) -or $count -lt 0 -or $count -gt 200000) { throw 'Unbounded diagnostic finding counts.' }
    }
    if ($Report.findings.Count -ne [Math]::Min($Report.totalFindings,32) -or
        $Report.truncated -ne ($Report.totalFindings -gt 32) -or
        (-not $Report.affectedLibraryPresent -and ($Report.totalFindings -gt 0 -or $Report.unfingerprintedMatches -gt 0 -or $Report.truncated)) -or
        ($Report.affectedLibraryPresent -and $Report.totalFindings -eq 0 -and $Report.unfingerprintedMatches -eq 0)) {
        throw 'Presence, bounded locations and diagnostic completeness disagree.'
    }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($finding in $Report.findings) {
        if ($finding -isnot [System.Collections.IDictionary] -or $finding.Count -ne 3 -or
            @($finding.Keys | Where-Object { $_ -cnotin @('locationChain','matchKind','sha256') }).Count -gt 0 -or
            $finding.locationChain -isnot [array] -or @($finding.locationChain | Where-Object { $_ -isnot [string] }).Count -gt 0 -or
            $finding.sha256 -isnot [string] -or $finding.sha256 -cnotmatch '^[a-f0-9]{64}$' -or
            $finding.matchKind -cnotin @('package-path','manifest','minimatch-reference','optimizer-method')) {
            throw 'Unknown or unsanitized diagnostic finding.'
        }
        Assert-MinimatchDiagnosticLocation $finding.locationChain
        if (-not $seen.Add($finding.locationChain -join '!')) { throw 'Duplicate diagnostic library location.' }
    }
}
