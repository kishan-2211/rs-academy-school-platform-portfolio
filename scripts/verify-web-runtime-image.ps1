$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-package-security-common.ps1')
if (-not $IsLinux) { throw 'Real web image gate requires the Linux Docker CI runner; no container verification is claimed.' }
foreach ($command in @('docker', 'git', 'tar')) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required image-test command unavailable: $command" }
}
& docker version *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker engine unavailable; no image verification is claimed.' }
$repository = (Resolve-Path (Split-Path $PSScriptRoot -Parent)).Path
$invocation = [guid]::NewGuid().ToString('N')
$ownedDirectory = Join-Path $repository ".build-tmp/web-image-$invocation"
$context = Join-Path $ownedDirectory 'source'
$sourceArchive = Join-Path $ownedDirectory 'committed-source.tar'
$imageTag = "educore-web-smoke:$invocation"
$probeTag = "educore-context-smoke:$invocation"
$containerName = "educore-web-smoke-$invocation"
$containerId = $null
$http = $null
$builtImages = @()
if (Test-Path -LiteralPath $ownedDirectory) { throw 'Generated test directory already exists; refusing to reuse it.' }
New-Item -ItemType Directory -Path $context -Force | Out-Null

function Read-WebResponse([string]$Path) {
    if (-not $Path.StartsWith('/')) { throw 'Only local image HTTP paths are permitted.' }
    $response = $http.GetAsync($base + $Path).GetAwaiter().GetResult()
    try {
        $headers = @{}
        foreach ($header in $response.Headers) { $headers[$header.Key] = $header.Value -join ', ' }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            Type = "$($response.Content.Headers.ContentType)"
            Headers = $headers
        }
    } finally { $response.Dispose() }
}
function Assert-WebBytes([string]$Path, [string]$ExpectedDigest) {
    $response = Read-WebResponse $Path
    if ($response.Status -ne 200) { throw "Image asset did not return HTTP 200: $Path" }
    $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($response.Bytes)).ToLowerInvariant()
    if ($actual -cne $ExpectedDigest) { throw "Image asset bytes differ from the packaged source: $Path" }
    Write-Output "Real web image asset verified: $Path"
}

