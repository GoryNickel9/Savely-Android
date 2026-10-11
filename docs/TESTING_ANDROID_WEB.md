# Marker creato da Android e letto dal web

Questa prova opt-in conserva una sola transazione sintetica tra due invocazioni instrumentation e una lettura del sito reale `https://www.savely.cc`. Il report dichiara lo scope `native-data-created/native-ui-read/web-read`: la creazione usa `SupabaseApi.insert` nel processo Android, la riga viene letta da `MainActivity` e dal browser. Non verifica il submit della form Android, il login con password o lo SHA del deploy.

## Stato e comandi

Helper, fixture e classe Android sono presenti. I self-test host/Node e la compilazione instrumentation passano. Lo smoke reale del 2026-10-11 è passato sul SavelyTest dedicato: la stessa riga creata da Android è stata letta da `MainActivity` e dal sito reale, quindi eliminata con logout locale e clear della sessione confermati. Le nuove esecuzioni richiedono review e APK debug/test compilati e installati.

```powershell
./tools/run-android-web-smoke.ps1 -SelfTest
node ./tools/web-read-smoke.mjs --self-test
# Solo dopo review, build e installazione dei due APK debug sul SavelyTest dedicato:
./tools/run-android-web-smoke.ps1 -RealAndroidWeb -Device emulator-5556
```

Richiede Node, Edge, Playwright già presente nella repo web e CLI Supabase autenticata sul progetto autorizzato. Non installa dipendenze, non compila/modifica il web e non riusa profili browser dell'utente. La modalità già verificata `--real-web-read` conserva la propria creazione host e il proprio cleanup; lo smoke unificato usa due modalità distinte senza POST finanziarie host.

## Sequenza

1. Il helper controlla l'AVD `SavelyTest`, progetto e chiave pubblica, quindi genera una sessione temporanea per il solo account QA `9daffd41-39bf-4b1e-be68-abf12549c4a7` / `e2e-savely@example.com`. Nessuna email viene inviata e nessuna password/configurazione viene cambiata. La chiave amministrativa resta sul computer, in RAM. La sessione passa via stdin ADB nello storage privato debug e viene consumata/rimossa dalla fase Android; nessun file di credenziali viene scritto sul computer. La persistenza successiva usa soltanto il Keystore cifrato.
2. `RealAndroidWebSmokeTest#prepareNativeMarker` richiede debug, package, progetto, QA, sessione JWT e UUID di run esatti, store vuoto e checkpoint assente. Conferma l'identità reale e l'assenza di MFA prima di salvare. Scrive un checkpoint non segreto prima dell'unico INSERT: UUID uguale al run, `Android-Web-QA-<run>`, expense EUR 0,01, data Europe/Rome, categoria nulla e cambio EUR 1. Un GET per UUID/proprietario riconcilia anche una risposta POST persa senza retry. La vera UI Android deve leggere il marker prima di consegnare lo stato `native_prepared`.
3. `--real-native-web-read` riceve sessione e checkpoint tramite stdin RAM. Richiede lo stesso run/sessione/payload e le prove `native_created`/`native_ui_read`. Verifica identità e riga reali prima di aprire un nuovo contesto Edge headless. L'API host in questa modalità accetta soltanto GET; la sessione viene fornita nel localStorage della sola origine canonica. Il browser legge marker/importo unici e blocca ogni scrittura, audit automatici e WebSocket. Non effettua logout o cancellazione: la sessione resta disponibile per la fase Android finale.
4. Il `finally` host invoca `#cleanupNativeMarker` anche dopo un errore browser. Se una preparazione fallita ha già ripulito tutto, conserva il risultato `prepare_failed` con ownership esatta, tre conferme di cleanup e `cleanup_pending=false`, senza sovrascriverlo con una seconda invocazione. La fase Android richiede checkpoint, run e `session_id` coincidenti con la sola sessione locale QA; preserva un altro proprietario o una diversa sessione. Legge per UUID/proprietario, rifiuta payload cambiati, esegue una sola DELETE con predicati su tutti i campi e verifica zero righe. Poi effettua logout `scope=local` e clear della sola sessione identificata.
5. Se il cleanup nativo non si conclude, l'host legge il checkpoint esatto e può chiamare `--cleanup-native-marker`: identità reale, GET, eventuale DELETE sul payload integro e GET zero, poi logout locale. Non crea record e non cancella preferenze Android. Un device offline può quindi lasciare `cleanup_local=false`; lo smoke resta fallito con `cleanup_pending=true` finché la pulizia locale non è verificata. Nessun retry cieco di INSERT/DELETE.

