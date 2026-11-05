param(
    [string]$ReportPath = "apps/api/target/failsafe-reports/TEST-com.educore.api.files.PrivateFilesIT.xml",
    [datetime]$NotBefore = [datetime]::MinValue
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) {
    throw "The real private-file integration report is missing. No success is inferred."
}
if ((Get-Item -LiteralPath $ReportPath).LastWriteTimeUtc -lt $NotBefore.ToUniversalTime()) {
    throw "The private-file report predates this verification run."
}
[xml]$report = Get-Content -LiteralPath $ReportPath -Raw
$suite = $report.testsuite
foreach ($attribute in @("tests", "failures", "errors", "skipped")) {
    if ($null -eq $suite -or $suite.GetAttribute($attribute) -notmatch '^\d+$') {
        throw "The private-file report is missing a valid $attribute count."
    }
}
if ($suite.name -ne "com.educore.api.files.PrivateFilesIT" -or [int]$suite.tests -lt 9 -or
    [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
    throw "The real private-file gate failed, was incomplete, or skipped tests."
}
if (@($suite.testcase).Count -ne [int]$suite.tests -or
    $suite.SelectNodes("testcase/failure|testcase/error|testcase/skipped").Count -gt 0) {
    throw "The private-file test cases do not match the reported successful execution."
}
foreach ($required in @(
    "realScannerAcceptsGeneratedPdfAndQuarantineCleansUp",
    "realPrivateStoragePreservesScannedBytesAndDeniesAnonymousRead",
    "realStorageRejectsAnIncorrectSha256",
    "realStorageValidChecksumCannotReplaceTheRecordedDatabaseDigest",
    "realStorageMissingOrWrongSizedRestoreNeverReleasesPrivateBytes",
    "realLimitedStorageCredentialsCannotAccessAnotherBucket",
    "realLimitedStorageCredentialsCannotAdministerBuckets",
    "realScannerDetectsTheHarmlessStandardAntivirusTestSignature",
    "unreachableScannerFailsClosedForAGeneratedPdf"
)) {
    $case = @($suite.testcase | Where-Object { $_.name -eq $required })
    if ($case.Count -ne 1 -or $case[0].SelectNodes("failure|error|skipped").Count -gt 0) {
        throw "Required real private-file scenario did not pass: $required"
    }
}
Write-Host "Verified $($suite.tests) real private-file scenarios with no failures, errors or skips."
