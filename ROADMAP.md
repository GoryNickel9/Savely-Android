# Roadmap Savely Android

**[V10, Poker, è PASS LOCALE](docs/POKER_QUALIFICATION.md)**: 601 JVM per variante, lint debug senza errori e **12/12 API 31 in 442,269 s**, nove PNG/JSON IT/EN revisionati. Corretti cutoff locale della mediana, overflow e gestione degli esiti incerti di Next Cut. Sei risultati del codice web fissato coincidono. Due client QA hanno entrambi creato lo stesso mese: assenza di unicità server documentata, sole due righe rimosse con CAS e baseline ripristinato. **15 criteri chiusi localmente, 18 aperti**; nessun nuovo R8 o rilascio pubblico. V09 conserva il percorso firmato; segue V11, Fumo.

## Stato corrente — FIRE V09: fixture locali qualificate

**[V09 resta aperto](docs/FIRE_QUALIFICATION.md).** Sorgente `eb2ffed`, main debug congelato `923037…`: 593 JVM per variante, 24/24 FIRE API 31 in 261,134 s. Dieci nuovi casi qualificano form EN e confini del login; le fixture precedenti verificano draft, persistenza, default DB, import/condivisione e proiezione. Cinque failure iniziali e isolato conservati; correzioni limitate alle fixture. Il percorso firmato da 21 invocazioni, force-stop/nuovo PID e annullamento del chooser reale resta da eseguire. **14 criteri chiusi localmente, 19 aperti**; nessuna nuova qualifica R8 o parità globale.

## Consuntivo Portafoglio V06 e Insights V08 — candidata locale 0.1.17-alpha

**[V08 è PASS LOCALE](docs/INSIGHTS_QUALIFICATION.md)**: 593 JVM per variante, lint debug senza errori, 11/11 API 31 in 79,068 s e 18 PNG/18 JSON IT/EN revisionati. Otto rilevatori, soglie, filtri/severità, testi e stati vuoto/errore sono verificati nelle fixture. Il testo delle perdite allinea i pareggi negativi a `Math.round`; i tentativi iniziali e diagnostici falliti restano conservati.

**Quattordici criteri chiusi localmente, 19 aperti.** Il canary V06 sul servizio v17 passa: AAPL USD aggiornato dal form, BTC USD rifiutato per valuta incompatibile e baseline QA ripristinato. Flag globali invariati, nessuna pubblicazione o nuova build R8. Segue **V09, FIRE**.

## Consuntivo Patrimonio V07

**[V07 è PASS LOCALE](docs/NET_WORTH_QUALIFICATION.md)** nel criterio delle fixture e del confronto con le fonti web. Passano 590 JVM per variante, lint debug senza errori e 10/10 API 31 in 67,506 s. Revisionati dieci PNG e dieci JSON IT/EN. Snapshot senza valuta e totale corrente per valuta restano distinti; i componenti malformati sono rifiutati. La percentuale sulla base negativa coincide con il web. Nessuna mutazione QA o nuova build release R8.

Alla chiusura di V07 erano **dodici criteri chiusi localmente e 21 aperti**. Flag globali invariati, nessuna pubblicazione. V08 è qualificato nel perimetro sopra.

## Consuntivo Portafoglio V06 — candidata locale 0.1.17-alpha

[V06](docs/PORTFOLIO_QUALIFICATION.md) passa 586 JVM per variante, 14/14 fixture API 31 e 17 verifiche native sul release firmato, con baseline QA ripristinato. Il servizio v16 poteva applicare quotazioni nella valuta errata. La correzione `ca97547`, verificata offline/Deno, è [distribuita come v17 con bundle verificato](backend-proposals/update-prices/DEPLOYMENT_REPORT.md). Il canary separato passa tre metodi nativi API 31 e il rifiuto host B: asset/storico AAPL entrambi 345,53 USD, BTC invariato salvo il log atteso. Sei record adottati eliminati con CAS; alle 14:25:22,917393 UTC del 14 ottobre 2026 sessioni/fattori zero, 33 categorie e sei snapshot conservati. **V06 è PASS LOCALE.** La CI `38567179309` passa build e 222/224 Android; due timeout iniziali restano falliti, con causa non dimostrata e diagnostica aggiunta solo ai test.

Al checkpoint CRUD erano **undici criteri chiusi localmente e 22 aperti**. Con V07, V08 e il canary V06 conclusi sono **14 chiusi e 19 aperti**. Nessuna pubblicazione; flag globali invariati. Nella nuova CI `38580288372` il job build è fallito e il job UI conclude 229/230, con una verifica raster USD da diagnosticare; questo campione locale non ne cambia gli esiti. Segue **V09, FIRE**.

## Consuntivo Grafici V05 — candidata locale 0.1.16-alpha

**[V05 è PASS LOCALE](docs/CHARTS_QUALIFICATION.md)** nel criterio delle fixture comuni e acquisizioni IT/EN. Corretti i filtri rimasti su categorie/valute rimosse, aggiunto il tasso di risparmio decimale per valuta e sistemati i pulsanti dei grafici a font 200%. Produzione `522051c`, code 17; passano **570 JVM per variante**, lint senza errori e **13/13 Grafici API 31 in 107,677 s**. I test finali riproducono i due difetti sulle rispettive build precedenti.

