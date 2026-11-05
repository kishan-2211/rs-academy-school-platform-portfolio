param([Parameter(Mandatory)][string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-image-security-common.ps1')
. (Join-Path $PSScriptRoot 'production-dependency-policy-common.ps1')
. (Join-Path $PSScriptRoot 'runtime-dependency-exposure-common.ps1')
. (Join-Path $PSScriptRoot 'minimatch-exposure-report-common.ps1')
$repository = (Resolve-Path (Split-Path $PSScriptRoot -Parent)).Path
if ($env:GITHUB_API_URL -cne 'https://api.github.com' -or $env:GITHUB_REPOSITORY -cne 'kishan-2211/rs-academy-school-platform' -or
    $env:GITHUB_RUN_ID -cnotmatch '^[0-9]+$' -or [string]::IsNullOrWhiteSpace($env:GH_TOKEN)) { throw 'Authenticated current-repository CI metadata is required; no local release-policy pass inferred.' }
$jobs = Invoke-RestMethod "$env:GITHUB_API_URL/repos/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID/jobs?per_page=100" `
    -Headers @{Authorization="Bearer $env:GH_TOKEN"; Accept='application/vnd.github+json'; 'X-GitHub-Api-Version'='2022-11-28'} -TimeoutSec 30
$jobsJson = $jobs | ConvertTo-Json -Depth 12 -Compress
Assert-DependencyAuditCompletion ($jobsJson | ConvertFrom-Json -AsHashtable) $env:GITHUB_RUN_ID
$commit = (& git -C $repository rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify policy source commit.' }
$ignored = @(& git -C $repository ls-files '*osv-scanner*.toml')
if ($LASTEXITCODE -ne 0 -or $ignored.Count) { throw 'OSV suppression/configuration requires review; no invisible exception allowed.' }
function Read-Evidence([string]$Relative,[ref]$Sha256) {
    $path = Join-Path $EvidenceDirectory $Relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -gt 4MB) { throw 'Required bounded security evidence is missing.' }
    $bytes=[IO.File]::ReadAllBytes($path)
    if ($null -ne $Sha256) { $Sha256.Value=([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))).ToLowerInvariant() }
    return ConvertFrom-ImageSecurityJson ([Text.UTF8Encoding]::new($false,$true).GetString($bytes))
}
$lockHash = (Get-FileHash -LiteralPath (Join-Path $repository 'pnpm-lock.yaml') -Algorithm SHA256).Hash.ToLowerInvariant()
$minimatchImages=@{}
foreach ($service in @('web','api')) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $repository "apps/$service/Dockerfile") -Algorithm SHA256).Hash.ToLowerInvariant()
    $security = Read-Evidence "$service/image-security-$service-report.json"
    Assert-ImageSecurityReport $security $service $commit $hash -RequireClean
    $exposure = Read-Evidence "$service/dependency-exposure-$service.json"
    Assert-FinalDependencyExposure $exposure $service $commit $hash $lockHash $security.imageId
    $minimatchHash=$null
    $minimatch=Read-Evidence "$service/dependency-minimatch-exposure-$service.json" ([ref]$minimatchHash)
    $diagnostic=Read-Evidence "$service/dependency-minimatch-findings-$service.json"
    $minimatchImages[$service]=@{ exposure=$minimatch; diagnostic=$diagnostic; exposureSha256=$minimatchHash;
        dockerfileSha256=$hash; imageId=$security.imageId }
}
Assert-FinalMinimatchImages $minimatchImages $commit $lockHash
$whyJson = & pnpm --recursive why braces --json
if ($LASTEXITCODE -ne 0) { throw 'Complete dependency path could not be inspected.' }
$productionJson = & pnpm --filter @educore/web list --prod --depth Infinity --json
if ($LASTEXITCODE -ne 0) { throw 'Production dependency graph could not be inspected.' }
$why = ($whyJson -join "`n") | ConvertFrom-Json -AsHashtable -NoEnumerate
$production = ($productionJson -join "`n") | ConvertFrom-Json -AsHashtable -NoEnumerate
$manifest = ConvertFrom-ImageSecurityJson ([IO.File]::ReadAllText((Join-Path $repository 'apps/web/package.json')))
Assert-DevelopmentBracesPath $why $production $manifest
$minimatchWhyJson = & pnpm --recursive why minimatch --json
if ($LASTEXITCODE -ne 0 -or ($minimatchWhyJson -join "`n").Length -gt 8MB) { throw 'Complete bounded minimatch graph could not be inspected.' }
$minimatchWhy = ($minimatchWhyJson -join "`n") | ConvertFrom-Json -AsHashtable -NoEnumerate
Assert-DevelopmentMinimatchPath $minimatchWhy $production $manifest $repository
$sarif = Read-Evidence 'audit/results.sarif'
$count = Assert-FullDependencyAudit $sarif
if (@($sarif.runs[0].results | Where-Object { $_.message.text -ceq "Package 'braces@3.0.3' is vulnerable to 'CVE-2026-93687' (also known as 'GHSA-vfj7-8cjw-p6xm')." }).Count -gt 0) {
    # An official release closes this temporary classification; fail for immediate patch review.
    $upstream = Invoke-RestMethod 'https://registry.npmjs.org/braces/latest' -TimeoutSec 20
    if ($upstream.name -cne 'braces' -or $upstream.version -cne '3.0.3') { throw 'Official upstream version changed; update/review tracked SEC-BRACES-2026-93687 now.' }
    Write-Warning 'OPEN HIGH advisory GHSA-vfj7-8cjw-p6xm / braces@3.0.3: exact reviewed lint-only path, absent from both proven final images. Full audit is retained; SEC-BRACES-2026-93687 remains open.'
}
if (@($sarif.runs[0].results | Where-Object { $_.ruleId -ceq 'CVE-2026-118764' }).Count -gt 0) {
    # Official compatible patch availability ends this classification for review.
    $upstreamResponse=Invoke-WebRequest 'https://registry.npmjs.org/minimatch' -Headers @{Accept='application/vnd.npm.install-v1+json'} -TimeoutSec 20
    $upstreamBytes=if ($upstreamResponse.Content -is [byte[]]) { $upstreamResponse.Content } else { [Text.Encoding]::UTF8.GetBytes([string]$upstreamResponse.Content) }
    if ($upstreamBytes.Length -gt 8MB) { throw 'Official minimatch metadata exceeded its bound.' }
    Assert-MinimatchCompatibleUpstream (ConvertFrom-ImageSecurityJson ([Text.UTF8Encoding]::new($false,$true).GetString($upstreamBytes)))
    Write-Warning 'OPEN advisory GHSA-g3hv-75wg-m249: minimatch 3.1.5/10.2.6 exact reviewed development graph; absent from both current proven final images. Full audit retained; SEC-MINIMATCH-2026-118764 remains open. No runtime exception.'
}
Write-Output 'Production dependency policy passed: complete unsuppressed audit, exact development scope and fresh same-commit clean final-image/security evidence. No runtime vulnerability exception, merge or deployment approval.'
