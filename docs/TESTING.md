# Verifica Savely Android

## Risultati 0.1.9 — 12 ottobre 2026

Verifica locale su Windows/JBR, senza CI GitHub. Il sorgente finale comprende la correzione degli INSERT privati con risposta incerta; i candidati precedenti restano immutabili e non vengono distribuiti. Il manifest di 190 file resta invariato durante le build e le suite finali.

| Prova | Esito | Evidenza locale |
|---|---|---|
| JVM | 503 casi, zero fallimenti/errori/skip | `artifacts/parity-0.1.9-final-integrated-debug.txt` e XML JVM |
| Host Python | 6 test superati | `artifacts/host-python-0.1.9-final.txt` |
| Host PowerShell | 41 controlli puri offline superati | `artifacts/host-powershell-0.1.9-final.txt` |
| Lint debug/release | Zero errori, 32 avvertenze e 6 suggerimenti per variante | `artifacts/parity-0.1.9-release-candidate-build.txt` e XML lint |
| Android 15 | 130/130, zero fallimenti/skip, singola suite completa finale: 770,007 s | `artifacts/android15-0.1.9-full-final.txt` |
| Android 12 | 130/130, zero fallimenti/skip, singola suite completa finale: 623,733 s | `artifacts/android12-0.1.9-full-final.txt` |
| Cambio pubblico | HTTP 200, USD→EUR positivo, data ISO, nessuna autenticazione inviata | `artifacts/fx-public-contract-0.1.9.json`; lettura host, non form Android |
| Backend QA | 41 contratti su 42; Google Books HTTP 429; pulizia completa nello stesso run | `artifacts/roadmap-smoke/f49c81d3-b066-4d8e-8f1f-a4565ec908d0/result.json` e `host.json` |

La build debug/JVM finale impiega 17 s; release firmata e lint 5m58s. Sono tempi osservati sul computer, non benchmark del telefono. Le due suite ordinarie escludono esplicitamente le cinque classi opt-in del backend: il runner ADB non eredita automaticamente gli argomenti Gradle. Entrambi gli esiti finali provengono da una singola esecuzione completa, senza sommare i rerun mirati. Le esecuzioni si sono sovrapposte parzialmente fra loro e alla build release; i tempi includono il carico dell’host.

### Fallimenti conservati e correzioni

Le prime suite complete passano 125/130 su API 35 e 126/130 su API 31. La ricreazione del form Manga provocava un accesso alla revisione dell'account ancora nulla: ora il form attende quel dato senza perdere il piano pendente. Il dialogo mapping poteva chiudersi all'apertura a causa del messaggio di un precedente salvataggio: ora consuma il risultato soltanto dopo una nuova richiesta esplicita. Il validatore SVG rifiuta più radici e applica il limite ai byte UTF-8. Queste sono correzioni del codice di produzione.

Le altre correzioni riguardano i test: messaggio Transfer precedente, selettori ambigui delle quote, sottostringhe dei documenti, focus/IME del submit MFA, form Poker ancora in caricamento e scorrimento dei figli Lazy non composti. Gli assert su UUID, payload, proprietario, quantità di scritture, totali e assenza di duplicazioni restano attivi. Collection passa 4/4 in 44,820 s dopo lo scan progressivo e l'attesa di due frame stabili. Un run Legal è stato interrotto durante uno scroll del harness bloccato: non è un run superato.

I log falliti e mirati restano in `artifacts/android15-0.1.9-*` e `artifacts/android12-0.1.9-*`. La revisione finale scopre inoltre il rischio di ripetere un INSERT privato dopo una risposta persa: cinque regressioni JVM verificano risposta malformata, connessione persa, 503, 403 correggibile e interruzione dopo il commit; il form si chiude e resta il blocco fino al refresh completo riuscito.

### Backend e limiti della candidata

Il conteggio backend della 0.1.8 è rettificato da 42/43 a 41/42 contando le voci del report sorgente. Gli asset pubblici precedenti e il bundle originale restano immutabili; il rapporto 0.1.9 riporta la correzione.

Il nuovo run QA verifica letture, export, diniego Admin, CardTrader, 12 INSERT, 8 UPDATE, chiusura CAS di due posizioni e storico. Google Books restituisce ancora HTTP 429: il run complessivo resta fallito e `crud_verified=false`, anche se i singoli contratti di scrittura sono confermati. Non viene ritentata la ricerca per aggirare la quota. `records_removed`, `logout_server`, `cleanup_local`, `input_removed` e `cleanup_complete` sono veri; `cleanup_pending` e `checkpoint_retained` sono falsi. Non servono recovery o modifiche ai dati preesistenti, password, MFA, ruoli, trigger o RLS.

