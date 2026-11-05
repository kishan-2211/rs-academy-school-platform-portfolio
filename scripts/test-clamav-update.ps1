# Pure controlled-update policy/dispatch fixtures. No Docker, process, network,
# signature update, scanner or private-service acceptance is performed.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'clamav-freshness-common.ps1')
. (Join-Path $PSScriptRoot 'clamav-update-common.ps1')
$project = 'educore-files-test-012345abcdef'
$port = 13310
$identity = [pscustomobject]@{
    id=('a'*64);imageId=('sha256:'+('b'*64));imageReference='clamav/clamav:1.4'
    project=$project;service='malware-scanner';running=$true;health='healthy'
    timezone="TZ=UTC`n";locale="LC_ALL=C`n";ports=@([pscustomobject]@{HostIp='127.0.0.1';HostPort="$port"})
}
$mode = "CLAMAV_NO_FRESHCLAMD=true`n"
$version = "ClamAV 1.4.6`n"
$noProcess = "NO_FRESHCLAM_PROCESS`n"
$privateMarker = 'SYNTHETIC_PRIVATE_UPDATER_DIAGNOSTIC_NOT_FOR_OUTPUT'
$checks = 0
function Assert-UpdateRejected([scriptblock]$Action) {
    $denied = $false
    try { & $Action | Out-Null } catch { $denied = $true }
    if (-not $denied) { throw 'Unsafe or incomplete controlled-update evidence was accepted.' }
    $script:checks++
}
# Docker's inspector adds one record LF after executing the template. The old
# printf-with-LF template consequently failed for an otherwise correct flag.
Assert-UpdateRejected { Assert-ClamAvControlledUpdatePrerequisites $identity $project $port ($mode+"`n") $version $noProcess }
$dockerModeFrame = (ConvertTo-Json -InputObject $mode -Compress)+"`n"
if ((ConvertFrom-ClamAvControlledModeFrame $dockerModeFrame) -cne $mode) { throw 'Valid Docker JSON scalar/record framing was not decoded.' }
$checks++
foreach ($invalidFrame in @(
    '',$mode,('x'*513),
    ((ConvertTo-Json -InputObject "CLAMAV_NO_FRESHCLAMD=false`n" -Compress)+"`n"),
    ((ConvertTo-Json -InputObject ($mode+$mode) -Compress)+"`n"),
    ((ConvertTo-Json -InputObject ($mode+$privateMarker) -Compress)+"`n"),
    ($dockerModeFrame+$dockerModeFrame),
    ('['+(ConvertTo-Json -InputObject $mode -Compress)+"]`n"),
    ('{"mode":'+(ConvertTo-Json -InputObject $mode -Compress)+"}`n"),
    ($dockerModeFrame+$privateMarker)
)) {
    $denied=$false
    try { ConvertFrom-ClamAvControlledModeFrame $invalidFrame | Out-Null }
    catch { $denied=$true; if ($_.Exception.Message.Contains($privateMarker)) { throw 'Raw mode metadata must never enter diagnostics.' } }
    if (-not $denied) { throw 'Invalid, duplicate or compound Docker mode evidence was accepted.' }
    $checks++
}
if ((Assert-ClamAvControlledUpdatePrerequisites $identity $project $port $mode $version $noProcess) -cne '1.4.6') { throw 'Valid controlled updater prerequisite failed.' }
$checks++
if ((Assert-ClamAvControlledUpdatePrerequisites $identity $project $port $mode "ClamAV 1.4.6/28164/Sun Oct 25 06:24:12 2026`n" $noProcess) -cne '1.4.6') { throw 'Valid version header with signature metadata was not recognized.' }
$checks++
foreach ($invalid in @('', "CLAMAV_NO_FRESHCLAMD=false`n", "CLAMAV_NO_FRESHCLAMD=true`nCLAMAV_NO_FRESHCLAMD=true`n", $privateMarker)) {
    Assert-UpdateRejected { Assert-ClamAvControlledUpdatePrerequisites $identity $project $port $invalid $version $noProcess }
}
foreach ($invalid in @('', "ClamAV 1.4.5`n", "ClamAV 1.4.7`n", "ClamAV 1.4.6-dev`n", "ClamAV 1.4.6`n$privateMarker", ('x'*513))) {
    Assert-UpdateRejected { Assert-ClamAvControlledUpdatePrerequisites $identity $project $port $mode $invalid $noProcess }
}
foreach ($invalid in @('', "FRESHCLAM_PRESENT`n", "NO_FRESHCLAM_PROCESS`n$privateMarker")) {
    Assert-UpdateRejected { Assert-ClamAvControlledUpdatePrerequisites $identity $project $port $mode $version $invalid }
}
$foreign = $identity | ConvertTo-Json -Depth 5 | ConvertFrom-Json
$foreign.project = 'educore-files-test-ffffffffffff'
Assert-UpdateRejected { Assert-ClamAvControlledUpdatePrerequisites $foreign $project $port $mode $version $noProcess }
Assert-UpdateRejected { Assert-ClamAvControlledUpdatePrerequisites $identity 'school-production' $port $mode $version $noProcess }
Assert-UpdateRejected { Assert-ClamAvControlledUpdatePrerequisites $identity $project 13311 $mode $version $noProcess }
foreach ($case in @(
    @{code=0;category='EXIT_ZERO'},@{code=5;category='NETWORK_FAILED'},@{code=7;category='DATABASE_VERIFICATION_FAILED'},
    @{code=8;category='DATABASE_LOAD_TEST_FAILED'},@{code=9;category='CONFIGURATION_FAILED'},@{code=17;category='FORBIDDEN'},
    @{code=18;category='RETRY_LATER'},@{code=19;category='UNKNOWN'},@{code=1;category='UNKNOWN'},
    @{code=90;category='CONTROLLED_MODE_INVALID'},@{code=91;category='UPDATER_ALREADY_RUNNING'},@{code=92;category='UPDATE_ALREADY_CLAIMED'},
    @{code=124;category='UNKNOWN'},@{code=137;category='UNKNOWN'},@{code=255;category='UNKNOWN'}
)) {
    if ((Get-ClamAvControlledUpdateCategory '1.4.6' $case.code $false) -cne $case.category -or
        (Get-ClamAvControlledUpdateCategory '1.4.6' $case.code $true) -cne 'TIMEOUT') { throw 'Exit/category or independent timeout classification is incorrect.' }
    $checks++
}
if ((Get-ClamAvControlledUpdateCategory 'unverified' 0 $false) -cne 'UNKNOWN') { throw 'Unknown updater versions cannot imply success.' }
$checks++

