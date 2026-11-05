param(
    [string]$ReportPath = 'apps/api/target/failsafe-reports/TEST-com.educore.api.identity.StaffAccountInvitationPostgresIT.xml',
    [string]$SourcePath = (Join-Path $PSScriptRoot '../apps/api/src/test/java/com/educore/api/identity/StaffAccountInvitationPostgresIT.java')
)
$ErrorActionPreference = 'Stop'
$staffInvitationClass = 'com.educore.api.identity.StaffAccountInvitationPostgresIT'
$staffInvitationRequired = @(
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
if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf) -or -not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) {
    throw 'Current staffInvitation PostgreSQL source or actual report missing.'
}
$staffInvitationSource = Get-Content -LiteralPath $SourcePath -Raw
$staffInvitationNames = @([regex]::Matches($staffInvitationSource,'(?m)^\s*@Test\s+void\s+(\w+)\s*\(') | ForEach-Object { $_.Groups[1].Value })
if ($staffInvitationNames.Count -ne $staffInvitationRequired.Count -or
    [regex]::Matches($staffInvitationSource,'@Test\b').Count -ne $staffInvitationRequired.Count -or
    $staffInvitationSource -cmatch '@(?:ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b' -or
    @(Compare-Object ($staffInvitationNames | Sort-Object) ($staffInvitationRequired | Sort-Object)).Count) {
    throw 'StaffInvitation PostgreSQL guard inventory is stale or differs from current named source.'
}
if ((Get-Item -LiteralPath $ReportPath).LastWriteTimeUtc -lt (Get-Item -LiteralPath $SourcePath).LastWriteTimeUtc) {
    throw 'StaffInvitation PostgreSQL report predates its current integration-test source.'
}
$staffInvitationReaderSettings = [Xml.XmlReaderSettings]::new()
$staffInvitationReaderSettings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
$staffInvitationReaderSettings.XmlResolver = $null
$staffInvitationReaderSettings.MaxCharactersInDocument = 8388608
$staffInvitationReader = [Xml.XmlReader]::Create([IO.Path]::GetFullPath($ReportPath),$staffInvitationReaderSettings)
try {
    $staffInvitationReport = [Xml.XmlDocument]::new()
    $staffInvitationReport.XmlResolver = $null
    $staffInvitationReport.Load($staffInvitationReader)
} finally { $staffInvitationReader.Dispose() }
$staffInvitationSuite = $staffInvitationReport.DocumentElement
if ($null -eq $staffInvitationSuite -or $staffInvitationSuite.LocalName -cne 'testsuite' -or
    $staffInvitationSuite.NamespaceURI -cne '' -or $staffInvitationSuite.GetAttribute('name') -cne $staffInvitationClass) {
    throw 'StaffInvitation PostgreSQL report requires its one exact suite identity.'
}
foreach ($staffInvitationAttribute in @('tests','failures','errors','skipped')) {
    if ($staffInvitationSuite.GetAttribute($staffInvitationAttribute) -cnotmatch '^(0|[1-9][0-9]*)$') {
        throw 'StaffInvitation PostgreSQL report counts invalid.'
    }
}
$staffInvitationCases = @($staffInvitationSuite.SelectNodes('testcase'))
if ($staffInvitationSuite.GetAttribute('tests') -cne [string]$staffInvitationRequired.Count -or
    $staffInvitationCases.Count -ne $staffInvitationRequired.Count -or
    $staffInvitationSuite.SelectNodes('.//testcase').Count -ne $staffInvitationRequired.Count -or
    $staffInvitationSuite.SelectNodes('.//testsuite|.//failure|.//error|.//skipped').Count -ne 0 -or
    $staffInvitationSuite.GetAttribute('failures') -cne '0' -or $staffInvitationSuite.GetAttribute('errors') -cne '0' -or
    $staffInvitationSuite.GetAttribute('skipped') -cne '0') {
    throw 'StaffInvitation PostgreSQL report failed, skipped, extra or incomplete.'
}
foreach ($staffInvitationCase in $staffInvitationCases) {
    if ($staffInvitationCase.NamespaceURI -cne '' -or $staffInvitationCase.GetAttribute('classname') -cne $staffInvitationClass -or
        $staffInvitationCase.GetAttribute('name') -cnotin $staffInvitationRequired) {
        throw 'StaffInvitation PostgreSQL scenario identity is invalid or unexpected.'
    }
}
foreach ($staffInvitationName in $staffInvitationRequired) {
    if (@($staffInvitationCases | Where-Object { $_.GetAttribute('name') -ceq $staffInvitationName }).Count -ne 1) {
        throw "Required staffInvitation PostgreSQL scenario missing or duplicated: $staffInvitationName"
    }
}
Write-Output "StaffInvitation PostgreSQL gate passed: $($staffInvitationRequired.Count) exact cases. This is not staff roster, private handoff, MFA/browser or production acceptance."