Le fixture verificano il client con dati sintetici, non certificano tutte le policy distribuite. Restano aperti password/email/PKCE/MFA reali, revoca di altre sessioni, Coppia con due account, Admin autorizzato ed eliminazione di un account sintetico. SAF usa registry di risultato e provider locale, senza azionare il selettore di sistema o provider cloud; API 26–28 e nuovi flussi sul telefono fisico non sono verificati. L'utente ha escluso ulteriori prove dettagliate sul proprio dispositivo. La parità globale resta falsa; [PARITY_0_1_9.md](PARITY_0_1_9.md) registra le condizioni della 1.0.

## Risultati 0.1.8 — 12 ottobre 2026

Verifiche locali, senza CI GitHub. Sorgenti web invariati. JDK di Android Studio su Windows, emulatori x86_64 dedicati: SavelyAndroid12 (Android 12/API 31, Google APIs) e SavelyVerify35 (Android 15/API 35, Google Play).

| Prova | Esito | Evidenza locale |
|---|---|---|
| JVM | 399 casi, zero fallimenti/errori/skip | `app/build/test-results/testDebugUnitTest/`, `artifacts/verification-0.1.8-complete.log` |
| Host Python | 6 test superati | `artifacts/host-tests-0.1.8-final.log` |
| Host PowerShell | 41 controlli offline superati; solo funzioni pure estratte via AST | `artifacts/backend-helper-offline-0.1.8.txt` |
| Lint debug/release | Zero errori, 32 avvertenze e 5 suggerimenti | Report lint e `artifacts/verification-0.1.8-final-labels.log` |
| Android 12 | 108 casi ordinari con esito finale positivo dopo riesecuzioni mirate; non è un singolo run finale completo | `artifacts/android12-0.1.8-aggregate.json` e tre log sorgente |
| Android 15 | 108 casi, zero fallimenti/skip, singola suite completa finale: 375,253 s | `artifacts/android15-0.1.8-full-ime-final.txt` e relativo JSON |
| Distribuzione pubblica | Quattro asset senza autenticazione; SHA-256 scaricato e commit coincidono; canale alpha verificato | `artifacts/public-release-verification-0.1.8.json` |
| Guardie QA offline Android 12 | Tre fixture passano, inclusa serializzazione CAS booleana/numerica | `artifacts/android12-0.1.8-final-targets.txt`, `artifacts/roadmap-cleanup-boolean-fixture-0.1.8.txt` |
| Riavvio processo reale Android 12 | Persistenza 1,550 s; ripristino 8,395 s; identità, Dashboard, nuovo PID e logout verificati | `artifacts/backend-process-death-host-0.1.8-final.log`, `artifacts/backend-process-death-result-0.1.8-final.json` |

I tempi sono osservazioni sul computer locale, non benchmark o promesse sul telefono. La prima suite API 35 falliva due casi: layout Compose eseguito dalla continuation OkHttp del test Poker e callback login non consegnato nel test. Poker usa ora un dispatcher di test controllato e scroll touch entro il viewport; login nasconde la tastiera e verifica il submit con un tap sul controllo visibile. Assert e codice di produzione restano invariati. I 17 casi mirati passano in 78,670 s (`artifacts/android15-0.1.8-targets-corrected.txt`); la suite successiva passa 107/108 in 392,582 s. Il login fallisce ancora nell’assert di visibilità prima del tap: il test attende ora che gli insets Android confermino la tastiera nascosta prima di scorrere fino al pulsante. Anche questo log resta distinto (`artifacts/android15-0.1.8-full-corrected.txt`). I sei auth passano in 23,049 s dopo il fix (`artifacts/android15-0.1.8-auth-ime-final.txt`), poi l’intera suite finale passa 108/108 in 375,253 s. Le ultime correzioni del harness Poker/IME sono eseguite su API 35; i risultati API 31 restano quelli della precedente aggregazione, con log separati.

Il primo run JVM falliva 11 casi per il certificato TLS locale privo dei SAN IP; dopo il fix della sola fixture, 12 test auth mirati e poi tutti i 399 passano. La prima suite Android 12 aveva quattro failure: matcher di sottostringhe, messaggio vecchio del canale release e selettore sbagliato della scheda Account. Il run mirato risolve quei casi, ma scopre tre failure nei nuovi helper Transfer (LazyColumn/dialoghi). Dopo correzioni del solo harness, tutti i sei Transfer passano in 27,616 s. Assert di owner, contenuto, numero delle scritture e assenza di retry restano attivi.

