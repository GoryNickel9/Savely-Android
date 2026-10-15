# Confronto locale delle schermate Android/web — 12 ottobre 2026

Acquisite schermate di produzione con dati sintetici del [dataset comune](../parity/fixtures/static-common-dataset.json). Il confronto copre letture, riepiloghi, alcuni filtri e presentazione; le prove di scrittura, autorizzazione del servizio, email e aggiornamento firmato conservano i propri gate.

Il riferimento web è `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. Il [rapporto web](COMMON_VISUAL_WEB_OBSERVATIONS.md) registra sorgenti, adapter, rete e catture. I run `native-paired` aggiungono 38 combinazioni schermata/profilo e 114 PNG, conservando separatamente le 105 immagini delle osservazioni precedenti e le 54 originali. Una combinazione o una fotografia non corrisponde a un test.

## Metodo e limiti

Android usa un solo AVD Android 12/API 31, transport TLS loopback e sessioni in memoria degli attori fittizi A/B/Admin. Non sono gli account QA del servizio. Nei 24 casi visuali le richieste sono limitate alle letture previste; i test verificano che non arrivino mutazioni o endpoint estranei. I quattro casi ordinari `SettingsSecurityTest` verificano invece revoca, modifica password, chiusura delle altre sessioni ed eliminazione su fixture TLS separate. Nessuna di queste operazioni raggiunge il servizio reale o attesta RLS e ruoli distribuiti.

I profili nativi sono IT chiaro 360×800 dp/fontScale 1 ed EN scuro 320×800 dp/fontScale 2. I metadata conservano dimensioni effettive, densità e bounds. La cattura riguarda il contenuto Compose senza shell di navigazione. Il web conserva viewport e full con la shell originale; il ritaglio content nasconde temporaneamente soltanto menu/sidebar/barra inferiore. Le unità CSS e dp e l'ingrandimento HTML al 200% non rendono le immagini equivalenti pixel per pixel.

La finanza riceve la data 15 giugno 2026 tramite un parametro con default `LocalDate.now()`: l'uso ordinario mantiene il clock del dispositivo. Timestamp di aggiornamento e operazioni restano quelli runtime. FIRE EN copre solo il grafico di produzione isolato, con anno 2026; il form intero è osservato in IT. Per gli altri moduli le date dei record sono esplicite e il dispositivo resta nel 2026, senza clock globale sostituito.

Gli adapter web convertono i decimali nel formato numerico PostgREST e forniscono metadata sintetici. Fumo legacy conserva i derivati salvati; Standard e Barista usano parametri distinti. Il primo campione Fumo web e il primo Security web avevano input differenti e restano osservazioni indipendenti: soltanto i successivi run appaiati sono usati per queste righe della comparazione.

## Valori osservati e differenze

Le colonne native si riferiscono alle asserzioni e alle immagini del contenuto. Gli importi con valute diverse non vengono sommati per costruire un atteso EUR.

| Area | Android | Web appaiato | Esito del confronto |
|---|---|---|---|
| Dashboard | Saldo 180; entrate 120; uscite 30; risparmio 90 EUR; 75%. Donut 30; widget mese intero 40 spesi/20 disponibili. | Stessi importi, widget 66,7%. | Periodo KPI 1–15 giugno distinto dal Budget dell'intero mese. |
| Transazioni | Quattro record di giugno; ricerca categoria CIBO restituisce Cena e Spesa futura, −30/−10 EUR. | Stessi quattro record, comprese date 16 e 30 giugno. | Il filtro del mese include record futuri; la ricerca nativa è provata sul client locale. |
| Budget | Totale 60; mediana 40; differenza −20 EUR. | 60/40/−20. | Riepilogo coerente con i mesi del dataset. |
| Grafici | Range 1–15 giugno: 120/30/90 EUR; cumulativo giornaliero 120 al 1° giugno e 90 al 15. | `/charts/income-expense`, range scelto nella UI: 120/30/+90 e curva cumulativa 120→90. | Il cumulativo usa la stessa serie giornaliera. `time-graphic` nativo conserva le barre mensili delle entrate/uscite e non è la controparte della curva web. Tutte le modalità/filtri non sono qualificati da questo campione. |
| Portafoglio | EUR 300/costo 250/profitto 50; USD 25/20/5; realizzato −10 EUR. | Totali grezzi 325/270/55, realizzato −10; rendimento 20,45%. | Separazione valutaria intenzionale. L'interpolazione nativa usa giorni locali interi, il web frazioni di tempo. |
| Patrimonio | EUR 315 e USD 5. Storico server −39, variazione +11/+22%, senza valuta attribuita. | Totale grezzo 320; storico −39 mostrato con simbolo EUR. | I valori server non hanno valuta nel contratto. |
| Insights selezionati | Risparmio 140, media 45, +211%; nuovo massimo server −39. | Stessi valori. | Due detector osservati; il campione non qualifica tutti gli otto in ogni stato. |
| FIRE Standard / Barista | Target Standard 1.200.000 EUR; Barista 600.000 contro FIRE completo 900.000. | Stessi target dai rispettivi parametri. | Form e risultati IT; EN nativo limitato al grafico isolato. |
| Poker hourly | 3 ore; net 90/EV 120 EUR; orario dopo deal 16,50/EV-h 22. | Stessi importi annuali con date 1 giugno/1 maggio. | Deal applicato alla presentazione del guadagno orario. Rakeback web resta un campione aggiuntivo senza controparte in questi nuovi test. |
| Fumo legacy | 20 giorni, 1,25 ml/giorno, 0,63 EUR/giorno, 18,75 EUR/mese; date 1–11 giugno preservate. | Stessi derivati salvati nel wire/DOM. | La tabella web ha overflow orizzontale: i quattro derivati non sono tutti leggibili nello stesso ritaglio. Le date non fanno ricalcolare i dati legacy in sola lettura. |
| TCG | 9 pezzi; EUR investimento 35/valore 45/profitto 10; USD 10/12/2. Distribuzione giochi: 2/3/4 pezzi, 22,2/33,3/44,4%. | Totali grezzi 45/57/12 sotto simbolo EUR; torta quantità 2/3/4. | Separazione valutaria intenzionale; la torta conta pezzi, senza conversione di valute. |
| Libreria | 6 pezzi; investimento 23,50/valore 24/profitto 0,50 EUR; prezzi null preservati. | Stessi totali. | Panoramica IT; servizi catalogo e cover esclusi. |
| Coppia, B | Mediane 5,01 EUR e 2,01 USD; Budget EUR 4; differenza EUR 1,01. | Mediana grezza 7,02 e differenza 3,02 sotto simbolo EUR. | Separazione valutaria intenzionale; equal 5,01/5,01 è una fixture della view SQL, non nuova esecuzione SQL reale. |
| Sicurezza, A | Fattori verified/unverified distinti; sessione attuale individuata dal `session_id` canonico. | Il filtro SDK espone solo il fattore verified; badge corrente basato sul confronto user-agent, assente per le UA sintetiche native. | Stessi input, differenze di presentazione e identificazione esplicite. Nessuna revoca o enrollment nei campioni visuali. |
| Admin / Login / Settings | Tre utenti sintetici, registrazioni disabilitate, diniego per A; login senza submit e profilo A/EUR. | Stessi utenti e switch disabilitato nel campione appaiato; form Auth senza submit. | Nessuna qualifica di creazione account o modifica permessi. |

## Correzioni emerse dalle immagini

Sicurezza usava un `ListItem` con il pulsante Revoca a destra. A 320 dp e fontScale 2 il pulsante sottraeva spazio ai dettagli: il nome del browser e il timestamp si spezzavano in numerose righe strette. Ora ogni accesso ha una card con nome e data a larghezza piena e il pulsante sotto. UUID della revoca, stato enabled, conferme e logica della sessione restano gli stessi. Una regressione richiede al nome almeno il 70% della larghezza del viewport nel profilo ingrandito; i test account ordinari verificano anche le operazioni locali di sicurezza.

La barra Account/Sicurezza/Dati non scorreva: nel profilo EN ingrandito Dati rimaneva fuori dalla riga raggiungibile. La barra ora consente lo scorrimento orizzontale. La regressione raggiunge Dati con un gesto reale, seleziona la tab e verifica Import and export, senza aprire il picker o avviare richieste aggiuntive; poi torna a Sicurezza.

Lo storico disponeva i pulsanti precedente/successivo in una riga senza quote di larghezza. In EN/fontScale 2 il primo occupava quasi tutta la riga e il secondo spezzava le parole in pochi caratteri. Ora sono impilati, entrambi a larghezza piena. La regressione verifica larghezza, testo completo, visibilità e navigazione verso il punto precedente e ritorno al finale.

Nei grafici la tastiera restava attiva dopo Applica intervallo: il viewport EN aveva origine verticale −637 px e la cattura includeva parti esterne al contenuto. Dopo un intervallo valido l'app libera il focus e chiude la tastiera; gli errori di validazione conservano il comportamento precedente. Le nuove catture attendono un viewport interamente nel display, senza chiudere la tastiera dal test. Le due immagini EN range-summary/time-graphic del terzo tentativo sono conservate come acquisizioni invalide e non sostengono il confronto visivo.

Il form FIRE mostrava il rendimento iniziale del 7% come `7.000000000000001`, prodotto dalla moltiplicazione binaria prima di `toString()`. La conversione decimale riguarda inizializzazione, reset e import della percentuale; conserva i tassi nel passaggio testo/frazione, senza cambiare formule o formato salvato. Il grafico aggiunge i limiti numerici della scala e le età/anni dei due estremi come testo Compose accessibile. Le curve mantengono la stessa scala e il selettore mostra già i valori precisi per ogni anno.

La panoramica TCG aveva quantità e riepiloghi ma mancava della torta per gioco del web. Ora disegna la distribuzione dei pezzi con gli stessi tre colori, più legenda con quantità e percentuali a un decimale IT/EN. I dati arrivano dalla stessa panoramica completa; non si calcolano quote su valori multivaluta. Le categorie sconosciute restano nei riepiloghi, mentre la torta segue i tre giochi del riferimento. Il ramo vuoto mostra l'avviso dedicato; il nuovo campione visuale copre la distribuzione popolata.

La finanza rende iniettabile la data per confrontare le stesse finestre temporali. Non è stato introdotto un clock globale né modificato il calendario ordinario. I restanti cambiamenti riguardano fixture, acquisizione e strumenti di confronto.

## Esecuzioni e prove conservate

Le esecuzioni iniziali sono separate dal run finale. Il primo tentativo ha completato 11 casi: otto superati e tre falliti; root ha interrotto il dodicesimo, bloccato sullo scroll della fixture. `Process crashed` in quel log è l'effetto del force-stop manuale, non un crash dell'app osservato durante l'uso.

Il secondo tentativo ha eseguito tutti i 24 casi: 14 superati e dieci falliti in 440,821 s. Le cause erano fixture Coppia con paginazione/alias errati, atteso inglese del numero server, scorrimenti con fling che smontavano il nodo e timeout nei bridge di scroll del dispatcher di test. I controlli sugli importi, sulla visibilità e sull'assenza di scritture sono stati mantenuti. Non si sommano i PASS dei tentativi per dichiarare superata una suite.

Il terzo tentativo completa 28 casi: 25 superati e tre falliti in 169,843 s. I timeout sono in Panoramica TCG/Libreria. La prima correzione rende espliciti la tab selezionata e lo scroll al totale, ma non risolve la causa. Questo run aveva già superato tutti i sette casi finanza e gli otto account, mentre la cattura spostata dalla tastiera richiedeva comunque una correzione.

Il quarto tentativo completa 28 casi: ancora 25 superati e tre falliti, in 141,128 s, stavolta attendendo Aggiorna abilitato. La causa è il formato dei prezzi nella fixture: il master conserva stringhe decimali, copiate senza adattamento nel JSON REST. `CollectionOverview` richiede numeri, come nel servizio, e mostra Riprova dopo il rifiuto. Le copie di trasporto TCG/Libreria convertono soltanto i prezzi non null in numeri decimali; master, valori, null e controlli di produzione restano invariati. I limiti della suite non vengono mascherati rimuovendo gli assert.

Log e PNG iniziali restano in `artifacts/visual-parity/android31-initial*`, `android31-rerun*`, `android31-attempt3*` e `android31-attempt4*`. Il manifest finale registra i sorgenti e gli SHA degli APK debug. Questi APK non sono una nuova release pubblica.

Il run intermedio `android31-before-tcg-chart` passa 28/28 in 128,427 s, con 75 PNG validi e nessun bounds fuori dal display. I sorgenti e gli APK di quel run sono conservati separatamente. La revisione successiva individua la torta TCG mancante e la tab Dati irraggiungibile. Il run `android31-before-terminal-fix`, con entrambe le correzioni e le prove del cumulativo, passa 28/28 in 99,984 s e produce 81 PNG. L'ispezione rileva però che il valore finale 90 nel PNG cumulativo EN resta sotto il bordo: l'assert semantico accettava una riga parzialmente visibile. Il test ora richiede tutti i lati del rettangolo non clippato, calcolato da posizione e dimensioni, dentro il viewport e usa scorrimenti controllati dalla geometria. Non cambia la produzione. Anche log, APK, manifest e immagini di questo run sono conservati separatamente.

## Run finale qualificato nel perimetro locale

Sorgente Android `16c70112f5d2240abb61478074fcf4e19c779d21`, web `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. Il manifest registra 203 file di app, configurazione Gradle e dataset; nessuno cambia tra compilazione e acquisizione finale. Il dataset SHA-256 è `40a18e403c3020910a8762bad716ec3f76490b3db2ed90a1c25a221221048774`.

