$ErrorActionPreference = 'Stop'
# Extract only the two pure report predicates; never execute the launcher or its top-level code.
$launcher = Join-Path $PSScriptRoot 'run-backend-smoke.ps1'
$parseTokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($launcher, [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'Backend smoke launcher has PowerShell syntax errors.' }
$functionNames = @('Test-ProcessDeathResultOwned', 'Test-ProcessDeathCleanupComplete')
foreach ($name in $functionNames) {
    $definitions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name }, $false))
    if ($definitions.Count -ne 1) { throw "Expected exactly one pure function: $name." }
    Invoke-Expression $definitions[0].Extent.Text
}

$projectRef = 'fixture-project'
$qaId = '00000000-0000-4000-8000-000000000010'
$qaEmail = 'qa@example.test'
$runId = '00000000-0000-4000-8000-000000000001'
$sessionId = '00000000-0000-4000-8000-000000000002'
$checks = 0
function Assert-Guard([bool]$condition, [string]$label) {
    if (!$condition) { throw "Offline report regression failed: $label." }
    $script:checks++
}
function New-Report {
    return [pscustomobject]@{run_id=$runId;session_id=$sessionId;user_id=$qaId;email=$qaEmail;
        project_host="$projectRef.supabase.co";stage='restore_failed';cleanup_local=$true;logout_server=$true;
        local_session_cleared=$true;server_logout_confirmed=$true;cleanup_pending=$false}
}
$report = New-Report
Assert-Guard (Test-ProcessDeathResultOwned $report $runId $sessionId) 'matching identity'
Assert-Guard (Test-ProcessDeathCleanupComplete $report $runId $sessionId) 'restore_failed cleanup complete'
Assert-Guard ($report.stage -cne 'passed') 'cleanup does not turn restore_failed into PASS'
foreach ($stage in @('passed', 'persist_failed', 'cleanup_passed')) {
    $report = New-Report
    $report.stage = $stage
    Assert-Guard (Test-ProcessDeathCleanupComplete $report $runId $sessionId) "complete stage $stage"
}
foreach ($field in @('run_id', 'session_id', 'user_id', 'email', 'project_host')) {
    $report = New-Report
    $report.$field = 'another-owner'
    Assert-Guard (!(Test-ProcessDeathResultOwned $report $runId $sessionId)) "wrong $field ownership"
    Assert-Guard (!(Test-ProcessDeathCleanupComplete $report $runId $sessionId)) "wrong $field cleanup"
    $report = New-Report
    $report.PSObject.Properties.Remove($field)
    Assert-Guard (!(Test-ProcessDeathResultOwned $report $runId $sessionId)) "missing $field ownership"
}
Assert-Guard (!(Test-ProcessDeathResultOwned (New-Report) '' $sessionId)) 'empty expected run'
Assert-Guard (!(Test-ProcessDeathResultOwned (New-Report) $runId '')) 'empty expected session'
foreach ($field in @('cleanup_local', 'logout_server', 'local_session_cleared', 'server_logout_confirmed', 'cleanup_pending')) {
    foreach ($variant in @('missing', 'opposite', 'string')) {
        $report = New-Report
        if ($variant -eq 'missing') { $report.PSObject.Properties.Remove($field) }
        elseif ($variant -eq 'opposite') { $report.$field = !$report.$field }
        else { $report.$field = $report.$field.ToString() }
        Assert-Guard (!(Test-ProcessDeathCleanupComplete $report $runId $sessionId)) "$variant $field"
    }
}
foreach ($stage in @('persisted', 'cleanup_failed', 'unknown')) {
    $report = New-Report
    $report.stage = $stage
    Assert-Guard (!(Test-ProcessDeathCleanupComplete $report $runId $sessionId)) "non-complete stage $stage"
}
Write-Output "Backend smoke offline report regression: $checks checks PASS. No launcher, device or network executed."