La prima prova process death 0.1.8 ripristinava la sessione e l’identità, ma attendeva un’etichetta Dashboard precedente; `restore_failed` registra comunque logout e clear completati. Il test usa ora il titolo con tag stabile e il periodo tradotto nella lingua persistita; la nuova esecuzione passa. L’helper riconosce il report della stessa sessione prima del cleanup di fallback, senza ripetere una revoca già confermata. Il log fallito resta conservato.

### Backend reale e recupero

Il run `8f56e075-5f3a-49eb-87f1-0edbcebe8fe2` passa 41 contratti su 42: letture finanza/moduli, contesto import, export, diniego Admin per QA, catalogo/prezzo CardTrader, 12 CREATE, 8 UPDATE e chiusura CAS di due posizioni. La ricerca Google Books fallisce; una lettura pubblica separata osserva HTTP 429/RESOURCE_EXHAUSTED. Il limite di quota non viene aggirato né presentato come PASS.

La prima pulizia è stata bloccata prima di una DELETE dal confronto Boolean/stringa del solo guard CAS del test. La regressione offline mantiene tipi, owner, payload, snapshot e prenotazione prima del trasporto. Recovery esatta in nuovo processo: righe rimosse, logout server, clear locale, bootstrap e checkpoint eliminati; `cleanup_complete=true`, `cleanup_pending=false`. Il report originale e `recovery-result.json` restano distinti in `artifacts/roadmap-smoke/8f56e075-5f3a-49eb-87f1-0edbcebe8fe2/`. Non sono modificati dati precedenti, password, MFA, trigger, RLS o stato delle registrazioni.

### Copertura e limiti

Le nuove fixture verificano callback PKCE, pending link cifrati, recupero password, archivio Coppia, donut Dashboard, navigazione, IT/EN, font 200%/320 dp, rete e documenti. I risultati SAF sono forniti da un registry di test; il provider MediaStore, metadati e byte sono reali. Sei casi documenti richiedono API 29+, quindi su API 26–28 sarebbero esclusi. Non è una prova del selettore di sistema, di provider cloud o del ripristino dell’anteprima dopo terminazione del processo.

Tre screenshot dei componenti Nav, l’icona nel launcher dell’AVD e la schermata anonima dell’APK firmato finale sono stati ispezionati. L’aggiornamento 0.1.7/code 8 → 0.1.8/code 9 conserva la preferenza inglese; il finale mostra Show/Hide con un tap reale. I callback cold/warm con dati sintetici vengono instradati e rifiutati con un messaggio sicuro. I tre cold start sul primo candidato osservano 2657/2547/1897 ms al primo frame; il finale cambia soltanto due etichette nel JSON inglese e conserva il DEX identico. Non sono verificati una sessione autenticata durante l’upgrade o tutte le pagine con lo stesso dataset web. I report finali sono raccolti in `artifacts/verified-0.1.8/`; `build-results.json` include conteggi, hash e limiti. Telefono e launcher Doogee, TalkBack completo, email/PKCE reali, password QA, MFA positivo, Coppia con due account, Admin autorizzato ed eliminazione account restano non verificati. La revisione web distribuita è ora legata al deployment e al bundle pubblico confrontato: questo non dimostra equivalenza semantica globale. I dettagli sono in ROADMAP_CLOSURE.md; `globalParityVerified=false` resta invariato.

## Evidenza storica — 11 ottobre 2026


La verifica locale finale 0.1.7-alpha passa **366 test JVM e 87 Android**, zero fallimenti/errori/skip. Lint debug e release: zero errori, 28 avvertenze e 5 suggerimenti ciascuno. Le avvertenze riguardano versioni delle dipendenze/AGP (16), convenzioni KTX (11) e nome di una funzione Compose (1); i cinque suggerimenti riguardano state Int. Build integrata completata in 10m24s; suite Android: 399,550 secondi, JVM: 14,304 secondi. Sono tempi osservati su questo computer, non benchmark. Manifest dei sorgenti invariato durante la build. Report XML/HTML, lint, log e hash in `artifacts/verified-0.1.7/`; riepilogo in `build-results.json`.

I primi 16 fallimenti JVM derivavano da fixture di paginazione non aggiornate e da un’attesa errata nello storico; i dati di test sono stati corretti. Un helper MockResponse usava inoltre un getter deprecato che impediva la compilazione dei test Android; ora usa `status`. Il primo run Android completo ha passato 84/87 casi: due attese cercavano elementi fuori dalla porzione visibile della LazyColumn, il terzo attendeva due GET nella categoria Libri vuota anziché uno. Corrette solo fixture e attese, conservando gli assert sulle scritture, l’owner e i refresh. Riesecuzione mirata: 3/3; riesecuzione integrata finale: 87/87. Report iniziale e mirato conservati nelle sottocartelle `initial-failure/` e `targeted-pass/`.

