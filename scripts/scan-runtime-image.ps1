param([Parameter(Mandatory)][ValidateSet('web','api')][string]$Service,
      [Parameter(Mandatory)][string]$Image)
$ErrorActionPreference = 'Stop'
if (-not $IsLinux) { throw 'Actual image security scanning requires the Linux Docker CI runner; no scan is claimed.' }
. (Join-Path $PSScriptRoot 'runtime-image-security-common.ps1')
$repository = (Resolve-Path (Split-Path $PSScriptRoot -Parent)).Path
$id = [guid]::NewGuid().ToString('N')
$directory = Join-Path $repository ".build-tmp/image-scan-$id"
$reportPath = Join-Path $repository ".build-tmp/image-security-$Service-report.json"
if (Test-Path -LiteralPath $directory) { throw 'Refusing to reuse a generated scan directory.' }
if (Test-Path -LiteralPath $reportPath) { throw 'Existing scan evidence must not be overwritten or accepted as a fresh scan.' }
$commit = (& git -C $repository rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot bind the scan to its exact source commit.' }
Assert-ImageSecurityIdentity $Service $Image ('sha256:' + ('0' * 64)) $commit
if ($Service -ceq 'api') {
    # API builds from this checkout; web builds from its existing Git archive.
    # Do not label uncommitted application code with a committed-source identity.
    $dirty = @(& git -C $repository status --porcelain --untracked-files=all -- apps/api)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) { throw 'Actual API image evidence requires a clean committed API source checkout.' }
}
& git -C $repository diff --quiet HEAD -- "apps/$Service/Dockerfile"
if ($LASTEXITCODE -ne 0) { throw 'Actual runtime Dockerfile must match the recorded source commit.' }
# Read only the image identifier; never dump image config, environment or history.
$imageId = (& docker image inspect --format '{{.Id}}' $Image).Trim()
if ($LASTEXITCODE -ne 0) { throw 'The actual built runtime image is not available.' }
Assert-ImageSecurityIdentity $Service $Image $imageId $commit
$dockerfileSha256 = (Get-FileHash -LiteralPath (Join-Path $repository "apps/$Service/Dockerfile") -Algorithm SHA256).Hash.ToLowerInvariant()
New-Item -ItemType Directory -Path $directory | Out-Null
& chmod 700 $directory
if ($LASTEXITCODE -ne 0) { throw 'Cannot restrict the private scan directory.' }
$summary = $null

function Invoke-ImageScanCommand([string]$Executable, [string[]]$Arguments, [switch]$Scanner) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.WorkingDirectory = $directory
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    if ($Scanner) {
        # Child process only: no ambient user suppression or scanning options.
        foreach ($key in @($start.Environment.Keys | Where-Object { $_ -match '^(GRYPE|SYFT)_' })) { [void]$start.Environment.Remove($key) }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'Image assessment utility could not start.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(600000)) { $process.Kill($true); throw 'Image assessment exceeded its bounded timeout.' }
        if (-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)) { throw 'Image assessment output did not close.' }
        # Raw scanner errors/reports can contain source paths/configuration. Do not log them.
        return @{ code = $process.ExitCode; output = $stdout.GetAwaiter().GetResult() }
    } finally { $process.Dispose() }
}

try {
    $archive = Join-Path $directory 'grype.tar.gz'
    try {
        Invoke-WebRequest 'https://github.com/anchore/grype/releases/download/v0.120.0/grype_0.120.0_linux_amd64.tar.gz' -OutFile $archive -TimeoutSec 120
    } catch { throw 'Pinned scanner download failed; no scan is claimed.' }
    # Pin reviewed official release bytes, not a mutable install script/action/latest tag.
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        'a5a1218dce63acdac152a6b3b5bb366e7267e36f4069848cf455543b3fa5700e') { throw 'Pinned scanner archive checksum mismatch.' }
    $extract = Invoke-ImageScanCommand 'tar' @('--extract','--gzip','--file',$archive,'--directory',$directory,'grype')
    if ($extract.code -ne 0) { throw 'Verified scanner extraction failed.' }
    $binary = Join-Path $directory 'grype'
    $versionResult = Invoke-ImageScanCommand $binary @('version','--output','json') -Scanner
    if ($versionResult.code -ne 0) { throw 'Pinned scanner could not execute.' }
    $version = ConvertFrom-ImageSecurityJson $versionResult.output
    if ($version.application -cne 'grype' -or $version.version -cne '0.120.0' -or $version.platform -cne 'linux/amd64') { throw 'Unexpected scanner version/platform.' }
    $config = Join-Path $directory 'grype.yaml'
    $cache = (Join-Path $directory 'database') | ConvertTo-Json -Compress
    # Explicit config overrides ambient files. No user ignore/VEX/exclude policies,
    # fixed-only filters, external metadata lookup or insecure registry fallback.
    [IO.File]::WriteAllText($config, @"
check-for-app-update: false
only-fixed: false
only-notfixed: false
ignore: []
exclude: []
vex-documents: []
vex-add: []
external-sources:
  enable: false
search:
  scope: squashed
db:
  cache-dir: $cache
  auto-update: true
  validate-by-hash-on-start: true
  validate-age: true
  max-allowed-built-age: 120h
  require-update-check: true
"@)
    $imageArchive = Join-Path $directory 'runtime-image.tar'
    $save = Invoke-ImageScanCommand 'docker' @('image','save','--output',$imageArchive,$Image)
    if ($save.code -ne 0) { throw 'Actual runtime image export failed.' }
    $rawPath = Join-Path $directory 'raw-scan.json'
    # Archive input: the scanner receives no Docker socket, registry credentials,
    # live container environment, application network or school database access.
    $scan = Invoke-ImageScanCommand $binary @("docker-archive:$imageArchive",'--config',$config,
        '--output','json','--file',$rawPath,'--fail-on','high','--quiet') -Scanner
    if ($scan.code -notin @(0,2) -or -not (Test-Path -LiteralPath $rawPath)) { throw 'Scanner failed or did not produce complete actual-image evidence.' }
    $raw = ConvertFrom-ImageSecurityJson ([IO.File]::ReadAllText($rawPath))
    $summary = ConvertTo-ImageSecurityReport $raw $scan.code $Service $imageId $commit $dockerfileSha256
    Assert-ImageSecurityReport $summary $Service $commit $dockerfileSha256
} finally {
    # Only the exact newly generated private directory; no glob/workspace delete.
    $expected = [IO.Path]::GetFullPath((Join-Path $repository ".build-tmp/image-scan-$id"))
    if ((Resolve-Path -LiteralPath $directory).Path -cne $expected -or
        (Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint -or
        @(Get-ChildItem -LiteralPath $directory -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) {
        throw 'Unsafe generated scan directory; cleanup refused.'
    }
    Remove-Item -LiteralPath $directory -Recurse -Force
}
# Write only after raw image/config/report cleanup. Security findings remain in
# this allowlisted artifact even if the following severity gate correctly fails.
[IO.File]::WriteAllText($reportPath, ($summary | ConvertTo-Json -Depth 12))
$high = @($summary.findings | Where-Object { $_.severity -cin @('High','Critical') }).Count
Write-Output "Actual $Service runtime image assessment completed: $($summary.findings.Count) findings, $high HIGH/CRITICAL. Sanitized evidence saved; the separate fail-closed security gate must still pass."