| Verifica eseguita | Risultato | Evidenza locale |
|---|---|---|
| Build debug, test APK, JVM e lint | Build riuscita in 3 min 27 s; 530 JVM, zero fallimenti/errori/skip | `complete-visual-build.log`, XML copiati in `complete-build-evidence/` |
| Ricompilazione della sola cattura finale e lint | Riuscita in 1 min 32 s; produzione invariata | `terminal-capture-build.log` |
| Lint debug | Zero errori, 32 avvertenze e 6 suggerimenti | `app/build/reports/lint-results-debug.xml` |
| Android 12/API 31, un solo run finale | 28/28, zero fallimenti/skip, 103,01 s; 24 casi visuali e quattro regressioni Sicurezza | `android31-final.log` |
| Catture native finali | 81 PNG: 45 IT 1080×2400 e 36 EN 960×2400; bounds nel display | `android31-final/files/common-dataset-visual/`, `native-final-summary.json` |
| Web appaiato | 114 PNG/38 combinazioni, sorgenti invariati e nessun servizio reale | [Rapporto web](COMMON_VISUAL_WEB_OBSERVATIONS.md) |
| Strumenti web | Entrambi superano `node --check` | `tools/visual-parity-web.mjs`, `tools/visual-parity-web-summary.mjs` |

