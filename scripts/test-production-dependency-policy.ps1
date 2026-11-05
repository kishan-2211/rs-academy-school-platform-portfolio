# Pure policy fixtures; not real runtime-image or full-audit proof.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'production-dependency-policy-common.ps1')
$rejected = 0
function Assert-Denied([scriptblock]$Action) {
    $denied = $false
    try { & $Action | Out-Null } catch { $denied = $true }
    if (-not $denied) { throw 'Unsafe production dependency policy fixture accepted.' }
    $script:rejected++
}
function New-Why {
    $node = @{ name = '@educore/web'; version = '0.1.0'; depField = 'devDependencies' }
    foreach ($pair in @(@('eslint-config-next','16.4.1'),@('@next/eslint-plugin-next','16.4.1'),@('fast-glob','3.3.1'),@('micromatch','4.0.8'),@('braces','3.0.3'))) {
        $node = @{ name = $pair[0]; version = $pair[1]; dependents = @($node) }
    }
    return ,@($node)
}
function New-Jobs { return @{ total_count=1; jobs=@(@{name='Open-source dependency audit / osv-scan'; run_id=123; status='completed'; conclusion='success';
    steps=@(@{name='Run scanner'; status='completed'; conclusion='success'},@{name='Run osv-scanner-reporter'; status='completed'; conclusion='success'})}) } }
