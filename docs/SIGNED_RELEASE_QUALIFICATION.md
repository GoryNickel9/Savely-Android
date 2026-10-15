# Qualifica locale dell’APK firmato 0.1.10-alpha

12 ottobre 2026. Il campione sul release firmato e le suite complete debug passano su Android 12 e 15. Qualifica locale circoscritta ai percorsi riportati: nessuna CI GitHub, nuova release pubblica o dichiarazione di parità globale.

## Sorgente, APK e firma

Build integrata locale sul checkout pulito `459bbac1a380f05b598f0712ee8e66bda3d4eae1`, conclusa in 3 minuti e 32 secondi. La correzione di produzione dell’export è in `3e1365cd7253ebf3e8aa3dc62f4a2e5af77ac219`; i due commit successivi correggono soltanto fixture JVM. Gli hash di 237 file tracciati e i report XML sono conservati nel bundle locale.

| Proprietà | Valore osservato |
|---|---|
| APK immutabile | `artifacts/release-qualification/qualified-apks/savely-0.1.10-alpha-release.apk` |
| Package / versione / code | `cc.savely.android` / `0.1.10-alpha` / `11` |
| Dimensione | 2.747.518 byte |
| SHA-256 | `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa` |
| Certificato SHA-256 | `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1` |
| Firma | v2 valida, unico signer, RSA 3072; stesso certificato degli APK pubblicati |
| SDK | min 26, target 36 |
| Ottimizzazione | minify e resource shrinking abilitati; marker R8 full 8.13.19 e mapping corrispondenti |
| Manifest | `debuggable=false`, `testOnly=false` |

L’ispezione indipendente confronta tutti i 19 asset con la fonte, inclusi i sette file di licenza: identità byte per byte. [DEPENDENCY_LICENSES.md](DEPENDENCY_LICENSES.md) documenta 80 coordinate runtime, 110 POM verificati e le fonti ufficiali dei metadati mancanti. NOTICE/Public Suffix e MPL completi coincidono con la fonte upstream dopo sola normalizzazione CRLF→LF; questa verifica non è un parere legale.

Il primo candidato dello stesso code 11 è conservato separatamente: SHA-256 `ac914bc580091ae435afa7f15a3a6ff4e50be7715e0574b0ba472a4b165ea5bd`. Ha dimostrato il difetto descritto sotto e non è l’APK qualificato da questo rapporto. L’associazione sorgente/build deriva dal checkout, log e hash; non è una prova di rebuild identico bit per bit.

## Suite e ambiente

| Verifica | Risultato |
|---|---|
| JVM debug | 536/536, zero failure/error/skip, 58 suite |
| JVM release | 536/536, zero failure/error/skip, 58 suite |
| Host Python | 36/36, 0,215 secondi |
| Guardie backup PowerShell | 9/9; nessuna scrittura, modifica ACL o lettura credenziali |
| Lint debug e release | zero errori; ciascuna variante 32 Warning e 6 Hint |
| Android 15 / API 35, debug | 170/170, zero failure/skip; singolo run finale 562,616 secondi |
| Android 12 / API 31, debug | 170/170, zero failure/skip; singolo run finale 551,778 secondi |

JVM debug/release eseguono lo stesso corpus due volte: sono 536 casi distinti. Le suite Android complete usano lo stesso APK debug del bundle e il test APK corretto in `74d7d0d`, SHA-256 `8be8c7eb741c8dde3bddec23ea3dfe312c8f7f8fd50983d793be600c35f95a15`. Escludono le otto classi reali opt-in e comprendono 146 casi ordinari più 24 visuali. Non sono 170 casi sull’APK release R8. Le fixture controllano richieste TLS, UI, errori e isolamento; il percorso firmato seguente usa invece il backend QA reale. Il commit `74d7d0d` cambia soltanto una fixture Android: produzione, JVM e APK release/debug rimangono quelli di `459bbac`. Le modifiche successive dell’harness sono isolate nel modulo separato.

AVD dedicati x86_64: `SavelyReleaseQA31` e `SavelyReleaseQA35`, uno attivo alla volta. Suite Compose a 1080×2600, density 480, font scale 1 e animazioni disabilitate; casi visuali applicano le proprie varianti. Acquisizioni native: 81 PNG più 81 metadati per API, recuperati individualmente con hash, controllo header/footer PNG e parsing JSON. Sono il campione visuale già definito, senza un nuovo confronto completo dei 50 casi master o del sito distribuito. Entrambi gli emulatori sono stati spenti dopo raccolta e ripristino di display/density/font; nessun `qemu-system-x86_64-headless` della qualifica resta attivo.

