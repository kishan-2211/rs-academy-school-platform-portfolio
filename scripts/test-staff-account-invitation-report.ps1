$ErrorActionPreference = 'Stop'
$staffInvitationFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('educore-staffInvitation-report-' + [guid]::NewGuid().ToString('N'))
$staffInvitationFixtureFile = Join-Path $staffInvitationFixtureRoot 'synthetic.xml'
$staffInvitationSourceFixture = Join-Path $staffInvitationFixtureRoot 'synthetic-source.java'
$staffInvitationGuard = Join-Path $PSScriptRoot 'assert-staff-account-invitation-report.ps1'
$staffInvitationActualSource = Join-Path $PSScriptRoot '../apps/api/src/test/java/com/educore/api/identity/StaffAccountInvitationPostgresIT.java'
$staffInvitationClass = 'com.educore.api.identity.StaffAccountInvitationPostgresIT'
$staffInvitationChecks = 0
$staffInvitationNames = @(
    'populatedV084UpgradePreservesEveryExistingRowAndUsesActualRestrictedRuntime',
    'newHolderAcceptanceAtomicallyCreatesExactRoleLinksEmployeeConsumesTokenAndMintsNoSession',
    'existingHolderUsesAuthenticatedAcceptancePreservesPasswordAndRevokesPriorSessions',
    'existingAccountCannotBeClaimedThroughPublicPasswordOrDifferentSignedInHolder',
    'existingEnabledMfaRequiresVerifiedPrincipalAndActualStoredSessionBeforeGrant',
    'handoffIsExplicitDefaultDisabledOneUseAndCryptographicallyPurposeScoped',
    'issuanceReplaysExactRequestRejectsChangedTermsAndSupersedesOpenInvitation',
    'invalidInvitationTermsAndPasswordConfirmationCannotCreateAccess',
    'schoolTenantAndLiveCapabilityChecksRejectUnpermittedIssuerWithoutWrites',
    'tokenDiscoveryDoesNotExposeUnscopedOrForeignTenantInvitationsThroughRuntimeRls',
    'databaseConstraintsAndAppendOnlyHistoryRefuseForgedConsumptionAndDestructiveEdits',
    'revocationAndExpiryPreventAcceptanceAndNeverResetExistingIdentity',
    'changedEmployeeOrRevokedIssuerAuthorityInvalidatesPendingTokenWithoutGrant',
    'existingTargetSchoolMembershipCannotBeSilentlyReactivatedOrExpanded',
    'concurrentExactIssuanceAndTokenAcceptanceCannotDuplicateAccountsMembershipsOrHistory',
    'lateAuditFailureRollsBackNewAccountGrantLinkAndTokenConsumption',
    'accountCreatedAfterPreviewCannotBeOverwrittenOrAdoptedByPublicAcceptance'
)
function New-StaffInvitationReportFixture {
    [xml]$fixture = '<testsuite name="com.educore.api.identity.StaffAccountInvitationPostgresIT" failures="0" errors="0" skipped="0" />'
    $fixture.testsuite.SetAttribute('tests',[string]$staffInvitationNames.Count)
    foreach ($name in $staffInvitationNames) {
        $case = $fixture.CreateElement('testcase')
        $case.SetAttribute('name',$name); $case.SetAttribute('classname',$staffInvitationClass)
        $fixture.testsuite.AppendChild($case) | Out-Null
    }
    return ,$fixture
}
function Assert-StaffInvitationReportRejected([xml]$fixture) {
    $fixture.Save($staffInvitationFixtureFile)
    $rejected = $false
    try { & $staffInvitationGuard -ReportPath $staffInvitationFixtureFile *> $null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Malformed synthetic staffInvitation report passed.' }
    $script:staffInvitationChecks++
}
function Assert-StaffInvitationPathsRejected([string]$report,[string]$source=$staffInvitationActualSource) {
    $rejected = $false
    try { & $staffInvitationGuard -ReportPath $report -SourcePath $source *> $null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Missing, stale or malformed synthetic staffInvitation evidence passed.' }
    $script:staffInvitationChecks++
}
try {
    New-Item -ItemType Directory -Path $staffInvitationFixtureRoot | Out-Null
    $valid = New-StaffInvitationReportFixture; $valid.Save($staffInvitationFixtureFile)
    & $staffInvitationGuard -ReportPath $staffInvitationFixtureFile *> $null
    $staffInvitationChecks++
    Assert-StaffInvitationPathsRejected (Join-Path $staffInvitationFixtureRoot 'missing.xml')
    Assert-StaffInvitationPathsRejected $staffInvitationFixtureFile (Join-Path $staffInvitationFixtureRoot 'missing.java')
    (Get-Item -LiteralPath $staffInvitationFixtureFile).LastWriteTimeUtc = (Get-Item -LiteralPath $staffInvitationActualSource).LastWriteTimeUtc.AddMinutes(-1)
    Assert-StaffInvitationPathsRejected $staffInvitationFixtureFile
    $valid.Save($staffInvitationFixtureFile)
    # Changing the source inventory must require a deliberate guard update.
    $changedSource = (Get-Content -LiteralPath $staffInvitationActualSource -Raw).Replace($staffInvitationNames[0],'syntheticRenamedScenario')
    [IO.File]::WriteAllText($staffInvitationSourceFixture,$changedSource)
    $valid.Save($staffInvitationFixtureFile)
    Assert-StaffInvitationPathsRejected $staffInvitationFixtureFile $staffInvitationSourceFixture
    foreach ($attribute in @('tests','failures','errors','skipped')) {
        $fixture = New-StaffInvitationReportFixture; $fixture.testsuite.RemoveAttribute($attribute); Assert-StaffInvitationReportRejected $fixture
        foreach ($invalid in @('-1','11.0','invalid','01','')) {
            $fixture = New-StaffInvitationReportFixture; $fixture.testsuite.SetAttribute($attribute,$invalid); Assert-StaffInvitationReportRejected $fixture
        }
    }
    foreach ($attribute in @('failures','errors','skipped')) {
        $fixture = New-StaffInvitationReportFixture; $fixture.testsuite.SetAttribute($attribute,'1'); Assert-StaffInvitationReportRejected $fixture
    }
    foreach ($name in $staffInvitationNames) {
        $fixture = New-StaffInvitationReportFixture
        $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
        $fixture.testsuite.RemoveChild($case) | Out-Null
        $fixture.testsuite.SetAttribute('tests',[string]($staffInvitationNames.Count-1)); Assert-StaffInvitationReportRejected $fixture
        $fixture = New-StaffInvitationReportFixture; $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
        $case.SetAttribute('name','syntheticMissingRequiredCase'); Assert-StaffInvitationReportRejected $fixture
        foreach ($outcome in @('failure','error','skipped')) {
            $fixture = New-StaffInvitationReportFixture; $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
            $case.AppendChild($fixture.CreateElement($outcome)) | Out-Null; Assert-StaffInvitationReportRejected $fixture
        }
        $fixture = New-StaffInvitationReportFixture; $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
        $fixture.testsuite.AppendChild($case.CloneNode($true)) | Out-Null
        $fixture.testsuite.SetAttribute('tests',[string]($staffInvitationNames.Count+1)); Assert-StaffInvitationReportRejected $fixture
    }
    # Same count, duplicated name: a missing different case must not be hidden.
    $fixture = New-StaffInvitationReportFixture; $fixture.testsuite.testcase[0].SetAttribute('name',$staffInvitationNames[1]); Assert-StaffInvitationReportRejected $fixture
    $fixture = New-StaffInvitationReportFixture; $fixture.testsuite.SetAttribute('name','WrongClass'); Assert-StaffInvitationReportRejected $fixture
    $fixture = New-StaffInvitationReportFixture; $fixture.testsuite.SetAttribute('tests',[string]($staffInvitationNames.Count+1)); Assert-StaffInvitationReportRejected $fixture
    $fixture = New-StaffInvitationReportFixture; $fixture.testsuite.testcase[0].SetAttribute('classname','WrongClass'); Assert-StaffInvitationReportRejected $fixture
    $fixture = New-StaffInvitationReportFixture; $case=$fixture.CreateElement('testcase'); $case.SetAttribute('name','syntheticExtraCase'); $case.SetAttribute('classname',$staffInvitationClass)
    $fixture.testsuite.AppendChild($case) | Out-Null; $fixture.testsuite.SetAttribute('tests',[string]($staffInvitationNames.Count+1)); Assert-StaffInvitationReportRejected $fixture
    $fixture = New-StaffInvitationReportFixture; $wrapper=$fixture.CreateElement('properties'); $wrapper.AppendChild($fixture.CreateElement('failure')) | Out-Null
    $fixture.testsuite.AppendChild($wrapper) | Out-Null; Assert-StaffInvitationReportRejected $fixture
    [IO.File]::WriteAllText($staffInvitationFixtureFile,'<testsuites><testsuite name="com.educore.api.identity.StaffAccountInvitationPostgresIT" tests="0" failures="0" errors="0" skipped="0" /></testsuites>')
    Assert-StaffInvitationPathsRejected $staffInvitationFixtureFile
    [IO.File]::WriteAllText($staffInvitationFixtureFile,'<!DOCTYPE testsuite [<!ENTITY synthetic "not allowed">]><testsuite>&synthetic;</testsuite>')
    Assert-StaffInvitationPathsRejected $staffInvitationFixtureFile
    [IO.File]::WriteAllText($staffInvitationFixtureFile,'<testsuite')
    Assert-StaffInvitationPathsRejected $staffInvitationFixtureFile
    Write-Output "StaffInvitation report guard fixtures passed: $staffInvitationChecks checks; synthetic XML is not PostgreSQL verification."
} finally {
    $resolved = [IO.Path]::GetFullPath($staffInvitationFixtureRoot)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $resolved.StartsWith($temp+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolved -Leaf) -cnotmatch '^educore-staffInvitation-report-[a-f0-9]{32}$') {
        throw 'Unsafe generated staffInvitation fixture path; refusing cleanup.'
    }
    if (Test-Path -LiteralPath $staffInvitationFixtureFile) { Remove-Item -LiteralPath $staffInvitationFixtureFile }
    if (Test-Path -LiteralPath $staffInvitationSourceFixture) { Remove-Item -LiteralPath $staffInvitationSourceFixture }
    if (Test-Path -LiteralPath $staffInvitationFixtureRoot) { Remove-Item -LiteralPath $staffInvitationFixtureRoot }
}
