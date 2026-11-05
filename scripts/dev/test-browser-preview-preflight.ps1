$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'browser-preview-preflight.ps1') -NoProbe
$assertions = 0
function Assert-PreviewEvidence([string]$Kind, [int]$Status, [string]$Body, [string]$State, [string]$Health) {
    $result = Get-PreviewProbeEvidence $Kind $Status $Body
    if ($result.state -cne $State -or $result.health -cne $Health -or
        $result.httpStatus -ne $Status -or $result.kind -cne $Kind) {
        throw "Local preview evidence is inaccurate at bounded case $script:assertions ($Kind / $Status)."
    }
    if (($result | ConvertTo-Json -Compress) -match 'synthetic-private-must-not-escape|STOPPED') { throw 'Private body or false stopped-state escaped.' }
    $script:assertions++
}
Assert-PreviewEvidence 'web' 200 '' 'RESPONDING' 'NOT_VERIFIED'
Assert-PreviewEvidence 'web' 0 '' 'UNKNOWN' 'NOT_VERIFIED'
Assert-PreviewEvidence 'liveness' 0 '' 'UNKNOWN' 'NOT_VERIFIED'
Assert-PreviewEvidence 'readiness' 0 '' 'UNKNOWN' 'NOT_VERIFIED'
Assert-PreviewEvidence 'liveness' 200 '{"status":"UP"}' 'RESPONDING' 'UP'
Assert-PreviewEvidence 'readiness' 200 '{"status":"UP"}' 'RESPONDING' 'UP'
Assert-PreviewEvidence 'readiness' 503 '{"status":"DOWN"}' 'RESPONDING' 'UNAVAILABLE'
Assert-PreviewEvidence 'liveness' 200 '{"status":"DOWN"}' 'RESPONDING' 'DOWN'
Assert-PreviewEvidence 'liveness' 200 '{"status":"OUT_OF_SERVICE"}' 'RESPONDING' 'OUT_OF_SERVICE'
Assert-PreviewEvidence 'liveness' 200 '{"status":"UNKNOWN"}' 'RESPONDING' 'UNKNOWN'
Assert-PreviewEvidence 'liveness' 200 '{"status":"up"}' 'RESPONDING' 'NOT_VERIFIED'
Assert-PreviewEvidence 'readiness' 200 '{"status":"synthetic-private-must-not-escape"}' 'RESPONDING' 'NOT_VERIFIED'
Assert-PreviewEvidence 'liveness' 200 '{"status":' 'RESPONDING' 'NOT_VERIFIED'
Assert-PreviewEvidence 'readiness' 200 ('x' * 32769) 'RESPONDING' 'NOT_VERIFIED'
Assert-PreviewEvidence 'readiness' 403 'synthetic-private-must-not-escape' 'RESPONDING' 'NOT_VERIFIED'
foreach ($body in @('[{"status":"UP"}]', '{"status":["UP"]}', 'null', '"UP"')) {
    Assert-PreviewEvidence 'liveness' 200 $body 'RESPONDING' 'NOT_VERIFIED'
}
foreach ($invalidProbe in @(@{kind='other';status=200},@{kind='web';status=-1},@{kind='web';status=99},@{kind='web';status=600})) {
    $rejected=$false
    try { Get-PreviewProbeEvidence $invalidProbe.kind $invalidProbe.status '' | Out-Null } catch { $rejected=$true }
    if (-not $rejected) { throw 'Invalid probe metadata accepted.' }
    $assertions++
}
Write-Output "Preview preflight fixtures passed: $assertions status/unknown/malformed/privacy cases. No network or browser/production acceptance claimed."