APK 0.1.7-alpha, versionCode 8, firma v2 e checksum verificati: aggiornamento dalla release firmata 0.1.5 installato con `adb install -r` sul solo AVD SavelyTest. Avvio anonimo e schermata Accedi/Email controllati anche nello screenshot. Un avviso ANR di System UI copriva il primo dump: chiuso l’avviso del sistema, l’avvio e la schermata sono stati verificati di nuovo. Non costituisce prova di aggiornamento con sessione autenticata. Emulatore spento al termine. La scansione dell’APK non trova i pattern `sb_secret_` o JWT service_role; non è un audit di sicurezza completo.

Eseguito anche il test host di `test_web_parity_audit.py`: 1/1. Nessuna CI GitHub, nuova prova backend reale o prova sul telefono in questa verifica. Savely web resta alla revisione `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, checkout senza modifiche. Le prove remote sotto appartengono alle versioni indicate e non certificano i nuovi flussi 0.1.7.

La verifica finale 0.1.5-alpha passa 232 test JVM e 44 Android, zero failure/errori/skip, lint debug/release zero errori e 24 avvertenze. Snapshot sorgenti invariato; report finali in `artifacts/verified-0.1.5-final/`. Suite Android finale: 451.913 secondi sul solo AVD Android 15. La prima build completa era passata in 12m56s con suite Android di 435.305 secondi; il pacchetto test è stato poi ricompilato per diagnosi e selettori dello smoke opt-in, prima della riesecuzione finale. L’errore iniziale JSONObject.keySet è stato corretto con keys().asSequence().toSet().

APK 0.1.5-alpha: versionCode 6, 1.800.912 byte, SHA-256 `5c19146f97ddab4eb4e3e311440bcebadf6b6050a1c07a6cede41f217ee7d63e`, stessa firma v2; aggiornamento 0.1.4→0.1.5 e login anonimo controllati sull’AVD. Scansione senza credenziali privilegiate rilevate. Il test Library conserva entrambi i prezzi null, note/quantità e metadati, con un solo PATCH e righe estranee intatte.

Lo smoke reale dal form FinanceScreen passa sul solo account QA, run `d03234ac-4666-4aa6-b7d9-ad82764505d3`, sessione `d577c20d-d96e-4c2d-a3a8-287815eb63a1`: inserimento EUR 0,01, modifica 0,02, conferma e cancellazione, riletture REST, riga assente, logout local-scope e clear locale. Bootstrap rimosso, checkpoint assente, cleanup_pending=false. Report in `artifacts/finance-form/d03234ac-4666-4aa6-b7d9-ad82764505d3/`. L’UUID è aggiunto dal solo interceptor test: non verifica navigazione MainActivity o generazione UUID del database. Il primo run `10db1215-ff7b-4386-ba03-413423b03895` è fallito con errore generico, ma tutti i controlli di pulizia sono passati; audit e correzione dei selettori precedono il run riuscito. Le fixture TLS opt-in e i 47 controlli host sono passati; queste prove sono separate dai 44 test ordinari.

La CI della 0.1.5-alpha passa entrambi i job sulla revisione `23ee5159b9b402cd26c1fdfc04f018fad1e97fd4`: [run 38167247160](https://github.com/GoryNickel9/Savely-Android/actions/runs/38167247160). Il report UI scaricato in `artifacts/ci-38167247160-ui/` conferma 44 test, zero fallimenti e zero skip, durata 6m9.43s. La CI usa fixture e non esegue gli smoke opt-in sul backend reale.

La build integrata 0.1.4-alpha passa 232 test JVM e 43 Android, zero failure/errori/skip, lint debug/release zero errori e 24 avvertenze. Snapshot sorgenti invariato durante la build; report in `artifacts/verified-0.1.4/`. Aggiunge sei JVM per variazione del prezzo ricorrente e quattro Android per lo stesso rilevatore, diniego Admin con sessione conservata e TCG con prezzi HTTP 503. Suite Android: 274.014 secondi, osservazione locale sull’AVD Android 15.

APK 0.1.4-alpha: versionCode 5, 1.800.912 byte, SHA-256 `c70de53adc8732ccd46b38a17749d5d236a6776e4b3a1d56290ea46bb8c74789`, firma v2 e stessa chiave. Aggiornamento 0.1.3→0.1.4 e login anonimo controllati sull’AVD. Scansione APK senza credenziali privilegiate rilevate. Le nuove prove usano esclusivamente fixture locali; gli smoke reali della 0.1.2 restano evidenza separata.

La build integrata 0.1.3-alpha passa 226 test JVM e 39 Android, zero failure/errori/skip, lint debug/release zero errori e 24 avvertenze. I sorgenti app corrispondono allo snapshot prima della build. Aggiunge 14 JVM e tre Android: origine API canonica con callback/Origin invariati e redirect rifiutati, retry MFA, quarto segnale Insights e creazione/modifica Fumo con derivati null al reset. Suite Android: 281.334 secondi, osservazione locale sull’AVD Android 15. Report conservati in `artifacts/verified-0.1.3/`.

APK 0.1.3-alpha: versionCode 4, 1.800.916 byte, SHA-256 `beb12d54d9660e18ae08ed01fac7177e2ca1f9d334a071a5edbd71ec38f5aaaf`, firma v2 e stessa chiave. Aggiornamento 0.1.2→0.1.3 e login anonimo verificati sull’AVD. Scansione entry APK senza credenziali privilegiate rilevate. Non sono stati ripetuti gli smoke finanziari reali del blocco 0.1.2. La prova HTTP anonima del routing ha osservato 308 dall’apex e 400/403 dalle API canoniche con body vuoto; non verifica email o ruoli Admin reali.

Su Windows, JBR Android Studio 21, Gradle 8.13, SDK 36: APK debug e release ottimizzata 0.1.2-alpha compilati e testati sull'AVD SavelyTest (Android 15/API 35, x86_64, Pixel 2, WHPX, GPU software). I 212 test JVM e 36 Android integrati passano senza failure, errori o skip. Lint debug/release: zero errori e 23 avvertenze note. Il test Python del mapping sorgenti passa; il report sul checkout web indica zero file cambiati e lascia la parità non verificata. I report sono conservati in `artifacts/verified-0.1.2/`; quelli della 0.1.1 restano separati.

La 0.1.2-alpha aggiunge 19 test JVM e quattro Android: invalidazione globale della sessione da chiamate esterne al ViewModel, protezione da risposte tardive e nuovi login, segnale saving_month con severità/filtri e Guadagno orario Poker con validazione, singolo submit e arrotondamento binario coerente con il web. La prima verifica completa passa in 10m31s; dopo la correzione dell'avvertenza Kotlin sul copy della data class, la verifica finale passa in 8m36s. XML finale: 212 JVM e 36 Android, zero failure/errori/skip; tempo della suite Android 212,027 secondi. Questi tempi descrivono esecuzioni locali, non benchmark del telefono.

APK release 0.1.2-alpha: 1.800.912 byte, versionCode 3, SHA-256 `080ed2c9da69779d5e64f2e19186bee537a4e4ade4c93c3edceead7cbbbdd384`, firma v2 con lo stesso certificato delle alpha precedenti. Scansione delle entry APK senza credenziali privilegiate rilevate. Aggiornamento sopra la 0.1.1/versionCode 2 riuscito sul solo AVD, senza account; schermata login controllata. Cold start singolo `am start -W`: 1938 ms, riferito al primo frame. Non verifica sessione conservata nell'aggiornamento o prestazioni del telefono.

Ripetuti sulla build debug 0.1.2 anche gli smoke reali: run Android→web `c9e470f8-c3fe-4d39-a436-0217155fe3f0`, preparazione instrumentation 12,620 s e cleanup 3,378 s; riga EUR 0,01 letta nell'app e nel browser, screenshot controllato, rimozione/logout/clear confermati e nessuna pulizia pendente. Run process death `768413e0-8311-4ef8-ba7b-f9279a36a7b2`, 2,089/9,670 s, PID 25289→25343, dashboard e cleanup confermati. Il primo avvio dell'helper Android-web aveva trovato il package rimosso da Gradle: nessuna creazione finanziaria, sessione temporanea revocata. Il helper ora verifica package debug/test e debuggability prima di preparare credenziali; il rifiuto per test APK assente è stato eseguito e conserva il report riuscito.

La prima esecuzione dei cinque test Android ha passato quattro casi e fallito il caso del ciphertext corrotto: il test scriveva in device-protected storage mentre lo store ricavava l'application context e leggeva un'altra area. Il test usa ora un ContextWrapper con namespace isolato. La seconda esecuzione ha passato tutti i nove casi, inclusi quattro flussi HTTPS con Keystore e JSON Android, senza skip.

Il primo lint completo ha rilevato un errore nel percorso SDK del file locale: il separatore `C:` non era escaped nel formato properties. È stato corretto in `C\:`. La verifica successiva passa per debug e release: zero errori e 23 avvertenze (versioni dipendenze e stile KTX). Sono state aggiunte regole esplicite per escludere backup e trasferimento dati su Android 12+, seguendo la [documentazione Android](https://developer.android.com/identity/data/autobackup), oltre al flag allowBackup disabilitato.

Il cold start misurato una volta con `adb shell am start -W` ha restituito 4696 ms, prima della stabilizzazione dell'emulatore e con Gradle attivo. È un'osservazione del dispositivo virtuale, non un benchmark del telefono.

APK release 0.1.1-alpha: 1.800.912 byte, versionCode 2, firma APK v2 verificata da apksigner, SHA-256 `2a53238a2a46cb96b0af0f5163675e7e87d2c27e4bfa58b3fff1f69135eb4e04`. L'installazione sopra la 0.1.0-alpha con versionCode 1 e la stessa firma passa; avvio senza account e screenshot controllati, cold start singolo 1636 ms. Non verifica conservazione di una sessione reale né prestazioni del telefono. Sulla precedente 0.1.0, screenshot portrait, landscape e a 360×640 dp con font scale 1,5 mostrano campi e pulsanti raggiungibili scorrendo.

La prima sostituzione fra alpha interne 0.1.0 usava versionCode 1 per entrambe. La prova successiva 0.1.0→0.1.1 verifica anche l'aumento reale a versionCode 2. Non sono release stabili pubblicate.

I nuovi test FIRE e ricorrenze hanno richiesto correzioni del solo harness: attesa di fine scroll e coordinate stabili prima del touch, attesa del controllo abilitato dopo refresh e composizione dell'header prima dei messaggi. I nove casi mirati passano con touch normale; la successiva suite completa passa tutti i 26 test. La produzione ha ricevuto una regressione separata per l'etichetta della fase di validazione di una ricorrenza successiva a un record già salvato.

La 0.1.1 aggiunge 22 test JVM e sei test Android: mediana globale Budget e mezzo centesimo, soglia perdita esatta, parser/persistenza/ripresa Manga e filtri Libreria/TCG. Il primo run collezioni si bloccava durante lo scroll perché il dispatcher manuale non eseguiva la coroutine accodata: corretto usando la regola Compose standard. Un matcher del totale Libreria selezionava anche i singoli record: ora identifica il riepilogo completo. La suite finale passa tutti i 32 casi con touch normali e timeout dei nuovi test, senza cambiamenti di produzione per queste due correzioni del harness.

## Comandi

La CI della 0.1.4-alpha, commit `fd0ceeac16407366a0fc71d160965b0711dd2048`, passa entrambi i job: [esecuzione 38163488230](https://github.com/GoryNickel9/Savely-Android/actions/runs/38163488230). Il report Android scaricato conferma 43 test, zero failure, zero skip e 100% di successo in 4m35.31s; conservato in `artifacts/ci-38163488230-ui/`. Questa revisione precede la correzione dei prezzi facoltativi Library e lo smoke reale dal form transazioni.

La CI della 0.1.3-alpha, commit `2bc75672eafd22cbbc75b12f72b43bf2b03bfe80`, passa entrambi i job: [esecuzione 38162299922](https://github.com/GoryNickel9/Savely-Android/actions/runs/38162299922). Il report Android scaricato conferma 39 test, zero failure, zero skip e 100% di successo; conservato in `artifacts/ci-38162299922-ui/`. Questa revisione precede il quinto rilevatore Insights e la correzione della cache Admin sul diniego.

La CI della 0.1.2-alpha, commit `8e6dd4ec9c013f9df7880491b3447ad43edd1f7f`, passa entrambi i job: [esecuzione 38160631288](https://github.com/GoryNickel9/Savely-Android/actions/runs/38160631288). Il report Android scaricato conferma 36 test, zero failure, zero skip e 100% di successo; report conservato in `artifacts/ci-38160631288-ui/`. Questa revisione precede il quarto rilevatore Insights e la correzione dell'origine delle API web.

Anche la CI della 0.1.1-alpha, commit `3b086777d2104514e0cd7a797ffbe0c1d8dea723`, passa entrambi i job: [esecuzione 38157386878](https://github.com/GoryNickel9/Savely-Android/actions/runs/38157386878). Il report Android scaricato conferma 32 test, zero failure, zero skip e 100% di successo. Questo risultato precede il blocco 0.1.2 con invalidazione foreground e nuovo segnale Insights.

La CI GitHub del commit `020ac8161fcaeba3b2e1c75cdc6ae79edb90a7c8` passa sia build/lint/JVM sia instrumentation Android API 35: [esecuzione 38154018127](https://github.com/GoryNickel9/Savely-Android/actions/runs/38154018127). Questa revisione copre l'alpha 0.1.0, prima dei nuovi blocchi Budget/Manga. La prima esecuzione aveva fallito il setup SDK richiedendo il pacchetto rimosso `tools`; il workflow ora specifica platform-tools, platform Android 36 e build-tools 36.0.0.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
python -m unittest discover -s tools -p 'test_web_parity_audit.py'
python tools/web_parity_audit.py --web-repo ../Savely --output artifacts/web-parity-audit.json
```

