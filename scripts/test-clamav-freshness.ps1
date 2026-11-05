# Synthetic parser/ownership/stream fixtures only. No Docker, network port,
# signature download, scanner, private file or service acceptance is performed.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'clamav-freshness-common.ps1')
$rejected = 0; $accepted = 0
function Assert-FreshnessRejected([scriptblock]$Action) {
    $denied = $false
    try { & $Action | Out-Null } catch { $denied = $true }
    if (-not $denied) { throw 'Unsafe/incomplete synthetic scanner evidence was accepted.' }
    $script:rejected++
}
function Synthetic-Frame([string]$Value) { return ,[Text.Encoding]::ASCII.GetBytes($Value + "`0") }
$now = [DateTimeOffset]::ParseExact('2026-10-29T12:00:00+00:00', 'yyyy-MM-ddTHH:mm:sszzz', [Globalization.CultureInfo]::InvariantCulture)
$fresh = Synthetic-Frame 'ClamAV 1.4.3/30001/Thu Oct 29 11:00:00 2026'
$result = ConvertFrom-ClamAvVersionFrame $fresh $now
if ($result.engineVersion -cne '1.4.3' -or $result.dailyDatabaseVersion -ne 30001 -or $result.ageSeconds -ne 3600 -or
    $result.maximumAgeSeconds -ne 172800 -or $result.databaseBuiltAtUtc -cne '2026-10-29T11:00:00.0000000+00:00') { throw 'Incorrect parsed loaded-version evidence.' }
