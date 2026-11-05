# Focused synthetic guard fixtures; real images remain a separate Linux CI gate.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-package-security-common.ps1')
$rejected = 0
function Assert-PackageRejected([scriptblock]$Action) {
    $denied = $false
    try { & $Action | Out-Null } catch { $denied = $true }
    if (-not $denied) { throw 'Unsafe or incomplete installed-package evidence was accepted.' }
    $script:rejected++
}
foreach ($service in @('web','api')) {
    $packages = Get-RuntimeSecurityPackages $service
    if ($service -ceq 'api') { $packages.musl='1.2.6-r3'; $packages.'musl-utils'='1.2.6-r3' }
    $valid = @($packages.Keys | ForEach-Object { "$_-$($packages[$_])" })
    Assert-RuntimePackageInventory $service $valid
    Assert-RuntimePackageInventory $service @($valid + 'ca-certificates-bundle-20260911-r0')
    foreach ($package in $packages.Keys) {
        $expected = "$package-$($packages[$package])"
        Assert-PackageRejected { Assert-RuntimePackageInventory $service @($valid | Where-Object { $_ -cne $expected }) }
        Assert-PackageRejected { Assert-RuntimePackageInventory $service @($valid + $expected) }
        Assert-PackageRejected { Assert-RuntimePackageInventory $service @($valid | ForEach-Object { if ($_ -ceq $expected) { "$package-0.0.0-r0" } else { $_ } }) }
        Assert-PackageRejected { Assert-RuntimePackageInventory $service @($valid | ForEach-Object { if ($_ -ceq $expected) { "$package-99.0.0-r0" } else { $_ } }) }
    }
    foreach ($invalid in @('synthetic-secret=do-not-publish', 'zlib-1.3.2-r1 extra', "zlib-1.3.2-r1`n", "synthetic-1.0.0-r0`n", '', ('x' * 201))) {
        Assert-PackageRejected { Assert-RuntimePackageInventory $service @($valid + $invalid) }
    }
    Assert-PackageRejected { Assert-RuntimePackageInventory $service @() }
    Assert-PackageRejected { Assert-RuntimePackageInventory $service $null }
    Assert-PackageRejected { Assert-RuntimePackageInventory $service @($valid + @('synthetic-1.0.0-r0') * 501) }
}
Assert-PackageRejected { Get-RuntimeSecurityPackages 'transport' }
Assert-PackageRejected { Get-RuntimeSecurityPackages 'Web' }
Assert-PackageRejected { Get-RuntimeSecurityPackages '' }