I report sono in `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/` e `app/build/reports/lint-results-debug.html`. I 44 test device ordinari usano preferenze isolate e un server HTTPS locale con certificato temporaneo; non creano utenti o transazioni in produzione. Le tre classi smoke reali sono escluse da questa suite e richiedono un avvio esplicito.

## Smoke aggiuntivo sul backend reale

`RealBackendSmokeTest` è escluso dalla suite ordinaria e richiede un avvio esplicito, build debug e sessione privata dell'account E2E documentato nella repo web. Il tentativo di creare un nuovo account sintetico è stato respinto dal trigger delle registrazioni disabilitate (HTTP 500/P0001); non sono stati cambiati trigger, impostazioni o password. È stato riutilizzato l'account confermato `e2e-savely@example.com`, senza inviare email, con una sessione temporanea ottenuta tramite l'API amministrativa. La chiave amministrativa è rimasta nel processo host e non è entrata nell'app.

Il test eseguito sull'AVD Android 15 è passato in 17,092 secondi: identità server e permessi, sessione Keystore e nuovo client, dashboard nativa, creazione di una transazione con marker UUID da 0,01 EUR, visualizzazione in Movimenti dopo ricreazione dell'activity, modifica a 0,02 EUR, cancellazione normale e assenza dopo ricaricamento. Il risultato locale `artifacts/backend-smoke-result.json` riporta `stage=passed`, `hard_deleted=true`, `cleanup_pending=false`; input privato consumato e logout concluso. Nessun dato precedente è stato modificato.

