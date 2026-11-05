# Synthetic in-memory evidence fixtures only; no Docker, application, network,
# credentials, policy exception or real final-image absence is established here.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-dependency-exposure-common.ps1')
. (Join-Path $PSScriptRoot 'minimatch-exposure-report-common.ps1')
. (Join-Path $PSScriptRoot 'production-dependency-policy-common.ps1')
$checked = 0; $rejected = 0
function Assert-Evidence([bool]$Condition) {
    if (-not $Condition) { throw 'Synthetic minimatch evidence fixture failed.' }
    $script:checked++
}
function Assert-Denied([scriptblock]$Action) {
    $denied = $false
    try { & $Action | Out-Null } catch { $denied = $true }
    if (-not $denied) { throw 'Malformed or miscorrelated minimatch evidence was accepted.' }
    $script:rejected++
}
function Test-Content([string]$Path,[string]$Text) {
    return Test-RuntimeDependencyContent $Path ([Text.Encoding]::UTF8.GetBytes($Text)) 'minimatch'
}
foreach ($path in @('app/node_modules/minimatch/index.js',
    'app/node_modules/.pnpm/minimatch@10.2.6/node_modules/minimatch/package.json',
    'app/node_modules/next/dist/compiled/minimatch/index.js','BOOT-INF/classes/MINIMATCH/index.js')) {
    Assert-Evidence (Test-RuntimeDependencyPath $path 'minimatch')
}
foreach ($path in @('app/node_modules/picomatch/index.js','app/node_modules/next/dist/compiled/glob/glob.js',
    'app/node_modules/minimatchery/index.js','app/node_modules/not-minimatch/index.js')) {
    Assert-Evidence (-not (Test-RuntimeDependencyPath $path 'minimatch'))
}
foreach ($path in @('../vendor.js','/app/vendor.js','C:/vendor.js','app\vendor.js','app/../vendor.js',
    '',"app/vendor`n.js")) { Assert-Denied { Test-RuntimeDependencyPath $path 'minimatch' } }
foreach ($target in @('MINIMATCH','minimatch ','unknown')) {
    Assert-Denied { Test-RuntimeDependencyPath 'app/package.json' $target }
}
foreach ($text in @('{"name":"minimatch","version":"3.1.5"}', '{"name":"MiniMatch"}',
    '{"name":"vendor","dependencies":{"minimatch":"10.2.6"}}',
    '{"name":"vendor","optionalDependencies":{"MINIMATCH":"3.1.5"}}')) {
    Assert-Evidence (Test-Content 'app/package.json' $text)
}
foreach ($text in @('{"name":"next","devDependencies":{"minimatch":"3.1.5"}}',
    '{"name":"picomatch","description":"Different package; not minimatch"}')) {
    Assert-Evidence (-not (Test-Content 'app/package.json' $text))
}
foreach ($text in @('require("minimatch")','import { minimatch } from "minimatch"',
    'var matching = vendor.Minimatch; entry.minimatch = new matching(pattern, options);',
    'firstPhasePreProcess(parts) { return parts; }','secondPhasePreProcess(parts) { return parts; }',
    'vendor.levelTwoFileOptimize(parts);','const sourceMap = { sources: ["MINIMATCH/index.js"] };')) {
    Assert-Evidence (Test-Content 'app/vendor.js' $text)
}
foreach ($text in @('const optimizationLevel = 2; const unrelated = [];',
    'const globParts = []; const matcher = "picomatch";')) {
    Assert-Evidence (-not (Test-Content 'app/vendor.js' $text))
}
foreach ($text in @('{invalid','[]','{"name":12}','{"name":""}',
    '{"dependencies":null}','{"optionalDependencies":[]}',
    '{"name":"minimatch","dependencies":[]}')) {
    Assert-Denied { Test-Content 'app/package.json' $text }
}
Assert-Denied { Test-RuntimeDependencyContent 'app/vendor.js' ([byte[]](255,254)) 'minimatch' }
Assert-Denied { Test-RuntimeDependencyContent '../vendor.js' ([Text.Encoding]::UTF8.GetBytes('const value=1')) 'minimatch' }

