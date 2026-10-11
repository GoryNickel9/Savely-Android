param([string]$Device = 'emulator-5556', [switch]$ProcessDeath)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$sdkDirectory = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $sdkDirectory 'platform-tools/adb.exe'
$projectRef = 'crqnfbahytzenisospcx'
$baseUrl = "https://$projectRef.supabase.co"
$qaId = '9daffd41-39bf-4b1e-be68-abf12549c4a7'
$qaEmail = 'e2e-savely@example.com'
if ($Device -notmatch '^emulator-[0-9]+$') { throw 'Usare esclusivamente l''emulatore SavelyTest dedicato.' }
$avdName = (& $adb -s $Device emu avd name 2>$null | Select-Object -First 1).Trim()
if ($avdName -ne 'SavelyTest') { throw 'Il dispositivo non è l''AVD SavelyTest dedicato.' }
$configPath = Join-Path $projectDirectory 'savely.properties'
$configuration = Get-Content -LiteralPath $configPath
$publicKey = ($configuration | Where-Object { $_ -match '^SUPABASE_ANON_KEY=' } | Select-Object -First 1) -replace '^SUPABASE_ANON_KEY=', ''
if ([string]::IsNullOrWhiteSpace($publicKey) -or $publicKey.StartsWith('sb_secret_')) { throw 'Configurazione pubblica mancante o non valida.' }
$configuredUrl = ($configuration | Where-Object { $_ -match '^SUPABASE_URL=' } | Select-Object -First 1) -replace '^SUPABASE_URL=', ''
if ($configuredUrl.TrimEnd('/') -ne $baseUrl) { throw 'Il progetto configurato non corrisponde alla fixture E2E.' }
$debugApk = Join-Path $projectDirectory 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $projectDirectory 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
foreach ($apk in @($debugApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Compilare assembleDebug e assembleDebugAndroidTest prima dello smoke.' }
}
$keysJson = $null; $keys = $null; $adminKey = $null; $adminHeaders = $null
$link = $null; $auth = $null; $sessionJson = $null
$privateInputTransferAttempted = $false
$logoutVerified = $false
$runId = $null
$processDeathAttempted = $false
$localCleanupConfirmed = $false
$privateInputName = if ($ProcessDeath) { 'backend-process-death-session.json' } else { 'backend-smoke-session.json' }
$privateResultName = if ($ProcessDeath) { 'backend-process-death-result.json' } else { 'backend-smoke-result.json' }
try {
    # Existing CLI credentials stay on the host; no administrative key is installed in the app.
    $keysJson = & supabase projects api-keys --project-ref $projectRef --reveal --output json 2>$null | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'La CLI Supabase non può leggere le credenziali del progetto autorizzato.' }
    try { $keys = $keysJson | ConvertFrom-Json }
    catch { throw 'Risposta CLI non valida; nessun contenuto di credenziali viene stampato.' }
    $adminKey = ($keys | Where-Object { $_.name -eq 'service_role' } | Select-Object -First 1).api_key
    if ([string]::IsNullOrWhiteSpace($adminKey)) { throw 'Credenziale amministrativa non disponibile nel processo host.' }
    $adminHeaders = @{ apikey = $adminKey; Authorization = "Bearer $adminKey" }
    $user = Invoke-RestMethod "$baseUrl/auth/v1/admin/users/$qaId" -Headers $adminHeaders -TimeoutSec 30
    if ($user.id -ne $qaId -or $user.email -ne $qaEmail -or !$user.email_confirmed_at) { throw 'La fixture E2E esistente non corrisponde a ID, email e conferma attesi.' }
    if (@($user.factors | Where-Object { $_.status -eq 'verified' }).Count -ne 0) { throw 'L''account E2E richiede MFA: usare il flusso normale.' }
    # generate_link returns a link/token without sending an email or changing the password.
    $link = Invoke-RestMethod "$baseUrl/auth/v1/admin/generate_link" -Method Post -Headers $adminHeaders -ContentType 'application/json' -Body (@{type='magiclink';email=$qaEmail} | ConvertTo-Json -Compress) -TimeoutSec 30
    $linkUser = if ($link.user) { $link.user } else { $link }
    if ($linkUser.id -ne $qaId -or $linkUser.email -ne $qaEmail) { throw 'Il link non appartiene alla fixture E2E.' }
    $hashedToken = if ($link.hashed_token) { $link.hashed_token } else { $link.properties.hashed_token }
    if ([string]::IsNullOrWhiteSpace($hashedToken)) { throw 'Token del link assente.' }
    $auth = Invoke-RestMethod "$baseUrl/auth/v1/verify" -Method Post -Headers @{apikey=$publicKey} -ContentType 'application/json' -Body (@{type='magiclink';token_hash=$hashedToken} | ConvertTo-Json -Compress) -TimeoutSec 30
    if ($auth.user.id -ne $qaId -or $auth.user.email -ne $qaEmail) { throw 'La sessione non appartiene alla fixture E2E.' }
    $sessionJson = $auth | ConvertTo-Json -Depth 20 -Compress
    & $adb -s $Device install -r $debugApk
    if ($LASTEXITCODE -ne 0) { throw 'Installazione debug fallita. Non disinstallare una release con dati utente per aggirare una firma diversa.' }
    & $adb -s $Device install -r $testApk
    if ($LASTEXITCODE -ne 0) { throw 'Installazione test fallita.' }
    & $adb -s $Device shell run-as cc.savely.android mkdir -p files
    $privateInputTransferAttempted = $true
    $sessionJson | & $adb -s $Device shell "run-as cc.savely.android sh -c 'cat > files/$privateInputName'"
    if ($LASTEXITCODE -ne 0) { throw 'Trasferimento nello storage privato fallito.' }
    if ($ProcessDeath) {
        $runId = [guid]::NewGuid().ToString()
        $testClass = 'cc.savely.android.data.RealProcessDeathSmokeTest'
        $processDeathAttempted = $true
        $instrumentationOutput = & $adb -s $Device shell am instrument -w -r -e class "$testClass#persistQaSessionBeforeProcessDeath" -e realProcessDeathSmoke true -e processDeathRunId $runId cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner
        $instrumentationOutput | Write-Output
        if ($LASTEXITCODE -ne 0 -or ($instrumentationOutput -join "`n") -notmatch 'OK \(1 test\)') { throw 'Preparazione process death fallita; nessuna scrittura finanziaria prevista.' }
        & $adb -s $Device shell am force-stop cc.savely.android
        if ($LASTEXITCODE -ne 0) { throw 'Arresto del processo QA non riuscito.' }
        $remainingPid = & $adb -s $Device shell pidof cc.savely.android
        if (-not [string]::IsNullOrWhiteSpace(($remainingPid | Out-String))) { throw 'Il processo QA è ancora attivo; non dichiarare verificato il riavvio.' }
        $instrumentationOutput = & $adb -s $Device shell am instrument -w -r -e class "$testClass#restoreQaSessionAfterProcessDeath" -e realProcessDeathSmoke true -e processDeathRunId $runId cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner
    } else {
        $instrumentationOutput = & $adb -s $Device shell am instrument -w -r -e class cc.savely.android.data.RealBackendSmokeTest -e realBackendSmoke true cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner
    }
    $instrumentationOutput | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($instrumentationOutput -join "`n") -notmatch 'OK \(1 test\)') { throw 'Smoke Android fallito: leggere il risultato senza ripetere scritture alla cieca.' }
    $resultJson = & $adb -s $Device shell run-as cc.savely.android cat "files/$privateResultName"
    if ($LASTEXITCODE -ne 0) { throw 'Risultato privato assente.' }
    $result = $resultJson | ConvertFrom-Json
    $artifactDirectory = Join-Path $projectDirectory 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
    $resultJson | Set-Content -LiteralPath (Join-Path $artifactDirectory $privateResultName) -Encoding utf8
    if ($ProcessDeath) {
        if ($result.stage -ne 'passed' -or $result.run_id -ne $runId -or !$result.cleanup_local -or !$result.logout_server) { throw 'Ripristino dopo process death o logout non verificato.' }
    } elseif ($result.stage -ne 'passed' -or !$result.hard_deleted -or $result.cleanup_pending) { throw 'Smoke incompleto: controllare il risultato e ripulire solo la riga di questa esecuzione.' }
    $logoutVerified = $true
    if ($ProcessDeath) { Write-Output 'Process death reale passato: sessione ripristinata in un nuovo processo, dashboard verificata e logout concluso.' }
    else { Write-Output 'Smoke reale passato: il solo movimento sintetico è stato eliminato e la sessione chiusa.' }
} catch {
    # Avoid printing response bodies that could contain authentication credentials.
    if ($_ -is [System.Management.Automation.ErrorRecord] -and $_.Exception -is [Microsoft.PowerShell.Commands.HttpResponseException]) {
        throw 'API Supabase non riuscita; nessun corpo di autenticazione viene stampato. Verificare la fixture senza modificare impostazioni o password.'
    }
    throw
} finally {
    if ($privateInputTransferAttempted) {
        & $adb -s $Device shell run-as cc.savely.android rm -f "files/$privateInputName" | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'cleanup_pending=true: rimozione dell''input privato non verificata. Ricollegare SavelyTest e rimuovere solo il file di input di questo smoke.' }
    }
    if ($ProcessDeath -and !$logoutVerified -and $processDeathAttempted) {
        try {
            # Only the exact run/checkpoint/session may be cleaned. Never remove session preferences from the host.
            $cleanupOutput = & $adb -s $Device shell am instrument -w -r -e class 'cc.savely.android.data.RealProcessDeathSmokeTest#cleanupQaSessionAfterInterruptedProcessDeath' -e realProcessDeathSmoke true -e processDeathRunId $runId cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner
            $cleanupOutput | Write-Output
            $cleanupResultJson = & $adb -s $Device shell run-as cc.savely.android cat "files/$privateResultName"
            if ($LASTEXITCODE -eq 0) {
                $cleanupResult = $cleanupResultJson | ConvertFrom-Json
                if ($cleanupResult.run_id -eq $runId -and $cleanupResult.user_id -eq $qaId -and $cleanupResult.email -eq $qaEmail -and $cleanupResult.project_host -eq "$projectRef.supabase.co") {
                    $cleanupArtifactDirectory = Join-Path $projectDirectory 'artifacts'
                    New-Item -ItemType Directory -Force -Path $cleanupArtifactDirectory | Out-Null
                    $cleanupResultJson | Set-Content -LiteralPath (Join-Path $cleanupArtifactDirectory 'backend-process-death-result.json') -Encoding utf8
                    $localCleanupConfirmed = $cleanupResult.cleanup_local -eq $true
                    if ($localCleanupConfirmed -and $cleanupResult.logout_server -eq $true) { $logoutVerified = $true }
                }
            }
        } catch {
            # A disconnected device or mismatching checkpoint must preserve any unrelated local owner.
            $localCleanupConfirmed = $false
        }
        if (!$localCleanupConfirmed) {
            Write-Warning "cleanup_pending=true: pulizia locale non verificata per processDeathRunId=$runId. Ricollegare SavelyTest e usare solo il checkpoint di questa esecuzione; nessuna preferenza utente viene cancellata alla cieca."
        }
    }
    if ($auth.access_token -and !$logoutVerified) {
        try {
            Invoke-RestMethod "$baseUrl/auth/v1/logout?scope=local" -Method Post -Headers @{apikey=$publicKey;Authorization="Bearer $($auth.access_token)"} -TimeoutSec 15 | Out-Null
            $logoutVerified = $true
        }
        catch { Write-Warning 'cleanup_pending=true: revoca host non confermata; il test tenta anche il logout. Non stampare né riutilizzare la sessione.' }
    }
    $keysJson = $null; $keys = $null; $adminKey = $null; $adminHeaders = $null
    $link = $null; $linkUser = $null; $hashedToken = $null; $auth = $null; $sessionJson = $null
}
