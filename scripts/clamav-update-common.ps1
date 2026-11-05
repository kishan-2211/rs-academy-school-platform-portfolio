Set-StrictMode -Version Latest

function ConvertFrom-ClamAvControlledModeFrame([string]$Frame) {
    $document = $null
    try {
        if ([string]::IsNullOrEmpty($Frame) -or $Frame.Length -gt 512) { throw 'Invalid mode frame.' }
        $document = [Text.Json.JsonDocument]::Parse($Frame)
        if ($document.RootElement.ValueKind -ne [Text.Json.JsonValueKind]::String -or
            $document.RootElement.GetString() -cne "CLAMAV_NO_FRESHCLAMD=true`n") { throw 'Invalid mode value.' }
        return "CLAMAV_NO_FRESHCLAMD=true`n"
    } catch { throw 'Controlled scanner background-updater mode metadata was not verified.' }
    finally { if ($null -ne $document) { $document.Dispose() } }
}

# Disposable private-file test fixtures only. A completed updater command never
# replaces the separate connected-daemon loaded-definition freshness check.
function Assert-ClamAvControlledUpdatePrerequisites($Identity, [string]$Project, [int]$Port,
        [string]$ModeMetadata, [string]$VersionMetadata, [string]$ProcessMetadata) {
    Assert-ClamAvScannerIdentity $Identity $Project $Port
    if ($ModeMetadata -cne "CLAMAV_NO_FRESHCLAMD=true`n" -or
        $ProcessMetadata -cne "NO_FRESHCLAM_PROCESS`n") {
        throw 'Controlled scanner update requires its background updater disabled and no existing FreshClam process.'
    }
    # Exit-code classification below is verified against this exact upstream
    # release's libfreshclam.h enum, not the outdated man-page numeric table.
    if ($VersionMetadata.Length -gt 512 -or $VersionMetadata -cnotmatch
        '\AClamAV 1\.4\.6(?:/[1-9][0-9]{0,9}/(?:Sun|Mon|Tue|Wed|Thu|Fri|Sat) (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (?: [1-9]|[12][0-9]|3[01]) [0-2][0-9]:[0-5][0-9]:[0-5][0-9] [0-9]{4})?\r?\n\z') {
        throw 'The official updater version is unsupported or unverified; no update is started.'
    }
    return '1.4.6'
}

function Get-ClamAvControlledUpdateCategory([string]$EngineVersion, [int]$ExitCode, [bool]$TimedOut) {
    if ($EngineVersion -cne '1.4.6') { return 'UNKNOWN' }
    if ($TimedOut) { return 'TIMEOUT' }
    switch ($ExitCode) {
        0 { return 'EXIT_ZERO' }
        2 { return 'INITIALIZATION_FAILED' }
        3 { return 'DIRECTORY_FAILED' }
        4 { return 'FILE_FAILED' }
        5 { return 'NETWORK_FAILED' }
        6 { return 'EMPTY_DOWNLOAD' }
        7 { return 'DATABASE_VERIFICATION_FAILED' }
        8 { return 'DATABASE_LOAD_TEST_FAILED' }
        9 { return 'CONFIGURATION_FAILED' }
        10 { return 'DATABASE_DIRECTORY_DENIED' }
        11 { return 'REMOTE_READ_FAILED' }
        12 { return 'MIRROR_NOT_SYNCHRONIZED' }
        13 { return 'LOGGER_FAILED' }
        14 { return 'UPDATE_FAILED' }
        15 { return 'MEMORY_FAILED' }
        16 { return 'ARGUMENT_FAILED' }
        17 { return 'FORBIDDEN' }
        18 { return 'RETRY_LATER' }
        90 { return 'CONTROLLED_MODE_INVALID' }
        91 { return 'UPDATER_ALREADY_RUNNING' }
        92 { return 'UPDATE_ALREADY_CLAIMED' }
        default { return 'UNKNOWN' }
    }
}

function Get-ClamAvNoUpdaterProbeScript {
    return @'
set -eu
for entry in /proc/[0-9]*/comm; do
  IFS= read -r name < "$entry" || continue
  if [ "$name" = freshclam ]; then exit 91; fi
done
printf 'NO_FRESHCLAM_PROCESS\n'
'@
}

function Get-ClamAvControlledUpdateScript {
    return @'
set -eu
[ "${CLAMAV_NO_FRESHCLAMD:-}" = true ] || exit 90
[ -x /usr/bin/freshclam ] && [ -x /usr/bin/timeout ] || exit 90
for entry in /proc/[0-9]*/comm; do
  IFS= read -r name < "$entry" || continue
  if [ "$name" = freshclam ]; then exit 91; fi
done
mkdir -m 700 /run/clamav/educore-controlled-update-once 2>/dev/null || exit 92
exec /usr/bin/timeout -k 5 180 /usr/bin/freshclam --stdout --log=/dev/null --user=clamav --daemon-notify=/etc/clamav/clamd.conf >/dev/null 2>&1
'@
}