function New-ZipBytes([System.Collections.IDictionary]$Entries) {
    $memory = [IO.MemoryStream]::new()
    try {
        $zip = [IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Create,$true)
        try {
            foreach ($name in $Entries.Keys) {
                $stream = $zip.CreateEntry($name).Open()
                try {
                    [byte[]]$bytes = if ($Entries[$name] -is [byte[]]) { $Entries[$name] } else { [Text.Encoding]::UTF8.GetBytes($Entries[$name]) }
                    $stream.Write($bytes,0,$bytes.Length)
                } finally { $stream.Dispose() }
            }
        } finally { $zip.Dispose() }
        return ,$memory.ToArray()
    } finally { $memory.Dispose() }
}
function Inspect-Zip([byte[]]$Bytes,[hashtable]$Counts = @{archiveMembers=0;contentMembers=0},[int]$Depth = 0) {
    $state = @{present=$false}; $diagnostics=New-MinimatchDiagnosticState; $stream = [IO.MemoryStream]::new($Bytes,$false)
    try { Inspect-RuntimeDependencyJar $stream $Counts $Depth 'minimatch' $state @('app/synthetic.jar') $diagnostics }
    finally { $stream.Dispose() }
    return @{present=$state.present;counts=$Counts;diagnostics=$diagnostics}
}
$clean = Inspect-Zip (New-ZipBytes @{ 'BOOT-INF/classes/synthetic.js'='const synthetic=1;' })
Assert-Evidence (-not $clean.present -and $clean.counts.archiveMembers -eq 1 -and $clean.counts.contentMembers -eq 1)
$manifest = Inspect-Zip (New-ZipBytes @{ 'BOOT-INF/classes/vendor/package.json'='{"name":"minimatch"}' })
Assert-Evidence $manifest.present
$vendor = Inspect-Zip (New-ZipBytes @{ 'BOOT-INF/classes/vendor.js'='var constructor = bundled.Minimatch;' })
Assert-Evidence $vendor.present
$nested = New-ZipBytes @{ 'BOOT-INF/lib/synthetic.jar'=(New-ZipBytes @{ 'vendor.js'='firstPhasePreProcess(parts) {}' }) }
$found = Inspect-Zip $nested
Assert-Evidence ($found.present -and $found.counts.archiveMembers -eq 2 -and $found.counts.contentMembers -eq 1)
Assert-Evidence ($found.diagnostics.totalFindings -eq 1 -and $found.diagnostics.findings[0].locationChain.Count -eq 3)
Assert-Evidence ($found.diagnostics.findings[0].matchKind -ceq 'optimizer-method')
Assert-Evidence ($manifest.diagnostics.findings[0].matchKind -ceq 'manifest')
Assert-Denied { Inspect-Zip (New-ZipBytes @{ 'package.json'='{invalid' }) }
Assert-Denied { Inspect-Zip (New-ZipBytes ([ordered]@{ 'vendor.js'='var matcher=vendor.Minimatch;';'later/package.json'='{invalid' })) }
Assert-Denied { Inspect-Zip (New-ZipBytes @{ '../vendor.js'='const synthetic=1' }) }
Assert-Denied { Inspect-Zip ([Text.Encoding]::UTF8.GetBytes('not an archive')) }
Assert-Denied { Inspect-Zip $nested @{archiveMembers=0;contentMembers=0} 3 }
Assert-Denied { Inspect-Zip $nested @{archiveMembers=200000;contentMembers=0} }
foreach ($state in @($null,@{present='false'},@{present=$false;extra='unreviewed'})) {
    Assert-Denied {
        $stream=[IO.MemoryStream]::new((New-ZipBytes @{'synthetic.js'='const synthetic=1'}),$false)
        try { Inspect-RuntimeDependencyJar $stream @{archiveMembers=0;contentMembers=0} 0 'minimatch' $state }
        finally { $stream.Dispose() }
    }
}
Assert-Denied { Read-BoundedExposureBytes ([IO.MemoryStream]::new([byte[]](1,2))) 3 }
Assert-Denied { Read-BoundedExposureBytes ([IO.MemoryStream]::new([byte[]](1))) (64MB + 1) }