Percorsi degli artifact relativi a `artifacts/visual-parity/`; restano locali e ignorati da Git. Il conteggio PNG descrive le acquisizioni, non altrettanti test né un controllo visivo esaustivo. I 530 JVM sono del build completo; la modifica successiva riguarda soltanto l'harness Android. I 36 test Python e le suite Android complete da 132 casi restano risultati precedenti, senza nuovo esito attribuito a questo run. Non sono stati eseguiti benchmark di prestazioni.

| APK debug osservato, versione interna 0.1.9-alpha | SHA-256 |
|---|---|
| App | `826ef9085b82fb38b789287510e67a248220f2a31129ee73a2bee3d08ccceba4` |
| Instrumentation test | `ab8855a43b9a549b6203fac47e3a0b5d3fa7faabc049b2ffff2b57b14c0ec445` |

Questi APK non sono firmati per la distribuzione pubblica e non qualificano un aggiornamento autenticato. Gli hash associano il risultato a questi file, senza assegnare una nuova versione pubblica.

Gli APK finali, XML JVM/lint, manifest e summary sono conservati anche in `artifacts/visual-parity/terminal-final-build-evidence/`. Dopo il pull finale sono stati ripristinati dimensioni, densità e font dell'AVD, poi chiuso il solo emulatore avviato per questa prova. ADB non mostra dispositivi e il processo QEMU della prova non è più attivo.