function Invoke-ClamAvControlledUpdateProcess([string]$DockerExecutable, [string]$ContainerId) {
    if ($ContainerId -cnotmatch '^[a-f0-9]{64}$') { throw 'Exact owned scanner identity required before update execution.' }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $DockerExecutable; $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in @('exec',$ContainerId,'/bin/sh','-c',(Get-ClamAvControlledUpdateScript))) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(200))
    $timer = [Diagnostics.Stopwatch]::StartNew(); $started = $false
    try {
        if (-not $process.Start()) { throw 'Owned updater command did not start.' }
        $started = $true
        # Discard both raw pipes concurrently with constant memory. Never retain,
        # print or persist updater/Docker diagnostics, configuration or URLs.
        $stdout = $process.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null,8192,$timeout.Token)
        $stderr = $process.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null,8192,$timeout.Token)
        $remaining = 200000 - $timer.ElapsedMilliseconds
        if ($remaining -le 0 -or -not $process.WaitForExit([int]$remaining)) { throw 'Owned updater command exceeded its outer bound.' }
        $remaining = 200000 - $timer.ElapsedMilliseconds
        if ($remaining -le 0 -or -not [Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($stdout,$stderr)).Wait([int]$remaining)) {
            throw 'Owned updater pipes did not close within their bound.'
        }
        # BusyBox timeout can receive a successful exit from a TERM handler.
        # Monotonic elapsed time therefore independently rejects its 180s bound.
        return [pscustomobject]@{exitCode=$process.ExitCode;timedOut=($timer.ElapsedMilliseconds -ge 180000)}
    } catch { throw 'Controlled updater execution was not verified; raw process diagnostics are discarded.' }
    finally {
        $timeout.Cancel()
        if ($started -and -not $process.HasExited) { try { $process.Kill($true); $process.WaitForExit(1000) | Out-Null } catch {} }
        $timeout.Dispose(); $process.Dispose()
    }
}

function Stop-OwnedClamAvAfterUpdateFailure([string]$DockerExecutable, $Identity, [string]$Project, [int]$Port) {
    Assert-ClamAvScannerIdentity $Identity $Project $Port
    # BusyBox timeout targets one PID. On every failed/unknown update, stop the
    # exact disposable scanner namespace as well, including load-test descendants.
    $running = Invoke-ClamAvDockerRead $DockerExecutable @('inspect','--format','{{json .State.Running}}',$Identity.id)
    if ($running -ceq "true`n") {
        $stopped = Invoke-ClamAvDockerRead $DockerExecutable @('kill',$Identity.id)
        if ($stopped.Trim() -cne $Identity.id) { throw 'Owned updater failure cleanup is unverified.' }
        $running = Invoke-ClamAvDockerRead $DockerExecutable @('inspect','--format','{{json .State.Running}}',$Identity.id)
    }
    if ($running -cne "false`n") { throw 'Owned updater failure cleanup is unverified.' }
}

function Invoke-OwnedClamAvControlledUpdate([string]$DockerExecutable, [string[]]$ComposeArguments, [string]$Project, [int]$Port) {
    $identity = Get-OwnedClamAvIdentity $DockerExecutable $ComposeArguments $Project $Port
    # Only this explicit non-sensitive setting is read, never the whole env.
    # Docker inspect appends its own record newline. JSON keeps that framing
    # separate from matched env values, including duplicate-key evidence.
    $format = '{{ $mode := "" }}{{range .Config.Env}}{{if eq (printf "%.21s" .) "CLAMAV_NO_FRESHCLAMD="}}{{ $mode = printf "%s%s\n" $mode . }}{{end}}{{end}}{{json $mode}}'
    $modeFrame = Invoke-ClamAvDockerRead $DockerExecutable @('inspect','--format',$format,$identity.id)
    $mode = ConvertFrom-ClamAvControlledModeFrame $modeFrame
    $version = Invoke-ClamAvDockerRead $DockerExecutable @('exec',$identity.id,'/usr/bin/freshclam','--version')
    $probe = Invoke-ClamAvDockerRead $DockerExecutable @('exec',$identity.id,'/bin/sh','-c',(Get-ClamAvNoUpdaterProbeScript))
    $engine = Assert-ClamAvControlledUpdatePrerequisites $identity $Project $Port $mode $version $probe
    $completed = $false; $category = 'UNKNOWN'
    try {
        $result = Invoke-ClamAvControlledUpdateProcess $DockerExecutable $identity.id
        if ($null -eq $result -or @($result.PSObject.Properties).Count -ne 2 -or
            $result.exitCode -isnot [int] -or $result.exitCode -lt 0 -or $result.exitCode -gt 255 -or $result.timedOut -isnot [bool]) {
            throw 'Updater completion evidence is incomplete.'
        }
        $category = Get-ClamAvControlledUpdateCategory $engine $result.exitCode $result.timedOut
        if ($category -cne 'EXIT_ZERO') { throw 'The updater did not complete successfully.' }
        $after = Get-OwnedClamAvIdentity $DockerExecutable $ComposeArguments $Project $Port
        if ($after.id -cne $identity.id -or $after.imageId -cne $identity.imageId -or
            (Invoke-ClamAvDockerRead $DockerExecutable @('exec',$identity.id,'/bin/sh','-c',(Get-ClamAvNoUpdaterProbeScript))) -cne "NO_FRESHCLAM_PROCESS`n") {
            $category = 'POST_UPDATE_STATE_UNVERIFIED'
            throw 'The updater did not leave the expected scanner state.'
        }
        $completed = $true
    } catch {
        # Never include the caught exception or raw process output in the error.
        throw "Controlled official FreshClam update was not verified. Safe category: $category. Loaded-definition verification remains mandatory."
    } finally {
        if (-not $completed) { Stop-OwnedClamAvAfterUpdateFailure $DockerExecutable $identity $Project $Port }
    }
    Write-Output 'Owned official FreshClam command exited zero with no competing updater. This is not proof of loaded definition age; the separate freshness gate follows.'
}