function New-Source {
    return @{commit=('a'*40);dockerfileSha256=('c'*64);lockfileSha256=('d'*64)}
}
Assert-MinimatchExposureSourceIdentity (New-Source) (New-Source); $checked++
foreach ($mutation in @({param($v)$v.commit='b'*40},{param($v)$v.dockerfileSha256='e'*64},
    {param($v)$v.lockfileSha256='e'*64},{param($v)$v.externalPath='C:/untrusted'},
    {param($v)$v.Remove('commit')})) {
    Assert-Denied { $after=New-Source; & $mutation $after; Assert-MinimatchExposureSourceIdentity (New-Source) $after }
}
function New-Report([string]$Service='web',[bool]$Present=$false) {
    return @{format=1;inspectionTarget='minimatch';result=$(if($Present){'present'}else{'absent'});service=$Service;
        commit=('a'*40);imageId=('sha256:'+('b'*64));dockerfileSha256=('c'*64);lockfileSha256=('d'*64);
        source='final-image-docker-export';inspectorVersion=1;completedAt=[datetime]::UtcNow.ToString('o');
        counts=@{filesystemMembers=12;manifests=1;javascript=1;jars=1;archiveMembers=2;contentMembers=1};
        affectedLibraryPresent=$Present;cleanupSucceeded=$true}
}
function Assert-Report($Report,[string]$Service='web') {
    Assert-MinimatchExposureReport $Report $Service ('a'*40) ('c'*64) ('d'*64) ('sha256:'+('b'*64))
}
foreach ($service in @('web','api')) { foreach ($present in @($false,$true)) {
    Assert-Report (New-Report $service $present) $service; $checked++
} }
$jsonParameters=@{AsHashtable=$true}
if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $jsonParameters.DateKind='String' }
Assert-Report ((New-Report | ConvertTo-Json -Depth 4) | ConvertFrom-Json @jsonParameters); $checked++
foreach ($mutation in @(
    {param($r)$r.commit='e'*40},{param($r)$r.imageId='sha256:'+('e'*64)},
    {param($r)$r.dockerfileSha256='e'*64},{param($r)$r.lockfileSha256='e'*64},
    {param($r)$r.source='local-standalone'},{param($r)$r.service='api'},
    {param($r)$r.inspectionTarget='braces'},{param($r)$r.inspectionTarget='MINIMATCH'},
    {param($r)$r.result='unknown'},{param($r)$r.result='present'},{param($r)$r.result='ABSENT'},
    {param($r)$r.affectedLibraryPresent='false'},{param($r)$r.cleanupSucceeded=$false},
    {param($r)$r.format='1'},{param($r)$r.inspectorVersion=2},
    {param($r)$r.completedAt='invalid'},{param($r)$r.completedAt=[datetime]::UtcNow.AddHours(-7).ToString('o')},
    {param($r)$r.completedAt=[datetime]::UtcNow.AddHours(1).ToString('o')},
    {param($r)$r.counts.manifests=0},{param($r)$r.counts.javascript=0},
    {param($r)$r.counts.filesystemMembers=0},{param($r)$r.counts.archiveMembers=200001},
    {param($r)$r.counts.jars='1'},{param($r)$r.counts.contentMembers=-1},
    {param($r)$r.rawEnvironment='synthetic-not-published'},{param($r)$r.Remove('counts')}
)) { Assert-Denied { $report=New-Report; & $mutation $report; Assert-Report $report } }
Assert-Denied { $report=New-Report 'api';$report.counts.jars=0;Assert-Report $report 'api' }
Assert-Denied { $report=New-Report 'api';$report.counts.archiveMembers=0;Assert-Report $report 'api' }
Assert-Denied { Assert-Report $null }
# The new evidence cannot masquerade as the existing owner-approved braces proof.
Assert-Denied { Assert-FinalDependencyExposure (New-Report) 'web' ('a'*40) ('c'*64) ('d'*64) ('sha256:'+('b'*64)) }

