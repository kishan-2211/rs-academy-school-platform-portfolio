param([string]$JarPath = 'apps/api/target/educore-api-0.1.0-SNAPSHOT.jar')
$ErrorActionPreference = 'Stop'
$firstAdminJar = [IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $firstAdminJar -PathType Leaf)) { throw 'Reviewed packaged API artifact is missing.' }
$firstAdminJava = Get-Command java -CommandType Application | Select-Object -First 1
if ($null -eq $firstAdminJava) { throw 'Java runtime is unavailable.' }
$firstAdminWork = Join-Path ([IO.Path]::GetTempPath()) ('educore-first-admin-package-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $firstAdminWork | Out-Null
try {
foreach ($firstAdminExtra in @('', 'unexpected-nonsecret-argument')) {
    $firstAdminStart = [Diagnostics.ProcessStartInfo]::new()
    $firstAdminStart.FileName = $firstAdminJava.Source
    $firstAdminStart.UseShellExecute = $false
    $firstAdminStart.CreateNoWindow = $true
    $firstAdminStart.RedirectStandardInput = $true
    $firstAdminStart.RedirectStandardOutput = $true
    $firstAdminStart.RedirectStandardError = $true
    $firstAdminStart.WorkingDirectory = $firstAdminWork
    foreach ($firstAdminName in @($firstAdminStart.Environment.Keys)) {
        if ($firstAdminName -match '^(?:MIGRATION_|DATABASE_|FIRST_ADMIN_|LOADER_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)') {
            $firstAdminStart.Environment.Remove($firstAdminName) | Out-Null
        }
    }
    foreach ($firstAdminArgument in @('-Dloader.main=com.educore.api.identity.FirstAdministratorTool','-cp',$firstAdminJar,
            'org.springframework.boot.loader.launch.PropertiesLauncher')) { $firstAdminStart.ArgumentList.Add($firstAdminArgument) }
    if ($firstAdminExtra) { $firstAdminStart.ArgumentList.Add($firstAdminExtra) }
    $firstAdminProcess = [Diagnostics.Process]::new(); $firstAdminProcess.StartInfo = $firstAdminStart
    try {
        if (-not $firstAdminProcess.Start()) { throw 'Packaged first-administrator process did not start.' }
        $firstAdminProcess.StandardInput.Close()
        $firstAdminOutput = $firstAdminProcess.StandardOutput.ReadToEndAsync()
        $firstAdminError = $firstAdminProcess.StandardError.ReadToEndAsync()
        if (-not $firstAdminProcess.WaitForExit(30000)) {
            $firstAdminProcess.Kill($true); $firstAdminProcess.WaitForExit()
            throw 'Packaged first-administrator refusal exceeded its time bound.'
        }
        $firstAdminExpected = 'First administrator setup refused or failed. No partial account was committed. Review the operator runbook privately.'
        if ($firstAdminProcess.ExitCode -ne 1 -or $firstAdminOutput.GetAwaiter().GetResult().Length -ne 0 -or
            $firstAdminError.GetAwaiter().GetResult().Trim() -cne $firstAdminExpected) {
            throw 'Actual packaged first-administrator refusal did not match its no-server/no-secret boundary.'
        }
    } finally { $firstAdminProcess.Dispose() }
}
} finally {
    $firstAdminResolvedWork = [IO.Path]::GetFullPath($firstAdminWork)
    $firstAdminResolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $firstAdminResolvedWork.StartsWith($firstAdminResolvedTemp + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $firstAdminResolvedWork -Leaf) -cnotmatch '^educore-first-admin-package-[a-f0-9]{32}$') {
        throw 'Unexpected generated package-verification path; refusing cleanup.'
    }
    if (Test-Path -LiteralPath $firstAdminWork) { Remove-Item -LiteralPath $firstAdminWork }
}
Write-Output 'Actual packaged first-administrator entry point refused noninteractive/argument use without Spring/HTTP/workers, private output or account provisioning.'