## Aggiornamento autenticato sul release ottimizzato

Il modulo separato `release-qualification` usa `com.android.test` self-instrumenting, package `cc.savely.android.releaseqa`, e interagisce tramite accessibilità. Ha runtime Kotlin proprio e PID distinto dall’app. Non accede allo storage privato dell’app né compila contro le sue classi; nessun codice di test entra nell’APK distribuito.

Input privato monouso dell’harness, password dal vault DPAPI e nessun segreto in argomenti/log/report. L’unico account usato è QA A dei tre già autorizzati; i report registrano UUID e contatori, senza token. Il login parte dal form nativo della 0.1.9-alpha/code 10; segue installazione `-r` della candidata code 11, force-stop e avvio di un nuovo processo, senza cancellare dati.

| API | Login dal form / upgrade / lettura profilo / preferenze / logout | Continuità server |
|---|---|---|
| 35 | PASS; run `fdbfe17c-1bf5-4fe0-9c5f-d95c3ded0a4f` | Stessa singola sessione prima, dopo upgrade e dopo process death; zero dopo logout |
| 31 | PASS; run `b6e80895-2fb4-4a31-8e9f-fcb28fa74064`, PID app 9308→9406, instrumentation 9492 | Stessa singola sessione `326e2a9e-def4-4af2-a270-1943ee72c06a` prima/dopo upgrade; zero dopo logout |

API 35 usa harness sorgente `3e1365c`, APK SHA-256 `a0f3c2a41674ff3af1970d4bbefac5205310f63586981be424da02cc5d0e59c9`. Il run finale API 31 usa harness `f59fe64`, APK SHA-256 `4009c7f62b869842c368dc09fd3f346800db2c55b3c7d4c8122970690850603b`: correzioni di navigazione, scroll e diagnostica del driver, senza modifiche all’app. Sono due versioni del solo strumento di qualifica, con lo stesso APK release.

Le asserzioni osservano contenuto Dashboard reale, assenza del form Accedi, identità QA nelle Impostazioni, Save profile abilitato dopo lettura e controlli English/Light selezionati. Il PID dell’app cambia ed è distinto dall’instrumentation. Il backend conferma identità 3, fattori 0, transazioni QA 0 e registrazioni disabilitate prima/dopo. La prova riguarda sessione e preferenze osservate; non tutti i dati locali o moduli, signup, MFA o email/PKCE.

## Picker ed export: difetto osservato e correzione

Sul primo candidato API 31, un export normale produce 11 categorie QA in un CSV di 1.696 byte, dopo lettura delle 25 tabelle. Con `CreateDocument` in primo piano, `am kill cc.savely.android` termina realmente il processo. Il ritorno tramite SAVE avvia un nuovo PID e lascia un documento di zero byte senza messaggio. Questa prova fallita e il file vuoto restano conservati.

`TransferScreen` ora conserva soltanto il nome UUID della richiesta pendente, consumato una volta anche dopo annulla. Il CSV resta nello snapshot in memoria. Se il processo ha perso lo snapshot, la callback mostra l’errore e richiede una nuova preparazione; non ricostruisce l’export né ripete le letture. La pulizia riguarda soltanto un documento vuoto con nome esatto della richiesta e supporto alla cancellazione verificato. Documenti preesistenti, rinominati, non vuoti/parziali o di dimensione sconosciuta restano conservati con avviso esplicito. Se il cambio login avviene dopo una scrittura completa, il file già scritto non viene rimosso né dichiarato riuscito per il vecchio login.

Cinque nuove regressioni JVM e tre Android verificano snapshot perso, callback senza richiesta, replay, cleanup fallita, scrittura completata prima di nuovo login e isolamento dei file preesistenti. Le fixture Android ricreano il ViewModel e il registry con stato salvato e MediaStore controllato; sono distinte dal process death di sistema eseguito qui.

| Release finale / provider osservato | Risultato |
|---|---|
| API 35, DocumentsUI `com.google.android.documentsui`, Downloads locale | Annulla conserva snapshot; salvataggio normale CSV 11/11 categorie QA; process death reale PID 7303→assente→8188, messaggio di ripreparazione, nuovo file vuoto rimosso, precedente CSV 1.696 byte conservato |
| API 31, DocumentsUI / Downloads locale | Annulla conserva snapshot; salvataggio normale CSV 11/11 categorie QA; process death reale PID 5032→assente→5643, messaggio di ripreparazione, nuovo file vuoto rimosso, precedente CSV 1.696 byte conservato |