function Test-ControlledUpdateDispatch([hashtable]$Override, [bool]$ShouldPass) {
    $state = [pscustomobject]@{
        mode=$mode;version=$version;probe=$noProcess;afterProbe=$noProcess;result=[pscustomobject]@{exitCode=0;timedOut=$false}
        processFailure=$false;changedImage=$false;running=$true;cleanupFailure=$false
        identityCalls=0;processCalls=0;killCalls=0;readCalls=[Collections.Generic.List[object]]::new()
    }
    foreach ($key in $Override.Keys) { $state.$key=$Override[$key] }
    function Get-OwnedClamAvIdentity {
        param($DockerExecutable,$ComposeArguments,$Project,$Port)
        $state.identityCalls++
        $copy = $identity | ConvertTo-Json -Depth 5 | ConvertFrom-Json
        if ($state.changedImage -and $state.identityCalls -gt 1) { $copy.imageId='sha256:'+('c'*64) }
        return $copy
    }
    function Invoke-ClamAvDockerRead {
        param($Executable,[string[]]$Arguments)
        $state.readCalls.Add(@($Arguments))
        if ($Arguments[0] -ceq 'kill') { $state.killCalls++;$state.running=$false;return ($identity.id+"`n") }
        if ($Arguments[0] -ceq 'inspect' -and $Arguments[2] -ceq '{{json .State.Running}}') {
            if ($state.cleanupFailure) { return "unverified`n" }
            return $(if ($state.running) { "true`n" } else { "false`n" })
        }
        if ($Arguments[0] -ceq 'inspect') {
            if ($Arguments[2] -cnotmatch '\{\{json \$mode\}\}' -or $Arguments[2] -cnotmatch 'printf "%s%s\\n" \$mode') {
                throw 'The actual mode read must separate the JSON scalar from Docker record framing.'
            }
            return ((ConvertTo-Json -InputObject $state.mode -Compress)+"`n")
        }
        if ($Arguments[0] -ceq 'exec' -and $Arguments[2] -ceq '/usr/bin/freshclam') { return $state.version }
        if ($Arguments[0] -ceq 'exec' -and $Arguments[2] -ceq '/bin/sh') {
            return $(if ($state.processCalls -gt 0) { $state.afterProbe } else { $state.probe })
        }
        throw $privateMarker
    }
    function Invoke-ClamAvControlledUpdateProcess {
        param($DockerExecutable,$ContainerId)
        if ($ContainerId -cne $identity.id) { throw 'Update target changed.' }
        $state.processCalls++
        if ($state.processFailure) { throw $privateMarker }
        return $state.result
    }
    $passed = $false; $message = ''
    try { Invoke-OwnedClamAvControlledUpdate 'synthetic-docker-never-started' @('synthetic-compose') $project $port | Out-Null; $passed=$true }
    catch { $message=$_.Exception.Message }
    if ($passed -ne $ShouldPass -or $message.Contains($privateMarker)) { throw 'Controlled dispatch result or diagnostic privacy is incorrect.' }
    foreach ($call in $state.readCalls) {
        if ($call[0] -ceq 'inspect' -and $call[-1] -cne $identity.id -or
            $call[0] -cin @('exec','kill') -and $call[1] -cne $identity.id) { throw 'A command escaped its exact owned scanner identity.' }
    }
    $script:checks++
    return $state
}
$success = Test-ControlledUpdateDispatch @{} $true
if ($success.processCalls -ne 1 -or $success.killCalls -ne 0 -or $success.identityCalls -ne 2) { throw 'Exactly one successful owned updater command and unchanged post-identity required.' }
foreach ($unsafe in @(
    @{mode="CLAMAV_NO_FRESHCLAMD=false`n"},@{mode="$mode$mode"},@{version="ClamAV 1.4.5`n"},@{probe="FRESHCLAM_PRESENT`n"}
)) {
    $refused = Test-ControlledUpdateDispatch $unsafe $false
    if ($refused.processCalls -ne 0 -or $refused.killCalls -ne 0) { throw 'Invalid preconditions must never dispatch or stop a container.' }
}
foreach ($failure in @(
    @{result=[pscustomobject]@{exitCode=17;timedOut=$false}},@{result=[pscustomobject]@{exitCode=18;timedOut=$false}},
    @{result=[pscustomobject]@{exitCode=0;timedOut=$true}},@{result=[pscustomobject]@{exitCode=255;timedOut=$false}},
    @{result=[pscustomobject]@{exitCode='0';timedOut=$false}},@{result=[pscustomobject]@{exitCode=0;timedOut=$false;raw=$privateMarker}},
    @{processFailure=$true},@{changedImage=$true},@{afterProbe="FRESHCLAM_PRESENT`n"}
)) {
    $refused = Test-ControlledUpdateDispatch $failure $false
    if ($refused.processCalls -ne 1 -or $refused.killCalls -ne 1 -or $refused.running) { throw 'Every failed/unknown update must stop and verify only its owned disposable scanner.' }
}
$alreadyStopped = Test-ControlledUpdateDispatch @{running=$false;processFailure=$true} $false
if ($alreadyStopped.killCalls -ne 0) { throw 'Already stopped owned scanner must not be signalled again.' }
$cleanupFailed = Test-ControlledUpdateDispatch @{cleanupFailure=$true;processFailure=$true} $false
if ($cleanupFailed.killCalls -ne 0) { throw 'Unknown cleanup state must fail closed without an unverified kill.' }

