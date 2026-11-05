# Exact owner-authorized development classification, not an OSV suppression.
# Proposed classification is valid only after separate owner approval and all
# current-image/graph/full-audit guards below; this is not an OSV suppression.
function Assert-DevelopmentMinimatchPath($Why,$Production,$Manifest,[string]$Repository) {
    if ([string]::IsNullOrWhiteSpace($Repository) -or $Repository.Length -gt 4096 -or
        $Why -isnot [array] -or $Why.Count -ne 2 -or
        $Manifest.devDependencies.eslint -cne '9.38.0' -or
        $Manifest.devDependencies.'eslint-config-next' -cne '16.4.1' -or
        $Manifest.dependencies.eslint -or $Manifest.dependencies.'eslint-config-next' -or
        $Production -isnot [array] -or $Production.Count -ne 1 -or
        $Production[0].name -cne '@educore/web' -or $Production[0].version -cne '0.1.0' -or
        $Production[0].dependencies -isnot [System.Collections.IDictionary] -or
        $Production[0].dependencies.Count -ne $Manifest.dependencies.Count) {
        throw 'Minimatch development roots or complete production graph changed.'
    }
    foreach ($name in $Manifest.dependencies.Keys) {
        if ($Production[0].dependencies[$name].version -cne $Manifest.dependencies[$name]) {
            throw 'The production graph does not cover all exact direct dependencies.'
        }
    }
    $expected = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'minimatch-reviewed-development-paths.json')) | ConvertFrom-Json -NoEnumerate
    if ($expected -isnot [array] -or $expected.Count -ne 74) { throw 'Reviewed minimatch graph is missing or incompatible.' }
    $facts = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $budget = @{count=0}
    function Add-MinimatchAncestor($Node,[int]$Depth) {
        $budget.count++
        $allowed=@('name','version','path','dependents','depField','peersSuffixHash','deduped','circular')
        if ($Depth -gt 60 -or $budget.count -gt 5000 -or $Node -isnot [System.Collections.IDictionary] -or
            @($Node.Keys | Where-Object { $_ -cnotin $allowed }).Count -gt 0 -or
            $Node.name -isnot [string] -or $Node.version -isnot [string] -or
            $Node.name.Length -gt 100 -or $Node.version.Length -gt 40 -or
            ($Node.Contains('path') -and ($Node.name -cne 'minimatch' -or $Node.path -isnot [string])) -or
            ($Node.Contains('peersSuffixHash') -and ($Node.peersSuffixHash -isnot [string] -or $Node.peersSuffixHash -cnotmatch '^[a-f0-9]{4,64}$')) -or
            ($Node.Contains('dependents') -and $Node.dependents -isnot [array]) -or
            ($Node.Contains('depField') -and $Node.depField -cne 'devDependencies') -or
            ($Node.Contains('deduped') -and ($Node.deduped -isnot [bool] -or -not $Node.deduped)) -or
            ($Node.Contains('circular') -and ($Node.circular -isnot [bool] -or -not $Node.circular))) {
            throw 'Malformed, unbounded or non-development minimatch ancestor graph.'
        }
        $id=$Node.name+'@'+$Node.version
        [void]$facts.Add('NODE '+$id)
        if ($Node.Contains('depField')) { [void]$facts.Add('SCOPE '+$id+' '+$Node.depField) }
        if ($Node.name -ceq '@educore/web' -and ($Node.depField -cne 'devDependencies' -or $Node.dependents)) { throw 'Unexpected project scope.' }
        if (-not $Node.dependents -and $Node.name -cne '@educore/web' -and -not $Node.deduped -and -not $Node.circular) { throw 'Incomplete ancestor graph.' }
        $parents=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($parent in $Node.dependents) {
            $parentId=$parent.name+'@'+$parent.version
            if (-not $parents.Add($parentId)) { throw 'Duplicate ancestor edge.' }
            [void]$facts.Add('EDGE '+$id+' -> '+$parentId)
            Add-MinimatchAncestor $parent ($Depth+1)
        }
    }
    $roots=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($root in $Why) {
        if ($root.name -cne 'minimatch' -or $root.version -cnotin @('3.1.5','10.2.6') -or -not $roots.Add($root.version) -or
            $root.path -isnot [string] -or $root.path.Replace('\','/') -cne ($Repository.Replace('\','/').TrimEnd('/')+'/node_modules/.pnpm/minimatch@'+$root.version+'/node_modules/minimatch')) {
            throw 'Different installed minimatch version/path.'
        }
        Add-MinimatchAncestor $root 0
    }
    if (-not $facts.SetEquals([string[]]$expected)) { throw 'An added, removed or changed minimatch ancestor requires review.' }
    $lintNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($fact in $expected) {
        if ($fact.StartsWith('NODE ',[StringComparison]::Ordinal)) {
            $id=$fact.Substring(5);$name=$id.Substring(0,$id.LastIndexOf('@'))
            if ($name -cne '@educore/web') { [void]$lintNames.Add($name) }
        }
    }
    $visited=@{count=0}
    function Assert-NoRuntimeMinimatch($Node,[int]$Depth) {
        $visited.count++
        if ($Node -isnot [System.Collections.IDictionary] -or $Depth -gt 100 -or $visited.count -gt 50000) { throw 'Malformed or unbounded production graph.' }
        foreach ($field in @('name','from','version')) {
            if (-not $Node.Contains($field)) { continue }
            $target=$Node[$field]
            if ($target -isnot [string] -or $target.Length -gt 512) { throw 'Malformed production package identity.' }
            if ($field -ceq 'version' -and -not $target.StartsWith('npm:',[StringComparison]::Ordinal) -and -not $target.Contains('@npm:')) { continue }
            $aliasAt=$target.IndexOf('@npm:',[StringComparison]::Ordinal)
            if ($aliasAt -ge 0) { $target=$target.Substring($aliasAt+5) }
            elseif ($target.StartsWith('npm:',[StringComparison]::Ordinal)) { $target=$target.Substring(4) }
            $at=$target.IndexOf('@',1)
            if ($at -gt 0) { $target=$target.Substring(0,$at) }
            if ($target -cnotmatch '^(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+$') { throw 'Unknown production package/alias selector.' }
            if ($lintNames.Contains($target)) { throw 'Aliased matcher/lint code is reachable from production dependencies.' }
        }
        if ($Node.Contains('path')) {
            if ($Node.path -isnot [string] -or $Node.path.Length -gt 4096) { throw 'Malformed production package path.' }
            $parts=$Node.path.Replace('\','/').Split('/')
            if (@($parts | Where-Object { $_ -cin @('.','..') }).Count -gt 0) { throw 'Noncanonical production package path.' }
            for ($index=0;$index -lt $parts.Length-1;$index++) {
                if ($parts[$index].Equals('node_modules',[StringComparison]::OrdinalIgnoreCase) -and -not $parts[$index+1].Equals('.pnpm',[StringComparison]::OrdinalIgnoreCase)) {
                    $target=$parts[$index+1]
                    if ($target.StartsWith('@',[StringComparison]::Ordinal) -and $index+2 -lt $parts.Length) { $target+='/'+$parts[$index+2] }
                    if ($lintNames.Contains($target)) { throw 'Matcher/lint target exists under a production package path.' }
                }
                if ($parts[$index].Equals('.pnpm',[StringComparison]::OrdinalIgnoreCase)) {
                    $folder=$parts[$index+1];$at=$folder.IndexOf('@',1)
                    if ($at -gt 0 -and $lintNames.Contains($folder.Substring(0,$at).Replace('+','/'))) { throw 'Aliased matcher/lint target exists in the production virtual store.' }
                }
            }
        }
        if ($Node.Contains('resolved')) {
            $uri=$null
            if ($Node.resolved -isnot [string] -or $Node.resolved.Length -gt 4096 -or
                -not [uri]::TryCreate($Node.resolved,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -cnotin @('https','http')) { throw 'Unknown production resolution metadata.' }
            $path=[uri]::UnescapeDataString($uri.AbsolutePath).Trim('/')
            $separator=$path.IndexOf('/-/',[StringComparison]::Ordinal)
            if ($separator -lt 1) { throw 'Opaque production package resolution requires review.' }
            if ($lintNames.Contains($path.Substring(0,$separator))) { throw 'Resolved production package is reviewed matcher/lint code.' }
        }
        foreach ($kind in @('dependencies','optionalDependencies','unsavedDependencies')) {
            if ($Node.Contains($kind) -and $Node[$kind] -isnot [System.Collections.IDictionary]) { throw 'Invalid production dependency map.' }
            foreach ($name in @($Node[$kind].Keys)) {
                if ($null -eq $name) { continue }
                if ($lintNames.Contains($name)) { throw 'Reviewed matcher/lint code is reachable from production dependencies.' }
                Assert-NoRuntimeMinimatch $Node[$kind][$name] ($Depth+1)
            }
        }
    }
    Assert-NoRuntimeMinimatch $Production[0] 0
}
function Assert-FinalMinimatchImages($Images,[string]$Commit,[string]$LockfileHash) {
    if ($Images -isnot [System.Collections.IDictionary] -or $Images.Count -ne 2 -or
        @($Images.Keys | Where-Object { $_ -cnotin @('web','api') }).Count -gt 0) { throw 'Both current real-image minimatch proofs are required.' }
    foreach ($service in @('web','api')) {
        $proof=$Images[$service]
        $keys=@('exposure','diagnostic','exposureSha256','dockerfileSha256','imageId')
        if ($proof -isnot [System.Collections.IDictionary] -or $proof.Count -ne $keys.Count -or
            @($proof.Keys | Where-Object { $_ -cnotin $keys }).Count -gt 0) { throw 'Incomplete current-image minimatch evidence.' }
        Assert-MinimatchExposureReport $proof.exposure $service $Commit $proof.dockerfileSha256 $LockfileHash $proof.imageId
        Assert-MinimatchDiagnosticReport $proof.diagnostic $proof.exposure $proof.exposureSha256
        if ($proof.exposure.result -cne 'absent' -or $proof.exposure.affectedLibraryPresent -or
            $proof.diagnostic.totalFindings -ne 0 -or $proof.diagnostic.findings.Count -ne 0 -or
            $proof.diagnostic.unfingerprintedMatches -ne 0 -or $proof.diagnostic.truncated) {
            throw 'Minimatch is present or incompletely inspected in a current final image; classification denied.'
        }
    }
}
function Assert-MinimatchCompatibleUpstream($Metadata) {
    if ($Metadata.name -cne 'minimatch' -or $Metadata.versions -isnot [System.Collections.IDictionary] -or
        $Metadata.versions.Count -gt 10000) { throw 'Missing or unbounded official minimatch release metadata.' }
    $three=@($Metadata.versions.Keys | Where-Object { $_ -cmatch '^3\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' } | Sort-Object { [version]$_ } -Descending)
    $ten=@($Metadata.versions.Keys | Where-Object { $_ -cmatch '^10\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' } | Sort-Object { [version]$_ } -Descending)
    if ($three.Count -eq 0 -or $ten.Count -eq 0 -or $three[0] -cne '3.1.5' -or $ten[0] -cne '10.2.6' -or
        $Metadata.versions['3.1.5'].name -cne 'minimatch' -or $Metadata.versions['3.1.5'].version -cne '3.1.5' -or
        $Metadata.versions['10.2.6'].name -cne 'minimatch' -or $Metadata.versions['10.2.6'].version -cne '10.2.6') {
        throw 'Official compatible minimatch release changed; immediately remediate/review SEC-MINIMATCH-2026-118764.'
    }
}
function Assert-DependencyAuditCompletion($Jobs, [string]$RunId) {
    if ($RunId -cnotmatch '^[0-9]+$' -or $Jobs.jobs -isnot [array] -or $Jobs.jobs.Count -gt 100 -or
        $Jobs.total_count -ne $Jobs.jobs.Count) { throw 'Missing, partial or unbounded current-run audit completion evidence.' }
    $audit = @($Jobs.jobs | Where-Object { $_.name -ceq 'Open-source dependency audit / osv-scan' })
    if ($audit.Count -ne 1 -or "$($audit[0].run_id)" -cne $RunId -or $audit[0].status -cne 'completed' -or $audit[0].conclusion -cne 'success') {
        throw 'The full dependency-audit job did not successfully complete on this run.'
    }
    foreach ($name in @('Run scanner','Run osv-scanner-reporter')) {
        $steps = @($audit[0].steps | Where-Object { $_.name -ceq $name })
        if ($steps.Count -ne 1 -or $steps[0].status -cne 'completed' -or $steps[0].conclusion -cne 'success') { throw 'Scanner/reporter was failed, skipped or incomplete; no policy pass inferred.' }
    }
}
function Assert-DevelopmentBracesPath($Why, $Production, $Manifest) {
    if ($Why -isnot [array] -or $Why.Count -ne 1 -or $Why[0].name -cne 'braces' -or $Why[0].version -cne '3.0.3' -or
        $Manifest.devDependencies.'eslint-config-next' -cne '16.4.1' -or $Manifest.dependencies.'eslint-config-next' -or
        $Production -isnot [array] -or $Production.Count -ne 1 -or $Production[0].name -cne '@educore/web') {
        throw 'The complete dependency scope does not match the reviewed development path.'
    }
    $node = $Why[0]
    foreach ($expected in @(@('micromatch','4.0.8'), @('fast-glob','3.3.1'), @('@next/eslint-plugin-next','16.4.1'), @('eslint-config-next','16.4.1'), @('@educore/web','0.1.0'))) {
        if ($node.dependents -isnot [array] -or $node.dependents.Count -ne 1) { throw 'An additional dependency path requires review.' }
        $node = $node.dependents[0]
        if ($node.name -cne $expected[0] -or $node.version -cne $expected[1]) { throw 'Changed dependency path/version requires review.' }
    }
    if ($node.depField -cne 'devDependencies') { throw 'Braces is not exclusively development-scoped.' }
    Assert-NoProductionBraces $Production[0]
}
function Assert-NoProductionBraces($Node, [int]$Depth = 0) {
    if ($Depth -gt 100) { throw 'Unbounded production dependency graph.' }
    foreach ($kind in @('dependencies','optionalDependencies')) {
        if ($Node[$kind] -and $Node[$kind] -isnot [System.Collections.IDictionary]) { throw 'Invalid production graph.' }
        foreach ($name in @($Node[$kind].Keys)) {
            if ($null -eq $name) { continue }
            if ($name -cin @('braces','micromatch','fast-glob','@next/eslint-plugin-next','eslint-config-next')) {
                throw 'The affected lint dependency is reachable from a production dependency; no classification permitted.'
            }
            Assert-NoProductionBraces $Node[$kind][$name] ($Depth + 1)
        }
    }
}
function Assert-FinalDependencyExposure($Report, [string]$Service, [string]$Commit, [string]$DockerfileHash,
                                       [string]$LockfileHash, [string]$ImageId) {
    $keys = @('format','service','commit','imageId','dockerfileSha256','lockfileSha256','source','inspectorVersion','completedAt','counts','affectedLibraryPresent','cleanupSucceeded')
    if ($null -eq $Report -or @($Report.Keys).Count -ne $keys.Count -or @($Report.Keys | Where-Object { $_ -cnotin $keys }).Count -or
        $Report.format -ne 1 -or $Report.service -cne $Service -or $Report.commit -cne $Commit -or $Commit -cnotmatch '^[a-f0-9]{40}$' -or
        $Report.imageId -cne $ImageId -or $ImageId -cnotmatch '^sha256:[a-f0-9]{64}$' -or
        $Report.dockerfileSha256 -cne $DockerfileHash -or $DockerfileHash -cnotmatch '^[a-f0-9]{64}$' -or
        $Report.lockfileSha256 -cne $LockfileHash -or $LockfileHash -cnotmatch '^[a-f0-9]{64}$' -or
        $Report.source -cne 'final-image-docker-export' -or $Report.inspectorVersion -ne 1 -or
        $Report.affectedLibraryPresent -isnot [bool] -or $Report.affectedLibraryPresent -or
        $Report.cleanupSucceeded -isnot [bool] -or -not $Report.cleanupSucceeded -or
        $Report.completedAt -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{7}Z$') {
        throw 'Final-image absence proof is missing, wrong-source, altered or indicates affected runtime code.'
    }
    $completed = [datetime]::Parse($Report.completedAt, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
    if ($completed -lt [datetime]::UtcNow.AddHours(-6) -or $completed -gt [datetime]::UtcNow.AddMinutes(1)) { throw 'Stale/future image exposure proof.' }
    $countNames = @('filesystemMembers','manifests','javascript','jars','archiveMembers','contentMembers')
    if ($Report.counts -isnot [System.Collections.IDictionary] -or @($Report.counts.Keys).Count -ne $countNames.Count -or
        @($Report.counts.Keys | Where-Object { $_ -cnotin $countNames }).Count) { throw 'Incomplete image inventory counts.' }
    foreach ($name in $countNames) {
        if (($Report.counts[$name] -isnot [int] -and $Report.counts[$name] -isnot [long]) -or $Report.counts[$name] -lt 0) { throw 'Invalid image inventory counts.' }
    }
    if ($Report.counts.filesystemMembers -eq 0 -or ($Service -ceq 'web' -and ($Report.counts.manifests -eq 0 -or $Report.counts.javascript -eq 0)) -or
        ($Service -ceq 'api' -and ($Report.counts.jars -eq 0 -or $Report.counts.archiveMembers -eq 0))) { throw 'Final-image inventory is incomplete.' }
}
function Assert-FullDependencyAudit($Sarif) {
    if ($null -eq $Sarif -or $Sarif.version -cne '2.1.0' -or $Sarif.runs -isnot [array] -or $Sarif.runs.Count -ne 1 -or
        $Sarif.runs[0].tool.driver.name -cne 'osv-scanner' -or $Sarif.runs[0].tool.driver.version -cne '2.5.0' -or
        $Sarif.runs[0].results -isnot [array] -or $Sarif.runs[0].tool.driver.rules -isnot [array]) { throw 'Full audit evidence is missing or incompatible.' }
    $run = $Sarif.runs[0]
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $usedRules=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $miniMessages=@(
        "Package 'minimatch@3.1.5' is vulnerable to 'CVE-2026-118764' (also known as 'GHSA-g3hv-75wg-m249').",
        "Package 'minimatch@10.2.6' is vulnerable to 'CVE-2026-118764' (also known as 'GHSA-g3hv-75wg-m249')."
    )
    $miniCount=0
    foreach ($result in $run.results) {
        if ($result.kind -cne 'fail' -or
            @($result.suppressions | Where-Object { $null -ne $_ }).Count -gt 0 -or $result.baselineState -ceq 'absent' -or
            $result.locations -isnot [array] -or $result.locations.Count -ne 1 -or
            $result.locations[0].physicalLocation.artifactLocation.uri -cne 'pnpm-lock.yaml' -or
            -not $seen.Add($result.message.text)) {
            throw 'An unreviewed, runtime, suppressed or changed dependency finding blocks release; full audit is retained.'
        }
        $rules = @($run.tool.driver.rules | Where-Object { $_.id -ceq $result.ruleId })
        if ($rules.Count -ne 1) { throw 'Missing or duplicated full-audit rule.' }
        if ($result.message.text -cin $miniMessages) {
            if ($result.ruleId -cne 'CVE-2026-118764' -or $rules[0].properties.'security-severity' -cne '6.9' -or
                $rules[0].deprecatedIds -isnot [array] -or $rules[0].deprecatedIds.Count -ne 2 -or
                'CVE-2026-118764' -cnotin $rules[0].deprecatedIds -or 'GHSA-g3hv-75wg-m249' -cnotin $rules[0].deprecatedIds) {
                throw 'Changed minimatch advisory evidence requires review.'
            }
            $miniCount++
        } else {
            # Preserve the existing exact braces rule, severity and message.
            if ($result.ruleId -cnotin @('CVE-2026-93687','GHSA-vfj7-8cjw-p6xm') -or
                $result.message.text -cne "Package 'braces@3.0.3' is vulnerable to 'CVE-2026-93687' (also known as 'GHSA-vfj7-8cjw-p6xm')." -or
                $rules[0].properties.'security-severity' -cne '8.7' -or 'GHSA-vfj7-8cjw-p6xm' -cnotin $rules[0].deprecatedIds) {
                throw 'An unreviewed dependency finding blocks release; full audit is retained.'
            }
        }
        [void]$usedRules.Add($result.ruleId)
    }
    if ($run.results.Count -gt 3 -or $miniCount -notin @(0,2) -or $run.tool.driver.rules.Count -ne $usedRules.Count) {
        throw 'Duplicate or incomplete full-audit results.'
    }
    return $run.results.Count
}
