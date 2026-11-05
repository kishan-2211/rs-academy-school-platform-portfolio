# Pure configuration guards; no Docker, application, files, credentials or providers.
function Get-ComposeNotificationValues([ValidateSet('defaults','disabled','enabled')][string]$Mode) {
    $values = [ordered]@{}
    foreach ($group in @('GUARDIAN_INVITATION_EXPIRY','CALENDAR_NOTIFICATIONS','LEARNING_NOTIFICATIONS',
            'PARENT_REQUEST_NOTIFICATIONS','STUDENT_LEAVE_NOTIFICATIONS','PTM_REMINDERS')) {
        $values["APP_${group}_ENABLED"] = if ($Mode -ceq 'disabled') { 'false' } else { 'true' }
        $values["APP_${group}_POLL_DELAY_MS"] = if ($Mode -ceq 'enabled') { '62000' } else { '60000' }
        $values["APP_${group}_INITIAL_DELAY_MS"] = if ($Mode -ceq 'enabled') { '35000' } else { '30000' }
    }
    foreach ($flag in @('APP_PUSH_REGISTRATION_ENABLED','APP_PUSH_DELIVERY_ENABLED','APP_PUSH_WORKER_ENABLED')) {
        $values[$flag] = if ($Mode -ceq 'enabled') { 'true' } else { 'false' }
    }
    $values.APP_PUSH_POLL_DELAY_MS = if ($Mode -ceq 'enabled') { '18000' } else { '15000' }
    $values.APP_PUSH_INITIAL_DELAY_MS = if ($Mode -ceq 'enabled') { '36000' } else { '30000' }
    # These are invalid, clearly synthetic configuration markers, never usable credentials.
    $values.APP_PUSH_APPLICATION_SERVER_KEY = if ($Mode -ceq 'enabled') { 'synthetic-config-only-public' } else { '' }
    $values.APP_PUSH_DELIVERY_PRIVATE_KEY = if ($Mode -ceq 'enabled') { 'synthetic-config-only-private' } else { '' }
    $values.APP_PUSH_DELIVERY_SUBJECT = if ($Mode -ceq 'enabled') { 'mailto:synthetic-owner@example.test' } else { '' }
    $values.APP_PUSH_DELIVERY_ALLOWED_HOSTS = if ($Mode -ceq 'enabled') { 'fcm.googleapis.com' } else {
        'fcm.googleapis.com,updates.push.services.mozilla.com,web.push.apple.com'
    }
    $values.APP_PUSH_DELIVERY_REQUEST_TIMEOUT_SECONDS = if ($Mode -ceq 'enabled') { '12' } else { '10' }
    return $values
}

function Assert-ComposeNotificationEnvironment($Environment, $Expected) {
    if ($Environment -isnot [System.Collections.IDictionary] -or $Environment.Count -gt 256 -or
        $Expected -isnot [System.Collections.IDictionary] -or $Expected.Count -ne 28) {
        throw 'Missing, incomplete or unbounded effective Compose settings.'
    }
    foreach ($key in $Expected.Keys) {
        if ($Environment[$key] -isnot [string] -or $Environment[$key] -cne $Expected[$key]) {
            # Never print values or the complete environment, including on failure.
            throw "Controlled Compose notification setting is missing or incorrect: $key"
        }
    }
    if ($Environment.APP_BOOTSTRAP_ENABLED -cne 'false' -or $Environment.S3_AUTO_CREATE_BUCKET -cne 'false' -or
        $Environment.Contains('MINIO_ROOT_USER') -or $Environment.Contains('MINIO_ROOT_PASSWORD')) {
        throw 'Existing bootstrap/private-storage boundaries changed.'
    }
}
