param([Parameter(Mandatory)][ValidateSet('web','api')][string]$Service,
      [Parameter(Mandatory)][string]$Image,
      [ValidateSet('braces','minimatch')][string]$InspectionTarget = 'braces')
$ErrorActionPreference = 'Stop'
if (-not $IsLinux) { throw 'Final-image exposure proof requires real Linux Docker; no local image absence is inferred.' }
. (Join-Path $PSScriptRoot 'runtime-image-security-common.ps1')
. (Join-Path $PSScriptRoot 'runtime-dependency-exposure-common.ps1')
. (Join-Path $PSScriptRoot 'minimatch-exposure-report-common.ps1')
Assert-DependencyInspectionTarget $InspectionTarget
Add-Type -AssemblyName System.Formats.Tar
$repository = (Resolve-Path (Split-Path $PSScriptRoot -Parent)).Path
$commit = (& git -C $repository rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot bind exposure proof to committed source.' }
$imageId = (& docker image inspect --format '{{.Id}}' $Image).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Final runtime image is missing.' }
Assert-ImageSecurityIdentity $Service $Image $imageId $commit
function Read-MinimatchSourceIdentity {
    & git -C $repository diff --quiet HEAD -- "apps/$Service/Dockerfile" 'pnpm-lock.yaml'
    if ($LASTEXITCODE -ne 0) { throw 'Final-image source metadata differs from the committed candidate.' }
    $currentCommit = (& git -C $repository rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Current candidate identity could not be read.' }
    return @{ commit = $currentCommit;
        dockerfileSha256 = (Get-FileHash -LiteralPath (Join-Path $repository "apps/$Service/Dockerfile") -Algorithm SHA256).Hash.ToLowerInvariant();
        lockfileSha256 = (Get-FileHash -LiteralPath (Join-Path $repository 'pnpm-lock.yaml') -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$sourceBefore = if ($InspectionTarget -ceq 'minimatch') { Read-MinimatchSourceIdentity } else { $null }
if ($sourceBefore -and $sourceBefore.commit -cne $commit) { throw 'Candidate changed before image inspection.' }
$invocation = [guid]::NewGuid().ToString('N')
$directory = Join-Path $repository ".build-tmp/dependency-exposure-$invocation"
$reportName = if ($InspectionTarget -ceq 'minimatch') { "dependency-minimatch-exposure-$Service.json" } else { "dependency-exposure-$Service.json" }
$reportPath = Join-Path $repository ".build-tmp/$reportName"
$diagnosticPath = Join-Path $repository ".build-tmp/dependency-minimatch-findings-$Service.json"
if ((Test-Path -LiteralPath $directory) -or (Test-Path -LiteralPath $reportPath) -or
    ($InspectionTarget -ceq 'minimatch' -and (Test-Path -LiteralPath $diagnosticPath))) { throw 'Refusing reused or stale runtime exposure evidence.' }
New-Item -ItemType Directory -Path $directory | Out-Null
& chmod 700 $directory
if ($LASTEXITCODE -ne 0) { throw 'Private inventory directory permissions failed.' }
$container = $null
$counts = @{ filesystemMembers = 0; manifests = 0; javascript = 0; jars = 0; archiveMembers = 0; contentMembers = 0 }
$inspectionState = @{ present = $false }
$diagnostics = if ($InspectionTarget -ceq 'minimatch') { New-MinimatchDiagnosticState } else { $null }
try {
    # Never start it; inspect the final image filesystem without app/network access,
    # host mounts, runtime credentials or build layers.
    $container = & docker create --name "educore-dependency-exposure-$invocation" --entrypoint /bin/true $imageId
    if ($LASTEXITCODE -ne 0 -or $container -cnotmatch '^[a-f0-9]{64}$') { throw 'Invocation-owned image inventory container could not be created.' }
    $mounts = & docker inspect --format '{{len .Mounts}}' $container
    if ($LASTEXITCODE -ne 0 -or "$mounts" -cne '0') { throw 'Mounts could hide shipped image bytes; refusing absence claim.' }
    $rootfs = Join-Path $directory 'final-rootfs.tar'
    & docker export --output $rootfs $container
    if ($LASTEXITCODE -ne 0) { throw 'Final image filesystem export failed.' }
    $file = [IO.File]::OpenRead($rootfs)
    $tar = [System.Formats.Tar.TarReader]::new($file, $true)
    try {
        while ($null -ne ($entry = $tar.GetNextEntry())) {
            $counts.filesystemMembers++
            if ($counts.filesystemMembers -gt 200000) { throw 'Unbounded final image inventory.' }
            $pathMatched=Test-RuntimeDependencyPath $entry.Name $InspectionTarget
            if ($pathMatched) {
                if ($InspectionTarget -ceq 'braces') { throw 'Affected development dependency found in final image; no exception permitted.' }
                Add-MinimatchExposureFinding $inspectionState
            }
            if ($null -eq $entry.DataStream -or $entry.Name -notmatch '(?i)(^|/)package\.json$|\.(?:[cm]?js|map|jar)$') {
                if ($InspectionTarget -ceq 'minimatch' -and $pathMatched) { Add-MinimatchUnfingerprintedMatch $diagnostics }
                continue
            }
            $bytes = Read-BoundedExposureBytes $entry.DataStream $entry.Length
            if ($entry.Name -match '(?i)\.jar$') {
                $counts.jars++
                $jar = [IO.MemoryStream]::new($bytes, $false)
                if ($InspectionTarget -ceq 'minimatch' -and $pathMatched) {
                    Add-MinimatchFileDiagnostic $diagnostics @($entry.Name) $bytes 'package-path'
                }
                try { Inspect-RuntimeDependencyJar $jar $counts 0 $InspectionTarget $inspectionState @($entry.Name) $diagnostics } finally { $jar.Dispose() }
            } else {
                if ($entry.Name -match '(^|/)package\.json$') { $counts.manifests++ } else { $counts.javascript++ }
                $contentMatched=Test-RuntimeDependencyContent $entry.Name $bytes $InspectionTarget
                if ($contentMatched) {
                    if ($InspectionTarget -ceq 'braces') { throw 'Affected development dependency found in final runtime content; no exception permitted.' }
                    Add-MinimatchExposureFinding $inspectionState
                }
                if ($InspectionTarget -ceq 'minimatch' -and ($pathMatched -or $contentMatched)) {
                    Add-MinimatchFileDiagnostic $diagnostics @($entry.Name) $bytes (Get-MinimatchDiagnosticKind $entry.Name $bytes $pathMatched)
                }
            }
        }
    } finally { $tar.Dispose(); $file.Dispose() }
    if ($counts.filesystemMembers -eq 0 -or ($Service -ceq 'web' -and ($counts.manifests -eq 0 -or $counts.javascript -eq 0)) -or
        ($Service -ceq 'api' -and $counts.jars -eq 0)) { throw 'Incomplete final image inventory.' }
} finally {
    if ($container -cmatch '^[a-f0-9]{64}$') {
        & docker rm $container *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Owned image inventory cleanup failed.' }
    }
    $expected = [IO.Path]::GetFullPath((Join-Path $repository ".build-tmp/dependency-exposure-$invocation"))
    if ((Resolve-Path -LiteralPath $directory).Path -cne $expected -or
        (Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint -or
        @(Get-ChildItem -LiteralPath $directory -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'Unsafe private inventory cleanup target.' }
    Remove-Item -LiteralPath $directory -Recurse -Force
}
$report = @{ format = 1; service = $Service; commit = $commit; imageId = $imageId;
    dockerfileSha256 = (Get-FileHash -LiteralPath (Join-Path $repository "apps/$Service/Dockerfile") -Algorithm SHA256).Hash.ToLowerInvariant();
    lockfileSha256 = (Get-FileHash -LiteralPath (Join-Path $repository 'pnpm-lock.yaml') -Algorithm SHA256).Hash.ToLowerInvariant();
    source = 'final-image-docker-export'; inspectorVersion = 1; completedAt = [datetime]::UtcNow.ToString('o');
    counts = $counts; affectedLibraryPresent = $false; cleanupSucceeded = $true }
if ($InspectionTarget -ceq 'minimatch') {
    Assert-MinimatchExposureSourceIdentity $sourceBefore (Read-MinimatchSourceIdentity)
    $report.dockerfileSha256 = $sourceBefore.dockerfileSha256
    $report.lockfileSha256 = $sourceBefore.lockfileSha256
    $report.inspectionTarget = 'minimatch'
    $report.affectedLibraryPresent = $inspectionState.present
    $report.result = if ($inspectionState.present) { 'present' } else { 'absent' }
    Assert-MinimatchExposureReport $report $Service $commit $sourceBefore.dockerfileSha256 $sourceBefore.lockfileSha256 $imageId
}
[IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 4))
if ($InspectionTarget -ceq 'minimatch') {
    $exposureHash=(Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $diagnosticReport=New-MinimatchDiagnosticReport $report $exposureHash $diagnostics
    Assert-MinimatchDiagnosticReport $diagnosticReport $report $exposureHash
    [IO.File]::WriteAllText($diagnosticPath,($diagnosticReport | ConvertTo-Json -Depth 7))
    Write-Output "Actual final $Service image minimatch inspection: $($report.result). Private export removed; evidence only, no dependency-policy exception or release pass granted."
    Write-Output "Bounded diagnostic artifact: $($diagnostics.totalFindings) file matches; truncated=$($diagnostics.truncated); unfingerprinted=$($diagnostics.unfingerprintedMatches). No file contents published."
} else {
    Write-Output "Actual final $Service image: affected braces/lint libraries absent from package paths/manifests, identifiable runtime imports and JS/JAR content. Private export removed."
}
