Set-StrictMode -Version Latest

# Failure evidence is an allowlisted diagnostic, never an exception message,
# raw VERSION frame, container environment or scanner/updater log.
function ConvertTo-ClamAvSafeDiagnostic($Candidate) {
    $baseNames = @('schemaVersion','stage','category')
    $detailNames = @('engineVersion','dailyDatabaseVersion','databaseBuiltAtUtc','observedAtUtc','ageSeconds','maximumAgeSeconds')
    $frameNames = @('frameLength','terminatorKind','nulCount','lineFeedCount','carriageReturnCount','observationOffsetKind')
    if ($null -eq $Candidate -or $Candidate -isnot [pscustomobject]) { return $null }
    $names = @($Candidate.PSObject.Properties.Name)
    if (@(Compare-Object -CaseSensitive ($baseNames | Sort-Object) ($names | Sort-Object)).Count -ne 0 -and
        @(Compare-Object -CaseSensitive (($baseNames + $detailNames) | Sort-Object) ($names | Sort-Object)).Count -ne 0 -and
        @(Compare-Object -CaseSensitive (($baseNames + $frameNames) | Sort-Object) ($names | Sort-Object)).Count -ne 0) { return $null }
    $categories = @{
        identity = @('invalid-target','incomplete-evidence','untrusted-scanner','command-not-verified','identity-unresolved','identity-schema','utc-mismatch','unclassified')
        connect = @('invalid-target','deadline-expired','operation-not-verified','unclassified')
        write = @('operation-not-verified','unclassified')
        read = @('unreadable-stream','frame-too-large','read-not-verified','unclassified')
        parse = @('frame-layout','frame-encoding','version-format','database-date','unclassified')
        freshness = @('database-future','database-stale','unclassified')
        probe = @('unclassified')
    }
    if ($Candidate.schemaVersion -isnot [int] -or $Candidate.schemaVersion -ne 1 -or
        $Candidate.stage -isnot [string] -or $Candidate.stage -cnotin @($categories.Keys) -or
        $Candidate.category -isnot [string] -or $Candidate.category -cnotin $categories[$Candidate.stage]) { return $null }
    $safe = [ordered]@{schemaVersion=1;stage=$Candidate.stage;category=$Candidate.category}
    if ($names -ccontains 'frameLength') {
        if ($Candidate.stage -cne 'parse' -or $Candidate.category -cne 'frame-layout' -or
            $Candidate.frameLength -isnot [int] -or $Candidate.frameLength -lt 0 -or $Candidate.frameLength -gt 512 -or
            $Candidate.terminatorKind -isnot [string] -or $Candidate.terminatorKind -cnotin @('NONE','NUL','LF','CR','OTHER') -or
            $Candidate.observationOffsetKind -isnot [string] -or $Candidate.observationOffsetKind -cnotin @('UTC','NON_UTC')) { return $null }
        foreach ($name in @('nulCount','lineFeedCount','carriageReturnCount')) {
            if ($Candidate.$name -isnot [int] -or $Candidate.$name -lt 0 -or $Candidate.$name -gt $Candidate.frameLength) { return $null }
        }
        $controls = $Candidate.nulCount + $Candidate.lineFeedCount + $Candidate.carriageReturnCount
        if ($controls -gt $Candidate.frameLength -or
            ($Candidate.frameLength -eq 0 -and $Candidate.terminatorKind -cne 'NONE') -or
            ($Candidate.frameLength -gt 0 -and $Candidate.terminatorKind -ceq 'NONE') -or
            ($Candidate.terminatorKind -ceq 'NUL' -and $Candidate.nulCount -lt 1) -or
            ($Candidate.terminatorKind -ceq 'LF' -and $Candidate.lineFeedCount -lt 1) -or
            ($Candidate.terminatorKind -ceq 'CR' -and $Candidate.carriageReturnCount -lt 1) -or
            ($Candidate.terminatorKind -ceq 'OTHER' -and $controls -ge $Candidate.frameLength) -or
            ($Candidate.frameLength -ge 2 -and $Candidate.terminatorKind -ceq 'NUL' -and $Candidate.observationOffsetKind -ceq 'UTC')) { return $null }
        foreach ($name in $frameNames) { $safe[$name] = $Candidate.$name }
    } elseif ($names.Count -gt $baseNames.Count) {
        if ($Candidate.stage -cne 'freshness' -or $Candidate.category -cnotin @('database-future','database-stale') -or
            $Candidate.engineVersion -isnot [string] -or $Candidate.engineVersion -cnotmatch '\A1\.4\.(?:0|[1-9][0-9]{0,4})\z' -or
            $Candidate.dailyDatabaseVersion -isnot [uint32] -or $Candidate.dailyDatabaseVersion -eq 0 -or
            $Candidate.databaseBuiltAtUtc -isnot [string] -or $Candidate.observedAtUtc -isnot [string] -or
            $Candidate.ageSeconds -isnot [double] -or -not [double]::IsFinite($Candidate.ageSeconds) -or
            $Candidate.maximumAgeSeconds -isnot [int] -or $Candidate.maximumAgeSeconds -ne 172800) { return $null }
        [DateTimeOffset]$built = [DateTimeOffset]::MinValue; [DateTimeOffset]$observed = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParseExact($Candidate.databaseBuiltAtUtc,'o',[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$built) -or
            -not [DateTimeOffset]::TryParseExact($Candidate.observedAtUtc,'o',[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$observed) -or
            $built.Year -lt 2000 -or $observed.Year -lt 2000 -or $built.Offset -ne [TimeSpan]::Zero -or $observed.Offset -ne [TimeSpan]::Zero -or
            [Math]::Abs(($observed-$built).TotalSeconds-$Candidate.ageSeconds) -gt 0.000001 -or
            ($Candidate.category -ceq 'database-future' -and $Candidate.ageSeconds -ge 0) -or
            ($Candidate.category -ceq 'database-stale' -and $Candidate.ageSeconds -le 172800)) { return $null }
        foreach ($name in $detailNames) { $safe[$name] = $Candidate.$name }
    }
    return [pscustomobject]$safe
}

function New-ClamAvProbeException([string]$Stage, [string]$Category, $Freshness = $null, $FrameEnvelope = $null) {
    $candidate = [ordered]@{schemaVersion=1;stage=$Stage;category=$Category}
    if ($null -ne $Freshness) {
        foreach ($name in @('engineVersion','dailyDatabaseVersion','databaseBuiltAtUtc','observedAtUtc','ageSeconds','maximumAgeSeconds')) {
            $candidate[$name] = $Freshness.$name
        }
    }
    if ($null -ne $FrameEnvelope) {
        foreach ($name in @('frameLength','terminatorKind','nulCount','lineFeedCount','carriageReturnCount','observationOffsetKind')) {
            $candidate[$name] = $FrameEnvelope.$name
        }
    }
    $safe = ConvertTo-ClamAvSafeDiagnostic ([pscustomobject]$candidate)
    if ($null -eq $safe) { $safe = [pscustomobject]@{schemaVersion=1;stage='probe';category='unclassified'} }
    $failure = [InvalidOperationException]::new('ClamAV verification failed; only the allowlisted diagnostic may be published.')
    $failure.Data['educore.clamav.diagnostic.v1'] = $safe
    return $failure
}

function Get-ClamAvFrameEnvelope([byte[]]$Frame, [DateTimeOffset]$ObservedAtUtc) {
    if ($null -eq $Frame -or $Frame.Length -gt 512) { return $null }
    $nulCount = 0; $lineFeedCount = 0; $carriageReturnCount = 0
    foreach ($value in $Frame) {
        switch ($value) { 0 { $nulCount++ } 10 { $lineFeedCount++ } 13 { $carriageReturnCount++ } }
    }
    $terminator = if ($Frame.Length -eq 0) { 'NONE' } else {
        switch ($Frame[-1]) { 0 { 'NUL' } 10 { 'LF' } 13 { 'CR' } default { 'OTHER' } }
    }
    return [pscustomobject]@{
        frameLength=$Frame.Length;terminatorKind=$terminator
        nulCount=$nulCount;lineFeedCount=$lineFeedCount;carriageReturnCount=$carriageReturnCount
        observationOffsetKind=$(if ($ObservedAtUtc.Offset -eq [TimeSpan]::Zero) { 'UTC' } else { 'NON_UTC' })
    }
}

function Get-ClamAvProbeDiagnostic($Failure, [string]$FallbackStage = 'probe') {
    $exception = if ($Failure -is [Management.Automation.ErrorRecord]) { $Failure.Exception } else { $Failure }
    for ($depth = 0; $depth -lt 8 -and $exception -is [Exception]; $depth++) {
        $safe = ConvertTo-ClamAvSafeDiagnostic $exception.Data['educore.clamav.diagnostic.v1']
        if ($null -ne $safe) { return $safe }
        $exception = $exception.InnerException
    }
    $fallback = ConvertTo-ClamAvSafeDiagnostic ([pscustomobject]@{schemaVersion=1;stage=$FallbackStage;category='unclassified'})
    if ($null -eq $fallback) { $fallback = [pscustomobject]@{schemaVersion=1;stage='probe';category='unclassified'} }
    return $fallback
}

# This guard measures the connected daemon's loaded daily database age. It does
# not claim a FreshClam update occurred, certify CDN latest, or replace EICAR tests.
function Assert-ClamAvScannerIdentity($Identity, [string]$Project, [int]$Port) {
    if ($Project -cnotmatch '^educore-files-test-[a-f0-9]{12}$' -or $Port -lt 1024 -or $Port -gt 65535) {
        throw (New-ClamAvProbeException 'identity' 'invalid-target')
    }
    $names = @('id','imageId','imageReference','project','service','running','health','timezone','locale','ports')
    if ($null -eq $Identity -or @($Identity.PSObject.Properties).Count -ne $names.Count -or
        @(Compare-Object -CaseSensitive ($names | Sort-Object) (@($Identity.PSObject.Properties.Name) | Sort-Object)).Count) {
        throw (New-ClamAvProbeException 'identity' 'incomplete-evidence')
    }
    $ports = @($Identity.ports)
    if ($Identity.id -cnotmatch '^[a-f0-9]{64}$' -or $Identity.imageId -cnotmatch '^sha256:[a-f0-9]{64}$' -or
        $Identity.imageReference -cne 'clamav/clamav:1.4' -or $Identity.project -cne $Project -or
        $Identity.service -cne 'malware-scanner' -or $Identity.running -isnot [bool] -or -not $Identity.running -or
        $Identity.health -cne 'healthy' -or $Identity.timezone -cne "TZ=UTC`n" -or $Identity.locale -cne "LC_ALL=C`n" -or
        $ports.Count -ne 1 -or $ports[0].HostIp -cne '127.0.0.1' -or $ports[0].HostPort -cne "$Port") {
        throw (New-ClamAvProbeException 'identity' 'untrusted-scanner')
    }
}

function ConvertFrom-ClamAvVersionFrame([byte[]]$Frame, [DateTimeOffset]$ObservedAtUtc) {
    if ($null -eq $Frame -or $Frame.Length -lt 2 -or $Frame.Length -gt 512 -or $Frame[-1] -ne 0 -or
        $ObservedAtUtc.Offset -ne [TimeSpan]::Zero) {
        $envelope = Get-ClamAvFrameEnvelope $Frame $ObservedAtUtc
        throw (New-ClamAvProbeException 'parse' 'frame-layout' -FrameEnvelope $envelope)
    }
    for ($index = 0; $index -lt $Frame.Length - 1; $index++) {
        if ($Frame[$index] -lt 32 -or $Frame[$index] -gt 126) {
            throw (New-ClamAvProbeException 'parse' 'frame-encoding')
        }
    }
    $text = [Text.Encoding]::ASCII.GetString($Frame, 0, $Frame.Length - 1)
    $match = [regex]::Match($text, '^ClamAV (?<engine>1\.4\.(?:0|[1-9][0-9]{0,4}))/(?<version>[1-9][0-9]{0,9})/(?<weekday>Sun|Mon|Tue|Wed|Thu|Fri|Sat) (?<month>Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (?<day> [1-9]|[12][0-9]|3[01]) (?<time>[0-2][0-9]:[0-5][0-9]:[0-5][0-9]) (?<year>[0-9]{4})$', [Text.RegularExpressions.RegexOptions]::CultureInvariant)
    [uint32]$databaseVersion = 0
    if (-not $match.Success -or -not [uint32]::TryParse($match.Groups['version'].Value, [ref]$databaseVersion)) {
        throw (New-ClamAvProbeException 'parse' 'version-format')
    }
    $dateText = $match.Groups['weekday'].Value + ' ' + $match.Groups['month'].Value + ' ' +
        $match.Groups['day'].Value.Trim() + ' ' + $match.Groups['time'].Value + ' ' + $match.Groups['year'].Value
    [DateTimeOffset]$built = [DateTimeOffset]::MinValue
    $styles = [Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal
    if (-not [DateTimeOffset]::TryParseExact($dateText, 'ddd MMM d HH:mm:ss yyyy',
        [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$built) -or $built.Year -lt 2000) {
        throw (New-ClamAvProbeException 'parse' 'database-date')
    }
    $age = ($ObservedAtUtc - $built).TotalSeconds
    # Fixed release maximum: callers cannot increase it or exempt future dates.
    $freshness = [pscustomobject]@{
        engineVersion = $match.Groups['engine'].Value
        dailyDatabaseVersion = $databaseVersion
        databaseBuiltAtUtc = $built.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
        observedAtUtc = $ObservedAtUtc.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
        ageSeconds = $age
        maximumAgeSeconds = 172800
        timezone = 'UTC'
        scope = 'Connected daemon loaded daily-definition age only; not CDN latest, update success, malware detection or release acceptance'
    }
    if ($age -lt 0) { throw (New-ClamAvProbeException 'freshness' 'database-future' $freshness) }
    if ($age -gt 172800) { throw (New-ClamAvProbeException 'freshness' 'database-stale' $freshness) }
    return $freshness
}

function Read-ClamAvVersionFrame([IO.Stream]$Stream,
        [Threading.CancellationToken]$OperationCancellation = [Threading.CancellationToken]::None) {
    if ($null -eq $Stream -or -not $Stream.CanRead) { throw (New-ClamAvProbeException 'read' 'unreadable-stream') }
    $timeout = [Threading.CancellationTokenSource]::CreateLinkedTokenSource([Threading.CancellationToken[]]@($OperationCancellation))
    $timeout.CancelAfter([TimeSpan]::FromSeconds(5))
    $output = [IO.MemoryStream]::new()
    $buffer = [byte[]]::new(128)
    try {
        # VERSION closes its connection. Require EOF as well as NUL, rejecting
        # additional frames even when they arrive in separate packets.
        while ($true) {
            $read = $Stream.ReadAsync([Memory[byte]]::new($buffer), $timeout.Token).AsTask().GetAwaiter().GetResult()
            if ($read -eq 0) { break }
            if ($output.Length + $read -gt 512) { throw (New-ClamAvProbeException 'read' 'frame-too-large') }
            $output.Write($buffer, 0, $read)
        }
        return ,$output.ToArray()
    } catch {
        $diagnostic = Get-ClamAvProbeDiagnostic $_ 'read'
        if ($diagnostic.category -ceq 'unclassified') { throw (New-ClamAvProbeException 'read' 'read-not-verified') }
        throw
    }
    finally { $timeout.Dispose(); $output.Dispose() }
}

function Get-ClamAvLoadedFreshness([string]$Address, [int]$Port, [DateTimeOffset]$DeadlineUtc) {
    if ($Address -cne '127.0.0.1' -or $Port -lt 1024 -or $Port -gt 65535) {
        throw (New-ClamAvProbeException 'connect' 'invalid-target')
    }
    $remaining = ($DeadlineUtc - [DateTimeOffset]::UtcNow).TotalMilliseconds
    if ($remaining -le 0 -or $DeadlineUtc.Offset -ne [TimeSpan]::Zero) { throw (New-ClamAvProbeException 'connect' 'deadline-expired') }
    $client = [Net.Sockets.TcpClient]::new()
    $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromMilliseconds([Math]::Min(5000, $remaining)))
    $stage = 'connect'
    try {
        $client.ConnectAsync([Net.IPAddress]::Loopback, $Port, $timeout.Token).AsTask().GetAwaiter().GetResult() | Out-Null
        $stream = $client.GetStream()
        $command = [Text.Encoding]::ASCII.GetBytes("zVERSION`0")
        $stage = 'write'
        $stream.WriteAsync([ReadOnlyMemory[byte]]::new($command), $timeout.Token).AsTask().GetAwaiter().GetResult() | Out-Null
        $stage = 'read'
        $frame = Read-ClamAvVersionFrame $stream $timeout.Token
        $stage = 'parse'
        return ConvertFrom-ClamAvVersionFrame $frame ([DateTimeOffset]::UtcNow)
    } catch {
        $diagnostic = Get-ClamAvProbeDiagnostic $_ $stage
        if ($diagnostic.category -ceq 'unclassified') {
            $category = if ($stage -cin @('connect','write')) { 'operation-not-verified' } else { 'unclassified' }
            throw (New-ClamAvProbeException $stage $category)
        }
        throw
    }
    finally { $timeout.Dispose(); $client.Dispose() }
}

function Read-ClamAvCommandPipes([IO.Stream]$OutputStream, [IO.Stream]$ErrorStream,
        [int]$BudgetMilliseconds = 10000, [Threading.CancellationToken]$OperationCancellation = [Threading.CancellationToken]::None) {
    if ($null -eq $OutputStream -or $null -eq $ErrorStream -or -not $OutputStream.CanRead -or -not $ErrorStream.CanRead -or
        $BudgetMilliseconds -lt 1 -or $BudgetMilliseconds -gt 10000) { throw 'Bounded readable scanner identity pipes required.' }
    $timeout = [Threading.CancellationTokenSource]::CreateLinkedTokenSource([Threading.CancellationToken[]]@($OperationCancellation))
    $timeout.CancelAfter($BudgetMilliseconds)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $output = [IO.MemoryStream]::new(); $errorBytes = 0
    $outBuffer = [byte[]]::new(1024); $errBuffer = [byte[]]::new(1024)
    $outTask = $OutputStream.ReadAsync([Memory[byte]]::new($outBuffer), $timeout.Token).AsTask()
    $errTask = $ErrorStream.ReadAsync([Memory[byte]]::new($errBuffer), $timeout.Token).AsTask()
    $outClosed = $false; $errClosed = $false
    try {
        while (-not $outClosed -or -not $errClosed) {
            $pending = @()
            if (-not $outClosed) {
                if ($outTask.IsCompleted) {
                    $count = $outTask.GetAwaiter().GetResult()
                    if ($count -eq 0) { $outClosed = $true }
                    else {
                        if ($output.Length + $count -gt 8192) { throw 'Scanner identity output exceeded its bound.' }
                        $output.Write($outBuffer, 0, $count)
                        $outTask = $OutputStream.ReadAsync([Memory[byte]]::new($outBuffer), $timeout.Token).AsTask()
                    }
                }
                if (-not $outClosed) { $pending += $outTask }
            }
            if (-not $errClosed) {
                if ($errTask.IsCompleted) {
                    $count = $errTask.GetAwaiter().GetResult()
                    if ($count -eq 0) { $errClosed = $true }
                    else {
                        $errorBytes += $count
                        if ($errorBytes -gt 8192) { throw 'Scanner identity diagnostics exceeded their bound.' }
                        $errTask = $ErrorStream.ReadAsync([Memory[byte]]::new($errBuffer), $timeout.Token).AsTask()
                    }
                }
                if (-not $errClosed) { $pending += $errTask }
            }
            if ($pending.Count -gt 0) {
                $remaining = $BudgetMilliseconds - $timer.ElapsedMilliseconds
                if ($remaining -le 0 -or -not [Threading.Tasks.Task]::WhenAny([Threading.Tasks.Task[]]$pending).Wait([int]$remaining)) {
                    throw 'Scanner identity pipes did not close within their bound.'
                }
            }
        }
        return [Text.UTF8Encoding]::new($false, $true).GetString($output.ToArray())
    } catch { throw 'Scanner identity pipe capture failed, timed out or exceeded its bound.' }
    finally { $timeout.Cancel(); $timeout.Dispose(); $output.Dispose() }
}

function Invoke-ClamAvDockerRead([string]$Executable, [string[]]$Arguments) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    $timer = [Diagnostics.Stopwatch]::StartNew(); $started = $false
    try {
        if (-not $process.Start()) { throw 'Scanner identity process did not start.' }
        $started = $true
        $remaining = 10000 - $timer.ElapsedMilliseconds
        $value = Read-ClamAvCommandPipes $process.StandardOutput.BaseStream $process.StandardError.BaseStream ([int]$remaining)
        $remaining = 10000 - $timer.ElapsedMilliseconds
        if ($remaining -le 0 -or -not $process.WaitForExit([int]$remaining) -or $process.ExitCode -ne 0) {
            throw 'Scanner identity command failed or timed out.'
        }
        return $value
    } catch { throw (New-ClamAvProbeException 'identity' 'command-not-verified') }
    finally {
        # Stop only this helper's own CLI process, never the Docker engine or a
        # service. Do not wait without a bound for descendants holding pipes.
        if ($started -and -not $process.HasExited) { try { $process.Kill($true); $process.WaitForExit(1000) | Out-Null } catch {} }
        $process.Dispose()
    }
}

function Get-OwnedClamAvIdentity([string]$DockerExecutable, [string[]]$ComposeArguments, [string]$Project, [int]$Port) {
    if ($Project -cnotmatch '^educore-files-test-[a-f0-9]{12}$') { throw (New-ClamAvProbeException 'identity' 'invalid-target') }
    $container = (Invoke-ClamAvDockerRead $DockerExecutable (@($ComposeArguments) + @('ps','-q','malware-scanner'))).Trim()
    if ($container -cnotmatch '^[a-f0-9]{64}$') { throw (New-ClamAvProbeException 'identity' 'identity-unresolved') }
    # Read only allowlisted non-secret fields; never inspect a complete container
    # configuration or emit its environment. Concatenation detects duplicate TZs.
    $format = '{{ $tz := "" }}{{ $locale := "" }}{{ range .Config.Env }}{{ if eq (printf "%.3s" .) "TZ=" }}{{ $tz = printf "%s%s\n" $tz . }}{{ end }}{{ if eq (printf "%.7s" .) "LC_ALL=" }}{{ $locale = printf "%s%s\n" $locale . }}{{ end }}{{ end }}{"id":{{json .Id}},"imageId":{{json .Image}},"imageReference":{{json .Config.Image}},"project":{{json (index .Config.Labels "com.docker.compose.project")}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"running":{{json .State.Running}},"health":{{json .State.Health.Status}},"timezone":{{json $tz}},"locale":{{json $locale}},"ports":{{json (index .NetworkSettings.Ports "3310/tcp")}}}'
    try { $identity = (Invoke-ClamAvDockerRead $DockerExecutable @('inspect','--format',$format,$container)) | ConvertFrom-Json }
    catch {
        $diagnostic = Get-ClamAvProbeDiagnostic $_ 'identity'
        if ($diagnostic.category -ceq 'unclassified') { throw (New-ClamAvProbeException 'identity' 'identity-schema') }
        throw
    }
    Assert-ClamAvScannerIdentity $identity $Project $Port
    if ($identity.id -cne $container -or (Invoke-ClamAvDockerRead $DockerExecutable @('exec',$container,'date','+%z')).Trim() -cne '+0000') {
        throw (New-ClamAvProbeException 'identity' 'utc-mismatch')
    }
    return $identity
}

function Assert-OwnedClamAvFreshness([string]$DockerExecutable, [string[]]$ComposeArguments, [string]$Project, [int]$Port) {
    try { $identity = Get-OwnedClamAvIdentity $DockerExecutable $ComposeArguments $Project $Port }
    catch {
        $diagnostic = Get-ClamAvProbeDiagnostic $_ 'identity' | ConvertTo-Json -Compress
        throw "Scanner identity was not verified. Safe diagnostic: $diagnostic"
    }
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(180)
    $lastDiagnostic = [pscustomobject]@{schemaVersion=1;stage='probe';category='unclassified'}
    do {
        $freshness = $null
        try {
            $freshness = Get-ClamAvLoadedFreshness '127.0.0.1' $Port $deadline
        } catch {
            $lastDiagnostic = Get-ClamAvProbeDiagnostic $_
            if ([DateTimeOffset]::UtcNow -ge $deadline) {
                $safe = $lastDiagnostic | ConvertTo-Json -Compress
                throw "Scanner did not load daily definitions at most 48 hours old within the bounded wait. Safe diagnostic: $safe"
            }
            $remaining = ($deadline - [DateTimeOffset]::UtcNow).TotalMilliseconds
            if ($remaining -gt 0) { Start-Sleep -Milliseconds ([Math]::Min(1000, [Math]::Ceiling($remaining))) }
        }
        if ($null -ne $freshness) {
            $after = Get-OwnedClamAvIdentity $DockerExecutable $ComposeArguments $Project $Port
            if ($after.id -cne $identity.id -or $after.imageId -cne $identity.imageId) {
                throw 'Scanner changed while loaded-definition freshness was observed.'
            }
            return [pscustomobject]@{
                project = $Project; containerId = $identity.id; imageId = $identity.imageId
                imageReference = $identity.imageReference; engineVersion = $freshness.engineVersion
                dailyDatabaseVersion = $freshness.dailyDatabaseVersion; databaseBuiltAtUtc = $freshness.databaseBuiltAtUtc
                observedAtUtc = $freshness.observedAtUtc; ageSeconds = $freshness.ageSeconds
                maximumAgeSeconds = $freshness.maximumAgeSeconds; timezone = 'UTC'; scope = $freshness.scope
            }
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    $safe = $lastDiagnostic | ConvertTo-Json -Compress
    throw "Scanner freshness deadline expired. Safe diagnostic: $safe"
}
