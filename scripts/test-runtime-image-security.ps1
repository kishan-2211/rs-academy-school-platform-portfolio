# Focused guard/sanitization fixtures only. Not real image or vulnerability proof.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-image-security-common.ps1')
$commit = 'a' * 40
$hash = 'b' * 64
$imageId = 'sha256:' + ('c' * 64)
$image = 'educore-web-smoke:' + ('d' * 32)
$rejected = 0
function Assert-Rejected([scriptblock]$Action) {
    $denied = $false
    try { & $Action | Out-Null } catch { $denied = $true }
    if (-not $denied) { throw 'Unsafe/incomplete image security fixture was accepted.' }
    $script:rejected++
}
function New-Raw {
    return @{ descriptor = @{ name = 'grype'; version = '0.120.0'
        db = @{ status = @{ schemaVersion = 'v6.1.10'; built = [datetime]::UtcNow.AddHours(-2).ToString('o'); valid = $true } }
        configuration = @{ search = @{ scope = 'squashed' }; privateToken = 'synthetic-must-not-be-published' } }
        source = @{ type = 'image'; target = @{ imageID = $imageId; privateConfig = 'synthetic-must-not-be-published' } }
        distro = @{ name = 'alpine'; version = '3.24.2' }; matches = @() }
}
function New-Finding([string]$Severity, [string]$FixState = 'not-fixed') {
    return @{ vulnerability = @{ id = 'CVE-2099-0001'; namespace = 'synthetic:fixture'; severity = $Severity
        fix = @{ state = $FixState; versions = @() }; description = 'Synthetic not-for-publication raw metadata' }
        artifact = @{ name = 'synthetic-package'; version = '0.0.1'; type = 'apk'; locations = @('synthetic-private-path') }
        matchDetails = @(@{ type = 'exact-direct-match' }) }
}
Assert-ImageSecurityIdentity 'web' $image $imageId $commit
Assert-Rejected { Assert-ImageSecurityIdentity 'transport' $image $imageId $commit }
Assert-Rejected { Assert-ImageSecurityIdentity 'web' 'school-production:latest' $imageId $commit }
Assert-Rejected { Assert-ImageSecurityIdentity 'api' $image $imageId $commit }
Assert-Rejected { Assert-ImageSecurityIdentity 'web' $image 'not-an-image-id' $commit }
Assert-Rejected { Assert-ImageSecurityIdentity 'web' $image $imageId 'main' }
$raw = New-Raw
$summary = ConvertTo-ImageSecurityReport $raw 0 'web' $imageId $commit $hash
Assert-ImageSecurityReport $summary 'web' $commit $hash -RequireClean
$json = $summary | ConvertTo-Json -Depth 12
if ($json -match 'privateConfig|privateToken|synthetic-must-not-be-published|locations|description') { throw 'Raw metadata leaked into artifact.' }
Assert-ImageSecurityReport (ConvertFrom-ImageSecurityJson $json) 'web' $commit $hash -RequireClean
foreach ($severity in @('Negligible','Low','Medium')) {
    $raw = New-Raw; $raw.matches = @(New-Finding $severity)
    $report = ConvertTo-ImageSecurityReport $raw 0 'web' $imageId $commit $hash
    Assert-ImageSecurityReport $report 'web' $commit $hash -RequireClean
}
foreach ($severity in @('High','Critical')) {
    foreach ($fix in @('fixed','not-fixed','wont-fix','unknown')) {
        $raw = New-Raw; $raw.matches = @(New-Finding $severity $fix)
        $report = ConvertTo-ImageSecurityReport $raw 2 'web' $imageId $commit $hash
        Assert-ImageSecurityReport $report 'web' $commit $hash
        Assert-Rejected { Assert-ImageSecurityReport $report 'web' $commit $hash -RequireClean }
        $encoded = $report | ConvertTo-Json -Depth 12
        if ($encoded -match 'locations|description|privateConfig|privateToken') { throw 'A finding leaked raw metadata.' }
    }
}
$raw = New-Raw; $raw.matches = @(New-Finding 'Unknown')
$report = ConvertTo-ImageSecurityReport $raw 0 'web' $imageId $commit $hash
Assert-Rejected { Assert-ImageSecurityReport $report 'web' $commit $hash -RequireClean }
foreach ($severity in @('high','critical','unknown','hIgH')) {
    $raw = New-Raw; $raw.matches = @(New-Finding $severity '')
    $exit = if ($severity -ieq 'unknown') { 0 } else { 2 }
    $report = ConvertTo-ImageSecurityReport $raw $exit 'web' $imageId $commit $hash
    if ($report.findings[0].fixState -cne 'unknown') { throw 'Missing fix metadata became a fixed finding.' }
    Assert-Rejected { Assert-ImageSecurityReport $report 'web' $commit $hash -RequireClean }
}
foreach ($mutate in @(
    { param($r) $r.descriptor.version = 'latest' },
    { param($r) $r.source.type = 'directory' },
    { param($r) $r.source.target.imageID = 'sha256:' + ('e' * 64) },
    { param($r) $r.distro.name = 'unknown' },
    { param($r) $r.distro.version = '' },
    { param($r) $r.matches = $null },
    { param($r) $r.ignoredMatches = @(New-Finding 'High') },
    { param($r) $r.descriptor.db.status.valid = $false },
    { param($r) $r.descriptor.db.status.valid = 'true' },
    { param($r) $r.descriptor.db.status.error = 'synthetic-invalid-db' },
    { param($r) $r.descriptor.db.status.schemaVersion = 'v5.0.0' },
    { param($r) $r.descriptor.db.status.built = [datetime]::UtcNow.AddHours(-121).ToString('o') },
    { param($r) $r.descriptor.db.status.built = [datetime]::UtcNow.AddHours(1).ToString('o') },
    { param($r) $r.descriptor.configuration.search.scope = 'all-layers' },
    { param($r) $r.matches = @(New-Finding 'future-severity') },
    { param($r) $r.matches = @(New-Finding 'High'); $r.matches[0].artifact.name = "synthetic`ninvalid" },
    { param($r) $r.matches = @(New-Finding 'High'); $r.matches[0].matchDetails = @() }
)) {
    $raw = New-Raw; & $mutate $raw
    Assert-Rejected { ConvertTo-ImageSecurityReport $raw 0 'web' $imageId $commit $hash }
}
$raw = New-Raw
Assert-Rejected { ConvertTo-ImageSecurityReport $raw 1 'web' $imageId $commit $hash }
Assert-Rejected { ConvertTo-ImageSecurityReport $raw 2 'web' $imageId $commit $hash }
$raw.matches = @(New-Finding 'High')
Assert-Rejected { ConvertTo-ImageSecurityReport $raw 0 'web' $imageId $commit $hash }
foreach ($mutate in @(
    { param($r) $r.commit = 'e' * 40 },
    { param($r) $r.service = 'api' },
    { param($r) $r.dockerfileSha256 = 'e' * 64 },
    { param($r) $r.scannerArchiveSha256 = 'e' * 64 },
    { param($r) $r.imageId = 'school-image' },
    { param($r) $r.completedAt = [datetime]::UtcNow.AddHours(-7).ToString('o') },
    { param($r) $r.completedAt = [datetime]::UtcNow.AddHours(1).ToString('o') },
    { param($r) $r.completedAt = '2026-99-01T00:00:00.0000000Z' },
    { param($r) $r.databaseBuiltAt = [datetime]::UtcNow.AddHours(-121).ToString('o') },
    { param($r) $r.password = 'synthetic-forbidden-extra-field' },
    { param($r) $r.distributionAlerts = 1 },
    { param($r) $r.findings = $null },
    { param($r) $r.scanExitCode = 2 },
    { param($r) [void]$r.Remove('databaseSchema') }
)) {
    $report = ConvertFrom-ImageSecurityJson $json; & $mutate $report
    Assert-Rejected { Assert-ImageSecurityReport $report 'web' $commit $hash -RequireClean }
}
Write-Output "Image security guards passed: sanitized JSON round trips; $rejected unsafe/unfixed/stale/incomplete fixtures rejected. No real image scan claimed."
