$ErrorActionPreference = 'Stop'
$backupTestRoot = Split-Path -Parent $PSScriptRoot
$backupTestScript = Join-Path $PSScriptRoot 'backup-signing.ps1'
$backupTestTemp = Join-Path ([IO.Path]::GetTempPath()) ('savely-backup-guard-' + [guid]::NewGuid())
$backupTestJunction = Join-Path $backupTestTemp 'checkout-link'
$backupTestFakeCheckout = Join-Path $backupTestTemp 'synthetic-checkout'
$backupTestOutwardTarget = Join-Path $backupTestTemp 'external-target'
$backupTestOutwardLink = Join-Path $backupTestFakeCheckout 'outward-link'
$backupTestCalls = [Collections.Generic.List[string]]::new()
$backupTestCases = [Collections.Generic.List[string]]::new()

New-Item -ItemType Directory -Path $backupTestTemp | Out-Null
New-Item -ItemType Junction -Path $backupTestJunction -Target $backupTestRoot | Out-Null
New-Item -ItemType Directory -Path $backupTestFakeCheckout, $backupTestOutwardTarget | Out-Null
New-Item -ItemType Junction -Path $backupTestOutwardLink -Target $backupTestOutwardTarget | Out-Null
try {
    # Intercept the first side effect even if the guard regresses; never touch the signing vault.
    function New-Item { $backupTestCalls.Add('New-Item'); throw 'Unexpected backup write' }
    function Set-Acl { $backupTestCalls.Add('Set-Acl'); throw 'Unexpected backup ACL change' }
    function Get-Content { $backupTestCalls.Add('Get-Content'); throw 'Unexpected signing password read' }

    $destinations = [ordered]@{
        root = $backupTestRoot
        child = (Join-Path $backupTestRoot 'artifacts/forbidden-backup')
        normalizedRoot = (Join-Path $backupTestRoot 'tools/..')
        caseInsensitiveRoot = $backupTestRoot.ToUpperInvariant()
        junctionRoot = $backupTestJunction
        junctionChild = (Join-Path $backupTestJunction 'new-child/backup')
    }
    foreach ($case in $destinations.GetEnumerator()) {
        $rejected = $false
        try { . $backupTestScript -Destination $case.Value }
        catch {
            if ($_.Exception.Message -cne 'Il backup della firma deve restare fuori dal checkout.') { throw }
            $rejected = $true
        }
        if (-not $rejected -or $backupTestCalls.Count -ne 0) {
            throw "Backup guard failed before side effects: $($case.Key)"
        }
        $backupTestCases.Add($case.Key)
    }
    $safeDestination = Join-Path $backupTestTemp 'external/new-backup'
    if ((Get-SafeSigningBackupDestination $safeDestination $backupTestRoot) -cne [IO.Path]::GetFullPath($safeDestination)) {
        throw 'An external destination was not retained'
    }
    $backupTestCases.Add('externalDestination')
    $samePrefixSibling = $backupTestRoot + '-external-sibling'
    if ((Get-SafeSigningBackupDestination $samePrefixSibling $backupTestRoot) -cne [IO.Path]::GetFullPath($samePrefixSibling)) {
        throw 'A sibling sharing the checkout prefix was incorrectly rejected'
    }
    $backupTestCases.Add('samePrefixSibling')
    $outwardRejected = $false
    try { Get-SafeSigningBackupDestination (Join-Path $backupTestOutwardLink 'new-backup') $backupTestFakeCheckout | Out-Null }
    catch {
        if ($_.Exception.Message -cne 'Il backup della firma deve restare fuori dal checkout.') { throw }
        $outwardRejected = $true
    }
    if (-not $outwardRejected -or $backupTestCalls.Count -ne 0) { throw 'A checkout child pointing outside was incorrectly accepted' }
    $backupTestCases.Add('outwardJunctionChild')
    @{ tests = $backupTestCases.Count; passed = $backupTestCases.Count; failures = 0;
        cases = $backupTestCases.ToArray(); backupWrites = 0; aclChanges = 0; credentialReads = 0 } | ConvertTo-Json
} finally {
    Remove-Item Function:/New-Item, Function:/Set-Acl, Function:/Get-Content -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $backupTestJunction) { Remove-Item -LiteralPath $backupTestJunction -Force }
    if (Test-Path -LiteralPath $backupTestOutwardLink) { Remove-Item -LiteralPath $backupTestOutwardLink -Force }
    $resolvedTemp = [IO.Path]::GetFullPath($backupTestTemp)
    if (-not $resolvedTemp.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside the test temporary directory'
    }
    Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
}