$accepted++
$boundary = ConvertFrom-ClamAvVersionFrame (Synthetic-Frame 'ClamAV 1.4.3/30001/Tue Oct 27 12:00:00 2026') $now
if ($boundary.ageSeconds -ne 172800) { throw 'The exact 48-hour boundary must be accepted.' }; $accepted++
foreach ($invalid in @(
    'ClamAV 1.4.3/30001/Tue Oct 27 11:59:59 2026', # 48 hours plus one second
    'ClamAV 1.4.3/30001/Thu Oct 29 12:00:01 2026', # any future timestamp
    'ClamAV 1.4.3', 'ClamAV 1.4.3/0/Thu Oct 29 11:00:00 2026',
    'ClamAV 1.4.3/4294967296/Thu Oct 29 11:00:00 2026',
    'ClamAV 1.4.3/030001/Thu Oct 29 11:00:00 2026',
    'ClamAV 1.5.0/30001/Thu Oct 29 11:00:00 2026',
    'ClamAV 1.4.3-dev/30001/Thu Oct 29 11:00:00 2026',
    'PONG', 'COMMAND UNAVAILABLE', 'UNKNOWN COMMAND', '',
    'ClamAV 1.4.3/30001/Wed Oct 29 11:00:00 2026', # wrong weekday
    'ClamAV 1.4.3/30001/Thu Oct 32 11:00:00 2026',
    'ClamAV 1.4.3/30001/Thu Oct 29 24:00:00 2026',
    'ClamAV 1.4.3/30001/Thu Feb 29 11:00:00 2026', # invalid leap day
    'ClamAV 1.4.3/30001/Thu Oct 29 11:00:00 2026 +0000',
    ' ClamAV 1.4.3/30001/Thu Oct 29 11:00:00 2026',
    'ClamAV 1.4.3/30001/Thu Oct 29 11:00:00 2026 ',
    "ClamAV 1.4.3/30001/Thu Oct 29 11:00:00 2026`nPONG",
    "ClamAV 1.4.3/30001/Thu Oct 29 11:00:00 2026`0PONG", ('x' * 512)
)) { Assert-FreshnessRejected { ConvertFrom-ClamAvVersionFrame (Synthetic-Frame $invalid) $now } }
Assert-FreshnessRejected { ConvertFrom-ClamAvVersionFrame $null $now }
Assert-FreshnessRejected { ConvertFrom-ClamAvVersionFrame ([byte[]]@()) $now }
Assert-FreshnessRejected { ConvertFrom-ClamAvVersionFrame ([Text.Encoding]::ASCII.GetBytes('ClamAV 1.4.3/30001/Thu Oct 29 11:00:00 2026')) $now }
$highByte = [byte[]]$fresh.Clone(); $highByte[0] = 255
Assert-FreshnessRejected { ConvertFrom-ClamAvVersionFrame $highByte $now }
Assert-FreshnessRejected { ConvertFrom-ClamAvVersionFrame $fresh ($now.ToOffset([TimeSpan]::FromHours(5.5))) }
$dayOne = ConvertFrom-ClamAvVersionFrame (Synthetic-Frame 'ClamAV 1.4.3/30001/Thu Oct  1 11:00:00 2026') ($now.AddDays(-28))
if ($dayOne.ageSeconds -ne 3600) { throw 'Ctime space-padded single day was not parsed.' }; $accepted++
Assert-FreshnessRejected { ConvertFrom-ClamAvVersionFrame (Synthetic-Frame 'ClamAV 1.4.3/30001/Thu Oct 1 11:00:00 2026') ($now.AddDays(-28)) }
$culture = [Globalization.CultureInfo]::CurrentCulture
try {
    foreach ($name in @('hi-IN','fr-FR','ar-SA')) {
        [Globalization.CultureInfo]::CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo($name)
        if ((ConvertFrom-ClamAvVersionFrame $fresh $now).ageSeconds -ne 3600) { throw 'Parsing must be invariant.' }; $accepted++
    }
} finally { [Globalization.CultureInfo]::CurrentCulture = $culture }
$memory = [IO.MemoryStream]::new($fresh)
try {
    $frame = Read-ClamAvVersionFrame $memory
    if (-not [Linq.Enumerable]::SequenceEqual([byte[]]$frame, $fresh)) { throw 'Stream bytes changed.' }; $accepted++
} finally { $memory.Dispose() }
$oversized = [IO.MemoryStream]::new([byte[]]::new(513))
try { Assert-FreshnessRejected { Read-ClamAvVersionFrame $oversized } } finally { $oversized.Dispose() }
$closed = [IO.MemoryStream]::new(); $closed.Dispose()
Assert-FreshnessRejected { Read-ClamAvVersionFrame $closed }
$cancelled = [Threading.CancellationTokenSource]::new(); $cancelled.Cancel()
$cancelledStream = [IO.MemoryStream]::new($fresh)
try { Assert-FreshnessRejected { Read-ClamAvVersionFrame $cancelledStream $cancelled.Token } }
finally { $cancelledStream.Dispose(); $cancelled.Dispose() }
# Both native metadata pipes must be bounded even after a command exits. These
# in-memory streams model retained/infinite pipes without starting a process.
if (-not ('ClamAvPendingFixtureStream' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
public sealed class ClamAvPendingFixtureStream : MemoryStream {
    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) {
        return new ValueTask<int>(Wait(cancellationToken));
    }
    private static async Task<int> Wait(CancellationToken token) {
        await Task.Delay(Timeout.Infinite, token);
        return 0;
    }
}
'@
}
$outStream = [IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes('synthetic allowlisted output'))
$errStream = [IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes('synthetic diagnostic must not be returned'))
try {
    if ((Read-ClamAvCommandPipes $outStream $errStream) -cne 'synthetic allowlisted output') { throw 'Captured identity stdout must be exact and exclude stderr.' }; $accepted++
} finally { $outStream.Dispose(); $errStream.Dispose() }
foreach ($excessive in @('stdout','stderr','invalid-utf8')) {
    $outBytes = [byte[]]@(); $errBytes = [byte[]]@()
    if ($excessive -ceq 'stdout') { $outBytes = [byte[]]::new(8193) }
    if ($excessive -ceq 'invalid-utf8') { $outBytes = [byte[]]@(255) }
    if ($excessive -ceq 'stderr') { $errBytes = [byte[]]::new(8193) }
    $outStream = [IO.MemoryStream]::new($outBytes); $errStream = [IO.MemoryStream]::new($errBytes)
    try { Assert-FreshnessRejected { Read-ClamAvCommandPipes $outStream $errStream } }
    finally { $outStream.Dispose(); $errStream.Dispose() }
}
$outStream = [IO.MemoryStream]::new([Text.Encoding]::ASCII.GetBytes('a' * 8192))
$errStream = [IO.MemoryStream]::new([Text.Encoding]::ASCII.GetBytes('b' * 8192))
try {
    if ((Read-ClamAvCommandPipes $outStream $errStream).Length -ne 8192) { throw 'Exact pipe byte limit must pass.' }; $accepted++
    foreach ($invalidBudget in @(0,10001)) { Assert-FreshnessRejected { Read-ClamAvCommandPipes $outStream $errStream $invalidBudget } }
} finally { $outStream.Dispose(); $errStream.Dispose() }
foreach ($pendingPipe in @('stdout','stderr')) {
    $outStream = if ($pendingPipe -ceq 'stdout') { [ClamAvPendingFixtureStream]::new() } else { [IO.MemoryStream]::new() }
    $errStream = if ($pendingPipe -ceq 'stderr') { [ClamAvPendingFixtureStream]::new() } else { [IO.MemoryStream]::new() }
    try { Assert-FreshnessRejected { Read-ClamAvCommandPipes $outStream $errStream 25 } }
    finally { $outStream.Dispose(); $errStream.Dispose() }
}
$pendingFrame = [ClamAvPendingFixtureStream]::new()
$shortTimeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromMilliseconds(25))
try { Assert-FreshnessRejected { Read-ClamAvVersionFrame $pendingFrame $shortTimeout.Token } }
finally { $pendingFrame.Dispose(); $shortTimeout.Dispose() }
# All endpoint refusals happen before constructing/connecting a socket.
foreach ($address in @('localhost','::1','0.0.0.0','192.0.2.1','https://127.0.0.1','')) {
    Assert-FreshnessRejected { Get-ClamAvLoadedFreshness $address 13310 $now }
}
foreach ($port in @(0,80,65536)) { Assert-FreshnessRejected { Get-ClamAvLoadedFreshness '127.0.0.1' $port $now } }
Assert-FreshnessRejected { Get-ClamAvLoadedFreshness '127.0.0.1' 13310 ([DateTimeOffset]::MinValue) } # expired under any valid system clock, no socket
$project = 'educore-files-test-012345abcdef'
$identity = [pscustomobject]@{
    id = 'a' * 64; imageId = 'sha256:' + ('b' * 64); imageReference = 'clamav/clamav:1.4'
    project = $project; service = 'malware-scanner'; running = $true; health = 'healthy'
    timezone = "TZ=UTC`n"; locale = "LC_ALL=C`n"; ports = @([pscustomobject]@{HostIp='127.0.0.1';HostPort='13310'})
}
Assert-ClamAvScannerIdentity $identity $project 13310; $accepted++
foreach ($property in @('id','imageId','imageReference','project','service','running','health','timezone','locale','ports')) {
    $copy = $identity | ConvertTo-Json -Depth 5 | ConvertFrom-Json
    $copy.PSObject.Properties.Remove($property)
    Assert-FreshnessRejected { Assert-ClamAvScannerIdentity $copy $project 13310 }
}
foreach ($change in @(
    @{property='id';value='a'}, @{property='imageReference';value='untrusted/scanner:1.4'},
    @{property='project';value='educore-files-test-ffffffffffff'}, @{property='service';value='postgres'},
    @{property='running';value='true'}, @{property='running';value=$false}, @{property='health';value='starting'},
    @{property='timezone';value="TZ=Asia/Kolkata`n"}, @{property='timezone';value="TZ=UTC`nTZ=UTC`n"},
    @{property='timezone';value="TZ=UTC`nTZ=other`n"}, @{property='locale';value="LC_ALL=fr_FR`n"},
    @{property='ports';value=@([pscustomobject]@{HostIp='0.0.0.0';HostPort='13310'})},
    @{property='ports';value=@([pscustomobject]@{HostIp='127.0.0.1';HostPort='13311'})},
    @{property='ports';value=@($identity.ports + $identity.ports)}
)) {
    $copy = $identity | ConvertTo-Json -Depth 5 | ConvertFrom-Json
    $copy.($change.property) = $change.value
    Assert-FreshnessRejected { Assert-ClamAvScannerIdentity $copy $project 13310 }
}
Assert-FreshnessRejected { Assert-ClamAvScannerIdentity $identity 'school-production' 13310 }
Assert-FreshnessRejected { Assert-ClamAvScannerIdentity $identity $project 13311 }