# Preserve an already more recent API security revision, while the web image
# remains pinned exactly to the owner-approved fixed revision.
$apiPackages = Get-RuntimeSecurityPackages 'api'
$apiBase = @($apiPackages.Keys | ForEach-Object { "$_-$($apiPackages[$_])" })
Assert-RuntimePackageInventory 'api' @($apiBase + 'musl-1.2.6-r4' + 'musl-utils-1.2.6-r4')
# CI439 shipped this real vulnerable version. It must be refused even when
# every other approved package and the newer musl pair are present.
$apiOldExpat = @($apiBase | Where-Object { $_ -cnotmatch '^libexpat-' }) + @('libexpat-2.9.0-r0','musl-1.2.6-r4','musl-utils-1.2.6-r4')
Assert-PackageRejected { Assert-RuntimePackageInventory 'api' $apiOldExpat }
foreach ($pair in @(
    @('musl-1.2.6-r2','musl-utils-1.2.6-r2'),
    @('musl-1.2.6-r3','musl-utils-1.2.6-r4'),
    @('musl-1.2.7-r3','musl-utils-1.2.7-r3'),
    @('musl-1.3.0-r3','musl-utils-1.3.0-r3'),
    @('musl-1.2.6-r03','musl-utils-1.2.6-r03'),
    @('musl-1.2.6-r100000','musl-utils-1.2.6-r100000'),
    @('musl-1.2.6-r-3','musl-utils-1.2.6-r-3'),
    @('musl-1.2.6-r3'),
    @('musl-utils-1.2.6-r3'),
    @('musl-1.2.6-r3','musl-utils-1.2.6-r3','musl-utils-1.2.6-r3')
)) { Assert-PackageRejected { Assert-RuntimePackageInventory 'api' @($apiBase + $pair) } }
$webPackages = Get-RuntimeSecurityPackages 'web'
$webNewer = @($webPackages.Keys | ForEach-Object {
    if ($_ -cin @('musl','musl-utils')) { "$_-1.2.6-r4" } else { "$_-$($webPackages[$_])" }
})
Assert-PackageRejected { Assert-RuntimePackageInventory 'web' $webNewer }
Assert-WebRuntimeNodeVersion 'v24.22.1'
foreach ($invalid in @($null, '', '24.22.1', 'v24.19.0', 'v24.22.2', "v24.22.1`n", " v24.22.1", 24221)) {
    Assert-PackageRejected { Assert-WebRuntimeNodeVersion $invalid }
}
Assert-PackageRejected { Assert-WebRuntimeNodeVersion ([object[]]@('v24.22.1')) }
Assert-PackageRejected { Assert-WebRuntimeNodeVersion ([object[]]@('v24.22.1','v24.22.1')) }
# Guard the scope of the actual Dockerfile changes, not just fabricated inventories.
$repository = Split-Path $PSScriptRoot -Parent
foreach ($service in @('web','api')) {
    $dockerfile = [IO.File]::ReadAllText((Join-Path $repository "apps/$service/Dockerfile"))
    $stages = $dockerfile -split '(?m)^FROM '
    $runtime = @($stages | Where-Object { $_ -match '^\S+ AS runtime\r?\n' })
    if ($runtime.Count -ne 1 -or $dockerfile -match '(?m)^RUN apk upgrade(?:\s|$)') { throw 'Scoped final runtime stage is required; no blanket operating-system upgrade.' }
    foreach ($package in (Get-RuntimeSecurityPackages $service).Keys) {
        $expected = "$package=$((Get-RuntimeSecurityPackages $service)[$package])"
        if ($runtime[0] -notmatch [regex]::Escape($expected)) { throw 'Actual final Dockerfile is missing an approved patch pin.' }
        foreach ($other in @($stages | Where-Object { $_ -cne $runtime[0] })) {
            if ($other -match [regex]::Escape($expected)) { throw 'Runtime patch unexpectedly changed a build stage.' }
        }
    }
    if ($runtime[0] -notmatch '(?m)^USER educore\r?$') { throw 'Existing non-root runtime must be retained.' }
    if ($service -ceq 'web') {
        $froms = [regex]::Matches($dockerfile, '(?m)^FROM (\S+) AS (dependencies|builder|runtime)\r?$')
        if ($froms.Count -ne 3 -or @($froms | Where-Object { $_.Groups[1].Value -cne (Get-ApprovedWebRuntimeImage) }).Count) {
            throw 'Every web stage must use the same exact approved official Node image digest.'
        }
        foreach ($path in @('/usr/local/lib/node_modules/npm','/usr/local/bin/npm','/usr/local/bin/npx')) {
            if ($runtime[0] -notmatch [regex]::Escape($path)) { throw 'Final web-only package manager removal is incomplete.' }
            foreach ($other in @($stages | Where-Object { $_ -cne $runtime[0] })) {
                if ($other -match [regex]::Escape($path)) { throw 'Package manager removal must not affect build stages.' }
            }
        }
        if ($runtime[0] -notmatch 'CMD \["node", "apps/web/server\.js"\]') { throw 'Existing standalone Node entry point must not change.' }
    } elseif ($runtime[0] -notmatch 'ENTRYPOINT \["java",.*"educore-api-0\.1\.0-SNAPSHOT\.jar"\]') { throw 'Existing Java entry point must not change.' }
}
$ci = [IO.File]::ReadAllText((Join-Path $repository '.github/workflows/ci.yml'))
$nodePins = [regex]::Matches($ci, '(?m)^\s+node-version:\s*(\S+)\r?$')
if ($nodePins.Count -lt 2 -or @($nodePins | Where-Object { $_.Groups[1].Value -cne '24.22.1' }).Count) {
    throw 'Existing CI Node version pins must remain aligned with the approved runtime.'
}
$webVerifier = [IO.File]::ReadAllText((Join-Path $repository 'scripts/verify-web-runtime-image.ps1'))
if (-not $webVerifier.Contains('$probeImage = Get-ApprovedWebRuntimeImage') -or
    -not $webVerifier.Contains('FROM $probeImage`nCOPY . /context') -or
    -not $webVerifier.Contains('Assert-WebRuntimeNodeVersion $nodeVersion[0]')) {
    throw 'The context probe and actual runtime version check must use the approved Node contract.'
}
# Immutable official Dockerfile fixture, independently obtained from the pinned
# index 06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53:
# https://raw.githubusercontent.com/nodejs/docker-node/80f02a542da739e6a662451d1db66456a0dea253/24/alpine3.24/Dockerfile
# The byte-verified amd64 config also records these exact build commands.
$officialYarnLinks = @'
ln -s /opt/yarn-v$YARN_VERSION/bin/yarn /usr/local/bin/yarn
ln -s /opt/yarn-v$YARN_VERSION/bin/yarnpkg /usr/local/bin/yarnpkg
'@
function Assert-OfficialYarnLinkTargets([string]$Dockerfile) {
    foreach($line in $officialYarnLinks.Split("`n",[StringSplitOptions]::RemoveEmptyEntries)) {
        $words=$line.Trim().Split(' ',[StringSplitOptions]::RemoveEmptyEntries)
        if($words.Count -ne 4 -or $words[0] -cne 'ln' -or $words[1] -cne '-s') { throw 'Invalid official Yarn link fixture.' }
        $target=$words[2].Replace('$YARN_VERSION','1.22.22')
        $guard='test "$(readlink '+$words[3]+')" = '+$target
        if(-not $Dockerfile.Contains($guard)) { throw 'Removal guard disagrees with the pinned official image link target.' }
    }
}
function Assert-YarnRemovalScope([string]$Dockerfile,[string]$Verifier) {
    Assert-OfficialYarnLinkTargets $Dockerfile
    $stages=$Dockerfile -split '(?m)^FROM '
    $runtime=@($stages | Where-Object { $_ -match '^\S+ AS runtime\r?\n' })
    if($runtime.Count -ne 1) { throw 'One final web stage is required.' }
    $required=@(
        'test -d /opt/yarn-v1.22.22 && test ! -L /opt/yarn-v1.22.22',
        'test -f /opt/yarn-v1.22.22/lib/cli.js && test ! -L /opt/yarn-v1.22.22/lib/cli.js',
        'test -L /usr/local/bin/yarn && test -L /usr/local/bin/yarnpkg',
        'test "$(readlink /usr/local/bin/yarn)" = /opt/yarn-v1.22.22/bin/yarn',
        'test "$(readlink /usr/local/bin/yarnpkg)" = /opt/yarn-v1.22.22/bin/yarnpkg',
        'test "$(sha256sum /opt/yarn-v1.22.22/lib/cli.js | cut -d '' '' -f 1)" = 443ed69e76443b89afddccfc9faec1ff16eb5e500979cc079c696dec4c3d94ee',
        'rm -f /usr/local/bin/yarn /usr/local/bin/yarnpkg',
        'rm -rf /opt/yarn-v1.22.22'
    )
    foreach($guard in $required) {
        if(-not $runtime[0].Contains($guard)) { throw 'The exact reviewed Yarn removal fence changed.' }
        foreach($other in @($stages | Where-Object { $_ -cne $runtime[0] })) {
            if($other.Contains('/opt/yarn') -or $other.Contains('/usr/local/bin/yarn')) { throw 'Yarn removal must not affect build stages.' }
        }
    }
    foreach($guard in @('test ! -e /opt/yarn-v1.22.22','test ! -L /opt/yarn-v1.22.22',
        'test ! -e /usr/local/bin/yarn','test ! -L /usr/local/bin/yarn',
        'test ! -e /usr/local/bin/yarnpkg','test ! -L /usr/local/bin/yarnpkg',
        '! command -v yarn && ! command -v yarnpkg')) {
        if(-not $Verifier.Contains($guard)) { throw 'Actual container Yarn absence check is incomplete.' }
    }
}
$webDockerfile=[IO.File]::ReadAllText((Join-Path $repository 'apps/web/Dockerfile'))
Assert-YarnRemovalScope $webDockerfile $webVerifier
foreach($guard in @('test -d /opt/yarn-v1.22.22','test ! -L /opt/yarn-v1.22.22',
    'test -f /opt/yarn-v1.22.22/lib/cli.js','test ! -L /opt/yarn-v1.22.22/lib/cli.js',
    'test -L /usr/local/bin/yarn && test -L /usr/local/bin/yarnpkg',
    'test "$(readlink /usr/local/bin/yarn)" = /opt/yarn-v1.22.22/bin/yarn',
    'test "$(readlink /usr/local/bin/yarnpkg)" = /opt/yarn-v1.22.22/bin/yarnpkg',
    '443ed69e76443b89afddccfc9faec1ff16eb5e500979cc079c696dec4c3d94ee')) {
    Assert-PackageRejected { Assert-YarnRemovalScope ($webDockerfile.Replace($guard,'unreviewed')) $webVerifier }
}
Assert-PackageRejected { Assert-YarnRemovalScope ($webDockerfile.Replace('rm -rf /opt/yarn-v1.22.22','rm -rf /opt/yarn-v*')) $webVerifier }
Assert-PackageRejected { Assert-OfficialYarnLinkTargets ($webDockerfile.Replace('test "$(readlink /usr/local/bin/yarnpkg)" = /opt/yarn-v1.22.22/bin/yarnpkg',
    'test "$(readlink /usr/local/bin/yarnpkg)" = /opt/yarn-v1.22.22/bin/yarn')) }