Per ripetere i test mirati su un AVD API 31, impostare il display a 1080×2600 e densità 480, compilare e installare i due APK debug. Il runner del campione è:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb -s emulator-5580 shell wm size 1080x2600
adb -s emulator-5580 shell wm density 480
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e timeout_msec 60000 -e class 'cc.savely.android.finance.CommonDatasetVisualTest,cc.savely.android.ui.CommonAccountVisualTest,cc.savely.android.modules.CommonModuleVisualTest,cc.savely.android.ui.SettingsSecurityTest' cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner
```

Il seriale va sostituito con quello dell'AVD dedicato. I test visuali caricano gli asset del dataset comune, producono i PNG nella directory privata `files/common-dataset-visual` e usano soltanto il transport locale.

## Ispezione delle immagini e limiti dei ritagli

Root e tre subagent hanno ispezionato direttamente i campioni principali: riepiloghi Dashboard/Budget, filtri e cumulativo Grafici IT/EN, Portafoglio e Patrimonio IT/EN, due Insights, FIRE Standard/Barista e scala EN, Fumo, Poker, panoramiche Libreria/TCG e torta IT/EN, Auth, Settings IT, Sicurezza IT/EN, Coppia B e Admin/diniego. Gli assert controllano gli attesi numerici dei 24 casi; il confronto visivo riguarda questi campioni.

Nel PNG finale cumulativo EN la riga 15 giugno e l'importo 90 sono interamente visibili. Le legende TCG mostrano 2/3/4 pezzi e 22,2/33,3/44,4% in IT, 22.2/33.3/44.4% in EN, senza tagli o sovrapposizioni. Il nome del dispositivo e Revoca rimangono leggibili a fontScale 2. Il titolo dello storico accessi e Account nella barra superiore possono essere fuori dal ritaglio dopo lo scroll: la prova di Dati copre invece il gesto, il click e il contenuto selezionato.

Portafoglio/Patrimonio EN sono posizionati sui valori dello storico: le curve sopra il viewport non vengono qualificate da quei PNG. Il form FIRE EN intero, il profilo Account EN completo, tutte le credenziali IT, ogni categoria Libreria e ogni stato di form non sono documentati integralmente dalle immagini. I limiti del web, compresi overflow e tabelle più larghe del viewport, restano nel rapporto del riferimento.

## Gate ancora aperti

Il confronto completa un campione di contenuti popolati, IT/EN e testo ingrandito. Il dataset master contiene anche azioni, errori, dati vuoti e servizi che qui non sono stati eseguiti. La navigazione completa, ogni form e ogni detector richiedono osservazioni ulteriori; `datasetCasesExecuted=false` resta riferito all'intero perimetro dei 50 casi.

Non vengono attribuiti nuovi risultati al telefono fisico o all'API 35. Restano email/PKCE con mailbox controllata, flussi QA esclusi, picker/provider e servizi reali, interruzione di processo/disco, APK release finale e aggiornamento autenticato. [STABLE_NON_CODE_GATES.md](STABLE_NON_CODE_GATES.md) conserva le condizioni di chiusura. `globalParityVerified=false` e `verifiedApkVersion=null` restano invariati.
