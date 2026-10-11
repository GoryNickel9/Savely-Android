param([switch]$RealWebRead)
$ErrorActionPreference = 'Stop'
if (!$RealWebRead) { throw 'Richiesto il flag esplicito -RealWebRead. Questo smoke usa solo la fixture QA esistente.' }
$projectDirectory = Split-Path -Parent $PSScriptRoot
$projectRef = 'crqnfbahytzenisospcx'
$baseUrl = "https://$projectRef.supabase.co"
$qaId = '9daffd41-39bf-4b1e-be68-abf12549c4a7'
$qaEmail = 'e2e-savely@example.com'
$configuration = Get-Content -LiteralPath (Join-Path $projectDirectory 'savely.properties')
$publicKey = ($configuration | Where-Object { $_ -match '^SUPABASE_ANON_KEY=' } | Select-Object -First 1) -replace '^SUPABASE_ANON_KEY=', ''
$configuredUrl = ($configuration | Where-Object { $_ -match '^SUPABASE_URL=' } | Select-Object -First 1) -replace '^SUPABASE_URL=', ''
if ($configuredUrl.TrimEnd('/') -ne $baseUrl -or [string]::IsNullOrWhiteSpace($publicKey) -or $publicKey.StartsWith('sb_secret_')) { throw 'Progetto o chiave pubblica non corrispondenti alla fixture.' }
if (!$publicKey.StartsWith('sb_publishable_')) {
    try {
        $parts = $publicKey.Split('.')
        if ($parts.Count -ne 3) { throw 'invalid' }
        $encodedPayload = $parts[1].Replace('-', '+').Replace('_', '/')
        $encodedPayload = $encodedPayload.PadRight($encodedPayload.Length + ((4 - $encodedPayload.Length % 4) % 4), '=')
        $keyPayload = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encodedPayload)) | ConvertFrom-Json
        if ($keyPayload.role -ne 'anon') { throw 'invalid' }
    } catch { throw 'Il client smoke accetta soltanto una chiave pubblicabile o JWT anon.' }
}
$node = (Get-Command node -ErrorAction Stop).Source
$nodeScript = Join-Path $PSScriptRoot 'web-read-smoke.mjs'
if (!(Test-Path -LiteralPath (Join-Path $projectDirectory '../Savely/node_modules/playwright/package.json'))) { throw 'Playwright della repo web non è disponibile; nessuna dipendenza viene installata automaticamente.' }
$runId = [guid]::NewGuid().ToString()
$adminKey = $null; $adminHeaders = $null; $keysJson = $null; $keys = $null
$link = $null; $auth = $null; $inputJson = $null; $hashedToken = $null
$logoutVerified = $false
try {
    # Administrative credentials remain in this host process and are never passed to Node/browser.
    $keysJson = & supabase projects api-keys --project-ref $projectRef --reveal --output json 2>$null | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'CLI credentials unavailable' }
    $keys = $keysJson | ConvertFrom-Json
    $adminKey = ($keys | Where-Object { $_.name -eq 'service_role' } | Select-Object -First 1).api_key
    if ([string]::IsNullOrWhiteSpace($adminKey)) { throw 'Admin credential unavailable' }
    $adminHeaders = @{ apikey = $adminKey; Authorization = "Bearer $adminKey" }
    $user = Invoke-RestMethod "$baseUrl/auth/v1/admin/users/$qaId" -Headers $adminHeaders -TimeoutSec 30
    if ($user.id -ne $qaId -or $user.email -ne $qaEmail -or !$user.email_confirmed_at) { throw 'Unexpected existing QA fixture' }
    if (@($user.factors | Where-Object { $_.status -eq 'verified' }).Count -ne 0) { throw 'QA fixture requires MFA' }
    $link = Invoke-RestMethod "$baseUrl/auth/v1/admin/generate_link" -Method Post -Headers $adminHeaders -ContentType 'application/json' -Body (@{type='magiclink';email=$qaEmail} | ConvertTo-Json -Compress) -TimeoutSec 30
    $linkUser = if ($link.user) { $link.user } else { $link }
    if ($linkUser.id -ne $qaId -or $linkUser.email -ne $qaEmail) { throw 'Unexpected link owner' }
    $hashedToken = if ($link.hashed_token) { $link.hashed_token } else { $link.properties.hashed_token }
    if ([string]::IsNullOrWhiteSpace($hashedToken)) { throw 'Missing link token' }
    # generate_link sends no email and does not change the existing password.
    $auth = Invoke-RestMethod "$baseUrl/auth/v1/verify" -Method Post -Headers @{apikey=$publicKey} -ContentType 'application/json' -Body (@{type='magiclink';token_hash=$hashedToken} | ConvertTo-Json -Compress) -TimeoutSec 30
    if ($auth.user.id -ne $qaId -or $auth.user.email -ne $qaEmail) { throw 'Unexpected session owner' }
    $inputJson = @{ project_url=$baseUrl; site_origin='https://www.savely.cc'; public_key=$publicKey; run_id=$runId; session=$auth } | ConvertTo-Json -Depth 20 -Compress
    # Session JSON travels only over stdin. No auth file, command-line token or storageState file is created.
    $inputJson | & $node $nodeScript --real-web-read
    $nodeSucceeded = $LASTEXITCODE -eq 0
    $resultPath = Join-Path $projectDirectory 'artifacts/web-read-smoke-result.json'
    if (!(Test-Path -LiteralPath $resultPath -PathType Leaf)) { throw 'Missing non-secret report' }
    $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if ($result.run_id -ne $runId -or $result.user_id -ne $qaId -or $result.email -ne $qaEmail -or $result.project_host -ne "$projectRef.supabase.co") { throw 'Report does not match this invocation' }
    $logoutVerified = $result.logout_server -eq $true
    if (!$nodeSucceeded -or $result.stage -ne 'passed' -or !$result.browser_read_verified -or !$result.transaction_removed -or $result.cleanup_pending -or $result.effective_origin -ne 'https://www.savely.cc') { throw 'Real deployed website read did not pass' }
    Write-Output "Lettura verificata su https://www.savely.cc: marker creato dall'host e rimosso, sola sessione QA temporanea revocata. run_id=$runId"
} catch {
    # Never print HTTP bodies, token objects or raw browser exceptions.
    throw "Smoke web non riuscito. Consultare il report non segreto artifacts/web-read-smoke-result.json per run_id=$runId; nessuna lettura del deploy viene dichiarata senza il risultato completo."
} finally {
    if ($auth.user.id -eq $qaId -and $auth.user.email -eq $qaEmail -and $auth.access_token -and !$logoutVerified) {
        try {
            Invoke-RestMethod "$baseUrl/auth/v1/logout?scope=local" -Method Post -Headers @{apikey=$publicKey;Authorization="Bearer $($auth.access_token)"} -TimeoutSec 15 | Out-Null
        } catch { Write-Warning "cleanup_pending=true: logout host non confermato per run_id=$runId. Non stampare o riutilizzare la sessione." }
    }
    $adminKey = $null; $adminHeaders = $null; $keysJson = $null; $keys = $null
    $link = $null; $linkUser = $null; $auth = $null; $inputJson = $null; $hashedToken = $null
}