Una seconda esecuzione, tramite l'helper, è passata in 13,359 secondi con una nuova sessione. Il marker e l'ID nel risultato vengono aggiornati a ogni esecuzione; il risultato corrente conferma ancora eliminazione normale e nessuna pulizia pendente.

Questo test verifica la build debug e la sessione temporanea: non copre login con password, CRUD dalla UI release ottimizzata o lettura dal browser web. Non rilanciare il test con la sessione revocata; serve una nuova sessione del medesimo account.

`tools/run-backend-smoke.ps1` prepara una nuova sessione per il solo ID/email E2E già esistente tramite CLI Supabase autorizzata, la trasferisce via stdin nello storage privato del debug APK, lancia il test e verifica la pulizia. È limitato all'AVD SavelyTest, non modifica password o configurazioni e non fa parte della CI. Richiede APK debug/test già compilati, configurazione pubblica e CLI autenticata. Non tenta disinstallazioni per risolvere firme differenti; il test rifiuta di sovrascrivere una sessione locale esistente.

### Arresto completo e ripristino della sessione

`tools/run-backend-smoke.ps1 -ProcessDeath` esegue due metodi opt-in di `RealProcessDeathSmokeTest` in instrumentation separate. La prima verifica l'identità reale e salva la sessione cifrata; l'host arresta il package e controlla che non esista più un processo. La seconda rilegge il Keystore senza ricevere nuovi token, verifica identità e dashboard nativa, poi esegue logout e cancellazione locale. La suite ordinaria esclude anche questa classe.

