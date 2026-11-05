# Validator fixtures only: these never stand in for real PostgreSQL, storage, scan or API execution.
$ErrorActionPreference = 'Stop'
$guard = Join-Path $PSScriptRoot 'assert-paired-recovery-report.ps1'
$fixture = [IO.Path]::GetTempFileName()
$class = 'com.educore.api.config.OfficialDocumentRecoveryIT'
$names = @('realPairedRestoreRecoversPrivatePdfAndPreservesAuthorityHistoryAndRetries',
    'realPairedRestoreRequiresTheRecoveredKeyAndRejectsMissingWrongOrReboundKeys',
    'realPairedRestoreRejectsMissingAndSubstitutedObjectsWithoutRewritingMetadata')
$cases = ($names | ForEach-Object { '<testcase classname="' + $class + '" name="' + $_ + '"/>' }) -join ''
$valid = '<testsuite name="' + $class + '" tests="3" failures="0" errors="0" skipped="0">' + $cases + '</testsuite>'
$rejected = 0
function Assert-Rejected([string]$Xml, [datetime]$NotBefore = [datetime]::MinValue) {
    [IO.File]::WriteAllText($fixture, $Xml)
    $denied = $false
    try { & $guard -ReportPath $fixture -NotBefore $NotBefore 6>$null } catch { $denied = $true }
    if (-not $denied) { throw 'Invalid recovery report fixture was accepted.' }
    $script:rejected++
}
try {
    [IO.File]::WriteAllText($fixture, $valid)
    & $guard -ReportPath $fixture 6>$null
    foreach ($attribute in @('failures', 'errors', 'skipped')) {
        Assert-Rejected ($valid.Replace("$attribute=`"0`"", "$attribute=`"1`""))
        Assert-Rejected ($valid.Replace(" $attribute=`"0`"", ''))
    }
    Assert-Rejected ($valid.Replace('tests="3"', 'tests="4"'))
    Assert-Rejected ($valid.Replace('tests="3"', 'tests="NaN"'))
    Assert-Rejected ($valid.Replace($class, 'WrongSuite'))
    foreach ($name in $names) {
        $case = '<testcase classname="' + $class + '" name="' + $name + '"/>'
        Assert-Rejected ($valid.Replace($case, ''))
        Assert-Rejected ($valid.Replace($case, $case + $case))
        Assert-Rejected ($valid.Replace($case, $case.Replace('/>', '><skipped/></testcase>')))
        Assert-Rejected ($valid.Replace($case, $case.Replace('/>', '><failure/></testcase>')))
        Assert-Rejected ($valid.Replace($case, $case.Replace($class, 'WrongClass')))
    }
    Assert-Rejected '<testsuite'
    Assert-Rejected ('<!DOCTYPE testsuite [<!ENTITY external SYSTEM "file:///never-read-test-fixture">]>' + $valid)
    Assert-Rejected $valid ([datetime]::UtcNow.AddMinutes(1))
    $missingDenied = $false
    try { & $guard -ReportPath ($fixture + '.not-created') } catch { $missingDenied = $true }
    if (-not $missingDenied) { throw 'A missing recovery report was accepted.' }
    $rejected++
    Write-Host "Recovery report guards passed: valid fixture accepted; $rejected unsafe/incomplete/stale fixtures rejected. No real recovery run is claimed."
} finally { Remove-Item -LiteralPath $fixture }