Assert-DependencyAuditCompletion (New-Jobs) '123'
foreach ($mutation in @({param($j) $j.jobs[0].run_id=124}, {param($j) $j.jobs[0].conclusion='failure'},
    {param($j) $j.jobs[0].steps[0].conclusion='failure'}, {param($j) $j.jobs[0].steps[0].conclusion='skipped'},
    {param($j) $j.jobs[0].steps[1].status='in_progress'}, {param($j) $j.jobs[0].steps=@()}, {param($j) $j.jobs=@()})) {
    Assert-Denied { $j=New-Jobs; & $mutation $j; Assert-DependencyAuditCompletion $j '123' }
}
$manifest = @{ dependencies = @{ next = '16.4.1' }; devDependencies = @{ 'eslint-config-next' = '16.4.1' } }
$prod = @(@{ name = '@educore/web'; dependencies = @{ next = @{ version = '16.4.1'; dependencies = @{} } } })
Assert-DevelopmentBracesPath (New-Why) $prod $manifest
Assert-Denied { $why = New-Why; $why[0].version = '3.0.4'; Assert-DevelopmentBracesPath $why $prod $manifest }
Assert-Denied { $why = New-Why; $why[0].dependents += @{ name='synthetic-second-parent'; version='1.0.0' }; Assert-DevelopmentBracesPath $why $prod $manifest }
Assert-Denied { $why = New-Why; $node = $why[0]; for ($index=0; $index -lt 5; $index++) { $node = $node.dependents[0] }; $node.depField='dependencies'; Assert-DevelopmentBracesPath $why $prod $manifest }
foreach ($name in @('braces','micromatch','fast-glob','@next/eslint-plugin-next','eslint-config-next')) {
    Assert-Denied { Assert-NoProductionBraces @{ dependencies = @{ nested = @{ optionalDependencies = @{ $name = @{version='3.0.3'} } } } } }
}
function New-Exposure([string]$Service) {
    return @{ format=1; service=$Service; commit=('a'*40); imageId=('sha256:' + ('b'*64)); dockerfileSha256=('c'*64); lockfileSha256=('d'*64);
        source='final-image-docker-export'; inspectorVersion=1; completedAt=[datetime]::UtcNow.ToString('o'); affectedLibraryPresent=$false; cleanupSucceeded=$true;
        counts=@{ filesystemMembers=12; manifests=1; javascript=1; jars=1; archiveMembers=2; contentMembers=0 } }
}
function Assert-Exposure($Report,[string]$Service='web') { Assert-FinalDependencyExposure $Report $Service ('a'*40) ('c'*64) ('d'*64) ('sha256:' + ('b'*64)) }
Assert-Exposure (New-Exposure 'web')
Assert-Exposure (New-Exposure 'api') 'api'
foreach ($mutation in @(
    {param($r) $r.affectedLibraryPresent=$true}, {param($r) $r.affectedLibraryPresent='false'},
    {param($r) $r.cleanupSucceeded=$false}, {param($r) $r.imageId='sha256:' + ('e'*64)},
    {param($r) $r.commit='e'*40}, {param($r) $r.lockfileSha256='e'*64}, {param($r) $r.dockerfileSha256='e'*64},
    {param($r) $r.source='local-standalone-only'}, {param($r) $r.completedAt=[datetime]::UtcNow.AddHours(-7).ToString('o')},
    {param($r) $r.completedAt=[datetime]::UtcNow.AddHours(1).ToString('o')}, {param($r) $r.counts.manifests=0},
    {param($r) $r.counts.javascript=0}, {param($r) $r.counts.filesystemMembers=0}, {param($r) $r.counts.jars='1'},
    {param($r) $r.rawPrivateEnvironment='synthetic-not-published'}, {param($r) $r.Remove('counts')}
)) { Assert-Denied { $r=New-Exposure 'web'; & $mutation $r; Assert-Exposure $r } }
Assert-Denied { $r=New-Exposure 'api'; $r.counts.archiveMembers=0; Assert-Exposure $r 'api' }
function New-Audit {
    return @{ version='2.1.0'; runs=@(@{ tool=@{driver=@{name='osv-scanner'; version='2.5.0'; rules=@(@{id='CVE-2026-93687'; deprecatedIds=@('CVE-2026-93687','GHSA-vfj7-8cjw-p6xm'); properties=@{'security-severity'='8.7'}})}};
        results=@(@{ruleId='CVE-2026-93687'; kind='fail'; message=@{text="Package 'braces@3.0.3' is vulnerable to 'CVE-2026-93687' (also known as 'GHSA-vfj7-8cjw-p6xm')."}; locations=@(@{physicalLocation=@{artifactLocation=@{uri='pnpm-lock.yaml'}}})}) }) }
}
if ((Assert-FullDependencyAudit (New-Audit)) -ne 1) { throw 'Visible known advisory fixture was not retained.' }
$clean=New-Audit; $clean.runs[0].results=@(); $clean.runs[0].tool.driver.rules=@()
if ((Assert-FullDependencyAudit $clean) -ne 0) { throw 'Complete clean audit fixture rejected.' }
foreach ($mutation in @(
    {param($a) $a.runs[0].results[0].ruleId='CVE-2099-0001'}, {param($a) $a.runs[0].results[0].message.text='Different package or version'},
    {param($a) $a.runs[0].results[0].suppressions=@(@{kind='external'})}, {param($a) $a.runs[0].results[0].baselineState='absent'},
    {param($a) $a.runs[0].results[0].locations[0].physicalLocation.artifactLocation.uri='apps/api/pom.xml'},
    {param($a) $a.runs[0].tool.driver.rules[0].properties.'security-severity'='9.9'}, {param($a) $a.runs[0].tool.driver.version='unknown'},
    {param($a) $a.runs[0].results += $a.runs[0].results[0]}, {param($a) $a.runs[0].Remove('results')}, {param($a) $a.runs[0].tool.driver.rules=@()}
)) { Assert-Denied { $a=New-Audit; & $mutation $a; Assert-FullDependencyAudit $a } }
Assert-Denied { Assert-FullDependencyAudit $null }
Write-Output "Production dependency policy guards passed: complete visible advisory/clean fixtures accepted; $rejected changed/runtime/missing/stale/suppressed fixtures rejected. No real-image or audit execution claimed."
# New proposal fixtures are pure synthetic data. They grant no exception.
. (Join-Path $PSScriptRoot 'runtime-dependency-exposure-common.ps1')
. (Join-Path $PSScriptRoot 'minimatch-exposure-report-common.ps1')
$beforeMini=$rejected
$reviewed=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'minimatch-reviewed-development-paths.json'))|ConvertFrom-Json -NoEnumerate
function New-MiniWhy {
    $expanded=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    function New-Node([string]$Id,[string[]]$Ancestors) {
        $at=$Id.LastIndexOf('@');$node=@{name=$Id.Substring(0,$at);version=$Id.Substring($at+1)}
        if ($node.name -ceq '@educore/web') { $node.depField='devDependencies';return $node }
        if ($Id -cin $Ancestors) { $node.circular=$true;return $node }
        if (-not $expanded.Add($Id)) { $node.deduped=$true;return $node }
        $prefix='EDGE '+$Id+' -> '
        $node.dependents=@($reviewed|Where-Object{$_.StartsWith($prefix,[StringComparison]::Ordinal)}|ForEach-Object{New-Node $_.Substring($prefix.Length) @($Ancestors+$Id)})
        return $node
    }
    $roots=@()
    foreach ($version in @('3.1.5','10.2.6')) {
        $root=New-Node ('minimatch@'+$version) @()
        $root.path='/synthetic/node_modules/.pnpm/minimatch@'+$version+'/node_modules/minimatch'
        $roots+= $root
    }
    return ,$roots
}
$miniManifest=@{dependencies=@{next='16.4.1'};devDependencies=@{eslint='9.38.0';'eslint-config-next'='16.4.1'}}
function New-MiniProduction { return ,@(@{name='@educore/web';version='0.1.0';dependencies=@{next=@{version='16.4.1';dependencies=@{}}}}) }
function Assert-MiniPath($Why,$Production,$Manifest) { Assert-DevelopmentMinimatchPath $Why $Production $Manifest '/synthetic' }
Assert-MiniPath (New-MiniWhy) (New-MiniProduction) $miniManifest
# Every reviewed dependency edge is independently necessary, including cycles.
function Remove-ReviewedEdge($Node,[string]$From,[string]$To) {
    if (($Node.name+'@'+$Node.version) -ceq $From -and $Node.dependents) {
        $Node.dependents=@($Node.dependents|Where-Object{($_.name+'@'+$_.version) -cne $To})
    }
    foreach ($parent in $Node.dependents) { Remove-ReviewedEdge $parent $From $To }
}
foreach ($fact in @($reviewed|Where-Object{$_.StartsWith('EDGE ',[StringComparison]::Ordinal)})) {
    Assert-Denied {
        $edge=$fact.Substring(5).Split(' -> ',[StringSplitOptions]::None)
        $why=New-MiniWhy
        foreach($root in $why){Remove-ReviewedEdge $root $edge[0] $edge[1]}
        Assert-MiniPath $why (New-MiniProduction) $miniManifest
    }
}
foreach($mutation in @(
    {param($w) $w[0].version='3.1.6'}, {param($w) $w[1].version='11.0.1'},
    {param($w) $w[0].path='/synthetic/other/minimatch'}, {param($w) $w[0].path=$null},
    {param($w) $w[0].path='/external/node_modules/.pnpm/minimatch@3.1.5/node_modules/minimatch'},
    {param($w) $w[0].dependents[0].peersSuffixHash=@{}},
    {param($w) $w[0].dependents+=@{name='synthetic-runtime';version='1.0.0';depField='dependencies'}},
    {param($w) $w[0].dependents[0].version='0.21.3'},
    {param($w) $w[0].dependents+= $w[0].dependents[0]},
    {param($w) $w[1].dependents=@()}, {param($w) $w[0].extra='unknown'},
    {param($w) $w[0].dependents[0].dependents[0].dependents[0].depField='dependencies'}
)) { Assert-Denied { $why=New-MiniWhy;& $mutation $why;Assert-MiniPath $why (New-MiniProduction) $miniManifest } }
Assert-Denied { Assert-MiniPath @() (New-MiniProduction) $miniManifest }
Assert-Denied { $w=New-MiniWhy;Assert-MiniPath @($w[0],$w[0]) (New-MiniProduction) $miniManifest }
foreach($fact in @($reviewed|Where-Object{$_.StartsWith('NODE ',[StringComparison]::Ordinal)})) {
    $id=$fact.Substring(5);$name=$id.Substring(0,$id.LastIndexOf('@'))
    if($name -ceq '@educore/web'){continue}
    Assert-Denied {
        $prod=New-MiniProduction
        $prod[0].dependencies.next.dependencies=@{nested=@{optionalDependencies=@{$name=@{version='synthetic'}}}}
        Assert-MiniPath (New-MiniWhy) $prod $miniManifest
    }
}
Assert-Denied {$p=New-MiniProduction;$p[0].dependencies=@{};Assert-MiniPath (New-MiniWhy) $p $miniManifest}
Assert-Denied {$p=New-MiniProduction;$p[0].dependencies.next.version='16.4.2';Assert-MiniPath (New-MiniWhy) $p $miniManifest}
Assert-Denied {$p=New-MiniProduction;$p[0].dependencies.next.dependencies='invalid';Assert-MiniPath (New-MiniWhy) $p $miniManifest}
Assert-Denied {$p=New-MiniProduction;$p[0].dependencies.next.optionalDependencies=@{'synthetic-alias'=@{name='minimatch';version='3.1.5'}};Assert-MiniPath (New-MiniWhy) $p $miniManifest}
foreach($node in @(
    @{from='minimatch@3.1.5';version='3.1.5'},
    @{from='npm:minimatch@3.1.5';version='3.1.5'},
    @{from='npm:MINIMATCH@3.1.5';version='3.1.5'},
    @{from='synthetic-alias@npm:minimatch@3.1.5';version='3.1.5'},
    @{from='synthetic-alias';version='npm:minimatch@3.1.5'},
    @{version='3.1.5';path='/synthetic/node_modules/.pnpm/minimatch@3.1.5/node_modules/minimatch'},
    @{version='3.1.5';path='C:\synthetic\node_modules\.pnpm\minimatch@3.1.5\node_modules\synthetic-alias'},
    @{version='3.1.5';path='C:\synthetic\NODE_MODULES\.PNPM\MINIMATCH@3.1.5\node_modules\synthetic-alias'},
    @{version='3.1.5';path='/synthetic/NODE_MODULES/MINIMATCH'},
    @{version='0.21.2';path='/synthetic/node_modules/.pnpm/@eslint+config-array@0.21.2/node_modules/synthetic-alias'},
    @{version='3.1.5';resolved='https://registry.npmjs.org/minimatch/-/minimatch-3.1.5.tgz'},
    @{version='0.21.2';resolved='https://registry.npmjs.org/%40eslint%2Fconfig-array/-/config-array-0.21.2.tgz'},
    @{version='3.1.5';from=@{}}, @{version='3.1.5';path=@{}},
    @{version='3.1.5';resolved='https://synthetic.invalid/opaque-package.tgz'}
)) {
    foreach($kind in @('dependencies','optionalDependencies','unsavedDependencies')) {
        Assert-Denied {$p=New-MiniProduction;$p[0].dependencies.next[$kind]=@{'synthetic-alias'=$node};Assert-MiniPath (New-MiniWhy) $p $miniManifest}
    }
}
# Real pnpm metadata forms and unrelated unsaved entries remain valid.
$legitimate=New-MiniProduction
$legitimate[0].path='/synthetic/apps/web'
$legitimate[0].dependencies.next.from='next'
$legitimate[0].dependencies.next.resolved='https://registry.npmjs.org/next/-/next-16.4.1.tgz'
$legitimate[0].dependencies.next.path='/synthetic/node_modules/.pnpm/next@16.4.1/node_modules/next'
$legitimate[0].unsavedDependencies=@{'synthetic-safe'=@{from='synthetic-safe';version='1.0.0';resolved='https://registry.npmjs.org/synthetic-safe/-/synthetic-safe-1.0.0.tgz';path='/synthetic/node_modules/.pnpm/synthetic-safe@1.0.0/node_modules/synthetic-safe'}}
Assert-MiniPath (New-MiniWhy) $legitimate $miniManifest
Assert-Denied {$m=@{dependencies=@{next='16.4.1';eslint='9.38.0'};devDependencies=$miniManifest.devDependencies};Assert-MiniPath (New-MiniWhy) (New-MiniProduction) $m}
function New-MiniImages {
    $images=@{}
    foreach($service in @('web','api')) {
        $exposure=New-Exposure $service;$exposure.inspectionTarget='minimatch';$exposure.result='absent'
        $hash='e'*64
        $diagnostic=New-MinimatchDiagnosticReport $exposure $hash (New-MinimatchDiagnosticState)
        $images[$service]=@{exposure=$exposure;diagnostic=$diagnostic;exposureSha256=$hash;dockerfileSha256=('c'*64);imageId=('sha256:'+('b'*64))}
    }
    return $images
}
function Assert-MiniImages($Images){Assert-FinalMinimatchImages $Images ('a'*40) ('d'*64)}
Assert-MiniImages (New-MiniImages)
foreach($service in @('web','api')) {
    foreach($mutation in @(
        {param($p) $p.exposure.result='present';$p.exposure.affectedLibraryPresent=$true;$p.diagnostic.affectedLibraryPresent=$true;$p.diagnostic.unfingerprintedMatches=1},
        {param($p) $p.exposure.commit='f'*40}, {param($p) $p.exposure.imageId='sha256:'+('f'*64)},
        {param($p) $p.exposure.dockerfileSha256='f'*64}, {param($p) $p.exposure.lockfileSha256='f'*64},
        {param($p) $p.exposure.source='local-standalone'}, {param($p) $p.exposure.cleanupSucceeded=$false},
        {param($p) $p.exposure.completedAt=[datetime]::UtcNow.AddHours(-7).ToString('o')},
        {param($p) $p.exposure.completedAt=[datetime]::UtcNow.AddHours(1).ToString('o')},
        {param($p) $p.exposure.counts.filesystemMembers=0},
        {param($p) $p.exposureSha256='f'*64}, {param($p) $p.diagnostic=$null},
        {param($p) $p.diagnostic.truncated=$true}, {param($p) $p.diagnostic.totalFindings=1},
        {param($p) $p.diagnostic.unfingerprintedMatches=1}, {param($p) $p.diagnostic.cleanupSucceeded=$false},
        {param($p) $p.diagnostic.commit='f'*40}, {param($p) $p.extra='unexpected'}
    )) { Assert-Denied {$images=New-MiniImages;& $mutation $images[$service];Assert-MiniImages $images} }
    Assert-Denied {$images=New-MiniImages;$images.Remove($service);Assert-MiniImages $images}
}
Assert-Denied {$images=New-MiniImages;$images.other=$images.web;Assert-MiniImages $images}
function New-MiniAudit {
    return @{version='2.1.0';runs=@(@{tool=@{driver=@{name='osv-scanner';version='2.5.0';rules=@(@{id='CVE-2026-118764';deprecatedIds=@('CVE-2026-118764','GHSA-g3hv-75wg-m249');properties=@{'security-severity'='6.9'}})}};
        results=@('3.1.5','10.2.6'|ForEach-Object{@{ruleId='CVE-2026-118764';kind='fail';message=@{text="Package 'minimatch@$_' is vulnerable to 'CVE-2026-118764' (also known as 'GHSA-g3hv-75wg-m249')."};locations=@(@{physicalLocation=@{artifactLocation=@{uri='pnpm-lock.yaml'}}})}})})}
}
if((Assert-FullDependencyAudit (New-MiniAudit)) -ne 2){throw 'Exact visible minimatch results rejected.'}
$mixed=New-MiniAudit;$braces=New-Audit
$mixed.runs[0].results+=$braces.runs[0].results
$mixed.runs[0].tool.driver.rules+=$braces.runs[0].tool.driver.rules
if((Assert-FullDependencyAudit $mixed) -ne 3){throw 'Existing independent braces advisory behavior changed.'}
foreach($mutation in @(
    {param($a) $a.runs[0].results=@($a.runs[0].results[0])},
    {param($a) $a.runs[0].results[1]=$a.runs[0].results[0]},
    {param($a) $a.runs[0].results[0].message.text="Package 'minimatch@11.0.1' is vulnerable to 'CVE-2026-118764' (also known as 'GHSA-g3hv-75wg-m249')."},
    {param($a) $a.runs[0].results[0].ruleId='GHSA-g3hv-75wg-m249'},
    {param($a) $a.runs[0].results[0].kind='pass'},
    {param($a) $a.runs[0].results[0].suppressions=@(@{kind='external'})},
    {param($a) $a.runs[0].results[0].baselineState='absent'},
    {param($a) $a.runs[0].results[0].locations[0].physicalLocation.artifactLocation.uri='apps/api/pom.xml'},
    {param($a) $a.runs[0].tool.driver.rules[0].properties.'security-severity'='7.0'},
    {param($a) $a.runs[0].tool.driver.rules[0].deprecatedIds=@('GHSA-g3hv-75wg-m249')},
    {param($a) $a.runs[0].tool.driver.rules+=$a.runs[0].tool.driver.rules[0]},
    {param($a) $a.runs[0].tool.driver.rules+=@{id='CVE-2099-0001'}},
    {param($a) $a.runs[0].results[0].message.text='Different runtime advisory'}
)) {Assert-Denied {$a=New-MiniAudit;& $mutation $a;Assert-FullDependencyAudit $a}}
function New-MiniUpstream {return @{name='minimatch';versions=@{'3.0.0'=@{};'3.1.5'=@{name='minimatch';version='3.1.5'};'10.2.6'=@{name='minimatch';version='10.2.6'};'11.0.3'=@{name='minimatch';version='11.0.3'}}}}
Assert-MinimatchCompatibleUpstream (New-MiniUpstream)
foreach($version in @('3.1.6','3.2.0','10.2.7','10.3.0')) {
    Assert-Denied {$m=New-MiniUpstream;$m.versions[$version]=@{name='minimatch';version=$version};Assert-MinimatchCompatibleUpstream $m}
}
Assert-Denied {$m=New-MiniUpstream;$m.versions.Remove('3.1.5');Assert-MinimatchCompatibleUpstream $m}
Assert-Denied {$m=New-MiniUpstream;$m.name='untrusted-fork';Assert-MinimatchCompatibleUpstream $m}
Assert-Denied {Assert-MinimatchCompatibleUpstream @()}
Write-Output "Proposed minimatch classification fixtures passed: exact graph/both-image/visible audit/compatible-upstream cases accepted; $($rejected-$beforeMini) additional unsafe cases rejected; total $rejected refusals including unchanged braces guards. No actual image absence, policy approval or deployment claimed."