# The real daemon failure must remain diagnosable without publishing a raw frame,
# arbitrary exception text, updater logs or container configuration.
$diagnosticCases = 0
function Assert-ProbeDiagnostic([scriptblock]$Action, [string]$Stage, [string]$Category) {
    $caught = $false; $diagnostic = $null
    try { & $Action | Out-Null } catch { $caught = $true; $diagnostic = Get-ClamAvProbeDiagnostic $_ }
    if (-not $caught -or $diagnostic.stage -cne $Stage -or $diagnostic.category -cne $Category) {
        throw 'Actual probe failure lost its safe diagnostic category.'
    }
    $script:diagnosticCases++
    return $diagnostic
}
$stale = Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame (Synthetic-Frame 'ClamAV 1.4.3/30001/Tue Oct 27 11:59:59 2026') $now } 'freshness' 'database-stale'
if ($stale.ageSeconds -ne 172801 -or $stale.maximumAgeSeconds -ne 172800 -or $stale.engineVersion -cne '1.4.3' -or
    $stale.dailyDatabaseVersion -ne 30001 -or $stale.databaseBuiltAtUtc -cne '2026-10-27T11:59:59.0000000+00:00' -or
    $stale.observedAtUtc -cne $now.ToString('o')) { throw 'Stale definitions require exact validated age evidence.' }
