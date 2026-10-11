param([string]$Device = 'emulator-5556', [switch]$RealAndroidWeb, [switch]$SelfTest)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$projectRef = 'crqnfbahytzenisospcx'
$baseUrl = "https://$projectRef.supabase.co"
$qaId = '9daffd41-39bf-4b1e-be68-abf12549c4a7'
$qaEmail = 'e2e-savely@example.com'
$runId = [guid]::NewGuid().ToString()
$sessionId = $null
$day = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow, 'W. Europe Standard Time').ToString('yyyy-MM-dd')
function Assert-Checkpoint($checkpoint, [switch]$Prepared) {
    if ($checkpoint.run_id -ne $runId -or $checkpoint.session_id -ne $sessionId -or
        $checkpoint.user_id -ne $qaId -or $checkpoint.email -ne $qaEmail -or $checkpoint.project_host -ne "$projectRef.supabase.co" -or
        $checkpoint.transaction_id -ne $runId -or $checkpoint.marker -ne "Android-Web-QA-$runId" -or
        $checkpoint.date -ne $day -or $checkpoint.amount -cne '0.01' -or $checkpoint.currency -cne 'EUR' -or
        $checkpoint.type -cne 'expense' -or $null -ne $checkpoint.category_id -or $checkpoint.exchange_rate_eur -cne '1' -or
        $checkpoint.creation_attempted -ne $true -or $checkpoint.stage -notin @('creating', 'native_prepared') -or
        ($Prepared -and ($checkpoint.stage -ne 'native_prepared' -or $checkpoint.native_created -ne $true -or $checkpoint.native_ui_read -ne $true))) {
        throw 'Checkpoint does not match this native QA invocation.'
    }
}
function Test-CompletedCleanup($result, [string]$stage) {
    return $result.run_id -eq $runId -and $result.session_id -eq $sessionId -and $result.user_id -eq $qaId -and
        $result.email -eq $qaEmail -and $result.project_host -eq "$projectRef.supabase.co" -and $result.stage -eq $stage -and
        $result.transaction_removed -eq $true -and $result.cleanup_local -eq $true -and $result.logout_server -eq $true -and
        $result.cleanup_pending -eq $false
}
function Get-JwtMetadata([string]$token) {
    try {
        if ($token.Length -gt 16384) { throw 'invalid' }
        $parts = $token.Split('.')
        if ($parts.Count -ne 3) { throw 'invalid' }
        $encoded = $parts[1].Replace('-', '+').Replace('_', '/')
        $encoded = $encoded.PadRight($encoded.Length + ((4 - $encoded.Length % 4) % 4), '=')
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) | ConvertFrom-Json
    } catch { throw 'Invalid JWT metadata; no token content is printed.' }
}
if ($SelfTest) {
    $sessionId = '00000000-0000-4000-8000-000000000002'
    $fixture = [pscustomobject]@{ run_id=$runId;session_id=$sessionId;user_id=$qaId;email=$qaEmail;project_host="$projectRef.supabase.co";
        transaction_id=$runId;marker="Android-Web-QA-$runId";date=$day;amount='0.01';currency='EUR';type='expense';category_id=$null;
        exchange_rate_eur='1';creation_attempted=$true;stage='native_prepared';native_created=$true;native_ui_read=$true }
    Assert-Checkpoint $fixture -Prepared
    foreach ($field in @('run_id','session_id','user_id','email','project_host','transaction_id','marker','date','amount','currency','type','category_id','exchange_rate_eur','creation_attempted','stage','native_created','native_ui_read')) {
        $changed = $fixture | ConvertTo-Json | ConvertFrom-Json
        $changed.$field = 'changed'
        $rejected = $false
        try { Assert-Checkpoint $changed -Prepared } catch { $rejected = $true }
        if (!$rejected) { throw "Guard fixture failed: $field" }
    }
    $fixture.stage = 'creating'
    Assert-Checkpoint $fixture
    $completed = [pscustomobject]@{run_id=$runId;session_id=$sessionId;user_id=$qaId;email=$qaEmail;project_host="$projectRef.supabase.co";
        stage='prepare_failed';transaction_removed=$true;cleanup_local=$true;logout_server=$true;cleanup_pending=$false}
    if (!(Test-CompletedCleanup $completed 'prepare_failed')) { throw 'Already-clean prepare failure fixture failed.' }
    foreach ($field in @('run_id','session_id','user_id','email','project_host','stage','transaction_removed','cleanup_local','logout_server','cleanup_pending')) {
        $changed = $completed | ConvertTo-Json | ConvertFrom-Json
        $changed.$field = if ($field -eq 'cleanup_pending') { $true } elseif ($field -in @('transaction_removed','cleanup_local','logout_server')) { $false } else { 'changed' }
        if (Test-CompletedCleanup $changed 'prepare_failed') { throw "Completed cleanup guard failed: $field" }
    }
    $completed.stage = 'cleanup_passed'
    if (!(Test-CompletedCleanup $completed 'cleanup_passed') -or (Test-CompletedCleanup $completed 'prepare_failed')) { throw 'Cleanup stage fixture failed.' }
    Write-Output 'Host fixture self-test PASS: payload guards, preserved already-clean prepare failures and strict cleanup stage/pending.'
    return
}
if (!$RealAndroidWeb) { throw 'Richiesto il flag esplicito -RealAndroidWeb.' }
$sdkDirectory = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $sdkDirectory 'platform-tools/adb.exe'
if ($Device -notmatch '^emulator-[0-9]+$') { throw 'Usare solo l''AVD SavelyTest dedicato.' }
$avdName = (& $adb -s $Device emu avd name 2>$null | Select-Object -First 1).Trim()
if ($avdName -ne 'SavelyTest') { throw 'Il dispositivo non è SavelyTest.' }
# Gradle removes the app after connected tests; check installation before creating a QA session.
foreach ($package in @('cc.savely.android', 'cc.savely.android.test')) {
    $installed = & $adb -s $Device shell pm path $package 2>$null
    if ($LASTEXITCODE -ne 0 -or ($installed -join "`n") -notmatch '^package:') {
        throw 'Installare APK debug e androidTest prima dello smoke Android-web.'
    }
}
& $adb -s $Device shell run-as cc.savely.android true 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Lo smoke Android-web richiede un APK debuggable.' }
$configuration = Get-Content -LiteralPath (Join-Path $projectDirectory 'savely.properties')
$publicKey = ($configuration | Where-Object { $_ -match '^SUPABASE_ANON_KEY=' } | Select-Object -First 1) -replace '^SUPABASE_ANON_KEY=', ''
$configuredUrl = ($configuration | Where-Object { $_ -match '^SUPABASE_URL=' } | Select-Object -First 1) -replace '^SUPABASE_URL=', ''
if ($configuredUrl.TrimEnd('/') -ne $baseUrl -or [string]::IsNullOrWhiteSpace($publicKey) -or $publicKey.StartsWith('sb_secret_')) { throw 'Progetto o chiave client non validi.' }
if (!$publicKey.StartsWith('sb_publishable_') -and (Get-JwtMetadata $publicKey).role -ne 'anon') { throw 'Chiave privilegiata rifiutata.' }
$node = (Get-Command node -ErrorAction Stop).Source
$nodeScript = Join-Path $PSScriptRoot 'web-read-smoke.mjs'
if (!(Test-Path -LiteralPath (Join-Path $projectDirectory '../Savely/node_modules/playwright/package.json'))) { throw 'Playwright web assente; nessuna installazione automatica.' }
$class = 'cc.savely.android.data.RealAndroidWebSmokeTest'
$inputName = "android-web-session-$runId.json"
$checkpointName = 'android-web-checkpoint.json'
$resultName = 'android-web-native-result.json'
$artifactDirectory = Join-Path $projectDirectory 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
function Read-Checkpoint {
    $source = & $adb -s $Device shell run-as cc.savely.android cat "files/$checkpointName" 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Native checkpoint absent; preserve unrelated data.' }
    $checkpoint = ($source | Out-String) | ConvertFrom-Json
    Assert-Checkpoint $checkpoint
    return $checkpoint
}
function Read-NativeResult {
    $source = & $adb -s $Device shell run-as cc.savely.android cat "files/$resultName" 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Native result absent.' }
    $result = ($source | Out-String) | ConvertFrom-Json
    if ($result.run_id -ne $runId -or $result.session_id -ne $sessionId -or $result.user_id -ne $qaId -or
        $result.email -ne $qaEmail -or $result.project_host -ne "$projectRef.supabase.co") { throw 'Native result owner mismatch.' }
    $source | Set-Content -LiteralPath (Join-Path $artifactDirectory $resultName) -Encoding utf8
    return $result
}
function Invoke-NativePhase([string]$method) {
    $output = & $adb -s $Device shell am instrument -w -r -e class "$class#$method" -e realAndroidWebSmoke true -e androidWebRunId $runId -e androidWebSessionId $sessionId -e androidWebDate $day cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner
    $output | Write-Host
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'OK \(1 test\)') { throw 'Native instrumentation phase did not pass.' }
}
$adminKey=$null; $adminHeaders=$null; $keysJson=$null; $keys=$null; $link=$null; $auth=$null; $json=$null
$inputAttempted=$false; $inputRemoved=$true; $nativeAttempted=$false; $nativeCleanup=$false; $markerRemoved=$false; $logoutVerified=$false; $browserPassed=$false
try {
    # Credentials remain in host RAM; only the fresh QA user session is sent to the dedicated debug app.
    $keysJson = & supabase projects api-keys --project-ref $projectRef --reveal --output json 2>$null | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'Project CLI credentials unavailable.' }
    $keys = $keysJson | ConvertFrom-Json
    $adminKey = ($keys | Where-Object { $_.name -eq 'service_role' } | Select-Object -First 1).api_key
    if ([string]::IsNullOrWhiteSpace($adminKey)) { throw 'Admin credential unavailable.' }
    $adminHeaders = @{apikey=$adminKey;Authorization="Bearer $adminKey"}
    $user = Invoke-RestMethod "$baseUrl/auth/v1/admin/users/$qaId" -Headers $adminHeaders -TimeoutSec 30
    if ($user.id -ne $qaId -or $user.email -ne $qaEmail -or !$user.email_confirmed_at -or @($user.factors | Where-Object { $_.status -eq 'verified' }).Count) { throw 'Existing QA identity/MFA mismatch.' }
    $link = Invoke-RestMethod "$baseUrl/auth/v1/admin/generate_link" -Method Post -Headers $adminHeaders -ContentType 'application/json' -Body (@{type='magiclink';email=$qaEmail} | ConvertTo-Json -Compress) -TimeoutSec 30
    $linkUser = if ($link.user) { $link.user } else { $link }
    if ($linkUser.id -ne $qaId -or $linkUser.email -ne $qaEmail) { throw 'Magic-link owner mismatch.' }
    $hash = if ($link.hashed_token) { $link.hashed_token } else { $link.properties.hashed_token }
    if ([string]::IsNullOrWhiteSpace($hash)) { throw 'Missing magic-link token.' }
    $auth = Invoke-RestMethod "$baseUrl/auth/v1/verify" -Method Post -Headers @{apikey=$publicKey} -ContentType 'application/json' -Body (@{type='magiclink';token_hash=$hash} | ConvertTo-Json -Compress) -TimeoutSec 30
    if ($auth.user.id -ne $qaId -or $auth.user.email -ne $qaEmail) { throw 'Session owner mismatch.' }
    $claims = Get-JwtMetadata $auth.access_token
    if ($claims.sub -ne $qaId -or $claims.iss -ne "$baseUrl/auth/v1" -or $claims.session_id -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { throw 'Session JWT metadata mismatch.' }
    $sessionId = $claims.session_id
    # A pre-existing private input must be preserved; use a clean, dedicated QA installation.
    & $adb -s $Device shell "run-as cc.savely.android sh -c 'test ! -e files/$inputName'" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Existing private QA input must not be overwritten.' }
    & $adb -s $Device shell run-as cc.savely.android mkdir -p files | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Private app directory unavailable.' }
    $json = $auth | ConvertTo-Json -Depth 20 -Compress
    $inputAttempted = $true
    $inputRemoved = $false
    $json | & $adb -s $Device shell "run-as cc.savely.android sh -c 'cat > files/$inputName'"
    if ($LASTEXITCODE -ne 0) { throw 'Private session stdin transfer failed.' }
    $nativeAttempted = $true
    Invoke-NativePhase 'prepareNativeMarker'
    $checkpoint = Read-Checkpoint
    Assert-Checkpoint $checkpoint -Prepared
    $checkpoint | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $artifactDirectory $checkpointName) -Encoding utf8
    $json = @{project_url=$baseUrl;site_origin='https://www.savely.cc';public_key=$publicKey;run_id=$runId;session=$auth;native_checkpoint=$checkpoint} | ConvertTo-Json -Depth 20 -Compress
    $json | & $node $nodeScript --real-native-web-read
    if ($LASTEXITCODE -ne 0) { throw 'Browser read phase failed.' }
    $browser = Get-Content -LiteralPath (Join-Path $artifactDirectory 'android-web-browser-result.json') -Raw | ConvertFrom-Json
    if ($browser.run_id -ne $runId -or $browser.session_id -ne $sessionId -or $browser.stage -ne 'browser_read_passed' -or
        !$browser.browser_read_verified -or $browser.effective_origin -ne 'https://www.savely.cc') { throw 'Browser result mismatch.' }
    $browserPassed = $true
} catch {
    throw "Smoke Android-web non riuscito per run_id=$runId. Leggere gli artefatti non segreti; non dichiarare PASS senza le due letture e cleanup completo."
} finally {
    if ($inputAttempted) {
        & $adb -s $Device shell run-as cc.savely.android rm -f "files/$inputName" 2>$null | Out-Null
        $inputRemoved = $LASTEXITCODE -eq 0
        if (!$inputRemoved) { Write-Warning "cleanup_pending=true: input privato non rimosso per run_id=$runId." }
    }
    if ($nativeAttempted) {
        $alreadyClean = $false
        try {
            $previous = Read-NativeResult
            $alreadyClean = Test-CompletedCleanup $previous 'prepare_failed'
        } catch { # No matching result authorizes skipping cleanup.
        }
        if ($alreadyClean) {
            $nativeCleanup = $true; $markerRemoved = $true; $logoutVerified = $true
        } else {
        try {
            try { Invoke-NativePhase 'cleanupNativeMarker' } catch { Write-Warning 'La fase cleanup nativa ha fallito; riconciliare il suo risultato prima del fallback.' }
            $cleanup = Read-NativeResult
            $nativeCleanup = $cleanup.cleanup_local -eq $true
            $markerRemoved = $cleanup.transaction_removed -eq $true
            $logoutVerified = $cleanup.logout_server -eq $true
            if (!(Test-CompletedCleanup $cleanup 'cleanup_passed')) {
                $nativeCleanup = $false
                throw 'Native backend cleanup incomplete or pending.'
            }
        } catch {
            # Fallback needs an exact checkpoint; never infer ownership from a matching marker alone.
            try {
                $checkpoint = Read-Checkpoint
                $json = @{project_url=$baseUrl;site_origin='https://www.savely.cc';public_key=$publicKey;run_id=$runId;session=$auth;native_checkpoint=$checkpoint} | ConvertTo-Json -Depth 20 -Compress
                $json | & $node $nodeScript --cleanup-native-marker
                $fallback = Get-Content -LiteralPath (Join-Path $artifactDirectory 'android-web-fallback-result.json') -Raw | ConvertFrom-Json
                if ($LASTEXITCODE -eq 0 -and $fallback.run_id -eq $runId -and $fallback.session_id -eq $sessionId -and
                    $fallback.user_id -eq $qaId -and $fallback.email -eq $qaEmail -and $fallback.project_host -eq "$projectRef.supabase.co") {
                    $markerRemoved = $fallback.transaction_removed -eq $true
                    $logoutVerified = $fallback.logout_server -eq $true
                }
            } catch { Write-Warning "cleanup_pending=true: marker non riconciliato; preservare dati estranei. run_id=$runId" }
        }
        }
    }
    if ($auth.user.id -eq $qaId -and $auth.user.email -eq $qaEmail -and !$logoutVerified) {
        try { Invoke-RestMethod "$baseUrl/auth/v1/logout?scope=local" -Method Post -Headers @{apikey=$publicKey;Authorization="Bearer $($auth.access_token)"} -TimeoutSec 15 | Out-Null; $logoutVerified=$true }
        catch { Write-Warning "cleanup_pending=true: logout temporaneo non confermato. run_id=$runId" }
    }
    $pending = !$inputRemoved -or ($nativeAttempted -and (!$nativeCleanup -or !$markerRemoved -or !$logoutVerified)) -or ($auth -and !$logoutVerified)
    $summary = @{run_id=$runId;session_id=$sessionId;user_id=$qaId;email=$qaEmail;project_host="$projectRef.supabase.co";
        scope='native-data-created/native-ui-read/web-read';browser_read_verified=$browserPassed;transaction_removed=$markerRemoved;
        cleanup_local=$nativeCleanup;logout_server=$logoutVerified;cleanup_pending=$pending;
        stage=$(if ($browserPassed -and !$pending) { 'passed' } else { 'failed' })}
    $summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactDirectory 'android-web-result.json') -Encoding utf8
    Write-Output ($summary | ConvertTo-Json -Compress)
    if ($pending) { Write-Warning "cleanup_pending=true: riprendere solo il checkpoint di run_id=$runId; nessun clear locale generico." }
    $adminKey=$null; $adminHeaders=$null; $keysJson=$null; $keys=$null; $link=$null; $linkUser=$null; $hash=$null; $auth=$null; $json=$null
    if ($browserPassed -and $pending) { throw 'Letture completate ma cleanup non verificato; lo smoke non è passato.' }
}