Export, BANK e FX API 31 appartengono al precedente run `abd8d2dd-d137-484a-9d6e-b42690d757e5`, con sessione `43078ad4-214e-4200-b26b-97e1c94ceea3` conservata durante upgrade/export. Quel tentativo non supera il logout del driver; logout UI manuale e lettura server zero sono registrati separatamente. Il nuovo run completo di upgrade della tabella sopra ha una sessione diversa. Il summary distingue i due percorsi e non attribuisce il PID di upgrade al ritorno del picker.

I report conservano XML, PNG, nomi UUID, PID e CSV/hash. Il provider è identificato dalla UI/package/root osservati; non è stata acquisita l’autorità URI precisa. Il backend reale contiene soltanto 11 categorie: questa prova non dimostra pagine reali multiple o 25 tabelle popolate. Provider cloud e scrittura interrotta sul provider reale restano aperti.

Sul release finale API 31 è stata ripetuta anche la prova `OpenDocument` pendente con BANK/USD e process death reale: al ritorno mostra 12,50 USD, Expense/Ready, una riga e zero invalide, escluse o duplicate. Un secondo picker NORMALIZED mostra 12,50 EUR. Nessun commit import. Le nuove acquisizioni `android31-final-bank-*` e `android31-final-normalized-*` appartengono all’APK qualificato; quelle del primo candidato restano separate. Il ledger import rimane in memoria, senza checkpoint persistente.

## Servizi, profilo e recupero della firma