$future = Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame (Synthetic-Frame 'ClamAV 1.4.3/30001/Thu Oct 29 12:00:01 2026') $now } 'freshness' 'database-future'
if ($future.ageSeconds -ne -1) { throw 'Future definitions must remain refused with their exact observed age.' }
Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame $null $now } 'parse' 'frame-layout' | Out-Null
Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame $highByte $now } 'parse' 'frame-encoding' | Out-Null
Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame (Synthetic-Frame 'PONG') $now } 'parse' 'version-format' | Out-Null
Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame (Synthetic-Frame 'ClamAV 1.4.3/30001/Wed Oct 29 11:00:00 2026') $now } 'parse' 'database-date' | Out-Null
Assert-ProbeDiagnostic { Read-ClamAvVersionFrame $null } 'read' 'unreadable-stream' | Out-Null
$tooLarge = [IO.MemoryStream]::new([byte[]]::new(513))
try { Assert-ProbeDiagnostic { Read-ClamAvVersionFrame $tooLarge } 'read' 'frame-too-large' | Out-Null } finally { $tooLarge.Dispose() }
$pendingDiagnostic = [ClamAvPendingFixtureStream]::new()
$diagnosticTimeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromMilliseconds(25))
try { Assert-ProbeDiagnostic { Read-ClamAvVersionFrame $pendingDiagnostic $diagnosticTimeout.Token } 'read' 'read-not-verified' | Out-Null }
finally { $pendingDiagnostic.Dispose(); $diagnosticTimeout.Dispose() }
Assert-ProbeDiagnostic { Get-ClamAvLoadedFreshness 'invalid.example' 13310 $now } 'connect' 'invalid-target' | Out-Null
Assert-ProbeDiagnostic { Get-ClamAvLoadedFreshness '127.0.0.1' 13310 ([DateTimeOffset]::MinValue) } 'connect' 'deadline-expired' | Out-Null
Assert-ProbeDiagnostic { Assert-ClamAvScannerIdentity $null $project 13310 } 'identity' 'incomplete-evidence' | Out-Null
$wrongIdentity = $identity | ConvertTo-Json -Depth 5 | ConvertFrom-Json
$wrongIdentity.health = 'starting'
Assert-ProbeDiagnostic { Assert-ClamAvScannerIdentity $wrongIdentity $project 13310 } 'identity' 'untrusted-scanner' | Out-Null