$command = Get-ClamAvControlledUpdateScript
foreach ($required in @('[ "${CLAMAV_NO_FRESHCLAMD:-}" = true ]','/proc/[0-9]*/comm',
    'mkdir -m 700 /run/clamav/educore-controlled-update-once','exec /usr/bin/timeout -k 5 180 /usr/bin/freshclam',
    '--log=/dev/null','--user=clamav','--daemon-notify=/etc/clamav/clamd.conf','>/dev/null 2>&1')) {
    if (-not $command.Contains($required)) { throw 'Mandatory controlled one-shot/update/notify/bound guard missing.' }
}
if ($command -cmatch '--no-dns|--insecure|TestDatabases|curl|wget|--daemon\s|killall|pkill') { throw 'The update command must preserve official verification and avoid competing daemons or broad signals.' }
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'clamav-update-common.ps1') -Raw
if ([regex]::Matches($source,'CopyToAsync\(\[IO.Stream\]::Null,8192,\$timeout.Token\)').Count -ne 2 -or
    $source -cnotmatch 'FromSeconds\(200\)' -or $source -cnotmatch 'ElapsedMilliseconds -ge 180000' -or
    $source -cmatch 'ReadToEnd|Set-Content|Out-File|WriteAll|StandardOutput\.Read|StandardError\.Read') { throw 'Raw updater pipes must be discarded with bounded memory/time, never retained or published.' }
$verify = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify-private-files-integration.ps1') -Raw
$updateIndex=$verify.IndexOf('Invoke-OwnedClamAvControlledUpdate $dockerCommand.Source')
if ($updateIndex -lt 0 -or $updateIndex -ge $verify.IndexOf('$scannerBefore = Assert-OwnedClamAvFreshness') -or
    $verify -cnotmatch "'scripts/clamav-update-common.ps1','scripts/test-clamav-update.ps1'") { throw 'Controlled update must precede the unchanged real freshness/Maven gates with its sources hashed.' }
$compose = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../infra/compose.private-files-test.yaml') -Raw
if ($compose -cnotmatch 'CLAMAV_NO_FRESHCLAMD: "true"') { throw 'Only the disposable scanner fixture must disable its background updater.' }
$production = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../infra/compose.yaml') -Raw
if ($production -cmatch 'CLAMAV_NO_FRESHCLAMD') { throw 'Production updater behavior must remain unchanged by this fixture.' }
$checks++
Write-Output "Controlled FreshClam pure fixtures passed: $checks prerequisites, owned dispatch, concurrency, exit-code, privacy, timeout and cleanup checks. No Docker, process, network, signature update, scanner or release acceptance claimed."
