# Synthetic TCP fixtures for the actual NetworkStream probe. No ClamAV daemon,
# signature update, Docker, document or production-service acceptance is claimed.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'clamav-freshness-common.ps1')
if (-not ('ClamAvOwnedLoopbackFixture' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
public sealed class ClamAvOwnedLoopbackFixture : IDisposable {
    private readonly TcpListener listener;
    private readonly CancellationTokenSource timeout;
    private readonly Task worker;
    public int Port { get; }
    public bool ExactCommand { get; private set; }
    public bool Stopped { get; private set; }
    public bool WorkerCompleted => worker.IsCompleted;
    public ClamAvOwnedLoopbackFixture(byte[] response, int initialDelay, int chunkSize, int chunkDelay, int holdOpen) {
        if (response == null || response.Length > 513 || initialDelay < 0 || initialDelay > 400 ||
            chunkSize < 1 || chunkSize > 513 || chunkDelay < 0 || chunkDelay > 50 || holdOpen < 0 || holdOpen > 400)
            throw new InvalidOperationException("Bounded synthetic loopback fixture required.");
        timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start(1);
        Port = ((IPEndPoint)listener.LocalEndpoint).Port;
        if (Port < 1024 || Port > 65535) { listener.Stop(); timeout.Dispose(); throw new InvalidOperationException("Unprivileged loopback fixture port required."); }
        worker = Task.Run(async () => {
            using var client = await listener.AcceptTcpClientAsync(timeout.Token);
            using var stream = client.GetStream();
            byte[] expected = Encoding.ASCII.GetBytes("zVERSION\0");
            byte[] command = new byte[expected.Length];
            int count = 0;
            while (count < command.Length) {
                int read = await stream.ReadAsync(command.AsMemory(count), timeout.Token);
                if (read == 0) throw new InvalidOperationException("Synthetic probe closed before a complete command.");
                count += read;
            }
            if (!command.AsSpan().SequenceEqual(expected) || stream.DataAvailable)
                throw new InvalidOperationException("Synthetic fixture received an unexpected command.");
            ExactCommand = true;
            try {
                if (initialDelay > 0) await Task.Delay(initialDelay, timeout.Token);
                for (int offset = 0; offset < response.Length; offset += chunkSize) {
                    int length = Math.Min(chunkSize, response.Length - offset);
                    await stream.WriteAsync(response.AsMemory(offset, length), timeout.Token);
                    await stream.FlushAsync(timeout.Token);
                    if (offset + length < response.Length && chunkDelay > 0) await Task.Delay(chunkDelay, timeout.Token);
                }
                if (holdOpen > 0) await Task.Delay(holdOpen, timeout.Token);
                client.Client.Shutdown(SocketShutdown.Send);
            } catch (IOException) {
                // A deliberately refused or cancelled probe can close its peer.
            } catch (SocketException) {
                // Exact command and completed cleanup are still asserted below.
            }
        });
    }
    public void WaitForOwnedWorker() {
        if (!worker.Wait(TimeSpan.FromSeconds(10))) throw new InvalidOperationException("Synthetic fixture worker exceeded its bound.");
        worker.GetAwaiter().GetResult();
    }
    public void Dispose() {
        timeout.Cancel();
        listener.Stop();
        Stopped = true;
        try { worker.Wait(TimeSpan.FromSeconds(1)); } catch { }
        timeout.Dispose();
    }
}
'@
}

$now = [DateTimeOffset]::UtcNow
$built = $now.AddMinutes(-1)
$culture = [Globalization.CultureInfo]::InvariantCulture
$version = 'ClamAV 1.4.3/30001/' + $built.ToString('ddd MMM ', $culture) +
    $built.Day.ToString($culture).PadLeft(2, ' ') + $built.ToString(' HH:mm:ss yyyy', $culture) + "`0"
$frame = [Text.Encoding]::ASCII.GetBytes($version)
$canonical = ConvertFrom-ClamAvVersionFrame $frame $now
if ($canonical.dailyDatabaseVersion -ne 30001) { throw 'Canonical synthetic frame prerequisite failed.' }
$cases = @(
    @{name='delayed-fragmented-canonical';bytes=$frame;initial=250;chunk=7;delay=20;hold=0;deadline=8000;accept=$true;stage='';category=''},
    @{name='empty-eof';bytes=[byte[]]@();initial=0;chunk=1;delay=0;hold=0;deadline=8000;accept=$false;stage='parse';category='frame-layout'},
    @{name='missing-nul';bytes=[byte[]]$frame[0..($frame.Length-2)];initial=0;chunk=7;delay=5;hold=0;deadline=8000;accept=$false;stage='parse';category='frame-layout'},
    @{name='newline-instead-of-nul';bytes=[byte[]]($frame[0..($frame.Length-2)]+10);initial=0;chunk=7;delay=5;hold=0;deadline=8000;accept=$false;stage='parse';category='frame-layout'},
    @{name='second-frame-later-packet';bytes=[byte[]]($frame+[Text.Encoding]::ASCII.GetBytes("PONG`0"));initial=0;chunk=$frame.Length;delay=50;hold=0;deadline=8000;accept=$false;stage='parse';category='frame-encoding'},
    @{name='cancel-before-reply';bytes=$frame;initial=350;chunk=513;delay=0;hold=0;deadline=150;accept=$false;stage='read';category='read-not-verified'},
    @{name='nul-without-eof';bytes=$frame;initial=0;chunk=513;delay=0;hold=350;deadline=150;accept=$false;stage='read';category='read-not-verified'},
    @{name='oversized-response';bytes=[Text.Encoding]::ASCII.GetBytes(('A'*512)+"`0");initial=0;chunk=32;delay=1;hold=0;deadline=8000;accept=$false;stage='read';category='frame-too-large'}
)
$passed = 0
foreach ($case in $cases) {
    $server = $null; $accepted = $false; $diagnostic = $null
    try {
        $server = [ClamAvOwnedLoopbackFixture]::new($case.bytes,$case.initial,$case.chunk,$case.delay,$case.hold)
        try {
            $observations = @(Get-ClamAvLoadedFreshness '127.0.0.1' $server.Port ([DateTimeOffset]::UtcNow.AddMilliseconds($case.deadline)))
            if ($observations.Count -ne 1 -or $observations[0] -isnot [pscustomobject]) { throw 'Probe emitted an unexpected result or native async artifact.' }
            $freshness = $observations[0]
            $accepted = $freshness.dailyDatabaseVersion -eq 30001 -and $freshness.maximumAgeSeconds -eq 172800 -and $freshness.ageSeconds -ge 0
        } catch { $diagnostic = Get-ClamAvProbeDiagnostic $_ }
        $server.WaitForOwnedWorker()
        if (-not $server.ExactCommand -or -not $server.WorkerCompleted) { throw 'Owned fixture command/worker verification failed.' }
        if ($case.accept) {
            if (-not $accepted -or $null -ne $diagnostic) { throw 'Valid delayed canonical network response was not verified.' }
        } elseif ($accepted -or $null -eq $diagnostic -or $diagnostic.stage -cne $case.stage -or $diagnostic.category -cne $case.category) {
            throw 'Unsafe or incomplete network response was not refused with its expected category.'
        }
        $passed++
    } catch {
        # Only controlled case names and already sanitized metadata are emitted.
        $safe = if ($null -ne $diagnostic) { $diagnostic | ConvertTo-Json -Compress } else { 'none' }
        throw "Synthetic loopback case $($case.name) failed. Safe diagnostic: $safe"
    } finally {
        if ($null -ne $server) {
            $server.Dispose()
            if (-not $server.Stopped -or -not $server.WorkerCompleted) { throw 'Owned synthetic listener/worker cleanup did not complete.' }
        }
    }
}
Write-Output "ClamAV synthetic loopback fixtures passed: $passed exact-command, delayed/fragmented, NUL/EOF, cancellation, trailing-frame and size checks. Every owned listener and worker was stopped. No ClamAV daemon, update, scan, Docker, document or release acceptance claimed."