La prima prova dell'11 ottobre è passata in 1,723 e 8,702 secondi; la ripetizione dopo il fix di pulizia passa in 1,317 e 9,225 secondi. Il risultato corrente `artifacts/backend-process-death-result.json` registra PID 16350 e 16417, avvio del secondo processo successivo al checkpoint, `new_process_verified=true`, `native_dashboard_verified=true`, `cleanup_local=true`, `logout_server=true`, `cleanup_pending=false`. Non sono stati creati dati finanziari né cambiate password o impostazioni. Questa prova copre il riavvio del processo nella build debug; telefono, login con password e aggiornamento release restano verifiche distinte.

È stato verificato anche il ramo di interruzione host dopo la prima fase: persistenza in 1,543 secondi e pulizia opt-in in 1,571 secondi. Il cleanup richiede lo stesso run UUID, checkpoint, account E2E e session UUID; conserva qualsiasi sessione diversa. `artifacts/backend-process-death-cleanup-result.json` registra `stage=cleanup_passed`, `cleanup_local=true`, `logout_server=true`, `cleanup_pending=false`. Lo script temporaneo di fault injection è stato rimosso. Il trasferimento privato viene ripulito anche se la scrittura stdin fallisce; dispositivo offline o pulizia non confermata producono un avviso esplicito, senza cancellare preferenze alla cieca.