Assert-PackageRejected { Assert-YarnRemovalScope ($webDockerfile.Replace('WORKDIR /workspace',"WORKDIR /workspace`nRUN rm -rf /opt/yarn-v1.22.22")) $webVerifier }
foreach($guard in @('test ! -e /opt/yarn-v1.22.22','test ! -L /opt/yarn-v1.22.22',
    'test ! -e /usr/local/bin/yarn','test ! -L /usr/local/bin/yarn',
    'test ! -e /usr/local/bin/yarnpkg','test ! -L /usr/local/bin/yarnpkg',
    '! command -v yarn && ! command -v yarnpkg')) {
    Assert-PackageRejected { Assert-YarnRemovalScope $webDockerfile ($webVerifier.Replace($guard,'unreviewed')) }
}
# Real CI441 inherited GnuPG/SQLite must stay absent even at a newer version.
$apiClean = @($apiBase + 'musl-1.2.6-r4' + 'musl-utils-1.2.6-r4')
foreach ($removed in (Get-ApiPrunedBuildPackages)) {
    Assert-PackageRejected { Assert-RuntimePackageInventory 'api' @($apiClean + ($removed + '-2.4.9-r1')) }
}
Assert-PackageRejected { Assert-RuntimePackageInventory 'api' @($apiClean + 'sqlite-libs-3.53.4-r0') }
Assert-PackageRejected { Assert-RuntimePackageInventory 'api' @($apiClean + 'sqlite-libs-3.54.1-r0') }