try {
    # Read-only Git archive: test the exact committed source, never copy .env,
    # local credentials, uploaded data or unrelated uncommitted work into a build.
    & git -C $repository archive --format=tar --output $sourceArchive HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Committed source archive failed.' }
    & tar --extract --file $sourceArchive --directory $context
    if ($LASTEXITCODE -ne 0) { throw 'Committed source extraction failed.' }

    # Harmless privacy canaries exist ONLY in this invocation's disposable copy.
    # The real source, registry configuration, uploads and credentials are untouched.
    $excluded = @('.env', '.env.local', '.npmrc', 'credentials.json', 'service-account-test.json',
        'synthetic-private.pem', 'uploads/marker.txt', 'private-uploads/marker.txt',
        'storage/marker.txt', 'backups/marker.txt', 'secrets/marker.txt', 'credentials/marker.txt',
        '.build-tmp/marker.txt', '.aws/marker.txt', '.data/marker.txt',
        'apps/web/.env.local', 'apps/web/.npmrc', 'apps/web/synthetic-private.key',
        'apps/web/uploads/marker.txt', 'apps/web/private-uploads/marker.txt', 'apps/web/.build-tmp/marker.txt')
    foreach ($relative in $excluded) {
        $fixture = Join-Path $context $relative
        New-Item -ItemType Directory -Path (Split-Path $fixture -Parent) -Force | Out-Null
        [IO.File]::WriteAllText($fixture, 'Synthetic Docker context exclusion marker; no real secret or school data.')
    }
    $probeCode = "const fs=require('node:fs');for(const p of " + (ConvertTo-Json -Compress -InputObject $excluded) +
        "){if(fs.existsSync('/context/'+p))throw Error('Private context fixture leaked: '+p)}" +
        "for(const p of ['apps/web/src/app/page.tsx','apps/web/package.json','pnpm-workspace.yaml','.env.example'])" +
        "{if(!fs.existsSync('/context/'+p))throw Error('Required committed source missing: '+p)}" +
        "console.log('Actual Docker context excluded all 21 harmless privacy canaries and retained required source.')"
    $probeDockerfile = Join-Path $ownedDirectory 'Context.Dockerfile'
    $probeRun = ConvertTo-Json -Compress -InputObject @('node', '-e', $probeCode)
    $probeImage = Get-ApprovedWebRuntimeImage
    [IO.File]::WriteAllText($probeDockerfile, "FROM $probeImage`nCOPY . /context`nRUN $probeRun`n")
    & docker build --file $probeDockerfile --tag $probeTag $context
    if ($LASTEXITCODE -ne 0) { throw 'Actual Docker build-context privacy probe failed.' }
    $builtImages += $probeTag

    & docker build --file (Join-Path $context 'apps/web/Dockerfile') --tag $imageTag `
        --build-arg 'NEXT_PUBLIC_APP_URL=https://synthetic.example.test' `
        --build-arg 'NEXT_PUBLIC_API_URL=https://api.synthetic.example.test' $context
    if ($LASTEXITCODE -ne 0) { throw 'Real committed web Docker image did not build.' }
    $builtImages += $imageTag
    # No entry-point/command override, no host files, no school/provider service.
    $output = & docker run --detach --rm --name $containerName --publish '127.0.0.1::3000' `
        --security-opt 'no-new-privileges:true' --cap-drop ALL $imageTag
    if ($LASTEXITCODE -ne 0 -or "$output" -notmatch '^[a-f0-9]{64}$') { throw 'Owned web image could not start.' }
    $containerId = "$output".Trim()
    $binding = & docker port $containerId 3000/tcp
    if ($LASTEXITCODE -ne 0 -or "$binding".Trim() -notmatch '^127\.0\.0\.1:([0-9]+)$') { throw 'Image HTTP must be exposed only on a new loopback port.' }
    $base = "http://127.0.0.1:$($matches[1])"
    $http = [Net.Http.HttpClient]::new()
    $http.Timeout = [timespan]::FromSeconds(10)
    $deadline = [datetime]::UtcNow.AddSeconds(60)
    $homepageResponse = $null
    while ([datetime]::UtcNow -lt $deadline) {
        $running = & docker inspect --format '{{.State.Running}}' $containerId 2>$null
        if ($LASTEXITCODE -ne 0 -or $running -cne 'true') { throw 'Actual web container exited before HTTP readiness.' }
        try { $homepageResponse = Read-WebResponse '/'; if ($homepageResponse.Status -eq 200) { break } } catch { }
        Start-Sleep -Seconds 1
    }
    if ($null -eq $homepageResponse -or $homepageResponse.Status -ne 200) { throw 'Actual web image homepage never returned HTTP 200.' }
    foreach ($header in @{ 'X-Content-Type-Options'='nosniff'; 'X-Frame-Options'='DENY'; 'Referrer-Policy'='strict-origin-when-cross-origin' }.GetEnumerator()) {
        if ($homepageResponse.Headers[$header.Key] -cne $header.Value) { throw "Configured web security header missing: $($header.Key)" }
    }
    $uid = & docker exec $containerId id -u
    if ($LASTEXITCODE -ne 0 -or "$uid".Trim() -cne '10001') { throw 'Actual web process must run as non-root UID 10001.' }
    $nodeVersion = @(& docker exec $containerId node --version)
    if ($LASTEXITCODE -ne 0 -or $nodeVersion.Count -ne 1) { throw 'Actual web runtime Node.js version could not be read exactly.' }
    Assert-WebRuntimeNodeVersion $nodeVersion[0]
    $installedPackages = @(& docker exec $containerId apk info -v)
    if ($LASTEXITCODE -ne 0) { throw 'Actual web image package inventory could not be read.' }
    Assert-RuntimePackageInventory 'web' $installedPackages
    & docker exec $containerId sh -c 'test ! -e /usr/local/lib/node_modules/npm && test ! -L /usr/local/lib/node_modules/npm && test ! -e /usr/local/bin/npm && test ! -L /usr/local/bin/npm && test ! -e /usr/local/bin/npx && test ! -L /usr/local/bin/npx && ! command -v npm && ! command -v npx'
    if ($LASTEXITCODE -ne 0) { throw 'Unused npm/npx tooling remains in the actual final web image.' }
    & docker exec $containerId sh -c 'test ! -e /opt/yarn-v1.22.22 && test ! -L /opt/yarn-v1.22.22 && test ! -e /usr/local/bin/yarn && test ! -L /usr/local/bin/yarn && test ! -e /usr/local/bin/yarnpkg && test ! -L /usr/local/bin/yarnpkg && ! command -v yarn && ! command -v yarnpkg'
    if ($LASTEXITCODE -ne 0) { throw 'Unused Yarn Classic tooling remains in the actual final web image.' }
    Write-Output 'Actual final web image has approved Node.js 24.22.1, aligned musl, OpenSSL/zlib patches and no npm/npx commands, links or bundled npm tree; unchanged standalone startup remains live.'
    foreach ($asset in @('brand/rs-academy-logo.jpeg', 'animations/study-discussion.lottie', 'animations/dotlottie-player.wasm', 'sw.js')) {
        $expected = (Get-FileHash -LiteralPath (Join-Path $context "apps/web/public/$asset") -Algorithm SHA256).Hash.ToLowerInvariant()
        Assert-WebBytes "/$asset" $expected
    }
    foreach ($extension in @('css', 'woff2')) {
        $files = @(& docker exec $containerId find /app/apps/web/.next/static -type f -name "*.$extension")
        if ($LASTEXITCODE -ne 0 -or $files.Count -eq 0) { throw "Actual image is missing $extension assets." }
        $file = $files[0].Trim()
        if ($file -notmatch '^/app/apps/web/\.next/static/[a-zA-Z0-9._/-]+$') { throw 'Unexpected image asset path.' }
        $digest = & docker exec $containerId sha256sum $file
        if ($LASTEXITCODE -ne 0 -or "$digest" -notmatch '^([a-f0-9]{64})\s') { throw 'Image asset checksum failed.' }
        Assert-WebBytes ($file.Replace('/app/apps/web/.next/static/', '/_next/static/')) $matches[1]
    }
    $manifest = Read-WebResponse '/manifest.webmanifest'
    if ($manifest.Status -ne 200 -or $manifest.Type -notmatch 'json|manifest') { throw 'Actual image PWA manifest is unavailable.' }
    $manifestBody = [Text.Encoding]::UTF8.GetString($manifest.Bytes) | ConvertFrom-Json
    if ($manifestBody.start_url -cne '/portal' -or $manifestBody.scope -cne '/' -or $manifestBody.display -cne 'standalone') { throw 'Packaged PWA contract changed.' }
    $optimized = Read-WebResponse '/_next/image?url=%2Fbrand%2Frs-academy-logo.jpeg&w=64&q=75'
    if ($optimized.Status -ne 200 -or $optimized.Type -notmatch '^image/' -or $optimized.Bytes.Length -lt 128) { throw 'Native image optimization failed in the actual Linux runtime.' }
    Write-Output 'Real committed web image built and started with its default command; non-root UID 10001, loopback HTTP, security headers, branding/Lottie/WASM/CSS/font bytes, PWA files and native image optimization passed. No visual/keyboard, API/authenticated workflow, container-security scan or deployment approval is claimed.'
    & (Join-Path $PSScriptRoot 'verify-runtime-dependency-exposure.ps1') -Service web -Image $imageTag
    & (Join-Path $PSScriptRoot 'scan-runtime-image.ps1') -Service web -Image $imageTag
    & (Join-Path $PSScriptRoot 'verify-runtime-dependency-exposure.ps1') -Service web -Image $imageTag -InspectionTarget minimatch
} catch {
    if ($containerId) { & docker logs $containerId 2>&1 | Where-Object { $_ -notmatch '(?i)password|secret|credential|token' } | Select-Object -Last 25 }
    throw
} finally {
    if ($http) { $http.Dispose() }
    $cleanupFailed = $false
    if ($containerId -match '^[a-f0-9]{64}$') { & docker rm --force $containerId 2>$null | Out-Null; if ($LASTEXITCODE -ne 0) { $cleanupFailed = $true } }
    foreach ($tag in $builtImages) { & docker image rm $tag 2>$null | Out-Null; if ($LASTEXITCODE -ne 0) { $cleanupFailed = $true } }
    # Exact invocation-owned copy only. Refuse links, the workspace root or any
    # unexpected target before recursive cleanup; never remove running app files.
    $expectedDirectory = Join-Path $repository ".build-tmp/web-image-$invocation"
    if ((Resolve-Path -LiteralPath $ownedDirectory).Path -cne $expectedDirectory -or
        (Get-ChildItem -LiteralPath $ownedDirectory -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) {
        throw 'Unsafe generated image-test directory; cleanup refused.'
    }
    Remove-Item -LiteralPath $ownedDirectory -Recurse -Force
    if ($cleanupFailed) { throw 'Owned image/container cleanup failed; no successful gate is claimed.' }
}