$privateMarker = 'SYNTHETIC_PRIVATE_DIAGNOSTIC_MUST_NOT_BE_PUBLISHED'
$unknown = [InvalidOperationException]::new($privateMarker)
$unknown.Data['rawVersion'] = $privateMarker
$safeUnknown = Get-ClamAvProbeDiagnostic $unknown 'connect'
if ($safeUnknown.stage -cne 'connect' -or $safeUnknown.category -cne 'unclassified' -or
    ($safeUnknown | ConvertTo-Json -Compress).Contains($privateMarker)) { throw 'Unknown failures must publish only an allowlisted stage/category.' }
$diagnosticCases++
$wrapped = [Exception]::new($privateMarker, (New-ClamAvProbeException 'parse' 'version-format'))
$safeWrapped = Get-ClamAvProbeDiagnostic $wrapped
if ($safeWrapped.stage -cne 'parse' -or $safeWrapped.category -cne 'version-format' -or
    ($safeWrapped | ConvertTo-Json -Compress).Contains($privateMarker)) { throw 'Safe typed diagnostics must survive bounded exception wrapping without its raw text.' }
$diagnosticCases++

foreach ($mutate in @(
    { param($c) $c | Add-Member rawFrame $privateMarker },
    { param($c) $c.stage = 'FRESHNESS' },
    { param($c) $c.category = $privateMarker },
    { param($c) $c.engineVersion = $privateMarker },
    { param($c) $c.engineVersion = "1.4.3`n" },
    { param($c) $c.dailyDatabaseVersion = '30001' },
    { param($c) $c.databaseBuiltAtUtc = $privateMarker },
    { param($c) $c.observedAtUtc = '2026-10-29T12:00:00.0000000+05:30' },
    { param($c) $c.ageSeconds = [double]::NaN },
    { param($c) $c.ageSeconds = [double]172800 },
    { param($c) $c.maximumAgeSeconds = 172801 }
)) {
    # Keep the original UInt32/Double types while cloning the in-memory object.
    $copy = [pscustomobject]@{}; foreach ($property in $stale.PSObject.Properties) { $copy | Add-Member $property.Name $property.Value }
    & $mutate $copy
    $forged = [InvalidOperationException]::new($privateMarker)
    $forged.Data['educore.clamav.diagnostic.v1'] = $copy
    $safeForged = Get-ClamAvProbeDiagnostic $forged
    if ($safeForged.stage -cne 'probe' -or $safeForged.category -cne 'unclassified' -or
        ($safeForged | ConvertTo-Json -Compress).Contains($privateMarker)) { throw 'Forged or invalid diagnostic details must not be emitted.' }
    $diagnosticCases++
}