Il vecchio endpoint FX `.app/latest?from=USD&to=EUR` risponde 301. Il client rifiuta redirect: ora usa `https://api.frankfurter.dev/v1/latest?base=USD&symbols=EUR`, mantenendo precisione, assenza di credenziali Supabase e fallback manuale. La [documentazione ufficiale v1](https://frankfurter.dev/v1/) dichiara v1 deprecata ma disponibile. La regressione verifica una richiesta all’origine e zero alla destinazione del redirect.

Probe host sul nuovo endpoint: urllib HTTP 403; curl HTTP 200, 71 byte, USD→EUR 0,89437, data 2026-10-12. La differenza fra trasporti non ha una causa dimostrata. Sul release finale API 31, il form nativo salva una spesa QA di 0,01 USD con cambio automatico: il database registra `exchange_rate_eur=0.894370` e la riapertura del form mostra lo stesso valore a sei decimali. La data della quotazione non è conservata o mostrata dal form: la data osservata nel probe host non certifica quella della richiesta Android.

La transazione temporanea è stata eliminata dalla UI nativa. `transactions` usa DELETE fisico: due successive letture read-only confermano zero righe QA, comprese quelle eliminate logicamente. Un helper di pulizia previsto per righe soft-deleted ha rifiutato la riga già assente prima di riservare o inviare qualsiasi DELETE amministrativo. Nessuna modifica SQL è stata eseguita da quell’helper. Il trasporto read-only Management restituisce HTTP 201; il controllo locale è stato corretto dopo un probe read-only per accettare 200/201. Google Books risponde 429 `RESOURCE_EXHAUSTED` alla ricerca Dune con query applicativa: il percorso positivo rimane aperto.

Tre nuovi casi `SettingsProfileRecreationTest` qualificano localmente GET nullo/503 dopo ricreazione con bozza conservata e PATCH trattenuto: controlli disabilitati, doppio tap produce una sola richiesta, payload/proprietario esatti e sessione preservata. Non aggiornano credenziali QA reali e non chiudono le prove email o eliminazione account.

Il recupero locale del backup AES-256-GCM confronta i byte del keystore originale, rifiuta il backup alterato e firma un APK di test con lo stesso certificato/v2. File e directory privati temporanei rimossi; nessun accesso a backend/device in questa prova. Le nove guardie includono root checkout, discendenti, normalizzazione, maiuscole e junction: nessuna chiave viene scritta dentro la repo. `offDeviceBackupVerified=false`; copia esterna e recupero su altro computer restano aperti.

## Fallimenti conservati e limiti della qualifica

Il primo harness caricato nel processo release fallisce prima del login per `NoClassDefFoundError: kotlin.jvm.internal.Intrinsics` dopo R8: risolto con il modulo self-instrumenting, senza keep rule nell’app. I tentativi successivi espongono nodi accessibilità obsoleti, selezione controllata sulla sola etichetta e routing dalla scheda Dati persistita: corretto soltanto l’harness, mantenendo le asserzioni su contenuto, identità e selezione.

Il primo nuovo test export modificava direttamente lo store senza rendere osservabile la revisione al trasporto: corretto il setup con `api.session()`. Un test preesistente di failure tardiva MFA poteva completare la RPC immediatamente prima di accodare il callback Main: un latch limitato a 10 secondi rende deterministico il setup, senza aumentare timeout o indebolire assert. Gli XML dei fallimenti e i log build rimangono separati dai report finali. Un errore del dump host cp1252 su testo DocumentsUI Unicode è risolto con `PYTHONUTF8=1`, senza modificare l’app.

Il primo run completo API 35 termina 169 PASS e una failure in 606,51 secondi: la fixture reinvia un risultato dopo consumo della chiave e AndroidX lo conserva senza consegnare la callback alla UI. Il bytecode locale conferma il filtro `launchedKeys` per entrambi gli overload di `dispatchResult`. La fixture ora conserva il Bundle tramite API pubblica durante `onLaunch`, ripristina quel precedente stato pendente e poi reinvia il risultato dopo consumo del marker UI. Mantiene messaggio completo, due confronti byte dei file, zero richieste aggiuntive e un solo intent. Il mirato finale passa 1/1 in 8,859 secondi; build del solo test APK e lint debug passano. Il successivo run completo rimane distinto da questo mirato.

Quattro tentativi firmati API 31 passano tutte le asserzioni di upgrade/sessione/profilo/preferenze, ma falliscono nella pulizia del driver: non sono PASS completi. Il codice diagnostico espone `DASHBOARD_NAVIGATION_MISSING` nel rientro nelle Impostazioni. L’app consente ogni volta il logout manuale, confermato da zero sessioni server. Il driver ora tratta i bounds vuoti come direzione sconosciuta, usa i limiti della finestra target per la posizione della navigazione e registra soltanto codici/booleani sicuri. La causa del falso gate geometrico non è isolata. La correzione finale torna in cima alla pagina Account prima di richiedere Profilo e identità QA: non tenta un rientro quando il titolo è soltanto fuori schermo. Il successivo run completo firmato API 31 passa anche logout e pulizia server, mantenendo tutte le asserzioni.

V01 e R01 sono qualificate localmente: build ottimizzata firmata, integrità/firma/versione, lint, suite dello stesso codice applicativo e comportamento del campione R8 osservato. Le 31 altre righe della matrice restano aperte, incluse quelle con nuove prove parziali. R02 rimane parziale per dati/flussi non osservati e recupero della firma fuori dal computer. Non sono 170 test UI sul release ottimizzato e non è la certificazione della 1.0 stabile.

Nessuna modifica a web, schema, RLS, trigger o configurazioni globali del backend; nessuna email inviata, CI GitHub o pubblicazione. [STABLE_VERIFICATION_MATRIX.md](STABLE_VERIFICATION_MATRIX.md) conserva gli altri percorsi autonomi ancora da qualificare e le dipendenze esterne: mailbox, telefono fisico, provider cloud, disponibilità Books e copia della firma fuori dal computer. `globalParityVerified=false`, `verifiedApkVersion=null` e `datasetCasesExecuted=false` restano invariati.

## Evidenze locali

Cartella ignorata `artifacts/release-qualification/`: [indice e checksum delle evidenze](../artifacts/release-qualification/qualification-evidence-index.json), `qualified-source-build-manifest.json`, `qualified-apks/manifest.json`, `qualified-reports/`, `apk-static-qualified.json`, `export-fix-qualified-build.log`, `host-final/`, `recovered-signing-verification.json`; log/report `android31|35-*`, CSV validati e acquisizioni dei picker. Il checksum installabile è accanto all’APK in `qualified-apks/savely-0.1.10-alpha-release.apk.sha256`. `server-final-cleanup.json` conferma al termine tre identità QA, zero sessioni/fattori/transazioni e registrazioni disabilitate; `emulators-final-shutdown.json` registra ADB senza dispositivi e assenza dei processi della qualifica. Le evidenze precedenti mantengono nome e byte. La cartella contiene identificativi dei soli QA e non è un asset pubblico di release.
