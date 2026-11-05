# Report-validator tests only. These fixtures never stand in for real services.
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot 'private-files-cleanup-common.ps1')
Assert-PrivateFilesTestProject 'educore-files-test-0123456789ab'
Assert-PrivateFilesCleanupResult 0
$cleanupRejected = 0
foreach ($unsafeProject in @('educore','educore-files-test-../school','educore-files-test-0123456789AB')) {
    $denied = $false
    try { Assert-PrivateFilesTestProject $unsafeProject } catch { $denied = $true }
    if (-not $denied) { throw 'Unsafe private-file cleanup identity was accepted.' }
    $cleanupRejected++
}
foreach ($failedExitCode in @(-1,1,127)) {
    $denied = $false
    try { Assert-PrivateFilesCleanupResult $failedExitCode } catch { $denied = $true }
    if (-not $denied) { throw 'Failed private-file cleanup was accepted as success.' }
    $cleanupRejected++
}
Write-Host "Private-file cleanup guards passed: $cleanupRejected unsafe identities/failed results rejected. No containers executed."
$guard = Join-Path $PSScriptRoot "assert-private-files-integration-report.ps1"
$fixturePath = [IO.Path]::GetTempFileName()
$missingPath = $fixturePath + ".not-created"
$scenarioNames = @(
    "realScannerAcceptsGeneratedPdfAndQuarantineCleansUp",
    "realPrivateStoragePreservesScannedBytesAndDeniesAnonymousRead",
    "realStorageRejectsAnIncorrectSha256",
    "realStorageValidChecksumCannotReplaceTheRecordedDatabaseDigest",
    "realStorageMissingOrWrongSizedRestoreNeverReleasesPrivateBytes",
    "realLimitedStorageCredentialsCannotAccessAnotherBucket",
    "realLimitedStorageCredentialsCannotAdministerBuckets",
    "realScannerDetectsTheHarmlessStandardAntivirusTestSignature",
    "unreachableScannerFailsClosedForAGeneratedPdf"
)
$cases = ($scenarioNames | ForEach-Object { '<testcase name="' + $_ + '"/>' }) -join ""
$valid = '<testsuite name="com.educore.api.files.PrivateFilesIT" tests="9" failures="0" errors="0" skipped="0">' + $cases + '</testsuite>'
$rejected = 0
function Assert-Rejected([string]$Source, [datetime]$NotBefore = [datetime]::MinValue) {
    [IO.File]::WriteAllText($fixturePath, $Source)
    $didReject = $false
    try { & $guard -ReportPath $fixturePath -NotBefore $NotBefore } catch { $didReject = $true }
    if (-not $didReject) { throw "Invalid integration evidence was accepted: $Source" }
    $script:rejected++
}
try {
    [IO.File]::WriteAllText($fixturePath, $valid)
    & $guard -ReportPath $fixturePath 6>$null
    Assert-Rejected ($valid.Replace('failures="0"', 'failures="1"'))
    Assert-Rejected ($valid.Replace('errors="0"', 'errors="1"'))
    Assert-Rejected ($valid.Replace('skipped="0"', 'skipped="1"'))
    Assert-Rejected ($valid.Replace(' skipped="0"', ''))
    Assert-Rejected ($valid.Replace('tests="9"', 'tests="10"'))
    Assert-Rejected ($valid.Replace('com.educore.api.files.PrivateFilesIT', 'DifferentSuite'))
    foreach ($scenario in $scenarioNames) {
        $different = if ($scenario -eq $scenarioNames[0]) { $scenarioNames[1] } else { $scenarioNames[0] }
        Assert-Rejected ($valid.Replace($scenario, $different))
        Assert-Rejected ($valid.Replace('<testcase name="' + $scenario + '"/>', '<testcase name="' + $scenario + '"><skipped/></testcase>'))
    }
    Assert-Rejected ($valid.Replace('tests="9"', 'tests="8"').Replace('<testcase name="' + $scenarioNames[3] + '"/>', ''))
    $historical = $valid.Replace('tests="9"', 'tests="7"')
    $historical = $historical.Replace('<testcase name="' + $scenarioNames[3] + '"/>', '')
    $historical = $historical.Replace('<testcase name="' + $scenarioNames[4] + '"/>', '')
    Assert-Rejected $historical
    Assert-Rejected ($valid.Replace('<testcase name="' + $scenarioNames[0] + '"/>', '<testcase name="' + $scenarioNames[0] + '"><skipped/></testcase>'))
    Assert-Rejected $valid ([datetime]::UtcNow.AddMinutes(1))
    $missingRejected = $false
    try { & $guard -ReportPath $missingPath } catch { $missingRejected = $true }
    if (-not $missingRejected) { throw "A missing report was accepted." }
    $rejected++
    Write-Host "Report guard passed: valid evidence accepted; $rejected incomplete/failed/stale/missing fixtures rejected. No real-service execution is claimed."
} finally {
    # One exact generated fixture, not a recursive deletion or shared directory.
    Remove-Item -LiteralPath $fixturePath
}
