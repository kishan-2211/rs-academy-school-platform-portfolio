param(
    [string]$ReportPath = 'apps/api/target/failsafe-reports/TEST-com.educore.api.config.OfficialDocumentRecoveryIT.xml',
    [datetime]$NotBefore = [datetime]::MinValue
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) { throw 'Real paired recovery evidence is missing.' }
$file = Get-Item -LiteralPath $ReportPath
if ($file.LastWriteTimeUtc -lt $NotBefore.ToUniversalTime() -or $file.Length -gt 4MB) {
    throw 'Paired recovery evidence is stale or unbounded.'
}
$settings = [Xml.XmlReaderSettings]::new()
$settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
$settings.XmlResolver = $null
$settings.MaxCharactersInDocument = 4MB
$reader = [Xml.XmlReader]::Create($file.FullName, $settings)
try {
    $report = [Xml.XmlDocument]::new()
    $report.XmlResolver = $null
    $report.Load($reader)
} finally { $reader.Dispose() }
$suite = $report.DocumentElement
$class = 'com.educore.api.config.OfficialDocumentRecoveryIT'
if ($suite.LocalName -cne 'testsuite' -or $suite.GetAttribute('name') -cne $class) { throw 'Wrong paired recovery suite.' }
foreach ($attribute in @('tests', 'failures', 'errors', 'skipped')) {
    if ($suite.GetAttribute($attribute) -notmatch '^\d+$') { throw 'Missing or invalid paired recovery counts.' }
}
$required = @(
    'realPairedRestoreRecoversPrivatePdfAndPreservesAuthorityHistoryAndRetries',
    'realPairedRestoreRequiresTheRecoveredKeyAndRejectsMissingWrongOrReboundKeys',
    'realPairedRestoreRejectsMissingAndSubstitutedObjectsWithoutRewritingMetadata'
)
$cases = @($suite.SelectNodes('testcase'))
if ([int]$suite.GetAttribute('tests') -ne $required.Count -or $cases.Count -ne $required.Count -or
    [int]$suite.GetAttribute('failures') -ne 0 -or [int]$suite.GetAttribute('errors') -ne 0 -or
    [int]$suite.GetAttribute('skipped') -ne 0 -or $suite.SelectNodes('.//failure|.//error|.//skipped').Count -ne 0) {
    throw 'Real paired recovery scenarios failed, were omitted, or skipped.'
}
foreach ($scenario in $required) {
    $matches = @($cases | Where-Object { $_.GetAttribute('name') -ceq $scenario -and $_.GetAttribute('classname') -ceq $class })
    if ($matches.Count -ne 1) { throw 'A required real paired recovery scenario did not execute exactly once.' }
}
Write-Host 'Verified all three real PostgreSQL/private-object/key/API recovery scenarios with zero failures, errors or skips.'
