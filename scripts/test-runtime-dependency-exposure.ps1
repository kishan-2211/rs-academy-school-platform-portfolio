# Pure content-reader fixtures, not evidence from real production images.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-dependency-exposure-common.ps1')
foreach ($path in @('app/node_modules/braces/index.js','app/node_modules/.pnpm/braces@3.0.3/node_modules/braces/package.json',
    'app/node_modules/next/dist/compiled/micromatch/index.js','BOOT-INF/classes/node_modules/braces/index.js')) {
    if (-not (Test-RuntimeDependencyPath $path)) { throw 'Affected package/bundle path fixture was missed.' }
}
foreach ($text in @('{"name":"braces","version":"3.0.3"}', '{"name":"synthetic","dependencies":{"braces":"3.0.3"}}')) {
    if (-not (Test-RuntimeDependencyContent 'package.json' ([Text.Encoding]::UTF8.GetBytes($text)))) { throw 'Affected manifest fixture was missed.' }
}
foreach ($text in @('require("braces")','import("braces/lib/parse")','import braces from "braces"','braces.expand(pattern)')) {
    if (-not (Test-RuntimeDependencyContent 'synthetic.js' ([Text.Encoding]::UTF8.GetBytes($text)))) { throw 'Affected runtime import fixture was missed.' }
}
foreach ($text in @('const braces = []; braces.push("synthetic picomatch state");', 'const text = "React prose about curly braces";')) {
    if (Test-RuntimeDependencyContent 'synthetic.js' ([Text.Encoding]::UTF8.GetBytes($text))) { throw 'Unrelated brace syntax became the affected npm library.' }
}
if (Test-RuntimeDependencyContent 'package.json' ([Text.Encoding]::UTF8.GetBytes('{"name":"next","devDependencies":{"micromatch":"4.0.8"}}'))) { throw 'Unshipped development manifest declaration became runtime inclusion.' }
$archiveBytes = [IO.MemoryStream]::new()
try {
    $zip = [IO.Compression.ZipArchive]::new($archiveBytes, [IO.Compression.ZipArchiveMode]::Create, $true)
    $entry = $zip.CreateEntry('BOOT-INF/classes/node_modules/braces/package.json')
    $stream = $entry.Open()
    try { $bytes = [Text.Encoding]::UTF8.GetBytes('{"name":"braces"}'); $stream.Write($bytes) } finally { $stream.Dispose() }
    $zip.Dispose(); $archiveBytes.Position = 0
    $denied = $false
    try { Inspect-RuntimeDependencyJar $archiveBytes @{ archiveMembers = 0; contentMembers = 0 } } catch { $denied = $true }
    if (-not $denied) { throw 'Affected library hidden in an archive was accepted.' }
} finally { $archiveBytes.Dispose() }
$denied = $false
try { Read-BoundedExposureBytes ([IO.MemoryStream]::new([byte[]](1,2))) 3 | Out-Null } catch { $denied = $true }
if (-not $denied) { throw 'Truncated evidence was accepted.' }
Write-Output 'Runtime dependency inventory guards passed: affected paths/manifests/imports/archive entries detected; unrelated brace syntax and dev declarations distinguished. No real image absence claimed.'
