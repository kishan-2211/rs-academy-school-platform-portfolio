# Pure installed-package guards. Not Docker execution or a vulnerability scan.
function Get-ApprovedWebRuntimeImage {
    return 'node:24.22.1-alpine3.24@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53'
}

function Assert-WebRuntimeNodeVersion($Version) {
    if ($Version -isnot [string] -or $Version -cne 'v24.22.1') {
        throw 'Actual web runtime must use exactly the approved Node.js security version.'
    }
}

function Get-RuntimeSecurityPackages([string]$Service) {
    if ($Service -cnotin @('web','api')) { throw 'Only the approved web/API runtime profiles are valid.' }
    $packages = [ordered]@{ libcrypto3='3.5.9-r0'; libssl3='3.5.9-r0'; zlib='1.3.2-r1' }
    if ($Service -ceq 'web') { $packages.musl='1.2.6-r3'; $packages.'musl-utils'='1.2.6-r3' }
    if ($Service -ceq 'api') { $packages.openssl='3.5.9-r0'; $packages.libpng='1.6.59-r0'; $packages.libexpat='2.10.0-r0' }
    return $packages
}

# The API's verified Alpine 3.24.3 base may already carry a newer security
# revision. Require the approved fixed family and an aligned pair, not a downgrade.
function Assert-AlignedRuntimeMuslInventory([string[]]$Inventory) {
    $revisions = @()
    foreach ($package in @('musl','musl-utils')) {
        $records = @($Inventory | Where-Object { $_ -cmatch ('^' + [regex]::Escape($package) + '-[0-9]') })
        if ($records.Count -ne 1 -or $records[0] -cnotmatch ('\A' + [regex]::Escape($package) + '-1\.2\.6-r(0|[1-9][0-9]{0,4})\z')) {
            throw 'Actual runtime requires one recognized musl package and utility revision.'
        }
        $revision = [int]$Matches[1]
        if ($revision -lt 3) { throw 'Actual runtime musl security revision is below the approved fix.' }
        $revisions += $revision
    }
    if ($revisions[0] -ne $revisions[1]) { throw 'Actual runtime musl and musl-utils revisions must remain aligned.' }
}

# These are the complete GnuPG package chain from the digest-verified CI441
# Temurin base, plus SQLite's shell/library package names. No version is allowed
# back into this API runtime without review; shared crypto packages are not banned.
function Get-ApiPrunedBuildPackages {
    return @('gnupg','gnupg-dirmngr','gnupg-gpgconf','gnupg-keyboxd','gnupg-utils','gnupg-wks-client',
        'gpg','gpg-agent','gpg-wks-server','gpgsm','gpgv','sqlite','sqlite-libs')
}

# Executed only by the real image verifier, as the unchanged non-root runtime UID.
# Each absolute alias rejects both existing files and dangling symlinks.
function Get-ApiPrunedRuntimeProbe {
    $probe = @'
for tool in addgnupghome applygnupgdefaults dirmngr dirmngr-client gpg gpg2 gpg-agent gpg-card gpg-connect-agent gpg-mail-tube gpg-wks-client gpg-wks-server gpgconf gpgparsemail gpgsm gpgsplit gpgtar gpgv gpgv2 kbxutil watchgnupg sqlite3; do
    if command -v "$tool" >/dev/null 2>&1; then exit 31; fi
    for directory in /usr/local/bin /usr/bin /bin /usr/local/sbin /usr/sbin /sbin; do
        if test -e "$directory/$tool" || test -L "$directory/$tool"; then exit 32; fi
    done
done
for file in /usr/libexec/dirmngr_ldap /usr/libexec/keyboxd /usr/libexec/gpg-auth /usr/libexec/gpg-pair-tool /usr/libexec/gpg-wks-client /usr/libexec/gpg-check-pattern /usr/libexec/gpg-preset-passphrase /usr/libexec/gpg-protect-tool /usr/lib/libsqlite3.* /usr/lib64/libsqlite3.* /usr/local/lib/libsqlite3.* /lib/libsqlite3.*; do
    if test -e "$file" || test -L "$file"; then exit 33; fi
done
for package in fontconfig ttf-dejavu ca-certificates p11-kit p11-kit-trust coreutils openssl; do
    apk info -e "$package" >/dev/null
done
for required in fc-match trust openssl keytool csplit; do command -v "$required" >/dev/null; done
family=$(fc-match -f '%{family}' sans-serif)
test -n "$family"
openssl version >/dev/null
test -s /etc/ssl/certs/ca-certificates.crt
test -r "$JAVA_HOME/lib/security/cacerts"
test -r "$JAVA_HOME/lib/libfontmanager.so"
test -r "$JAVA_HOME/lib/libawt_headless.so"
echo API_PRUNED_TOOLS_ABSENT_FONTS_TRUST_PRESENT
'@
    return $probe.Replace("`r`n", "`n")
}

function Assert-RuntimePackageInventory([string]$Service, [string[]]$Inventory) {
    $packages = Get-RuntimeSecurityPackages $Service
    if ($null -eq $Inventory -or $Inventory.Count -eq 0 -or $Inventory.Count -gt 500) {
        throw 'Actual bounded installed-package inventory is required.'
    }
    foreach ($record in $Inventory) {
        if ($null -eq $record -or $record.Length -gt 200 -or $record -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9+_.-]*-[0-9][A-Za-z0-9+_.-]*\z') {
            throw 'Unexpected installed-package metadata; no raw configuration is accepted.'
        }
    }
    foreach ($package in $packages.Keys) {
        $packageRecords = @($Inventory | Where-Object { $_ -cmatch ('^' + [regex]::Escape($package) + '-[0-9]') })
        if ($packageRecords.Count -ne 1 -or $packageRecords[0] -cne "$package-$($packages[$package])") {
            throw "Actual runtime must contain exactly the approved $package patch."
        }
    }
    Assert-AlignedRuntimeMuslInventory $Inventory
    if ($Service -ceq 'api') {
        foreach ($package in (Get-ApiPrunedBuildPackages)) {
            if (@($Inventory | Where-Object { $_ -cmatch ('^' + [regex]::Escape($package) + '-[0-9]') }).Count) {
                throw 'Unused build-time GnuPG/SQLite package remains in the actual API runtime.'
            }
        }
    }
}
