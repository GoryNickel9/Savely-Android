# Qualifica form amministrazione — D01

## Stato e provenienza

**D01 è PASS LOCALE** nel criterio dei permessi QA, perdita del ruolo, diniego e ripristino. Il terzo run conclude undici checkpoint nativi nello stesso metodo; le dodici fixture finali passano su API 31 e API 35. Pulizia server e spegnimento sono verificati. Il consuntivo porta a **sette righe chiuse localmente e 26 aperte** nella matrice, senza qualificare parità globale o stabile. I due precedenti tentativi nativi falliti e i tre tentativi Android bloccati restano conservati separatamente.

Run iniziale [bee9eda2-cc53-4ad7-9e23-766562d63b50](../artifacts/admin-form-ui/bee9eda2-cc53-4ad7-9e23-766562d63b50/). Produzione iniziale `5197c711e2fa32ae3f30ddf6a9106f1dcd994be7`, candidata **0.1.12-alpha/code 13**. Riferimento web readonly invariato `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. `input-metadata.json` identifica gli APK frozen iniziali e conferma che i byte del release installato corrispondono:

| Componente iniziale | Sorgente | SHA-256 | Byte |
|---|---|---|---:|
| Release firmato | `5197c71` | `93839c429ee56689660d5a3ebc17dacf9059a8903a5d8ae23f1d0e5e0b5012fc` | 2.747.518 |
| App debug | Stessa produzione | `34ef94abe27d6ca65acc697c979db984346a171d6a48cfb0e9dba7d85c26b6c1` | 20.403.376 |
| Test APK iniziale | `a11a36a` | `5a8e1732a9cf99bb1591ef59c403a1b2c3a48cd5826a5511e9ce528a51041b7d` | 2.196.381 |
| APK separato di qualificazione | `357ac07` | `d5f042a8431d8ce236f3c3b35bab457c511fd2c77ba59c2a3787c1c8313c7106` | 1.767.223 |

`signature.txt` conferma schema v2 e continuità del certificato SHA-256 `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1`. Questi hash appartengono alla candidata iniziale; quelli successivi sono distinti sotto. Le qualifiche precedenti conservano i propri sorgenti, APK e criteri; nessuna viene trasferita automaticamente a questo blocco.

La candidata del figlio `fbd9aea8-a24f-4698-9de9-77e8dd20bc4e` mantiene versione/code ma cambia i byte. Produzione `a104e8c0752c0751b1535512fd43980c64b501ae`, prima fixture Android `8b219a4`, fixture JVM `67ca417`, driver `1a6516a` e ledger `7fe24f1`. Il suo `input-metadata.json` identifica i seguenti frozen e conferma il confronto dei byte installati; `signature.txt` conferma v2 e lo stesso certificato sopra indicato. I successivi aggiornamenti del driver e della sola fixture Android sono distinti sotto; i byte app restano questi anche nel consuntivo finale `9a2988a8`.

| Componente finale del figlio | SHA-256 | Byte |
|---|---|---:|
| Release firmato | `3e5252bb4c4ff0b3b7c1630c92598cd8d909c1e27b48077699c1fea33fc74bd6` | 2.747.518 |
| App debug | `298c19d5764706d305183482e0932d3ef25d9af6c01b25d5682d1cd5c794d0a8` | 20.716.749 |
| Test APK dei tentativi bloccati | `6e794508020babfc6c362bbe109d1c2fd8a3b51188c066b5b7b546488dae431b` | 2.196.582 |
| APK separato del primo figlio, driver `1a6516a` | `1308a7a810afafda693ea993c7153315e4ed3180d9c10ed6a3b5d8d2c91023f3` | 1.769.110 |

## Build e correzioni successive

| Verifica conclusa | Esito e fonte |
|---|---|
| Build iniziale | `local-build.log`: riuscita in 7m 30s. |
| JVM debug e release | **545 per variante**, zero failure/error/skip secondo `input-metadata.json`; conteggi distinti. |
| Lint debug e release | Zero errori, 32 warning e 6 hint per variante; `lint-results-debug.xml`, `lint-results-release.xml`. |
| Ledger offline | **24/24, 22,304 s**, source `7fe24f1`; [test_admin_form_evidence.py](../tools/test_admin_form_evidence.py), solo FakeExecutor. |
| Controllo Finance isolato del figlio | 1/1 dopo revisione della fixture; `finance-fixture-isolated.log`, build riuscita in 53s. |
| Build finale del figlio | `local-build-final.log`: riuscita in **8m 33s**, **545 JVM per variante**, zero failure/error/skip; lint zero errori, 32 warning e 6 hint per variante. |
| Form nativi del terzo run | **11/11 checkpoint, un metodo, 84,583 s**; `9a2988a8/native-admin-result.json` e log sanitizzato `native-admin.log`. Non sostituiscono le dodici fixture Android. |
| Fixture API 31 finale | **12/12, 49,164 s**, dodici start e dodici successi, zero failure/skip/timeout; `android31-admin-final-clock.log` e relativo risultato nel run `9a2988a8`. |
| Fixture API 35 finale | **12/12, 54,507 s**, stessi criteri e test APK; `android35-admin-final-clock.log` e relativo risultato. |

Il primo controllo produzione impedisce il riuso di cache amministrativa dopo perdita del ruolo quando `get_users` restituisce una lista vuota. La source `414a0a1c45b5d7be0fe07a0f8bee43a2afb06196` aggiunge il controllo del ruolo **dopo la lettura utenti e prima della pubblicazione**, per bloccare risultati caricati mentre il ruolo viene revocato. La produzione finale `a104e8c` comprende questo controllo e le preletture vincolate a expected owner/revision. Il conteggio 545 finale è quello della nuova build, distinto dal precedente consuntivo con lo stesso numero di casi.

Il tentativo intermedio del genitore, `post-refresh-guard-build.log`, fallisce in **5m 33s**: 545 test release, un timeout in `FinanceSharingViewModelTest`, caso `partial finance writes cannot repeat until a successful read even if refresh fails`, secondo await alla riga 38. L'XML originale è conservato sotto `jvm-release-post-guard-attempt1/`. La source `67ca41760a0320c16a25936770d5e7846d03a7d6` modifica soltanto la fixture Finance: attende i figli del refresh prima del secondo refresh e della chiusura, con limite di 10s. Una race di sincronizzazione è possibile, ma la causa esatta del timeout non è dimostrata. Il controllo isolato e la build finale del figlio passano; il tentativo intermedio resta fallito e non viene sommato agli esiti finali.

La fixture source `8b219a4` porta il gruppo Admin a **dodici casi totali: quattro originali e otto nuovi**. Il caso con HTTP 200 utenti e ruolo perso durante la lettura richiede stop della pubblicazione e cache vuota. È compilato nel test APK `6e7945…`; i tentativi API 31/35 su questo APK non concludono i dodici casi. Non vengono dichiarati dodici casi nuovi o una suite completa dell'app.

## Tentativi Android bloccati

| Tentativo sul test APK `6e7945…` | Esito conservato |
|---|---|
| API 35 completo, run `fbd9aea8` | Sette casi conclusi, poi l'ottavo `webPermissionDenialClosesDeleteDialogButServerFailurePreservesItAndSession` senza progresso dalle 08:01:50 UTC per oltre dodici minuti. Arresto forzato registrato in `android35-hung-aborted.json`; il successivo risultato Process crashed segue questo arresto e non viene presentato come crash spontaneo della produzione. |
| API 35 caso ottavo isolato, stesso APK | Zero casi conclusi, nuovo blocco e arresto forzato: `android35-admin-isolated.log`, `android35-admin-isolated-aborted.json`. Non è un PASS isolato. |
| API 31 completo, run `9a2988a8` | Tre casi conclusi, poi `passwordResetNeedsExplicitConfirmationAndCancelMakesNoRequest` bloccato; arresto forzato conservato in `android31-admin-final-aborted.json`. |

`9a2988a8/android31-debug-stack.txt` mostra il thread instrumentation in `waitForNextChoreographerFrame`/`Espresso.onIdle`, dentro `performScrollTo` alla riga 183 di AdminScreenTest; il main è in `nativePollOnce`. Il coordinatore controlla dispositivo awake, stay-on e timeout schermo prima dell'arresto. La causa definitiva del blocco o del dispatcher non è dimostrata e non viene attribuito un bug alla produzione, anche dopo i retry riusciti. I risultati parziali e gli arresti non vengono sommati per formare un PASS.

La source fixture `96b7d93` sostituisce la rule con `createComposeRule` predefinita: quattro righe di diff, tre rimosse e una aggiunta. Il diagnostico temporaneo viene rimosso prima della compilazione e non entra nel test APK. `fixture-clock-build.log` registra build riuscita in **35s**; `fixture-input-final.json` identifica `app-debug-androidTest-clock.apk`, SHA-256 `6370daf21839cc6dab86a97571fe0bd233328dda72d39f072be30e1e030e6b8e`, **2.196.536 byte**, con app debug `298c19…` e release `3e5252…` invariati.

Il reset isolato API 31 passa 1/1 in **9,950 s**, il delete isolato API 35 passa 1/1 in **11,739 s**. I successivi run completi dei dodici casi passano rispettivamente in **49,164 s** e **54,507 s**. I quattro log e risultati sono `android31-isolated-reset`, `android35-isolated-delete`, `android31-admin-final-clock`, `android35-admin-final-clock`. `qualification-final.json` registra dodici completamenti/successi per sistema, nessun timeout, sorgenti distinte e hash degli script host frozen. Sono gli stessi dodici casi su due sistemi, non una suite completa dell'app; gli isolati non aumentano il conteggio dei casi finali.

La diagnostica API 31 con `adbd root` avviene **dopo il campione nativo e la disinstallazione dell'APK release**, per leggere lo stack della sola fixture debug. Il coordinatore ripristina adbd come shell prima delle fixture finali e dello spegnimento. Non viene attribuita questa diagnostica al campione sul release firmato.

## Tentativo nativo conservato e nuovo run

Il login QAAdmin `714d32e4-c24b-49e2-9f32-ecd64f08f17b` passa sul code 13 (`admin1-login.json`). Il successivo `native-admin-result.json` termina fallito dopo il solo `admin_ready`: `failed-native-report.json` riporta `ADMIN_CHECKBOX_STATE_INVALID`, mentre il runner registra `NATIVE_ENDED_BEFORE_CHECKPOINT`. Nessun permesso viene modificato in questo tentativo.

Il diagnostico con sole etichette fisse, source `414a0a1`, passa un metodo in 1,952 s e osserva sette checkbox come sibling appiattiti nella gerarchia. Il driver source `1a6516a` passa alla corrispondenza tra checkbox e riga verticale dell'etichetta, verificando valore precedente e una sola corrispondenza. Questo errore di selezione del driver non dimostra un difetto del salvataggio permessi nell'app. Il tentativo originario resta fallito e non viene convertito in PASS.

Il diagnostico chiude il dialogo e il logout QAAdmin passa (`admin1-logout.json`). `failed-native-cleanup.json`, snapshot alle **07:32:02,565573 UTC del 13 ottobre 2026**, osserva zero fattori/sessioni, registrazioni disabilitate e permessi dei tre profili QA ripristinati al baseline.

Il run figlio [fbd9aea8-a24f-4698-9de9-77e8dd20bc4e](../artifacts/admin-form-ui/fbd9aea8-a24f-4698-9de9-77e8dd20bc4e/) conserva il collegamento al genitore in `parent-run.json`. `evidence/preflight-observed.json` alle **07:32:03,0817 UTC** registra zero fattori/sessioni e baseline dei tre QA. Il login QAAdmin `0288d1f4-791c-4eb0-9a02-5824d66bb70d` passa sul release finale code 13 (`admin1-login.json`), con Dashboard, identità e profilo verificati. Il metodo termina poi **FAILED**, `NATIVE_CHECKPOINT_TIMEOUT`, dopo `admin_ready` e `permission_cancelled`. `failed-native-report.json` riporta `MFA_MESSAGE_MISSING`, ultimo checkpoint `permission_cancelled`. `failed-native-server.json` alle **08:05:24,966883 UTC** conferma tuttavia `B.fire=false` applicato: due checkpoint osservati non significano zero scritture e non qualificano `permission_changed`.

La correzione successiva è soltanto nel driver, source `a594e19`: usa l'etichetta singolare `Registration enabled` e una ricerca dedicata del feedback Admin senza riportare la lista all'inizio. Non modifica il sorgente dell'app e non converte il tentativo fallito in PASS.

Il coordinatore ripristina `B.fire` con un client QAAdmin host, password/token in RAM, usando CAS su ID profilo, user ID e preimage completo dei permessi (`fire-cleanup-reservation.json`, `fire-cleanup-write.json`: HTTP 200). La sessione host aggiuntiva esegue logout locale HTTP 204 (`fire-cleanup-logout.json`). Il logout del QAAdmin nativo passa in **12,102 s** (`admin1-logout.json`, `admin1-logout.log`). `failed-native-cleanup.json` alle **08:08:55,807599 UTC** verifica tutte e tre le mappe permessi al baseline, zero fattori/sessioni e registrazioni disabilitate. È un recupero esplicito, distinto dalla qualifica del metodo.

Il figlio [9a2988a8-8650-49d0-b424-ab99f837a14f](../artifacts/admin-form-ui/9a2988a8-8650-49d0-b424-ab99f837a14f/) conserva il precedente in `parent-run.json`. Il preflight alle **08:08:57,151046 UTC** osserva baseline e zero fattori/sessioni. I tre APK app release, app debug e test restano quelli della tabella finale (`3e5252…`, `298c19…`, `6e7945…`). La prima compilazione del driver fallisce in **39s**, riferimento `ACTION_SHOW_ON_SCREEN` non risolto (`harness-feedback-build.log`). Source `a2981ea` usa l'ID di `AccessibilityAction`; il retry passa in **51s** (`harness-feedback-build-attempt2.log`). Cambia soltanto il driver separato, frozen SHA-256 `6be58a5dc396f79745e2f516e2131b3429ec49c657048a167a5f92d1268b24eb`, **1.770.020 byte**. La produzione resta `a104e8c` e il release resta `3e5252…`.

Il login QAAdmin `8651b96f-90ec-47a8-91ac-4b5247e0243f` passa. `native-admin-result.json` conclude **PASSED, 11/11 checkpoint** nello stesso run; `native-admin.log` registra **OK, un test, 84,583 s**. Nessun checkpoint dei run falliti viene trasferito al nuovo figlio. `final-native-server-clean.json` alle **08:14:00,505973 UTC** verifica baseline completo dei tre profili QA, proiezioni selezionate users/profili invariate, zero sessioni/fattori, registrazioni disabilitate e attività QA zero nelle tabelle controllate.

Il successivo `final-server-clean.json` alle **08:24:04,352365 UTC** verifica gli stessi vincoli dopo le fixture. `emulators-stopped.json` conferma entrambi i seriali assenti da ADB e adbd ripristinato come shell prima dello spegnimento; il coordinatore controlla inoltre zero processi qemu con Get-Process. Lo stato finale riguarda soltanto i QA e i processi controllati, non account estranei. `qualification-final.json` conserva questo consuntivo come `passed_local`, con `global_parity=false`, `ci_used=false` e `published=false`.

## Criterio nativo e client host

Il campione concluso usa **API 31/EN** e il QAAdmin autorizzato. I form Android modificano soltanto il profilo QA B identificato esattamente: annullamento senza scrittura, `fire=false`, ripristino, concessione temporanea di `admin=true` conservando gli altri sei flag e successiva rimozione del solo ruolo con ripristino completo.

QA B è un **client host con password e token in RAM**, non un secondo telefono. Il coordinatore vincola ogni CAS al profilo QA esatto e al payload precedente: QA B temporaneamente Admin revoca e poi ripristina il ruolo del QAAdmin principale. Il form principale mostra diniego, rimozione di cache/dialogo e successivo ricaricamento dopo ripristino. Il ledger resta separato da queste scritture: non effettua CAS, login, revoche, logout o cleanup.

L'elenco utenti amministrativo reale resta soltanto nella RAM della UI; non viene catturato, salvato o stampato. Le evidenze persistite selezionano esclusivamente i tre QA noti. Nessuna email, eliminazione account, modifica effettiva alle registrazioni, service-role host o scrittura SQL privilegiata fa parte del campione. I logout finali riguardano il QAAdmin nella UI e il solo client QA B host.

## Undici checkpoint del ledger

[admin_form_evidence.py](../tools/admin_form_evidence.py) usa lo snapshot MFA di sola lettura e aggiunge `qa_profile_rows` con soli `id`, `user_id`, `permissions` dei tre QA. La validazione dei ruoli è separata da MFA: durante questo flusso QA B diventa Admin e le sessioni appartengono soltanto a QAAdmin e QA B.

| Checkpoint osservato nel run `9a2988a8` | Criterio del ledger soddisfatto |
|---|---|
| `admin_ready`, `permission_cancelled`, `permission_changed`, `permission_restored` | Prima sessione QAAdmin fresca; baseline conservato dopo annulla; solo `B.fire=false`, poi ripristino esatto. |
| `secondary_admin_ready` | Solo `B.admin=true` e nuova sessione B distinta, creata dopo il preflight e il checkpoint precedente. |
| `primary_role_revoked`, `primary_role_denied` | Solo `QAAdmin.admin=false`, B resta Admin; stesse sessioni vincolate e diniego osservato dalla UI principale. |
| `primary_role_restored`, `primary_reloaded` | QAAdmin torna al baseline, B resta Admin; ricaricamento UI senza riutilizzare contenuto autorizzato dal ruolo precedente. |
| `secondary_admin_removed`, `completed` | B torna al baseline completo; logout finali, zero fattori/sessioni e tutti i permessi iniziali ripristinati. |

Il baseline contiene sette chiavi note booleane, `fire=true` nei tre QA, A/B non Admin e QAAdmin Admin. Ogni snapshot confronta i permessi completi, gli UUID profilo, le identità selezionate e i couple code A/B; le proiezioni A/B devono corrispondere a `qa_profile_rows`. Non ammette nuove chiavi, modifiche ad altri flag o righe estranee. Le sessioni sono al massimo due, con UUID/owner/creazione/AAL vincolati e timestamp monotoni. Fattori e attività QA nelle tabelle controllate restano zero; registrazioni sempre disabilitate.

Le reservation e le osservazioni sono scritte in modo esclusivo e durabile; il coordinatore invia ACK solo dopo la lettura validata. Errori, esiti parziali, ordine errato e replay vengono rifiutati, anche con una nuova istanza. **Undici checkpoint non sono undici test JUnit**: i 24 test Python, i dodici casi Android per sistema e il metodo nativo hanno perimetri distinti.

## Limiti del criterio qualificato

Il criterio riguarda permessi, diniego e ripristino del solo campione QA autorizzato. Non qualifica ogni funzione amministrativa, un secondo smartphone, tutti gli account reali, parità globale o versione stabile. Snapshot e proiezioni selezionate non rappresentano l'intero contenuto di auth.users o dei profili.

Il PASS locale unisce il campione nativo, i dodici casi mirati per API, le letture server e la pulizia finale; ciascun conteggio mantiene il proprio perimetro. I fallimenti precedenti non vengono convertiti in PASS e la loro causa definitiva non viene dedotta dal cambio di rule. Nessuna CI, pubblicazione, modifica a configurazione globale/schema/RLS/web. Il documento non contiene credenziali o token. La redazione usa soltanto artefatti locali, senza eseguire test, build, dispositivi o backend.
