param(
    [string]$Device = 'emulator-5556',
    [switch]$RealFinanceForm,
    [switch]$Recover,
    [switch]$FixtureOnly,
    [switch]$SelfTest,
    [string]$RunId,
    [string]$SessionId,
    [string]$Date
)
$ErrorActionPreference = 'Stop'
$projectRef = 'crqnfbahytzenisospcx'
$baseUrl = "https://$projectRef.supabase.co"
$qaId = '9daffd41-39bf-4b1e-be68-abf12549c4a7'
$qaEmail = 'e2e-savely@example.com'
$scope = 'production-FinanceScreen-form/test-transport-UUID'
$uuidPattern = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
function Assert-Invocation {
    if ($RunId -cnotmatch $uuidPattern -or $SessionId -cnotmatch $uuidPattern) { throw 'Run/session UUID non canonico.' }
    $parsed = [datetime]::MinValue
    if (![datetime]::TryParseExact($Date, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::None, [ref]$parsed)) { throw 'Data ISO non valida.' }
}
function Assert-Identity($value) {
    if ($value.run_id -cne $RunId -or $value.session_id -cne $SessionId -or $value.user_id -cne $qaId -or
        $value.email -cne $qaEmail -or $value.project_host -cne "$projectRef.supabase.co" -or
        $value.transaction_id -cne $RunId -or $value.marker -cne "Android-Form-QA-$RunId" -or $value.date -cne $Date) {
        throw 'Artefatto di un altro run/session/account: preservato.'
    }
}
function Assert-Checkpoint($value) {
    Assert-Identity $value
    $names = @('version','run_id','session_id','user_id','email','project_host','transaction_id','marker','amount','currency','date','type','category_id',
        'exchange_rate_eur','stage','first_pid','cleanup_amount','creation_attempted','creation_confirmed','update_attempted','update_confirmed',
        'delete_attempted','removal_confirmed','cleanup_delete_attempted','create_response','update_response','delete_response','cleanup_response')
    if (@($value.PSObject.Properties.Name).Count -ne $names.Count -or @($value.PSObject.Properties.Name | Where-Object { $_ -notin $names }).Count -or
        $value.version -ne 1 -or $value.amount -cne '0.01' -or $value.currency -cne 'EUR' -or $value.type -cne 'expense' -or
        $null -ne $value.category_id -or $value.exchange_rate_eur -cne '1' -or
        ($value.first_pid -isnot [int] -and $value.first_pid -isnot [long]) -or $value.first_pid -le 0 -or $value.first_pid -gt [int]::MaxValue) { throw 'Payload/checkpoint non valido.' }
    foreach ($field in @('creation_attempted','creation_confirmed','update_attempted','update_confirmed','delete_attempted','removal_confirmed','cleanup_delete_attempted')) {
        if ($value.$field -isnot [bool]) { throw 'Flag checkpoint non booleano.' }
    }
    if (($value.creation_confirmed -and !$value.creation_attempted) -or ($value.update_attempted -and !$value.creation_confirmed) -or
        ($value.update_confirmed -and !$value.update_attempted) -or ($value.delete_attempted -and !$value.update_confirmed) -or
        ($value.removal_confirmed -and (!$value.creation_attempted -or (!$value.delete_attempted -and !$value.cleanup_delete_attempted)))) { throw 'Checkpoint incoerente.' }
    $attempts = @{create='creation_attempted';update='update_attempted';delete='delete_attempted';cleanup='cleanup_delete_attempted'}
    foreach ($op in $attempts.Keys) {
        $field = "${op}_response"
        if ($value.$field -cnotin @('none','unknown','accepted','rejected') -or (($value.$field -cne 'none') -ne $value.($attempts[$op]))) { throw 'Risposta/stadio incoerenti.' }
    }
    if ($value.cleanup_delete_attempted) {
        if ($value.cleanup_amount -cnotin @('0.01','0.02') -or !$value.creation_attempted -or
            ($value.cleanup_amount -ceq '0.02' -and !$value.update_attempted)) { throw 'Importo cleanup non autorizzato.' }
    } elseif ($null -ne $value.cleanup_amount) { throw 'Importo cleanup senza tentativo.' }
    $consistent = switch -CaseSensitive ($value.stage) {
        'prepared' { !$value.creation_attempted }
        'creating' { $value.creation_attempted -and !$value.creation_confirmed -and !$value.cleanup_delete_attempted }
        'created' { $value.creation_confirmed -and !$value.update_attempted -and !$value.cleanup_delete_attempted }
        'updating' { $value.update_attempted -and !$value.update_confirmed -and !$value.cleanup_delete_attempted }
        'updated' { $value.update_confirmed -and !$value.delete_attempted -and !$value.cleanup_delete_attempted }
        'deleting' { $value.delete_attempted -and !$value.removal_confirmed -and !$value.cleanup_delete_attempted }
        'removed' { $value.removal_confirmed }
        'cleanup_pending' { $value.cleanup_delete_attempted -and !$value.removal_confirmed }
        default { $false }
    }
    if (!$consistent) { throw 'Stage/flag checkpoint incoerenti.' }
}
function Assert-Result($value) {
    Assert-Identity $value
    if ($value.scope -cne $scope -or $value.stage -cnotin @('passed','failed')) { throw 'Scope/stadio risultato non valido.' }
    foreach ($field in @('form_crud_verified','transaction_removed','cleanup_local','logout_server','cleanup_pending','checkpoint_retained')) {
        if ($value.$field -isnot [bool]) { throw 'Flag risultato non booleano.' }
    }
    if ($value.stage -ceq 'passed' -and (!$value.transaction_removed -or !$value.cleanup_local -or !$value.logout_server -or
        $value.cleanup_pending -or $value.checkpoint_retained -or $value.failure_code)) { throw 'PASS senza cleanup completo.' }
}
function Test-Completed($value, [bool]$NeedsUi) {
    Assert-Result $value
    return $value.stage -ceq 'passed' -and (Test-CleanupComplete $value) -and (!$NeedsUi -or $value.form_crud_verified)
}
function Test-CleanupComplete($value) {
    Assert-Result $value
    return $value.transaction_removed -and $value.cleanup_local -and $value.logout_server -and !$value.cleanup_pending -and !$value.checkpoint_retained
}
function Get-JwtMetadata([string]$token) {
    try {
        if ($token.Length -gt 16384) { throw 'invalid' }
        $parts = $token.Split('.')
        if ($parts.Count -ne 3) { throw 'invalid' }
        $encoded = $parts[1].Replace('-', '+').Replace('_', '/')
        $encoded = $encoded.PadRight($encoded.Length + ((4 - $encoded.Length % 4) % 4), '=')
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) | ConvertFrom-Json
    } catch { throw 'Metadata JWT non valida; token non mostrato.' }
}
if ($SelfTest) {
    if ($RealFinanceForm -or $Recover -or $FixtureOnly) { throw 'SelfTest non può usare modalità device/reali.' }
    $RunId='00000000-0000-4000-8000-000000000001'; $SessionId='00000000-0000-4000-8000-000000000002'; $Date='2026-10-11'
    Assert-Invocation
    $fixture = [pscustomobject]@{version=1;run_id=$RunId;session_id=$SessionId;user_id=$qaId;email=$qaEmail;project_host="$projectRef.supabase.co";
        transaction_id=$RunId;marker="Android-Form-QA-$RunId";amount='0.01';currency='EUR';date=$Date;type='expense';category_id=$null;exchange_rate_eur='1';
        stage='prepared';first_pid=1234;cleanup_amount=$null;creation_attempted=$false;creation_confirmed=$false;update_attempted=$false;
        update_confirmed=$false;delete_attempted=$false;removal_confirmed=$false;cleanup_delete_attempted=$false;
        create_response='none';update_response='none';delete_response='none';cleanup_response='none'}
    Assert-Checkpoint $fixture
    $checks=0
    foreach ($field in $fixture.PSObject.Properties.Name) {
        $changed=$fixture | ConvertTo-Json | ConvertFrom-Json
        $changed.$field='changed'
        $rejected=$false
        try { Assert-Checkpoint $changed } catch { $rejected=$true }
        if (!$rejected) { throw "Guard checkpoint fallita: $field" }
        $checks++
    }
    $fixture.stage='creating'; $fixture.creation_attempted=$true; $fixture.create_response='unknown'
    Assert-Checkpoint $fixture
    $fixture.stage='cleanup_pending'; $fixture.cleanup_delete_attempted=$true; $fixture.cleanup_amount='0.01'; $fixture.cleanup_response='unknown'
    Assert-Checkpoint $fixture
    $bad=$fixture | ConvertTo-Json | ConvertFrom-Json; $bad.cleanup_amount='0.02'
    $rejected=$false; try { Assert-Checkpoint $bad } catch { $rejected=$true }
    if (!$rejected) { throw 'Cleanup 0.02 non ammessa prima PATCH.' }; $checks++
    $completed=[pscustomobject]@{run_id=$RunId;session_id=$SessionId;user_id=$qaId;email=$qaEmail;project_host="$projectRef.supabase.co";
        transaction_id=$RunId;marker="Android-Form-QA-$RunId";date=$Date;scope=$scope;stage='passed';form_crud_verified=$true;
        transaction_removed=$true;cleanup_local=$true;logout_server=$true;cleanup_pending=$false;checkpoint_retained=$false}
    if (!(Test-Completed $completed $true)) { throw 'Risultato valido rifiutato.' }
    foreach ($field in $completed.PSObject.Properties.Name) {
        $bad=$completed | ConvertTo-Json | ConvertFrom-Json; $bad.$field='changed'
        $rejected=$false; try { Test-Completed $bad $true | Out-Null } catch { $rejected=$true }
        if (!$rejected) { throw "Guard risultato fallita: $field" }; $checks++
    }
    $completed.form_crud_verified=$false
    if ((Test-Completed $completed $true) -or !(Test-Completed $completed $false)) { throw 'Recovery confusa con test form.' }
    $checks++
    $completed.stage='failed'
    if ((Test-Completed $completed $false) -or !(Test-CleanupComplete $completed)) { throw 'Fallimento UI confuso con cleanup pending.' }
    $checks++
    Write-Output "Host fixture self-test PASS: $checks invalid payload/result cases and separate recovery scope; no ADB/network/credentials."
    return
}
if (@(@($RealFinanceForm,$Recover,$FixtureOnly) | Where-Object { $_ }).Count -ne 1) { throw 'Usare una sola modalità: -FixtureOnly, -RealFinanceForm o -Recover.' }
if ($Device -notmatch '^emulator-[0-9]+$') { throw 'Usare solo AVD SavelyTest.' }
$projectDirectory=Split-Path -Parent $PSScriptRoot
$sdkDirectory=if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb=Join-Path $sdkDirectory 'platform-tools/adb.exe'
$class='cc.savely.android.data.RealFinanceFormSmokeTest'
$runner='cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner'
function Invoke-Native([string]$method, [string]$flag) {
    $arguments=@('-s',$Device,'shell','am','instrument','-w','-r','-e','class',"$class#$method",'-e',$flag,'true')
    if ($flag -notin @('financeFormFixtures','financeFormPreflight')) { $arguments+=@('-e','financeFormRunId',$RunId,'-e','financeFormSessionId',$SessionId,'-e','financeFormDate',$Date) }
    $output=& $adb @arguments $runner 2>$null
    $exit=$LASTEXITCODE
    # Only sanitized runner status is printed; exception payloads or HTTP bodies are not copied into artifacts.
    if ($exit -ne 0 -or ($output -join "`n") -notmatch 'OK \(1 test\)') { throw 'Instrumentation non completata: nessun PASS inferito.' }
    Write-Output "Instrumentation PASS: $method"
}
# Complete preflight and synthetic device fixtures BEFORE obtaining any credential.
$avdOutput=@(& $adb -s $Device emu avd name 2>$null)
$avdExit=$LASTEXITCODE
$avdName=($avdOutput | Select-Object -First 1).Trim()
if ($avdExit -ne 0 -or $avdName -cne 'SavelyTest') { throw 'AVD non corrisponde a SavelyTest.' }
foreach ($package in @('cc.savely.android','cc.savely.android.test')) {
    $installed=& $adb -s $Device shell pm path $package 2>$null
    if ($LASTEXITCODE -ne 0 -or ($installed -join "`n") -notmatch '^package:') { throw 'Installare debug + androidTest prima di questo helper.' }
}
& $adb -s $Device shell run-as cc.savely.android true 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK debug obbligatorio.' }
Invoke-Native 'checkpointAndTransportFixturesUseSyntheticTlsOnly' 'financeFormFixtures'
if ($FixtureOnly) { return }
$configuration=Get-Content -LiteralPath (Join-Path $projectDirectory 'savely.properties')
$configuredUrl=($configuration | Where-Object { $_ -match '^SUPABASE_URL=' } | Select-Object -First 1) -replace '^SUPABASE_URL=', ''
$publicKey=($configuration | Where-Object { $_ -match '^SUPABASE_ANON_KEY=' } | Select-Object -First 1) -replace '^SUPABASE_ANON_KEY=', ''
if ($configuredUrl.TrimEnd('/') -cne $baseUrl -or [string]::IsNullOrWhiteSpace($publicKey) -or $publicKey.StartsWith('sb_secret_')) { throw 'Progetto/client key rifiutati.' }
if (!$publicKey.StartsWith('sb_publishable_') -and (Get-JwtMetadata $publicKey).role -cne 'anon') { throw 'Chiave privilegiata rifiutata.' }
if ($RealFinanceForm) {
    if ($RunId -or $SessionId -or $Date) { throw 'Il nuovo test genera un solo run/session; argomenti recovery rifiutati.' }
    Invoke-Native 'preflightEmptyLocalStateWithoutCredentials' 'financeFormPreflight'
    $RunId=[guid]::NewGuid().ToString()
    $Date=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow,'W. Europe Standard Time').ToString('yyyy-MM-dd')
} else { Assert-Invocation }
$inputName="finance-form-session-$RunId.json"
$checkpointName="finance-form-checkpoint-$RunId.json"
$resultName="finance-form-result-$RunId.json"
$artifactDirectory=Join-Path $projectDirectory "artifacts/finance-form/$RunId"
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
function Read-PrivateJson([string]$name, [int]$max) {
    $source=& $adb -s $Device shell run-as cc.savely.android cat "files/$name" 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Artefatto privato assente: preservare stato.' }
    $text=$source | Out-String
    if ([Text.Encoding]::UTF8.GetByteCount($text) -gt $max) { throw 'Artefatto troppo grande.' }
    return $text | ConvertFrom-Json
}
function Export-Checkpoint {
    $checkpoint=Read-PrivateJson $checkpointName 8192
    Assert-Checkpoint $checkpoint
    $checkpoint | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $artifactDirectory 'checkpoint.json') -Encoding utf8
    return $checkpoint
}
function Export-Result {
    $result=Read-PrivateJson $resultName 4096
    Assert-Result $result
    $result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $artifactDirectory 'native-result.json') -Encoding utf8
    return $result
}
if ($Recover) {
    # No new session, bootstrap, password, admin CLI or host-side financial mutation in recovery.
    $checkpoint=Export-Checkpoint
    if ($checkpoint.cleanup_delete_attempted -and !$checkpoint.removal_confirmed) {
        Write-Warning 'Cleanup DELETE già tentata: solo riconciliazione; nessuna seconda DELETE.'
    }
    & $adb -s $Device shell am force-stop cc.savely.android 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Impossibile creare processo recovery distinto.' }
    $phasePassed=$false
    try { Invoke-Native 'recoverOwnedMarkerInNewProcess' 'realFinanceFormRecovery'; $phasePassed=$true }
    finally {
        try {
            $result=Export-Result
            $passed=$phasePassed -and (Test-Completed $result $false)
            Write-Output "Recovery cleanup verified=$passed; form CRUD is not verified by recovery; run_id=$RunId"
        } catch { Write-Warning "Recovery pending; preservare checkpoint e stato locale: run_id=$RunId" }
    }
    if (!$passed) { throw 'Recovery non completata; nessun retry scritture o clear locale generico.' }
    return
}
$adminKey=$null; $adminHeaders=$null; $keysJson=$null; $keys=$null; $link=$null; $auth=$null; $json=$null
$inputAttempted=$false; $inputCreated=$false; $inputRemoved=$true; $nativeAttempted=$false; $phasePassed=$false; $result=$null; $logoutVerified=$false; $failed=$false; $checkpointFound=$false
try {
    # Privileged CLI credential remains only in host RAM; stderr and HTTP payloads are never printed.
    $keysJson=& supabase projects api-keys --project-ref $projectRef --reveal --output json 2>$null | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'Credenziali CLI non disponibili.' }
    $keys=$keysJson | ConvertFrom-Json
    $adminKey=($keys | Where-Object { $_.name -ceq 'service_role' } | Select-Object -First 1).api_key
    if ([string]::IsNullOrWhiteSpace($adminKey)) { throw 'Credenziale admin assente.' }
    $adminHeaders=@{apikey=$adminKey;Authorization="Bearer $adminKey"}
    $user=Invoke-RestMethod "$baseUrl/auth/v1/admin/users/$qaId" -Headers $adminHeaders -TimeoutSec 30
    if ($user.id -cne $qaId -or $user.email -cne $qaEmail -or !$user.email_confirmed_at -or @($user.factors | Where-Object { $_.status -ceq 'verified' }).Count) { throw 'Account QA confermato/MFA non corrisponde.' }
    # generate_link returns a token to this host; no email is sent and no password is changed.
    $link=Invoke-RestMethod "$baseUrl/auth/v1/admin/generate_link" -Method Post -Headers $adminHeaders -ContentType 'application/json' -Body (@{type='magiclink';email=$qaEmail} | ConvertTo-Json -Compress) -TimeoutSec 30
    $linkUser=if ($link.user) { $link.user } else { $link }
    if ($linkUser.id -cne $qaId -or $linkUser.email -cne $qaEmail) { throw 'Owner magic link non corrisponde.' }
    $hash=if ($link.hashed_token) { $link.hashed_token } else { $link.properties.hashed_token }
    if ([string]::IsNullOrWhiteSpace($hash)) { throw 'Token magic link assente.' }
    $auth=Invoke-RestMethod "$baseUrl/auth/v1/verify" -Method Post -Headers @{apikey=$publicKey} -ContentType 'application/json' -Body (@{type='magiclink';token_hash=$hash} | ConvertTo-Json -Compress) -TimeoutSec 30
    if ($auth.user.id -cne $qaId -or $auth.user.email -cne $qaEmail) { throw 'Owner sessione non corrisponde.' }
    $claims=Get-JwtMetadata $auth.access_token
    if ($claims.sub -cne $qaId -or $claims.iss -cne "$baseUrl/auth/v1" -or $claims.session_id -cnotmatch $uuidPattern -or
        $claims.exp -le [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+600) { throw 'Metadata/scadenza sessione non corrisponde.' }
    $SessionId=$claims.session_id
    Assert-Invocation
    & $adb -s $Device shell run-as cc.savely.android mkdir -p files 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Directory privata indisponibile.' }
    $json=$auth | ConvertTo-Json -Depth 20 -Compress
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 16384) { throw 'Bootstrap troppo grande.' }
    # noclobber is checked by the shell that opens the destination; no check-then-overwrite race.
    $inputAttempted=$true; $inputRemoved=$false
    $json | & $adb -s $Device shell "run-as cc.savely.android sh -c 'set -C; umask 077; cat > files/$inputName'" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Bootstrap stdin privato fallito o file preesistente preservato.' }
    $inputCreated=$true
    $nativeAttempted=$true
    Invoke-Native 'productionFormCreatesEditsAndDeletesOnlyOwnedQaMarker' 'realFinanceFormSmoke'
    $phasePassed=$true
} catch {
    $failed=$true
    Write-Warning "Smoke form non completato; nessun payload/credenziale stampato. run_id=$RunId"
} finally {
    if ($inputCreated) {
        # Only our randomly named bootstrap is removed; encrypted sessions/checkpoints are never blindly cleared.
        & $adb -s $Device shell run-as cc.savely.android rm -f "files/$inputName" 2>$null | Out-Null
        $inputRemoved=$LASTEXITCODE -eq 0
    }
    if ($nativeAttempted) {
        try { $result=Export-Result; $logoutVerified=$result.logout_server } catch { }
        if (!$result -or $result.checkpoint_retained) { try { Export-Checkpoint | Out-Null; $checkpointFound=$true } catch { } }
    }
    # Revoke only this RAM-held temporary session if it was never handed to native code,
    # or native code conclusively reports no remaining transaction. Keep the recovery credential for financial uncertainty.
    $canRevoke=(!$nativeAttempted) -or ($result -and $result.transaction_removed)
    if ($auth -and !$logoutVerified -and $canRevoke) {
        try {
            $claims=Get-JwtMetadata $auth.access_token
            if ($auth.user.id -cne $qaId -or $auth.user.email -cne $qaEmail -or $claims.session_id -cne $SessionId -or
                $claims.sub -cne $qaId -or $claims.iss -cne "$baseUrl/auth/v1") { throw 'Credenziale logout estranea.' }
            Invoke-RestMethod "$baseUrl/auth/v1/logout?scope=local" -Method Post -Headers @{apikey=$publicKey;Authorization="Bearer $($auth.access_token)"} -TimeoutSec 15 | Out-Null
            $logoutVerified=$true
        } catch { Write-Warning "Logout temporaneo non confermato: run_id=$RunId" }
    }
    $passed=$phasePassed -and $inputRemoved -and $result -and (Test-Completed $result $true)
    $checkpointRetained=$checkpointFound -or ($result -and $result.checkpoint_retained)
    $cleanupComplete=$inputRemoved -and !$checkpointRetained -and (($nativeAttempted -and $result -and (Test-CleanupComplete $result)) -or
        (!$nativeAttempted -and (!$auth -or $logoutVerified)))
    $summary=@{run_id=$RunId;session_id=$SessionId;user_id=$qaId;email=$qaEmail;project_host="$projectRef.supabase.co";transaction_id=$RunId;
        marker="Android-Form-QA-$RunId";date=$Date;scope=$scope;stage=$(if ($passed) { 'passed' } else { 'failed' });
        form_crud_verified=($result -and $result.form_crud_verified);transaction_removed=($result -and $result.transaction_removed);
        cleanup_local=($result -and $result.cleanup_local);logout_server=$logoutVerified;cleanup_pending=(!$cleanupComplete);input_removed=$inputRemoved;
        checkpoint_retained=$checkpointRetained}
    $summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $artifactDirectory 'host-result.json') -Encoding utf8
    Write-Output ($summary | ConvertTo-Json -Compress)
    if (!$passed -and $cleanupComplete) { Write-Warning "Form non verificato, cleanup completo: non invocare recovery. run_id=$RunId" }
    elseif (!$passed -and $checkpointFound -and !$logoutVerified -and !($result -and $result.cleanup_local)) {
        Write-Warning "Se la sessione locale corrisponde ancora, recovery esplicita -Recover -RunId $RunId -SessionId $SessionId -Date $Date. Nessun retry scritture, nessun reset locale generico."
    } elseif (!$passed) {
        Write-Warning "Cleanup incompleto; recovery finanziaria non autorizzata senza checkpoint e credenziale corrispondenti. Non creare una nuova sessione o cancellare lo stato locale. run_id=$RunId"
    }
    $adminKey=$null; $adminHeaders=$null; $keysJson=$null; $keys=$null; $link=$null; $linkUser=$null; $hash=$null; $auth=$null; $json=$null; $user=$null; $claims=$null
}
if ($failed -or !$passed) { throw 'Smoke form non passato o cleanup incompleto; consultare solo gli artefatti non segreti di questo run.' }
