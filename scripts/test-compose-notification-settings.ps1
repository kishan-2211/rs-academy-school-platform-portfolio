param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'compose-notification-settings-common.ps1')

# Pure synthetic guard fixtures only. These do not execute or emulate Docker.
$script:settingsAccepted = 0
$script:settingsRejected = 0
function New-SettingsFixture($Expected) {
    $fixture = [ordered]@{}
    foreach ($key in $Expected.Keys) { $fixture[$key] = $Expected[$key] }
    $fixture.APP_BOOTSTRAP_ENABLED = 'false'
    $fixture.S3_AUTO_CREATE_BUCKET = 'false'
    return $fixture
}
function Assert-SettingsRejected($Environment, $Expected) {
    $failed = $false
    try { Assert-ComposeNotificationEnvironment $Environment $Expected }
    catch { $failed = $true }
    if (-not $failed) { throw 'Invalid synthetic configuration fixture was accepted.' }
    $script:settingsRejected++
}
foreach ($mode in @('defaults','disabled','enabled')) {
    $expected = Get-ComposeNotificationValues $mode
    if ($expected.Count -ne 28) { throw 'Controlled notification-setting coverage changed.' }
    Assert-ComposeNotificationEnvironment (New-SettingsFixture $expected) $expected
    $script:settingsAccepted++
    foreach ($key in $expected.Keys) {
        $fixture = New-SettingsFixture $expected
        $fixture.Remove($key)
        Assert-SettingsRejected $fixture $expected
        $fixture = New-SettingsFixture $expected
        $fixture[$key] = 'incorrect-synthetic-marker'
        Assert-SettingsRejected $fixture $expected
        $fixture = New-SettingsFixture $expected
        $fixture[$key] = $true
        Assert-SettingsRejected $fixture $expected
    }
    foreach ($boundary in @('APP_BOOTSTRAP_ENABLED','S3_AUTO_CREATE_BUCKET')) {
        $fixture = New-SettingsFixture $expected
        $fixture[$boundary] = 'true'
        Assert-SettingsRejected $fixture $expected
        $fixture = New-SettingsFixture $expected
        $fixture.Remove($boundary)
        Assert-SettingsRejected $fixture $expected
    }
    foreach ($rootSetting in @('MINIO_ROOT_USER','MINIO_ROOT_PASSWORD')) {
        $fixture = New-SettingsFixture $expected
        $fixture[$rootSetting] = 'invalid-config-only-marker'
        Assert-SettingsRejected $fixture $expected
    }
    Assert-SettingsRejected $null $expected
    Assert-SettingsRejected @('not-a-map') $expected
    $oversized = New-SettingsFixture $expected
    for ($index = 0; $index -lt 257; $index++) { $oversized["SYNTHETIC_$index"] = '' }
    Assert-SettingsRejected $oversized $expected
    Assert-SettingsRejected (New-SettingsFixture $expected) $null
    Assert-SettingsRejected (New-SettingsFixture $expected) @{}
}
Write-Output "Pure Compose notification guards: $script:settingsAccepted valid and $script:settingsRejected invalid fixtures passed. No settings/credentials printed; not real Docker interpolation evidence."