Su questo host Windows Java NIO non riesce a creare il socket locale Gradle. Il workaround verificato è una variabile limitata al processo PowerShell, che lascia ricadere Gradle sulla connessione TCP:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/savely-nonexistent-socket-dir'
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Non creare quella directory e non modificare opzioni Java globali. Il percorso non è necessario su CI Linux.

## Lettura sul sito distribuito

Lo [smoke web](TESTING_WEB_READ.md) passa su `https://www.savely.cc/transactions`, origine canonica osservata tramite redirect HTTP 308. Edge usa un processo e contesto temporanei separati. Un movimento host da EUR 0,01 compare una sola volta con marker UUID e importo corretto; riga rimossa e sola sessione QA revocata, senza pulizia pendente. Non sono consentite scritture dal browser.

È passato anche il [flusso unico Android→sito](TESTING_ANDROID_WEB.md), run `99480d6e-a832-4e35-a72b-5f6057e9599f`: creazione tramite repository nel processo Android, lettura nella vera MainActivity e lettura della stessa riga nel browser. Preparazione instrumentation 12,456 secondi, cleanup 2,821 secondi; questi tempi non misurano l'intero flusso. Il report finale conferma eliminazione, logout della sola sessione temporanea, clear locale e `cleanup_pending=false`. Screenshot della riga controllato. Submit della form Android, login con password, telefono e SHA del deploy restano non verificati.

## Prova manuale sul telefono

L’utente ha installato la 0.1.7-alpha sullo smartphone già indicato (Doogee S100, Android 12) e riferisce che sembra funzionare correttamente. Installazione e uso generale sono confermati manualmente. Non sono stati descritti i singoli flussi provati, il metodo di accesso o la conservazione della sessione durante questo aggiornamento: questi controlli restano separati dalla conferma generale.

L’11 ottobre 2026 l’utente ha riferito, dopo la consegna della 0.1.5-alpha, che la sessione rimane attiva su un Doogee S100 con Android 12. È una conferma manuale della persistenza nell’uso osservato; procedura dettagliata, accesso con password e conservazione della sessione durante un aggiornamento APK non sono ancora confermati. Non estende gli smoke QA al telefono né certifica la parità completa.

## Gate prima di dichiarare stabile M1

1. Account sintetico: login con password ancora da verificare. CRUD repository nativo, flusso unico Android→sito, sessione temporanea, riavvio completo e logout reale sono verificati; il rinnovo con token scaduto è coperto da fixture. Il form FinanceScreen ha una prova QA separata riuscita sulla 0.1.5 con UUID aggiunto dal trasporto di test; navigazione MainActivity e UUID generato dal database restano da verificare.
2. Emulatore: test senza skip, errori e rete assente, tastiera, orientamento, font grandi e navigazione; lint senza errori bloccanti.
3. Telefono: installazione e uso generale della 0.1.7 confermati dall’utente sul Doogee S100, Android 12; persistenza nell’uso della 0.1.5 già riferita. Documentare i flussi della 0.1.7, la procedura di aggiornamento e la conservazione della sessione senza nuovo accesso.

MFA, Coppia con due account, Admin e servizi esterni richiedono verifiche dedicate prima della parità completa. Il test locale del contratto non prova che schema, RLS e funzioni distribuite siano uguali al mock.

La candidata finale `savely-0.1.9-alpha-release-candidate.apk` è installata sopra la 0.1.8/code 9 sul solo SavelyTest/API 35, senza disinstallazione. VersionCode 10, stessa firma, cold start anonimo e preferenza inglese conservati; screenshot finale ispezionato. Un avviso «System UI isn’t responding» copriva il primo dump: selezionato Wait sull’avviso osservato, il nuovo dump mostra l’app. Non è una prova di upgrade con sessione autenticata. Rapporto, XML, screenshot e manifest immutabile sono raccolti in `artifacts/verified-0.1.9/`.