# Envelope metadata distinguishes framing/clock failures without reproducing any
# payload bytes. Even a synthetic frame's content must never enter diagnostics.
$frameCases = @(
    @{frame=[byte[]]@();length=0;terminator='NONE';nul=0;lf=0;cr=0},
    @{frame=[byte[]]@(0);length=1;terminator='NUL';nul=1;lf=0;cr=0},
    @{frame=[byte[]]@(65,10);length=2;terminator='LF';nul=0;lf=1;cr=0},
    @{frame=[byte[]]@(65,13);length=2;terminator='CR';nul=0;lf=0;cr=1},
    @{frame=[byte[]]@(65);length=1;terminator='OTHER';nul=0;lf=0;cr=0},
    @{frame=[byte[]]@(65,0,10,13,10);length=5;terminator='LF';nul=1;lf=2;cr=1}
)
foreach ($case in $frameCases) {
    $layout = Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame $case.frame $now } 'parse' 'frame-layout'
    if ($layout.frameLength -ne $case.length -or $layout.terminatorKind -cne $case.terminator -or
        $layout.nulCount -ne $case.nul -or $layout.lineFeedCount -ne $case.lf -or $layout.carriageReturnCount -ne $case.cr -or
        $layout.observationOffsetKind -cne 'UTC' -or @($layout.PSObject.Properties).Count -ne 9) {
        throw 'Exact bounded frame-envelope diagnostics required; payload content is not evidence metadata.'
    }
}
$clockLayout = Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame $fresh ($now.ToOffset([TimeSpan]::FromHours(5.5))) } 'parse' 'frame-layout'
if ($clockLayout.terminatorKind -cne 'NUL' -or $clockLayout.nulCount -ne 1 -or $clockLayout.observationOffsetKind -cne 'NON_UTC') {
    throw 'A non-UTC observation must be distinguished without publishing a timestamp or offset value.'
}
$maxFrame = [Text.Encoding]::ASCII.GetBytes(('A' * 511) + "`n")
$maxLayout = Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame $maxFrame $now } 'parse' 'frame-layout'
if ($maxLayout.frameLength -ne 512 -or $maxLayout.terminatorKind -cne 'LF' -or $maxLayout.lineFeedCount -ne 1) { throw 'Exact diagnostic frame bound required.' }
foreach ($unavailableFrame in @($null, [byte[]]::new(513))) {
    $bare = Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame $unavailableFrame $now } 'parse' 'frame-layout'
    if (@($bare.PSObject.Properties).Count -ne 3) { throw 'Null or oversized frames must not create envelope evidence.' }
}
$syntheticPayload = [Text.Encoding]::ASCII.GetBytes($privateMarker + "`n")
$privateLayout = Assert-ProbeDiagnostic { ConvertFrom-ClamAvVersionFrame $syntheticPayload $now } 'parse' 'frame-layout'
if (($privateLayout | ConvertTo-Json -Compress).Contains($privateMarker)) { throw 'Frame content must never appear in diagnostic output.' }
$envelope = $layout
foreach ($mutate in @(
    { param($c) $c | Add-Member rawFrame $privateMarker },
    { param($c) $c.PSObject.Properties.Remove('nulCount') },
    { param($c) $c.frameLength = -1 },
    { param($c) $c.frameLength = 513 },
    { param($c) $c.frameLength = '5' },
    { param($c) $c.frameLength = 0 },
    { param($c) $c.terminatorKind = 'NONE' },
    { param($c) $c.terminatorKind = 'lf' },
    { param($c) $c.terminatorKind = $privateMarker },
    { param($c) $c.nulCount = -1 },
    { param($c) $c.nulCount = 6 },
    { param($c) $c.nulCount = '1' },
    { param($c) $c.nulCount = 3; $c.lineFeedCount = 3 },
    { param($c) $c.terminatorKind = 'NUL'; $c.nulCount = 0 },
    { param($c) $c.lineFeedCount = 0 },
    { param($c) $c.terminatorKind = 'CR'; $c.carriageReturnCount = 0 },
    { param($c) $c.terminatorKind = 'OTHER'; $c.carriageReturnCount = 2 },
    { param($c) $c.terminatorKind = 'NUL' },
    { param($c) $c.observationOffsetKind = $privateMarker },
    { param($c) $c.observationOffsetKind = 'utc' },
    { param($c) $c.category = 'frame-encoding' },
    { param($c) $c.stage = 'freshness' }
)) {
    $copy = [pscustomobject]@{}; foreach ($property in $envelope.PSObject.Properties) { $copy | Add-Member $property.Name $property.Value }
    & $mutate $copy
    $forged = [InvalidOperationException]::new($privateMarker)
    $forged.Data['educore.clamav.diagnostic.v1'] = $copy
    $safeForged = Get-ClamAvProbeDiagnostic $forged
    if ($safeForged.stage -cne 'probe' -or $safeForged.category -cne 'unclassified' -or
        ($safeForged | ConvertTo-Json -Compress).Contains($privateMarker)) { throw 'Inconsistent or forged envelope diagnostics must be discarded.' }
    $diagnosticCases++
}
Write-Output "ClamAV freshness synthetic fixtures passed: $accepted valid cases and $rejected refusals. No Docker, network connection, signature update, real scanner, document/service or release acceptance claimed."
Write-Output "ClamAV safe diagnostic fixtures passed: $diagnosticCases category/detail/sanitization checks. No raw diagnostics, daemon or signature freshness acceptance claimed."