function Assert-ApiPruningScope([string]$Dockerfile, [string]$Verifier, [string]$Probe) {
    $stages = $Dockerfile -split '(?m)^FROM '
    $runtime = @($stages | Where-Object { $_ -match '^\S+ AS runtime\r?\n' })
    if ($runtime.Count -ne 1 -or $Dockerfile.Split('apk del --no-network gnupg').Count -ne 2 -or
        $runtime[0].IndexOf('apk del --no-network gnupg') -le $runtime[0].IndexOf('libexpat=2.10.0-r0')) { throw 'Only the ordinary final-stage pruning after existing pins is permitted.' }
    $pruneLine = @($runtime[0] -split '\r?\n' | Where-Object { $_ -cmatch '^\s+&& apk del --no-network gnupg(?:\s|$)' })
    if ($pruneLine.Count -ne 1 -or $pruneLine[0].Trim() -cne '&& apk del --no-network gnupg \') { throw 'The pruning line must continue the existing Docker RUN before addgroup.' }
    foreach ($other in @($stages | Where-Object { $_ -cne $runtime[0] })) {
        if ($other.Contains('apk del')) { throw 'Build-stage verification tools must remain unchanged.' }
    }
    if ($runtime[0] -match 'apk del[^\r\n]*(?:--force|--rdepends)|rm[^\r\n]*(?:sqlite|gnupg|gpg)') { throw 'Do not force-delete shared dependencies or files.' }
    foreach ($required in @('$pruningProof = @(& docker exec $containerId sh -ec (Get-ApiPrunedRuntimeProbe))',
        '$LASTEXITCODE -ne 0 -or $pruningProof.Count -ne 1',
        "API_PRUNED_TOOLS_ABSENT_FONTS_TRUST_PRESENT")) {
        if (-not $Verifier.Contains($required)) { throw 'The real image verifier must run and require its bounded proof.' }
    }
    foreach ($required in @('if command -v "$tool" >/dev/null 2>&1; then exit 31; fi',
        'if test -e "$directory/$tool" || test -L "$directory/$tool"; then exit 32; fi',
        'if test -e "$file" || test -L "$file"; then exit 33; fi', '/usr/libexec/keyboxd', '/usr/lib/libsqlite3.*',
        'fc-match -f', 'ca-certificates.crt', '$JAVA_HOME/lib/security/cacerts', '$JAVA_HOME/lib/libfontmanager.so')) {
        if (-not $Probe.Contains($required)) { throw 'Incomplete actual command/file/alias/font/trust probe.' }
    }
}
$apiDockerfile = [IO.File]::ReadAllText((Join-Path $repository 'apps/api/Dockerfile'))
$apiVerifier = [IO.File]::ReadAllText((Join-Path $repository 'scripts/verify-api-runtime-image.ps1'))
$apiProbe = Get-ApiPrunedRuntimeProbe
Assert-ApiPruningScope $apiDockerfile $apiVerifier $apiProbe
Assert-PackageRejected { Assert-ApiPruningScope ($apiDockerfile.Replace('&& apk del --no-network gnupg \','&& apk del --no-network gnupg')) $apiVerifier $apiProbe }
Assert-PackageRejected { Assert-ApiPruningScope ($apiDockerfile.Replace('apk del --no-network gnupg','apk del --force gnupg')) $apiVerifier $apiProbe }
Assert-PackageRejected { Assert-ApiPruningScope ($apiDockerfile.Replace('WORKDIR /workspace',"WORKDIR /workspace`nRUN apk del --no-network gnupg")) $apiVerifier $apiProbe }
foreach ($required in @('Get-ApiPrunedRuntimeProbe', '$LASTEXITCODE -ne 0 -or $pruningProof.Count -ne 1')) {
    Assert-PackageRejected { Assert-ApiPruningScope $apiDockerfile ($apiVerifier.Replace($required,'omitted')) $apiProbe }
}
foreach ($required in @('test -L "$directory/$tool"', 'test -L "$file"', 'then exit 32', 'then exit 33', '/usr/libexec/keyboxd', '/usr/lib/libsqlite3.*', 'fc-match -f')) {
    Assert-PackageRejected { Assert-ApiPruningScope $apiDockerfile $apiVerifier ($apiProbe.Replace($required,'omitted')) }
}
Write-Output "Runtime-package guard fixtures passed: $rejected refusal cases; exact Yarn path/hash/link fences and actual absence checks; other approved runtime boundaries retained. No Docker execution or deployment verification claimed."
