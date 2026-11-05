param([switch]$NoProbe)
$ErrorActionPreference = 'Stop'

# Read-only diagnostics, not a launcher, recovery routine or production gate.
# Failed probes cannot prove that a process is stopped.
function Get-PreviewProbeEvidence([string]$Kind, [int]$Status, [string]$Body) {
    if ($Kind -cnotin @('web','liveness','readiness') -or $Status -lt 0 -or $Status -gt 599 -or ($Status -ne 0 -and $Status -lt 100)) {
        throw 'Invalid local probe metadata.'
    }
    $state = if ($Status -ge 100) { 'RESPONDING' } else { 'UNKNOWN' }
    $health = 'NOT_VERIFIED'
    if ($Kind -cne 'web' -and $Status -eq 200) {
        if ($null -ne $Body -and $Body.Length -le 32768) {
            try {
                $parsed = ConvertFrom-Json -InputObject $Body -NoEnumerate -ErrorAction Stop
                if ($null -ne $parsed -and $parsed.GetType().FullName -ceq 'System.Management.Automation.PSCustomObject' -and $parsed.status -is [string]) {
                    if ($parsed.status -ceq 'UP') { $health = 'UP' }
                    elseif ($parsed.status -cin @('DOWN','OUT_OF_SERVICE','UNKNOWN')) { $health = $parsed.status }
                }
            } catch { }
        }
    } elseif ($Kind -cne 'web' -and $Status -eq 503) {
        $health = 'UNAVAILABLE'
    }
    return [pscustomobject]@{ kind=$Kind; httpStatus=$Status; state=$state; health=$health }
}

if ($NoProbe) { return }
$evidence = @()
foreach ($probe in @(
    @{kind='web';url='http://127.0.0.1:3000/portal'},
    @{kind='liveness';url='http://127.0.0.1:8080/actuator/health/liveness'},
    @{kind='readiness';url='http://127.0.0.1:8080/actuator/health/readiness'}
)) {
    $status = 0
    $body = $null
    try {
        $response = Invoke-WebRequest -Uri $probe.url -TimeoutSec 8 -MaximumRedirection 0 -ErrorAction Stop
        $status = [int]$response.StatusCode
        if ($probe.kind -cne 'web') {
            if ($response.Content -is [byte[]]) {
                if ($response.Content.Length -le 32768) { $body = [Text.UTF8Encoding]::new($false,$true).GetString($response.Content) }
            } else { $body = $response.Content }
        }
    } catch {
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        # Do not print raw bodies, exceptions, headers, credentials or app logs.
    }
    $evidence += Get-PreviewProbeEvidence $probe.kind $status $body
}
[pscustomobject]@{
    format=1
    checkedAt=[datetime]::UtcNow.ToString('o')
    probes=$evidence
    readyForCoreAcceptance=($evidence[0].httpStatus -eq 200 -and $evidence[1].health -ceq 'UP')
    privateServiceReadiness=$evidence[2].health
    productionAcceptance='NOT_VERIFIED'
    automaticRecovery='NEVER'
} | ConvertTo-Json -Depth 4
