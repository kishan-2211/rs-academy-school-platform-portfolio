Set-StrictMode -Version Latest

function Assert-PrivateFilesTestProject([string]$Project) {
    if ($Project -cnotmatch '^educore-files-test-[a-f0-9]{12}$') {
        throw 'Only the generated disposable private-file project may be cleaned up.'
    }
}

function Assert-PrivateFilesCleanupResult([int]$ExitCode) {
    if ($ExitCode -ne 0) {
        throw 'Disposable private-file project cleanup failed. Real-service verification is incomplete.'
    }
}
