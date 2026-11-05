# Native, bounded content inspection; no app execution, credentials or package installation.
function Assert-DependencyInspectionTarget([string]$InspectionTarget) {
    if ($InspectionTarget -cnotin @('braces','minimatch')) { throw 'Unknown dependency inspection target.' }
}
function Assert-MinimatchMemberPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path.Length -gt 4096 -or
        $Path -match '[\x00-\x1f\x7f\\]' -or $Path -match '^/|^[A-Za-z]:|(^|/)\.\.(/|$)') {
        throw 'Malformed or external dependency inventory member path.'
    }
}
function Add-MinimatchExposureFinding([hashtable]$InspectionState) {
    if ($null -eq $InspectionState -or $InspectionState.Count -ne 1 -or
        -not $InspectionState.ContainsKey('present') -or $InspectionState.present -isnot [bool]) {
        throw 'Bounded dependency inspection state is required.'
    }
    $InspectionState.present = $true
}
function New-MinimatchDiagnosticState {
    return @{ totalFindings=0; truncated=$false; unfingerprintedMatches=0; findings=[Collections.Generic.List[object]]::new() }
}
function Assert-MinimatchDiagnosticLocation([string[]]$LocationChain) {
    if ($LocationChain.Count -lt 1 -or $LocationChain.Count -gt 4 -or
        ($LocationChain -join '!').Length -gt 512) { throw 'Unbounded diagnostic location chain.' }
    for ($index=0;$index -lt $LocationChain.Count;$index++) {
        $location=$LocationChain[$index]
        Assert-MinimatchMemberPath $location
        if ($location -cnotmatch '\A[A-Za-z0-9_@+.,/$~=\-\[\]\(\)]+\z') { throw 'Non-allowlisted diagnostic library path.' }
        if (($index -lt $LocationChain.Count-1 -and $location -notmatch '(?i)\.jar$') -or
            ($index -eq $LocationChain.Count-1 -and $location -notmatch '(?i)(^|/)package\.json$|\.(?:[cm]?js|map|jar)$')) {
            throw 'Diagnostic locations must identify inspected code/manifests and their containing JARs.'
        }
    }
}
function Assert-MinimatchDiagnosticState([hashtable]$Diagnostics) {
    if ($null -eq $Diagnostics -or $Diagnostics.Count -ne 4 -or
        $Diagnostics.totalFindings -isnot [int] -or $Diagnostics.totalFindings -lt 0 -or $Diagnostics.totalFindings -gt 200000 -or
        $Diagnostics.unfingerprintedMatches -isnot [int] -or $Diagnostics.unfingerprintedMatches -lt 0 -or $Diagnostics.unfingerprintedMatches -gt 200000 -or
        $Diagnostics.truncated -isnot [bool] -or $Diagnostics.findings -isnot [Collections.IList] -or $Diagnostics.findings.Count -gt 32) {
        throw 'Malformed bounded diagnostic collection state.'
    }
}
function Add-MinimatchUnfingerprintedMatch([hashtable]$Diagnostics) {
    if ($null -eq $Diagnostics) { return }
    Assert-MinimatchDiagnosticState $Diagnostics
    if ($Diagnostics.unfingerprintedMatches -eq 200000) { throw 'Unbounded unfingerprinted diagnostic matches.' }
    $Diagnostics.unfingerprintedMatches++
}
function Add-MinimatchFileDiagnostic([hashtable]$Diagnostics,[string[]]$LocationChain,[byte[]]$Bytes,[string]$MatchKind) {
    if ($null -eq $Diagnostics) { return }
    Assert-MinimatchDiagnosticState $Diagnostics
    Assert-MinimatchDiagnosticLocation $LocationChain
    if ($null -eq $Bytes -or $Bytes.Length -gt 64MB -or
        $MatchKind -cnotin @('package-path','manifest','minimatch-reference','optimizer-method')) {
        throw 'Unknown or incomplete library diagnostic evidence.'
    }
    if ($Diagnostics.totalFindings -eq 200000) { throw 'Unbounded library diagnostic findings.' }
    $Diagnostics.totalFindings++
    if ($Diagnostics.findings.Count -eq 32) { $Diagnostics.truncated=$true; return }
    $Diagnostics.findings.Add(@{ locationChain=@($LocationChain); matchKind=$MatchKind;
        sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant() })
}
function Get-MinimatchDiagnosticKind([string]$Path,[byte[]]$Bytes,[bool]$PathMatched) {
    if ($PathMatched) { return 'package-path' }
    if ($Path -match '(?i)(^|/)package\.json$') { return 'manifest' }
    $text=[Text.UTF8Encoding]::new($false,$true).GetString($Bytes)
    if ($text -match '(?i)\bminimatch\b') { return 'minimatch-reference' }
    return 'optimizer-method'
}
function Test-RuntimeDependencyPath([string]$Path, [string]$InspectionTarget = 'braces') {
    Assert-DependencyInspectionTarget $InspectionTarget
    if ($InspectionTarget -ceq 'minimatch') {
        Assert-MinimatchMemberPath $Path
        return $Path -match '(?i)(^|/)minimatch(@[^/]+)?(/|$)|next/dist/compiled/minimatch(/|$)'
    }
    return $Path -match '(?i)(^|/)(braces|micromatch|fast-glob)(@[^/]+)?(/|$)|next/dist/compiled/micromatch(/|$)'
}
function Test-RuntimeDependencyContent([string]$Path, [byte[]]$Bytes, [string]$InspectionTarget = 'braces') {
    Assert-DependencyInspectionTarget $InspectionTarget
    if ($Bytes.Length -gt 64MB) { throw 'Unbounded runtime dependency evidence.' }
    if ($InspectionTarget -ceq 'minimatch') {
        Assert-MinimatchMemberPath $Path
        if ($null -eq $Bytes) { throw 'Missing dependency content evidence.' }
        $text = [Text.UTF8Encoding]::new($false,$true).GetString($Bytes)
        if ($Path -match '(?i)(^|/)package\.json$') {
            $manifest = $text | ConvertFrom-Json -AsHashtable -Depth 100 -ErrorAction Stop
            if ($manifest -isnot [System.Collections.IDictionary]) { throw 'Invalid runtime package manifest.' }
            $present = $false
            if ($manifest.Contains('name')) {
                if ($manifest.name -isnot [string] -or [string]::IsNullOrWhiteSpace($manifest.name)) { throw 'Invalid runtime package name.' }
                if ($manifest.name -ieq 'minimatch') { $present = $true }
            }
            foreach ($kind in @('dependencies','optionalDependencies')) {
                if ($manifest.Contains($kind)) {
                    if ($manifest[$kind] -isnot [System.Collections.IDictionary]) { throw 'Malformed runtime dependency declarations.' }
                    if (@($manifest[$kind].Keys | Where-Object { $_ -ieq 'minimatch' }).Count) { $present = $true }
                }
            }
            return $present
        }
        # Detect named imports as well as bundled legacy/modern implementations,
        # even when the vendor manifest names glob rather than its included matcher.
        # A hit is presence evidence only; no runtime exception is granted here.
        return $text -match '(?i)\bminimatch\b|\b(?:firstPhasePreProcess|secondPhasePreProcess|levelTwoFileOptimize|adjascentGlobstarOptimize)\s*\('
    }
    $text = [Text.Encoding]::UTF8.GetString($Bytes)
    if ($Path -match '(^|/)package\.json$') {
        $manifest = $text | ConvertFrom-Json -AsHashtable
        if ($manifest -isnot [System.Collections.IDictionary]) { throw 'Invalid runtime package manifest.' }
        if ($manifest.name -cin @('braces','micromatch','fast-glob')) { return $true }
        foreach ($kind in @('dependencies','optionalDependencies')) {
            if ($manifest[$kind] -is [System.Collections.IDictionary] -and
                @($manifest[$kind].Keys | Where-Object { $_ -cin @('braces','micromatch','fast-glob') }).Count) { return $true }
        }
    }
    # Imports/API calls identify the affected library, not unrelated picomatch
    # brace syntax or React's prose mentioning curly braces. Include source maps.
    return $text -match '(?i)(?:require|import)\s*\(\s*[''" ](?:braces|micromatch|fast-glob)(?:/|[''"])|from\s*[''"](?:braces|micromatch|fast-glob)(?:/|[''"])|\bbraces\.(?:expand|create|compile|parse|stringify)\s*\('
}
function Read-BoundedExposureBytes([IO.Stream]$Stream, [long]$Length) {
    if ($Length -lt 0 -or $Length -gt 64MB) { throw 'Unbounded runtime dependency member.' }
    $buffer = [IO.MemoryStream]::new()
    try {
        $chunk = [byte[]]::new(65536)
        while (($count = $Stream.Read($chunk,0,$chunk.Length)) -gt 0) {
            $buffer.Write($chunk,0,$count)
            if ($buffer.Length -gt $Length -or $buffer.Length -gt 64MB) { throw 'Runtime dependency member exceeds its declared bound.' }
        }
        if ($buffer.Length -ne $Length) { throw 'Truncated runtime dependency member.' }
        return ,$buffer.ToArray()
    } finally { $buffer.Dispose() }
}
function Inspect-RuntimeDependencyJar([IO.Stream]$Stream, [hashtable]$Counts, [int]$Depth = 0,
        [string]$InspectionTarget = 'braces', [hashtable]$InspectionState = $null,
        [string[]]$LocationChain = @(), [hashtable]$Diagnostics = $null) {
    Assert-DependencyInspectionTarget $InspectionTarget
    if ($InspectionTarget -ceq 'minimatch' -and ($null -eq $InspectionState -or $InspectionState.Count -ne 1 -or
        -not $InspectionState.ContainsKey('present') -or $InspectionState.present -isnot [bool])) {
        throw 'Dependency inspection state is required for archive evidence.'
    }
    if ($Depth -gt 2) { throw 'Unexpected nested runtime archive requires review.' }
    $archive = [IO.Compression.ZipArchive]::new($Stream, [IO.Compression.ZipArchiveMode]::Read, $true)
    try {
        foreach ($entry in $archive.Entries) {
            $Counts.archiveMembers++
            if ($Counts.archiveMembers -gt 200000) { throw 'Unbounded runtime archive inventory.' }
            $pathMatched=Test-RuntimeDependencyPath $entry.FullName $InspectionTarget
            if ($pathMatched) {
                if ($InspectionTarget -ceq 'braces') { throw 'Affected development dependency found in final runtime archive; no exception permitted.' }
                Add-MinimatchExposureFinding $InspectionState
            }
            if ($entry.FullName -notmatch '(?i)(^|/)package\.json$|\.(?:[cm]?js|map|jar)$') {
                if ($InspectionTarget -ceq 'minimatch' -and $pathMatched) { Add-MinimatchUnfingerprintedMatch $Diagnostics }
                continue
            }
            $member = $entry.Open()
            try {
                $bytes = Read-BoundedExposureBytes $member $entry.Length
                if ($entry.FullName -match '(?i)\.jar$') {
                    $nested = [IO.MemoryStream]::new($bytes, $false)
                    if ($InspectionTarget -ceq 'minimatch' -and $pathMatched) {
                        Add-MinimatchFileDiagnostic $Diagnostics @($LocationChain + $entry.FullName) $bytes 'package-path'
                    }
                    try { Inspect-RuntimeDependencyJar $nested $Counts ($Depth + 1) $InspectionTarget $InspectionState @($LocationChain + $entry.FullName) $Diagnostics } finally { $nested.Dispose() }
                } else {
                    $Counts.contentMembers++
                    $contentMatched=Test-RuntimeDependencyContent $entry.FullName $bytes $InspectionTarget
                    if ($contentMatched) {
                        if ($InspectionTarget -ceq 'braces') { throw 'Affected development dependency found in runtime archive content; no exception permitted.' }
                        Add-MinimatchExposureFinding $InspectionState
                    }
                    if ($InspectionTarget -ceq 'minimatch' -and ($pathMatched -or $contentMatched)) {
                        Add-MinimatchFileDiagnostic $Diagnostics @($LocationChain + $entry.FullName) $bytes (Get-MinimatchDiagnosticKind $entry.FullName $bytes $pathMatched)
                    }
                }
            } finally { $member.Dispose() }
        }
    } finally { $archive.Dispose() }
}