La [CI finale](https://github.com/GoryNickel9/Savely-Android/actions/runs/38558425255), fonte `522051c`, passa entrambi i job: **570 JVM e 219/219 Android 15/API 35**, zero failure/skip, UI 11m1,56s. La sola estensione della fixture `f84eb25` aggiunge i controlli Torta: **1/1 API 31 in 37,797 s**, con produzione invariata; non è una nuova suite completa API 35. **20 PNG e 20 JSON** revisionati, viewport effettivi 360×577 dp IT/font 100% e 320×577 dp EN/font 200%; gli 800 dp dei nomi sono la richiesta della fixture. Tentativi falliti, ANR SystemUI e run annullato restano documentati.

**Undici criteri chiusi localmente, 22 aperti.** Nessun accesso o mutazione sul backend QA; emulatore locale spento. La candidata resta alpha e locale, senza pubblicazione: `globalParityVerified=false`, `verifiedApkVersion=null` e `datasetCasesExecuted=false` restano invariati. Prossimo blocco: **V06, Portafoglio**.

## Consuntivo Ricorrenze V04 — candidata locale 0.1.15-alpha

[V04 è PASS LOCALE](docs/RECURRING_FORM_QUALIFICATION.md): 14 form sul release firmato API 31/EN, con calendario, pausa/ripresa, prima creazione, reset della data, deduplica e annullamento della cancellazione. Corretti validazione prima dell'HTTP, PATCH ambigui, isolamento dell'ignore e controlli durante riconciliazione. Passano 567 JVM per variante, 38 Finance + una preferenze API 31 e la [CI finale: 213/213 Android 15](https://github.com/GoryNickel9/Savely-Android/actions/runs/38549914173); lint senza errori.

Il coordinatore host originale resta fallito per il parser numerico; la ripresa completa solo i due CAS e ripristina il baseline QA. Zero transazioni/budget/ricorrenze/sessioni/fattori QA; 33 categorie originali ed emulatore locale spento. La RLS richiede di provare il soft delete nella fixture locale e pulire sul backend la ricorrenza ancora in pausa e visibile. Nessuna policy modificata.

**V01/V02/V03/V04/R01/C01/C02/A04/A05/D01: dieci righe chiuse localmente, 23 aperte.** Parità globale non qualificata e nessuna pubblicazione. Alla chiusura di V04 seguiva V05, ora qualificato nel perimetro sopra.

## Consuntivo Finance V03 — candidata 0.1.14-alpha

[V03 è PASS LOCALE](docs/FINANCE_FORM_QUALIFICATION.md) nel perimetro Finance: 18 verifiche dai form API 31/EN e 14 CRUD con riletture sul server. Corretti i confini della sessione per letture/salvataggi e la validazione prima dell'HTTP. Il logout originale resta fallito per timeout; il recupero UI separato passa in 15,571 s, poi due cleanup host esatti ripristinano il baseline. Passano 558 JVM per variante, 34 guardie host, 37/37 Finance e 13/13 navigazione per ciascuna API 31/35; lint senza errori. QA originali, sessioni/fattori zero e AVD di qualifica spenti.

**V01/V02/V03/R01/C01/C02/A04/A05/D01: nove righe chiuse localmente, 24 aperte.** Parità globale non qualificata, nessuna CI o pubblicazione. Dopo V03 il blocco successivo era **V04**, ora qualificato nel perimetro descritto sopra.

Consuntivo V02 sulla precedente candidata locale 0.1.13-alpha:

[V02](docs/NAVIGATION_FORM_QUALIFICATION.md) è **PASS LOCALE**. Produzione `3ae685f`, release firmato code 14/SHA-256 `38fb4b33a8db74912c0c294d486db4310a4e2b5eb4756492ad662d4471f138d5`. Il run finale conclude tutti i quindici checkpoint, con tre account QA, quattro sessioni distinte e i soli cambi autorizzati di B.Fire. Le destinazioni Admin/A/B, Indietro, azione nativa «Altro», diniego, revoca e ripristino sono osservati; il client Admin secondario tiene i token in RAM.

Il ritorno a Dashboard non ripristina più il child salvato. Il callback Back del menu viene registrato nuovamente alla sua apertura; nuovi login ricreano navigazione e cache, mentre il refresh conserva lo stato dello stesso login. La regressione Dashboard fallisce sulla build precedente e passa su quella corretta con lo stesso APK test. Passano 552 JVM per variante e, per ciascuna API 31/35, 13 casi navigazione e 33 sicurezza. Lint senza errori; tentativi falliti e recuperi conservati.

Snapshot finale **13:54:57,827055 UTC del 13 ottobre 2026**: permessi/proiezioni dei tre QA originali, sessioni/fattori zero e registrazioni disabilitate. AVD QA31/QA35 spenti. **V01/V02/R01/C01/C02/A04/A05/D01: otto righe chiuse localmente, 25 aperte.** La candidata resta alpha e locale; nessuna CI o pubblicazione, parità globale non qualificata. I gruppi selezionati non sono una nuova suite completa dell'app.

Alla chiusura di V02 il blocco successivo era **V03**, ora concluso nel perimetro descritto sopra.

## Consuntivo precedente — qualifica Admin locale 0.1.12-alpha

[D01](docs/ADMIN_FORM_QUALIFICATION.md) è **PASS LOCALE**: produzione `a104e8c`, release firmato code 13/SHA-256 `3e5252bb4c4ff0b3b7c1630c92598cd8d909c1e27b48077699c1fea33fc74bd6`. Un metodo nativo API 31/EN conclude undici checkpoint in 84,583 s. Annullamento, modifica/ripristino Fire, concessione temporanea del ruolo Admin a QA B, revoca del ruolo principale con cache/dialogo svuotati, ripristino e logout sono osservati. QA B è un client host con token in RAM, non un secondo telefono.

Passano **545 JVM per variante debug/release**, 24 guardie host offline e **12/12 fixture Admin su ciascuna API 31/35**, in 49,164/54,507 s; lint zero errori, 32 warning e sei hint per variante. Le patch rileggono il ruolo prima delle azioni e della pubblicazione e vincolano le letture alla sessione. I primi tentativi del driver, il timeout JVM intermedio e tre esecuzioni UI bloccate sono conservati. La regola Compose standard passa i casi isolati e le suite finali; la causa precisa del blocco dei frame non è dimostrata. La correzione Finance riguarda solo la sincronizzazione della fixture.

I tre profili QA tornano ai permessi iniziali; fattori/sessioni e attività nelle tabelle controllate sono zero, registrazioni disabilitate, emulatori spenti. **V01/R01/C01/C02/A04/A05/D01: sette righe chiuse nei rispettivi perimetri locali, 26 aperte.** La candidata resta locale; nessuna CI o pubblicazione. Non sono qualificati reset email, eliminazione utenti, toggle globale o tutti i flussi dell'app sul nuovo APK.

In quel consuntivo il prossimo blocco era V02, ora qualificato nel perimetro indicato sopra.

## Consuntivo precedente — qualifica sessioni locale 0.1.11-alpha

[A05](docs/SESSION_FORM_QUALIFICATION.md) è **PASS LOCALE**: sul release firmato invariato code 12, un metodo nativo API 31/EN conclude nove checkpoint in 253,075 s. Annulla conserva entrambe le sessioni; la revoca singola dal form elimina il bersaglio e il suo refresh restituisce HTTP 400 `refresh_token_not_found`. La revoca corrente riporta l'app al login; una terza sessione host distinta continua a rinnovarsi con lo stesso UUID, poi il logout locale lascia zero fattori/sessioni QA. Proiezioni selezionate d'identità/profili invariate, registrazioni disabilitate e attività QA zero nelle tabelle controllate.

Passano 543 JVM per variante debug/release e 7/7 fixture SettingsSecurity per API 31/35 in 32,511/56,665 s; lint zero errori, 32 warning e sei hint. Conservati il primo fallimento JVM, corretto nel teardown, e il crash Compose API 31 durante distruzione dell'Activity, con isolato e suite successiva sullo stesso APK superati. La causa definitiva del crash Compose resta non dimostrata. I client secondari sono host in RAM, non telefoni; il refresh diretto della sessione corrente già cancellata dallo store non viene esercitato. Nessuna nuova suite completa dell'app o parità globale.

**Sei righe chiuse nei rispettivi perimetri locali, 27 aperte in quel consuntivo.** Produzione e versione 0.1.11-alpha invariate; nessuna CI o pubblicazione. Il successivo blocco D01 è consuntivato sopra.

## Consuntivo precedente — qualifica MFA locale 0.1.11-alpha

[A04](docs/MFA_FORM_QUALIFICATION.md) è **PASS LOCALE nel criterio dei form con setup manuale e QR nelle fixture**. Produzione `44274e7`, release code 12/hash `5cdaca53d41d67d42cb05c0d2753f2611d6ba4d2f830067cde094d3355c757cd`: un metodo nativo API 31/EN passa 14 checkpoint in 207,03 s, con due fattori e due sessioni QA distinti. Codice errato, pending riaperto senza segreto, annulla, attivazione AAL2, nuovo login con gate e rimozione sono osservati. Dopo la rimozione il server riporta AAL1; dopo logout fattori/sessioni zero, registrazioni disabilitate e proiezioni QA invariate. Entrambi gli AVD sono spenti.

Passano 540 JVM per variante debug/release; lint zero errori, 32 warning e sei hint. Le 14 fixture mirate `ced3a93` passano su API 31/35 in 41,232/96,471 s: non sono una nuova suite completa da 184 casi. I fallimenti iniziali Compose e i tentativi del driver restano nel rapporto. Il campione reale usa la chiave manuale: QR del provider non visualizzato, decodifica dei pixel e scanner esterno non qualificati. **Cinque righe chiuse nei rispettivi perimetri locali, 28 aperte**; nessuna CI, nuova pubblicazione o parità globale.

Il successivo blocco A05 è consuntivato sopra. La qualifica completa della candidata code 12 e i gate esterni della stabile restano distinti.

## Consuntivo precedente — qualifica locale 0.1.10-alpha

La candidata locale 0.1.10-alpha/code 11 usa produzione `459bbac` e APK release immutabile SHA-256 `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa`. Passano 536 JVM per variante debug/release; lint di entrambe: zero errori, 32 avvertenze e 6 suggerimenti. La suite debug API 35 passa 170/170 in 562,616 s sul test source `74d7d0d`; API 31 passa 170/170 in 551,778 s, senza failure/skip, in una singola esecuzione completa. Il campione sul release firmato R8 è una prova distinta dalla suite debug.

La [qualifica firmata](docs/SIGNED_RELEASE_QUALIFICATION.md) osserva sul release R8 l’upgrade autenticato API 35, sessione e preferenze conservate, export/annulla e recovery esplicito dopo process death. Il nuovo run firmato API 31 passa login, profilo, upgrade, English/Light e logout con continuità della sessione server e pulizia finale. Export e preview BANK/USD sul nuovo hash appartengono al precedente run separato, con logout UI/server zero. API 31 passa 170/170 in 551,778 s, senza failure/skip. Il form FX API 31 salva 0,01 USD/cambio 0,894370, poi elimina fisicamente il solo record QA; Books positivo resta bloccato da 429.

Conservati 81 PNG e 81 JSON per API; raccolta conclusa ed emulatori spenti, come confermato dal responsabile dell’integrazione.

Consuntivo precedente, prima di C01: la matrice qualificava V01 e R01 nel perimetro locale e manteneva 31 righe aperte. Le prove residue comprendono anche form/stati locali e percorsi QA esclusi, oltre ai gate esterni. Nessuna data della stabile o nuova pubblicazione. I risultati successivi non vengono attribuiti retroattivamente ai sorgenti delle sezioni sotto.

Consuntivo precedente [C01 — inviti Coppia](docs/COUPLE_INVITATION_QUALIFICATION.md): **PASS LOCALE**, con sette fixture mirate superate su ciascuna API 31/35 in 19,716/40,444 s, source test `b5860c3`. Sullo stesso release firmato API 31/EN, i form QA verificano codice invalido, invito pending che impedisce un nuovo invio, rifiuto A→B e cancellazione B→A. Quattro riletture REST per ruolo coincidono; pulizia limitata alle due richieste/quattro audit e sessioni finali zero. Il primo logout automatico A fallisce, poi logout manuale verificato; driver corretto e successivi logout nativi conservano evidenze separate. Allora V01/R01/C01 erano le tre righe chiuse localmente: **30 rimanevano aperte**. Le suite storiche da 170 casi non diventano una nuova suite completa da 173 casi. Il consuntivo immutabile conserva 22 acquisizioni XML/PNG e hash; entrambi gli AVD sono spenti.

Aggiornamento [C02 — form Coppia](docs/COUPLE_FORM_QUALIFICATION.md): **PASS LOCALE** sul medesimo release/API 31/EN. Quote EUR 10,01 a metà (5,01+5,01 con avviso di 0,01), USD 7 custom (2,01/4,99), budget EUR 3→4 sullo stesso UUID, quattro rimozioni da Coppia/Transazioni, revoca annullata poi confermata e archivio in sola lettura sono osservati. Sette fasi REST completano 220 letture ripetute per ruolo; pulizia CAS di 16 righe adottate e zero sessioni QA non scadute/fattori/righe residue, con sole proiezioni d'identità e profili A/B confrontati. Passano 14 fixture per API 31/35 in 47,894/56,859 s, source `b069756`, cinque nuove e nove precedenti. Il primo API 35 crasha in Compose durante distruzione dell'Activity: causa non dimostrata, tentativo conservato; isolato e run finale passano sullo stesso APK senza edit. Il report conserva anche i recuperi del driver e il limite del capture budget zero fuori viewport. **V01/R01/C01/C02 chiuse localmente: 29 righe aperte**. Le suite complete da 170 casi restano storiche; il consuntivo C02 conserva 45 PNG/45 XML con hash e AVD spenti. Nessuna nuova build dell'app, CI o pubblicazione.

Stima precedente di pianificazione per il residuo tecnico: 36–60 ore cumulative, circa 4,5–7,5 giornate da otto ore, riusando fixture e driver disponibili. Comprende form/stati/confronti, percorsi QA autorizzati, file/interruzioni e regressioni/pacchetto; le righe aperte non equivalgono a funzioni mancanti e la stima non assegna una data della stabile. Mailbox, telefono, cloud, Books e recovery su altro computer hanno tempi di calendario separati. La stima va rivista dopo i blocchi qualificati o nuovi difetti osservati; C01/C02 concluse non permettono di sottrarre automaticamente una durata nominale.

Evidenza storica al 12 ottobre 2026: 0.1.9-alpha pubblicata con APK firmato, 503 JVM e due suite Android complete da 130 casi (API 31 e 35). Il codice successivo corregge timestamp, cambio account Admin e sovrapposizione del recupero MFA; 514 JVM e la suite finale Android 12 da 132 casi passano. Creati tre account QA dopo autorizzazione, con registrazioni ripristinate disabilitate. Accesso nativo con password, TOTP/revoca sessioni, flusso Coppia e lettura/diniego/ripristino permessi Admin hanno prove reali con scope dichiarato in [QA_QUALIFICATION.md](docs/QA_QUALIFICATION.md). Nessuna nuova release pubblica o parità globale deriva automaticamente da questi PASS. La 1.0.0 richiede ancora i gate in [PARITY_0_1_9.md](docs/PARITY_0_1_9.md). I cinque blocchi precedenti conservano l’evidenza storica in docs/ROADMAP_CLOSURE.md.

Audit statico successivo: [STATIC_PARITY_AUDIT_2026_10_12.md](docs/STATIC_PARITY_AUDIT_2026_10_12.md), inizialmente senza test. La [verifica locale autorizzata](docs/STATIC_AUDIT_VERIFICATION.md) passa 528 JVM, 36 host Python e 132/132 su entrambe le API 31/35, in singoli run finali completi. Lint senza errori; sorgente `4d6a376`, hash invariati, nessuna CI o nuova prova backend. I PASS QA reali sopra restano associati a `0714f39`. I [gate non di codice](docs/STABLE_NON_CODE_GATES.md) e la parità globale restano aperti.

Confronto sul [dataset comune](docs/COMMON_DATASET_PARITY_REVIEW.md): fissati 50 casi di base con input, ruoli fittizi e attese dai sorgenti. Dopo l'autorizzazione ai test, tre subagent hanno rieseguito 528 JVM e 36 host Python, tutti superati; output TypeScript FIRE/Fumo identici al golden. Nessuna nuova modifica alla produzione, CI, emulatore, prova backend o release. Completata allora la preparazione del confronto; le acquisizioni successive sono documentate qui sotto.

Il [confronto osservato Android/web](docs/COMMON_VISUAL_PARITY_REVIEW.md) sul dataset comune registra una nuova esecuzione di 530 test JVM e un run completo di 28/28 test mirati su Android 12/API 31, con 81 PNG nativi e 114 PNG web appaiati. Corretti layout Sicurezza/storico e accesso a Dati con testo grande, tastiera dei grafici e percentuali/scala FIRE; aggiunta la distribuzione TCG per quantità. Lint debug senza errori; risultati circoscritti a fixture locali, senza CI, backend reale o nuova release. Il confronto di tutti i flussi e i gate della stabile restano aperti.

## 1. Risultato richiesto e vincoli

Revisione successiva: [STABLE_VERIFICATION_MATRIX.md](docs/STABLE_VERIFICATION_MATRIX.md) collega 33 verifiche al codice, alle fixture e ai 50 casi comuni. Corrette staticamente opzioni import e bozza profilo, sorgente `de53ac7`, poi preparate otto regressioni. La [verifica locale autorizzata](docs/RECREATION_REGRESSION_VERIFICATION.md) passa 530 JVM, 36 host Python e due suite complete da 164/164 casi API 31/35; lint senza errori. Corretti soltanto i callsite di montaggio delle fixture in `f60ab38`, con sei failure iniziali conservati. V01 qualificata localmente, altre 32 verifiche aperte. Nessuna CI, prova backend, modifica web o nuova release pubblica; emulatori spenti.

Realizzare un'app Android nativa con tutte le funzioni attualmente disponibili in Savely, account e dati condivisi, APK firmato distribuibile direttamente e schermate utilizzabili senza caricare il sito.

| Vincolo | Decisione |
|---|---|
| Repository | Tutto il lavoro Android in `GoryNickel9/Savely-Android` |
| Web | Nessuna modifica a sorgenti, configurazioni, workflow o asset della repo Savely |
| Vercel | Nessuna modifica al progetto web, ai deploy o al collegamento Git |
| Interfaccia | Kotlin e Jetpack Compose; nessuna WebView/TWA per le funzioni Savely |
| Backend | Riutilizzare Supabase, tabelle, RPC e API esistenti dopo verifica dei contratti |
| Database | Non modificare schema, trigger o policy RLS per adattare Android |
| Autenticazione | Eventuali nuovi callback aggiunti alla configurazione Supabase senza rimuovere quelli web |
| API mancanti | Valutare un endpoint dedicato gestito nella nuova repo; non cambiare gli endpoint web |
| Distribuzione | APK release firmato, versioni e download su GitHub Releases |
| Connessione | Prima versione online per sincronizzazione; comportamento senza rete esplicito, senza promettere piena operatività offline |

Condividere il backend significa che le modifiche ai dati effettuate dall'app saranno visibili anche nel sito. Il vincolo riguarda il codice e la distribuzione web, non l'isolamento dei dati dello stesso account.

La parità funzionale riguarda comportamenti, risultati, dati e autorizzazioni. La disposizione delle schermate sarà adattata ad Android. Ogni differenza intenzionale va registrata nella matrice di parità.

Riferimento iniziale verificato: `Savely/main`, commit `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. Il commit GitHub e quello effettivamente distribuito su Vercel vanno distinguibili nel registro delle revisioni.

## 2. Architettura proposta

Usare Kotlin, Jetpack Compose, ViewModel, coroutine e uno strato dati separato dall'interfaccia. La struttura iniziale può restare in un singolo modulo Gradle, con package per area funzionale: evitare una frammentazione in decine di moduli senza necessità concreta. Queste scelte seguono le [raccomandazioni Android](https://developer.android.com/topic/architecture/recommendations).

| Area | Responsabilità |
|---|---|
| App e navigazione | Avvio, schermate, tema, lingua, permessi e lifecycle |
| Auth e dati | Supabase Auth, sessioni, query, RPC, API privilegiate e gestione errori |
| Logica di dominio | Calcoli finanziari, aggregazioni, importazioni, ricorrenze e split |
| Funzioni | Finanza, Poker, FIRE, Fumo, TCG, Libreria, Coppia, amministrazione |
| Test e distribuzione | Fixture di parità, test JVM/UI, APK, firma, versionamento e report |

I componenti React non sono riutilizzabili come schermate native. Le regole di calcolo TypeScript saranno documentate e portate in Kotlin, verificando gli stessi casi di input. Importi, precisione, arrotondamenti, date e valute devono seguire convenzioni esplicite; evitare `Float` per i calcoli monetari.

Le chiavi amministrative, Resend e le credenziali server non devono essere presenti nell'APK. La chiave pubblicabile Supabase e il token dell'utente devono operare entro i permessi già imposti dal backend. I controlli sulle schermate non sostituiscono RLS e autorizzazione server.

Aggiornare i dati all'apertura, al ritorno in primo piano e dopo le operazioni. Gestire caricamento, cache eventualmente disponibile, errore e retry senza mostrare dati obsoleti come aggiornati. Nella prima versione non introdurre una coda offline di scritture finanziarie.

## 3. Uso dei subagent

Durante lo sviluppo utilizzare al massimo quattro agenti attivi: responsabile più tre subagent. Ogni subagent riceve un ambito, file di proprietà, input, dipendenze e criteri di completamento. I file condivisi vengono modificati dal responsabile o da un solo assegnatario.

| Ruolo | Prima assegnazione | Assegnazioni successive | Evidenza richiesta |
|---|---|---|---|
| Responsabile | Architettura, navigazione, integrazione e pianificazione | Revisione, test end-to-end, firma e release | Build integrata, report dei gate e APK verificato |
| Subagent A: account e contratti | API, autenticazione, callback, MFA e sessioni | Permessi, admin, compatibilità backend | Contratti documentati e test delle autorizzazioni |
| Subagent B: finanza | Transazioni, budget, categorie, ricorrenze | Portafoglio, patrimonio, grafici, insights | Fixture numeriche e flussi completi |
| Subagent C: parità e importazioni | Inventario, mapping web, import/export e fixture | Report differenze; poi moduli extra assegnati a blocchi | Matrice aggiornata, test import e report revisioni |

Quando A o B terminano un blocco, assegnare loro FIRE, Poker, Fumo, TCG, Libreria o Coppia in base alle dipendenze. Non avviare tutti i moduli prima che modelli dati, navigazione e gestione errori siano stabili.

Integrare blocchi piccoli, con test e note sui limiti. Il responsabile riesegue i controlli sull'insieme: i test superati sul singolo modulo non dimostrano che l'app completa funzioni.

Questa roadmap usa subagent anche per revisione e confronto dei risultati. Il parallelismo riduce il calendario solo nelle attività indipendenti; non consente di dividere automaticamente la stima per quattro.

## 4. Fasi operative

Le ore seguenti sono stime preliminari di lavoro cumulativo, non benchmark o scadenze garantite. Le fasi possono sovrapporsi solo dopo il completamento dei loro prerequisiti.

### Fase 1: inventario e contratti, 8-12 ore

1. Espandere [FEATURE_PARITY.md](docs/FEATURE_PARITY.md) in casi verificabili per schermata, operazione, filtro, errore e ruolo. Registrare i sorgenti e il commit di riferimento.
2. Auditare query Supabase, RPC, Edge Functions, API Vercel e servizi esterni; descrivere richieste, risposte, null, errori e autorizzazioni.
3. Risolvere sulla carta registrazione, conferma email e recupero password nativi. Il reset attuale genera un redirect web: non dichiararlo già compatibile con Android.
4. Definire dispositivi Android supportati, ambiente di test e dati sintetici; verificare separatamente le API del servizio Bank.
5. Approvare contratti e convenzioni numeriche, scegliere fixture e aggiornare le stime prima di avviare i moduli.

Assegnazione: A contratti/auth; B regole finanziarie; C inventario e parità; responsabile architettura e dipendenze.

Gate: nessuna funzione omessa; ogni API ha un percorso di riuso o una soluzione dedicata; nessuna soluzione richiede modifiche web. Se un contratto non è disponibile, segnalarlo come blocco specifico e continuare i moduli indipendenti.

### Fase 2: base nativa e primo APK, 16-24 ore

1. Creare progetto Gradle, struttura dei package, navigazione, componenti comuni e build riproducibile.
2. Implementare accesso, sessione persistente, rinnovo e logout, con storage dei token appropriato alla piattaforma.
3. Collegare dashboard e transazioni essenziali agli account esistenti; implementare creazione, modifica e cancellazione con conferme e validazione.
4. Gestire lifecycle, refresh, rete assente, errori, tastiera, date, tema e navigazione indietro.
5. Generare un APK di test e provarlo su emulatore e telefono fisico, senza aprire il client PokerStars.

Assegnazione: responsabile app/navigazione; A auth/dati; B transazioni/dashboard; C fixture, test di parità e basi import.

Gate: APK installabile; accesso e riapertura conservano correttamente la sessione; una transazione di test compare nel backend ed è leggibile dal web; nessuna pagina Savely viene caricata.

Questo APK è parziale. Registrazione, MFA e recupero nativi devono essere completati e verificati prima del rilascio completo, anche se non sono tutti necessari al primo smoke test.

### Fase 3: finanza completa, 40-60 ore

| Blocco | Lavoro richiesto | Controlli specifici |
|---|---|---|
| Transazioni | Filtri, ordinamento, categorie, dettaglio e operazioni disponibili sul web | Segni, date, valute, errori e risultati dopo refresh |
| Ricorrenze | CRUD, calendari, suggerimenti, ignore e comportamento del servizio esistente | Fine mese, anni bisestili, duplicazioni e timezone |
| Budget/categorie | Limiti, spesa raggiunta, categorie e regole esistenti | Categoria rimossa, zero, limite superato e periodo |
| Grafici/insights | Entrate, uscite, aggregazioni e metriche | Stessi input e stessi risultati del riferimento web |
| Portafoglio/patrimonio | Posizioni, chiusure, aggiornamenti, cambi, breakdown, storico e net worth | Precisione, servizi non disponibili e dati mancanti |
| Import/export | Revolut, BBVA/Trade Republic, CSV, mapping ISIN e portabilità | Anteprima, formati, duplicati, righe invalide e round-trip |

Assegnazione: B finanza; C import/export; A completa MFA/sessioni e supporta API; responsabile integrazione e test dei flussi.

Gate: ogni funzione finanziaria della matrice ha test ed evidenza; nessuna importazione modifica dati prima della conferma; export copre le tabelle previste dal web, non soltanto quelle con una schermata dedicata.

### Fase 4: moduli, account e ruoli, 60-90 ore

| Blocco | Funzioni da portare | Dipendenze |
|---|---|---|
| Poker | Contabilità, NextCut, guadagno orario, NetWon/EV e rakeback presenti | Calcoli e permesso `poker` |
| FIRE | Standard e Barista, parametri e risultati | Portafoglio, convenzioni finanziarie e fixture |
| Fumo | Liquido/sigaretta, CBD e THC | Tabelle e permesso `fumo` |
| TCG | Magic, Pokémon, Yu-Gi-Oh, collezioni e prezzi | API prezzi e permesso `tcg` |
| Libreria | Libri, fumetti e manga, ricerca e gestione collezioni | API di ricerca e proxy esistenti |
| Coppia | Inviti, accettazione/rifiuto/cancellazione, revoca, budget comuni, divisioni, condivisione e storico | Due utenti, RPC, permessi e visibilità dei dati |
| Account | Registrazione/conferma, reset, email/password, MFA, sessioni ed eliminazione account | Callback e contratti della fase 1 |
| Admin | Utenti, permessi, registrazioni abilitate/disabilitate, reset per utente ed eliminazione di altri utenti | Token dell'admin, endpoint server e test autorizzazioni |
| Preferenze/documenti | Tema, lingua IT/EN, valuta, privacy e termini | Testi e comportamento del web di riferimento |

Assegnazione: A account/admin e poi Coppia; B FIRE/Poker e poi Fumo; C TCG/Libreria e parità; responsabile integrazione. Ribilanciare dopo ogni blocco, evitando modifiche concorrenti agli stessi file.

Gate: tutti i moduli sono nativi; utenti senza permesso ricevono un comportamento coerente; test con due account per Coppia e con ruoli distinti per Admin; nessun solver, HUD, lettura del client poker o automazione di gioco viene introdotto.

Il flusso di eliminazione account deve coprire conferma, autorizzazione della Edge Function `delete-account`, rimozione dei dati prevista dal backend e chiusura della sessione. Verificarlo esclusivamente con account sintetici destinati alla cancellazione.

Il link attuale a `bank.savely.cc` è un servizio esterno. La fase 1 deve chiarire quali operazioni si vogliono includere nell'app e quali API le espongono. Un'integrazione nativa Bank aggiuntiva non è stimabile dall'inventario della sola repo Savely e non va nascosta sotto la dicitura "parità completa".

### Fase 5: parità verificata e distribuzione, 24-40 ore

1. Eseguire test numerici, contratti, importazioni e UI; usare account di test per MFA, Coppia e Admin. Non effettuare cancellazioni o importazioni massive su account reali.
2. Verificare lifecycle, cambio orientamento, tastiera, accessibilità, font grandi, rete intermittente, link scaduti e sessioni revocate; misurare avvio e refresh con dispositivo e dataset dichiarati.
3. Generare il report web/Android, risolvere ogni differenza funzionale e registrare separatamente quelle intenzionali. Controllare che la repo web non abbia modifiche attribuibili al progetto Android.
4. Preparare APK release, firma, checksum, versione e note. Testare installazione e aggiornamento sopra l'APK precedente preservando dati e sessioni dove previsto.
5. Pubblicare la release direttamente dalla nuova repo, con istruzioni e limiti verificati. Il workflow Android deve vivere esclusivamente qui.

Assegnazione: responsabile release; A test account/ruoli; B test numerici; C matrice, report e import/export.

Gate: tutti i casi obbligatori della matrice superati; nessun test saltato essenziale alla dichiarazione di parità; APK firmato installato su telefono reale; aggiornamento verificato; nessuna credenziale privilegiata nell'APK.

Android richiede APK firmati e continuità della firma per gli aggiornamenti. Conservare un backup protetto della chiave, senza inserirla nel repository. [Firma Android](https://developer.android.com/studio/publish/app-signing). La distribuzione diretta richiede il consenso Android all'installazione dalla sorgente scelta. [Distribuzione alternativa](https://developer.android.com/distribute/marketing-tools/alternative-distribution).

## 5. Stime e milestone

Totale iniziale: 148-226 ore/persona, arrotondate a 150-230 ore. Sono stime da rivedere dopo l'audit; non comprendono un'eventuale integrazione Bank priva di API già utilizzabili.

| Milestone | Risultato verificabile | Finestra preliminare con responsabile e tre subagent |
|---|---|---|
| M0 | Inventario, contratti e rischi definiti | Giorno operativo 1 |
| M1 | Primo APK con accesso, dashboard e transazioni essenziali | Giorni operativi 2-4 |
| M2 | Finanza e import/export completi | Giorni operativi 5-8 |
| M3 | Tutti i moduli e ruoli disponibili | Giorni operativi 8-12 |
| M4 | Parità verificata, APK release e update testato | Giorni operativi 10-15 |

La finestra complessiva è 80-120 ore di calendario operativo, assumendo giornate da 8 ore e lavoro parallelo effettivo. Non è una promessa di completamento continuo in 80 ore: disponibilità dei dispositivi, credenziali di test, callback, API esterne e correzioni possono allungarla. Con un solo esecutore, 150-230 ore corrispondono a circa 19-29 giornate da 8 ore.

Non esiste una scadenza attiva nella notte 11-12 ottobre per questa nuova architettura. Prima di associare date, serve una data di avvio dello sviluppo e il risultato della fase 1.

## 6. Capire rapidamente le differenze dal sito

Implementare nella sola repo Android un controllo che confronti il commit web già verificato con quello nuovo. Non basta confrontare il numero di versione: serve il mapping fra funzioni e sorgenti descritto in [FEATURE_PARITY.md](docs/FEATURE_PARITY.md).

| Modifica web | Conseguenza tipica su Android |
|---|---|
| CSS o disposizione senza cambio di comportamento | Nessun nuovo APK necessario per mantenere parità funzionale |
| Dati aggiornati nel backend | Visibili alla lettura successiva, entro le API già supportate |
| Nuova funzione, validazione o formula nel client web | Analizzare e portare la modifica nativa; spesso richiede nuovo APK |
| Cambiamento backend compatibile | Verificare il contratto; un nuovo APK può non essere necessario |
| API, schema, auth o permessi incompatibili | Verifica urgente e aggiornamento coordinato |

Il futuro workflow `web-parity-audit.yml` sarà manuale e periodico, con lettura di Savely e report come artifact. Se Savely è privata, usare una credenziale con accesso in sola lettura al repository necessario. Non aggiungere workflow al web e non introdurre notifiche/issue automatiche finché un semplice report è sufficiente.

Il report mostrerà SHA confrontati, file, moduli coinvolti, rischio proposto, test da rieseguire e stato della parità. Percorsi nuovi o non mappati devono essere segnalati. La classificazione richiede revisione: un diff non dimostra automaticamente equivalenza semantica.

Le modifiche dirette alla configurazione Supabase o al backend distribuito possono non comparire nel diff Git. Verificare anche i contratti attivi periodicamente e prima delle release, con letture e account di test; includere callback, permessi e RPC.

Aggiornare la baseline di ciascuna funzione solo dopo la verifica; aggiornare la baseline globale quando tutte le funzioni richieste sono allineate. Registrare quale APK è associato alle baseline. Non dichiarare la parità semplicemente copiando una SHA nuova nel registro.

## 7. Aggiornamenti dell'app

I dati condivisi possono aggiornarsi senza sostituire l'APK. Schermate e logica Kotlin compilate richiedono una nuova versione Android quando cambiano.

Prevedere un controllo della release disponibile all'apertura, con versione, note e download. L'utente conferma l'installazione tramite Android; nessuna promessa di aggiornamento silenzioso o di sincronizzazione automatica del codice nativo con un deploy Vercel.

Per il primo rilascio scegliere il meccanismo più semplice: link alla release e installazione guidata. Un download interno con gestione del package installer può essere aggiunto solo se necessario e dopo verifica dei requisiti di distribuzione sui dispositivi interessati.

## 8. File da implementare successivamente

I percorsi seguenti descrivono il piano. Sono già presenti app, wrapper, contratti per area, baseline, mapping JSON, fixture, workflow build/test e audit manuale. La distribuzione stabile resta subordinata ai gate della fase 5.

| Percorso indicativo | Scopo | Quando |
|---|---|---|
| `app/`, Gradle wrapper e configurazione | Progetto Android e codice nativo | Fase 2 |
| `docs/API_CONTRACTS.md` | Query, RPC, endpoint e autorizzazioni | Fase 1 |
| `parity/web-baseline.json` | Revisioni web e versione APK verificata | Fasi 1-2 |
| `parity/source-map.json` | Mapping sorgenti-funzioni | Fasi 1-2 |
| `parity/fixtures/` | Input sintetici e risultati attesi | Dalla fase 1 |
| `.github/workflows/web-parity-audit.yml` | Report differenze in sola lettura | Entro fase 3 |
| `.github/workflows/android.yml` | Build, test e artifact | Fase 2 |
| `.github/workflows/release.yml` | APK firmato, checksum e release | Fase 5 |
| `docs/TESTING.md`, `docs/RELEASE.md`, `CHANGELOG.md` | Verifica, installazione e aggiornamenti | Prima della release |

## 9. Rischi iniziali e controllo

| Rischio osservato | Controllo previsto |
|---|---|
| Reset/signup rimandano a URL web | Callback Android supportato; eventuale componente dedicato senza cambiare endpoint web |
| Logiche TypeScript duplicate in Kotlin | Fixture comuni, baseline per funzione e report dei diff |
| Backend condiviso | Account sintetici, nessuna migrazione Android e operazioni distruttive fuori dalla produzione |
| SDK Kotlin e API non coprono identici flussi | Verificare MFA, revoke session, errori e RPC prima della UI completa |
| API esterne e Bank | Separare dati assenti, indisponibilità e funzioni non ancora integrabili |
| Firma perduta o diversa | Backup protetto e update testato con la stessa firma |
| Stime ridotte artificialmente dal parallelismo | Integrare presto; misurare attività completate e lavoro residuo |
| Client PokerStars aperto durante sviluppo/test | Procedura in [DEVELOPMENT_AND_TESTING.md](docs/DEVELOPMENT_AND_TESTING.md); nessuna garanzia di autorizzazione specifica |

## 10. Stato dei cinque blocchi

Il dettaglio delle prove e dei limiti è in [ROADMAP_CLOSURE.md](docs/ROADMAP_CLOSURE.md), [TESTING.md](docs/TESTING.md) e [RELEASE.md](docs/RELEASE.md). Le attività autorizzate sono eseguite localmente, senza GitHub CI; il workflow build/test ora richiede avvio manuale.

1. **Funzioni native:** callback PKCE, conferma email e recupero password, archivio Coppia revocati, donut categorie e collegamenti Dashboard implementati. Archivio limitato ai metadati consentiti dalle RLS. Bank inventariato: interoperabilità CSV inclusa, convertitore PDF separato.
2. **Identità web:** icona originale, palette, font locali e cinque destinazioni di navigazione implementati. Tre screenshot di fixture controllati; accessibilità, lingua e font grandi coperti dai test registrati. Il confronto visivo di tutte le pagine non è certificato.
3. **Backend:** la prova storica passa 41 contratti su 42; Google Books risponde HTTP 429. Le successive prove QA verificano password/TOTP/revoca, il flusso Coppia e le operazioni Admin elencate in QA_QUALIFICATION.md, con pulizia completa. Nessuno di questi risultati copre tutti i percorsi o le correzioni statiche successive.
4. **Lifecycle e file:** la 0.1.9 passa due suite complete da 130 casi su API 31 e 35. Il sorgente successivo `0714f39` passa 132 casi ordinari su API 31; API 35 non è stata rieseguita su quel sorgente. Picker di sistema, provider cloud e terminazione reale del processo restano verifiche distinte. Nessuna suite eseguita durante l'audit statico.
5. **Distribuzione:** APK pubblico 0.1.9-alpha con firma continua, repository separato per APK e metadati, backup portabile cifrato verificato. Aggiornamento 0.1.8→0.1.9 provato sul solo AVD anonimo. Il codice successivo non è distribuito; aggiornamento autenticato e copia della firma su un altro dispositivo restano aperti.

## 11. Gate esterni ancora aperti

Sono disponibili due utenti QA e un Admin dedicati, creati con apertura temporanea autorizzata delle registrazioni e ripristino immediato di `false`. Password, TOTP, revoca delle altre sessioni e i percorsi Coppia/Admin documentati hanno prove reali. Gli indirizzi sintetici non hanno mailbox: consegna email, conferma e recupero PKCE restano aperti. I PASS storici non includono modifiche email/password, eliminazione di un account sacrificabile e tutte le operazioni Admin. Rifiuto/cancellazione degli inviti hanno ora il campione distinto [C01](docs/COUPLE_INVITATION_QUALIFICATION.md), senza estendere retroattivamente i PASS storici.

L’utente conferma installazione e uso generale della 0.1.7 sul Doogee S100 Android 12, ma non proverà direttamente i nuovi flussi. Le prove su emulatori non vengono attribuite al telefono. I gate originari relativi al dispositivo restano quindi non verificati, salvo le conferme già documentate.

`parity/web-baseline.json` conserva `globalParityVerified=false` e `verifiedApkVersion=null`. Mapping ISIN/categorie e import CSV sono presenti; XLS/XLSX, restore globale e convertitore PDF nativo non sono requisiti del web. Restano confronto finale sullo stesso dataset, import reale e provider cloud, servizi esterni, percorsi dei ruoli esclusi e qualifica dell'APK firmato finale.

## 12. Prossime attività per la stabile

1. **Completato — audit statico:** confronto del riferimento web `1cfcf629`, correzioni delle lacune dimostrate e regressioni inizialmente scritte senza eseguirle.
2. **Completato — verifica locale:** 528 JVM, 36 host Python, suite finali Android 12/15 da 132 casi ciascuna e lint senza errori. Due problemi di attesa/scroll delle fixture corretti senza rimuovere gli assert. Revisione testuale dei documenti e riproduzione 12/12 asset completate; nessuna prova reale aggiunta ai PASS QA storici.
3. **Completato — campione visuale locale:** 530 JVM, 28/28 mirati API 31, 81 PNG nativi e 114 PNG web appaiati; [confronto e differenze](docs/COMMON_VISUAL_PARITY_REVIEW.md). Ogni form/stato, servizi e API 35 sul nuovo sorgente restano fuori da questo run.
4. **Completato nel perimetro locale — SC01/SC02 e V01:** 14/14 mirati finali e due suite complete da 164/164 API 31/35, 530 JVM, 36 host Python, lint senza errori. [Rapporto con sorgenti e failure](docs/RECREATION_REGRESSION_VERIFICATION.md). Allora restavano 32 verifiche della matrice: form/stati web/Android esclusi, percorsi account/Coppia/Admin reali, picker/provider e servizi. Risolvere il gate mailbox prima delle prove email/PKCE; eventuali correzioni dipenderanno dagli esiti.
5. **Completato localmente — candidata firmata, C01 e C02:** firma/R8, campioni autenticati API 31/35 e suite complete debug da 170 casi per API sono qualificati. R01 è PASS LOCALE nel criterio originale. C01 chiude inviti QA e sette fixture mirate per API; C02 chiude quote/budget/rimozioni/revoca/archivio nel campione form con 14 fixture per API e letture/pulizia QA. Restano **29 righe** della matrice, inclusi form/stati, altri percorsi QA, provider cloud e dispositivo fisico; tentativi falliti conservati. Pubblicare 1.0.0 solo sul perimetro qualificato o con esclusioni esplicitamente accettate.
