param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'compose-notification-settings-common.ps1')
$settingsRepository = (Resolve-Path (Split-Path $PSScriptRoot -Parent)).Path
$settingsDocker = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $settingsDocker) { throw 'Docker CLI/Compose is unavailable; no actual configuration-interpolation pass is claimed.' }
$settingsSource = Join-Path $settingsRepository 'infra/compose.yaml'
if ([IO.File]::ReadAllText($settingsSource) -match '(?m)^\s*(env_file|extends|include)\s*:') {
    throw 'External environment/configuration sources require separate review; refusing to read them.'
}
$settingsId = [guid]::NewGuid().ToString('N')
$settingsRoot = [IO.Path]::GetFullPath((Join-Path $settingsRepository ".build-tmp/compose-settings-$settingsId"))
$settingsParent = [IO.Path]::GetFullPath((Join-Path $settingsRepository '.build-tmp'))
if ((Split-Path $settingsRoot -Parent) -cne $settingsParent -or (Test-Path -LiteralPath $settingsRoot)) {
    throw 'Unexpected test-only configuration directory.'
}
New-Item -ItemType Directory -Path $settingsRoot | Out-Null
$settingsEmptyEnv = Join-Path $settingsRoot 'empty.env'
New-Item -ItemType File -Path $settingsEmptyEnv | Out-Null
try {
    foreach ($mode in @('defaults','disabled','enabled')) {
        $expected = Get-ComposeNotificationValues $mode
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = $settingsDocker.Source
        $start.WorkingDirectory = $settingsRepository
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        # Never inherit developer .env selection, credentials, provider flags or Docker targets.
        $start.Environment.Clear()
        foreach ($ordinary in @('PATH','SystemRoot','WINDIR','TEMP','TMP','HOME','USERPROFILE','LOCALAPPDATA')) {
            $value = [Environment]::GetEnvironmentVariable($ordinary,'Process')
            if ($null -ne $value) { $start.Environment[$ordinary] = $value }
        }
        $start.Environment['COMPOSE_DISABLE_ENV_FILE'] = '1'
        foreach ($pair in @{
            POSTGRES_DB='educore_compose_synthetic'; POSTGRES_USER='synthetic-migrator';
            POSTGRES_PASSWORD='synthetic-config-not-used'; DATABASE_USER='synthetic-runtime';
            DATABASE_PASSWORD='synthetic-runtime-not-used'; REDIS_PASSWORD='synthetic-config-not-used';
            MINIO_ROOT_USER='synthetic-administrator'; MINIO_ROOT_PASSWORD='synthetic-config-not-used';
            S3_ACCESS_KEY='synthetic-bucket-identity'; S3_SECRET_KEY='synthetic-config-not-used';
            APP_ENCRYPTION_KEY='synthetic-config-not-used';
            APP_PUBLIC_ORGANISATION_ID='10000000-0000-4000-8000-000000000001';
            APP_PUBLIC_SCHOOL_ID='10000000-0000-4000-8000-000000000002';
            APP_PUBLIC_URL='https://synthetic.example.test'; APP_ALLOWED_ORIGINS='https://synthetic.example.test';
            APP_COOKIE_SECURE='true'
        }.GetEnumerator()) { $start.Environment[$pair.Key] = $pair.Value }
        if ($mode -cne 'defaults') {
            foreach ($key in $expected.Keys) { $start.Environment[$key] = $expected[$key] }
        }
        foreach ($argument in @('compose','--project-name',"educore-settings-$settingsId",
                '--env-file',$settingsEmptyEnv,'--file',$settingsSource,'--profile','application',
                'config','--format','json')) { $start.ArgumentList.Add($argument) }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $start
        try {
            if (-not $process.Start()) { throw 'Read-only Compose configuration process did not start.' }
            $outputTask = $process.StandardOutput.ReadToEndAsync()
            $errorTask = $process.StandardError.ReadToEndAsync()
            if (-not $process.WaitForExit(30000)) {
                $process.Kill($true)
                $process.WaitForExit()
                throw 'Owned read-only Compose configuration process exceeded its bound.'
            }
            $output = $outputTask.GetAwaiter().GetResult()
            $null = $errorTask.GetAwaiter().GetResult()
            if ($process.ExitCode -ne 0 -or $output.Length -gt 1048576) {
                throw 'Real Compose configuration failed or was unbounded; raw output withheld.'
            }
            try { $configuration = ConvertFrom-Json -AsHashtable -InputObject $output -ErrorAction Stop }
            catch { throw 'Effective Compose JSON is invalid; raw configuration withheld.' }
            Assert-ComposeNotificationEnvironment $configuration.services.api.environment $expected
            Write-Output "Actual no-start Compose interpolation passed: $mode, all 28 controlled settings. No environment, credentials or raw config printed."
        } finally { $process.Dispose() }
    }
    Write-Output 'Three actual Compose configuration modes verified; no containers, services, networks, volumes or providers started. No deployment acceptance claimed.'
} finally {
    # This directory contains only the invocation's empty file. Never recursively clean shared paths.
    $settingsItem = Get-Item -LiteralPath $settingsRoot
    if (($settingsItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        @(Get-ChildItem -LiteralPath $settingsRoot -Force).Count -ne 1 -or
        ((Get-Item -LiteralPath $settingsEmptyEnv).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Unexpected generated configuration contents; cleanup refused.'
    }
    Remove-Item -LiteralPath $settingsEmptyEnv
    Remove-Item -LiteralPath $settingsRoot
}