## Contratto della classe Android

La classe deve essere esclusa dalle suite instrumentation ordinarie e usare gli argomenti `realAndroidWebSmoke=true`, `androidWebRunId=<UUID>`, `androidWebSessionId=<UUID>`, `androidWebDate=yyyy-MM-dd`. Tutti sono metadati non segreti.

| File privato | Contenuto |
| --- | --- |
| `android-web-session-<run UUID>.json` | Bootstrap Supabase completo, limitato a 16 KiB, consumato prima delle operazioni finanziarie; il cleanup host rimuove solo il file di quella run |
| `android-web-checkpoint.json` | Metadati atomici di run, sessione, QA e intero payload; nessun token |
| `android-web-native-result.json` | Risultati di creazione/lettura e cleanup; nessun token |

Il checkpoint include `run_id`, `session_id`, `user_id`, `email`, `project_host`, `transaction_id`, `marker`, `date`, `amount="0.01"`, `currency="EUR"`, `type="expense"`, `category_id=null`, `exchange_rate_eur="1"`, `creation_attempted=true`. Lo stage è `creating` prima dell'INSERT e `native_prepared` dopo creazione/lettura native, con `native_created=true` e `native_ui_read=true`. La fase di cleanup accetta soltanto questi due stage appartenenti alla run corrente.

Il risultato nativo mantiene i metadati di ownership e pubblica `transaction_removed`, `cleanup_local`, `logout_server`, `cleanup_pending`. Un checkpoint incompleto o un mismatch non autorizza cancellazioni. Il checkpoint deve restare disponibile quando il backend cleanup è incompleto, anche se la sessione locale è stata rimossa. Una rimozione del checkpoint fallita viene dichiarata pending; i tre flag di cleanup da soli non autorizzano un PASS senza lo stage corretto e `cleanup_pending=false`.

## Evidenze e limiti

La run reale `99480d6e-a832-4e35-a72b-5f6057e9599f` ha usato la sola sessione QA temporanea `2a9e9afa-cffb-4c03-a3c9-0c9bc0f5aa6d`. La preparazione Android è passata in **12,456 s**, il cleanup in **2,821 s**; sono durate delle due invocazioni instrumentation, non dell'intero flusso o benchmark di prestazioni. Lo screenshot della sola riga è stato verificato visivamente dal coordinatore.

| Evidenza della run | Risultato |
| --- | --- |
| INSERT nel processo Android e lettura in `MainActivity` | `native_created=true`, `native_ui_read=true` |
| Lettura browser del marker e importo EUR 0,01 | `browser_read_verified=true`, origine effettiva `https://www.savely.cc`, HTTP 200 |
| Scritture browser | Zero consentite; un audit automatico e una richiesta esterna bloccati, zero scritture inattese |
| Eliminazione della sola transazione, logout locale e clear | `transaction_removed=true`, `logout_server=true`, `cleanup_local=true` |
| Stato finale | Riepilogo `passed`, cleanup nativo `cleanup_passed`, `cleanup_pending=false` |

Le fixture locali verificano ogni campo del checkpoint, rifiuto di scritture nella modalità browser, risposta INSERT/DELETE persa senza retry, conservazione dei payload modificati e assenza verificata per UUID/proprietario. Non chiamano backend e non aprono browser.

Gli artefatti locali sotto `artifacts/` sono `android-web-checkpoint.json`, `android-web-browser-result.json`, `android-web-native-result.json`, eventuale `android-web-fallback-result.json` e riepilogo `android-web-result.json`. La cartella è ignorata da Git: i report e `web-read-smoke-row-99480d6e-a832-4e35-a72b-5f6057e9599f.png` non vengono versionati. Lo screenshot mostra solo la riga filtrata della run. Nessun token, trace, HAR, storageState o video viene salvato. Un `browser_read_passed` conserva intenzionalmente `cleanup_pending=true`; soltanto il riepilogo finale può dichiarare `passed` dopo letture, cancellazione, logout, rimozione input e clear locale verificati.

Questa run non verifica password, submit della form Android, comportamento su un telefono fisico o SHA del deploy. L'audit web automatico è stato bloccato intenzionalmente e non viene dichiarato verificato. La sessione è stata ottenuta dal magic link amministrativo senza invio email; non dimostra il normale flusso di accesso con password o MFA.

Un'interruzione simultanea di host e device può impedire ogni `finally`; i metadati consentono di riprendere la pulizia del solo UUID/payload. Non dichiarare PASS, non rigenerare o modificare l'account QA e non rimuovere sessioni o fixture preesistenti per sbloccare il test.