foreach ($location in @(@('opt/yarn-v1.22.22/lib/cli.js'),@('app/[route]/(group)/next@16.4.1+synthetic.js'),
    @('app/outer.jar','BOOT-INF/lib/inner.jar','vendor.js'),@('app/'+('a'*505)+'.js'))) {
    Assert-MinimatchDiagnosticLocation $location; $checked++
}
foreach ($location in @(@(),@('../vendor.js'),@('/app/vendor.js'),@('app/vendor;private.js'),
    @('app/private name.js'),@('app/.env'),@('app/script.js','inner.js'),@('app/'+('a'*506)+'.js'),@('a.jar','b.jar','c.jar','d.jar','e.js'))) {
    Assert-Denied { Assert-MinimatchDiagnosticLocation $location }
}
$diagnostics=New-MinimatchDiagnosticState
$fixedBytes=[Text.Encoding]::UTF8.GetBytes('var synthetic=vendor.Minimatch;')
Add-MinimatchFileDiagnostic $diagnostics @('opt/yarn-v1.22.22/lib/cli.js') $fixedBytes 'minimatch-reference'
$expectedHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($fixedBytes)).ToLowerInvariant()
Assert-Evidence ($diagnostics.findings[0].sha256 -ceq $expectedHash -and $diagnostics.totalFindings -eq 1)
foreach ($index in 1..33) { Add-MinimatchFileDiagnostic $diagnostics @("app/synthetic-$index.js") $fixedBytes 'minimatch-reference' }
Assert-Evidence ($diagnostics.totalFindings -eq 34 -and $diagnostics.findings.Count -eq 32 -and $diagnostics.truncated)
$present=New-Report 'web' $true
$truncated=New-MinimatchDiagnosticReport $present ('e'*64) $diagnostics
Assert-MinimatchDiagnosticReport $truncated $present ('e'*64); $checked++
$incomplete=New-MinimatchDiagnosticState; Add-MinimatchUnfingerprintedMatch $incomplete
Assert-MinimatchDiagnosticReport (New-MinimatchDiagnosticReport $present ('e'*64) $incomplete) $present ('e'*64); $checked++
$absent=New-Report
Assert-MinimatchDiagnosticReport (New-MinimatchDiagnosticReport $absent ('e'*64) (New-MinimatchDiagnosticState)) $absent ('e'*64); $checked++
Assert-Denied { Assert-MinimatchDiagnosticReport (New-MinimatchDiagnosticReport $absent ('e'*64) $incomplete) $absent ('e'*64) }
Assert-Denied { Assert-MinimatchDiagnosticReport (New-MinimatchDiagnosticReport $present ('e'*64) (New-MinimatchDiagnosticState)) $present ('e'*64) }
Assert-Denied { Add-MinimatchFileDiagnostic (New-MinimatchDiagnosticState) @('app/vendor.js') $fixedBytes 'unreviewed-kind' }
function New-DiagnosticFixture {
    $exposure=New-Report 'web' $true; $state=New-MinimatchDiagnosticState
    Add-MinimatchFileDiagnostic $state @('app/vendor.js') ([Text.Encoding]::UTF8.GetBytes('var synthetic=vendor.Minimatch;')) 'minimatch-reference'
    return @{exposure=$exposure;diagnostic=(New-MinimatchDiagnosticReport $exposure ('e'*64) $state)}
}
foreach ($mutation in @(
    {param($r)$r.exposureReportSha256='f'*64},{param($r)$r.imageId='sha256:'+('f'*64)},
    {param($r)$r.commit='f'*40},{param($r)$r.source='local-standalone'},
    {param($r)$r.totalFindings=200001},{param($r)$r.truncated=$true},
    {param($r)$r.findings[0].sha256='invalid'},{param($r)$r.findings[0].matchKind='source-snippet'},
    {param($r)$r.findings[0].content='synthetic never publish raw source'},
    {param($r)$r.findings[0].locationChain=@('../untrusted.js')},
    {param($r)$r.findings[0].locationChain=@(12)},
    {param($r)$r.findings=@()},{param($r)$r.Remove('unfingerprintedMatches')}
)) { Assert-Denied { $fixture=New-DiagnosticFixture; & $mutation $fixture.diagnostic; Assert-MinimatchDiagnosticReport $fixture.diagnostic $fixture.exposure ('e'*64) } }
$fixture=New-DiagnosticFixture
$roundTrip=$fixture.diagnostic | ConvertTo-Json -Depth 7 | ConvertFrom-Json @jsonParameters
Assert-MinimatchDiagnosticReport $roundTrip $fixture.exposure ('e'*64); $checked++
Assert-Evidence ((@($roundTrip.findings[0].Keys | Sort-Object) -join ',') -ceq 'locationChain,matchKind,sha256')
Write-Output "Minimatch evidence fixtures passed: $checked bounded presence/absence/source fixtures accepted; $rejected malformed/external/stale/mismatched fixtures rejected. No real image or policy exception claimed."
