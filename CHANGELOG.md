# Changelog

## 0.1.11-alpha candidata locale — 12 ottobre 2026

Il gate MFA verifica UID, identità della sessione e revisione iniziale prima del challenge: un vecchio form non può elevare un login sostitutivo dello stesso utente. Enrollment malformati restituiscono un errore esplicito, con limiti per secret/URI; QR opzionale e setup manuale conservato. Input OTP e apertura dell'autenticatore sono disabilitati durante le operazioni busy.

La [qualifica A04](docs/MFA_FORM_QUALIFICATION.md) passa nel perimetro dei form con chiave manuale sul release firmato code 12: 14 checkpoint server/UI nello stesso run, 540 JVM per variante e 14/14 fixture su ciascuna API 31/35. Lint senza errori. Le fixture coprono anche QR locale, busy, ricreazione, login sostitutivo e codice incompleto. Il QR effettivo del provider non viene visualizzato; scanner esterno non qualificato. Conservati fallimenti iniziali e tentativi del driver; fattori/sessioni QA zero ed emulatori spenti. Cinque righe chiuse nei rispettivi perimetri locali, 28 verifiche aperte. Nessuna CI o pubblicazione; le suite complete precedenti restano legate ai propri sorgenti.

Qualifica C02: quote EUR/USD, budget EUR 3→4, quattro rimozioni da Coppia/Transazioni e revoca con archivio in sola lettura dai form QA sul release firmato invariato. Passano 14/14 fixture API 31/35 e 16+15 guardie host; 220 letture REST, pulizia dei soli 16 record residui e sessioni QA attive zero. Primo crash Compose API 35 conservato: causa non dimostrata, test isolato e ripetizione completa sullo stesso APK superati. Aggiunte cinque regressioni e guardie dei journal; il setter accessibile riguarda soltanto il harness separato. Nessuna modifica alla produzione o nuova release; emulatori spenti. C02 PASS LOCALE, 29 verifiche aperte; [rapporto](docs/COUPLE_FORM_QUALIFICATION.md).

Qualifica successiva C01: rifiuto A→B e cancellazione B→A dai form QA sul release 0.1.10-alpha invariato; verifica degli UUID/stati/audit e quattro riletture REST con entrambi i ruoli, poi pulizia delle sole due richieste e quattro audit. Passano 7/7 fixture Coppia su API 31 e 35 e 18 guardie host offline. Corrette soltanto fixture e scroll del driver, conservando i fallimenti. Nessuna nuova build di produzione, CI o pubblicazione; [rapporto](docs/COUPLE_INVITATION_QUALIFICATION.md).

## 0.1.10-alpha candidata locale — 12 ottobre 2026

Corretto il ritorno da CreateDocument dopo perdita dello snapshot export: messaggio esplicito per preparare nuovamente il CSV, senza nuove letture o salvataggio automatico. Un marker monouso salvabile limita il callback; la rimozione riguarda solo il nuovo URI con nome atteso, dimensione zero e permessi/proprietà verificabili. File preesistenti, parziali o non verificabili sono conservati con messaggio; un CSV già scritto resta intatto anche se cambia login. Aggiunte cinque regressioni JVM e tre Android. La fixture replay ripristina lo stato pubblico del registry per consegnare davvero il callback stale, senza cambi di produzione.

La candidata locale 0.1.10-alpha/code 11 usa produzione `459bbac` e APK release immutabile SHA-256 `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa`. Passano 536 JVM per variante debug/release; lint di entrambe: zero errori, 32 avvertenze e 6 suggerimenti. La suite debug API 35 passa 170/170 in 562,616 s sul test source `74d7d0d`; API 31 passa 170/170 in 551,778 s, senza failure/skip, in una singola esecuzione completa. Il campione sul release firmato R8 è una prova distinta dalla suite debug.

Sul campione release R8 passano upgrade autenticato API 35, preferenze/sessione, export locale 11 categorie/1.696 byte, annulla e recovery dopo process death con nuovo documento vuoto rimosso. API 31 passa il nuovo run firmato di login/upgrade/profilo/English/Light/logout. Export/recovery, BANK/USD 12,50 dopo process death e NORMALIZED/EUR 12,50 senza commit import appartengono al precedente run separato, chiuso con logout UI/server zero. Quattro tentativi del driver falliti restano conservati. Form FX nativo: 0,01 USD/cambio EUR 0,894370 persistito e riga eliminata via UI; ricerca Books ancora HTTP 429. [Rapporto](docs/SIGNED_RELEASE_QUALIFICATION.md).

Conservati 81 PNG e 81 JSON per API; raccolta conclusa ed emulatori spenti, come confermato dal responsabile dell’integrazione.

Firma v2/certificato continuo e asset licenze verificati; backup AES recuperato per firmare un APK di test con la stessa chiave, tamper respinto e file temporanei rimossi sullo stesso computer. Recupero fuori computer non qualificato. R01 PASS LOCALE nel suo criterio originale; nessuna CI, pubblicazione o dichiarazione di stabile/parità completa.

## In sviluppo dopo la 0.1.9 — 12 ottobre 2026

Verifica locale autorizzata delle regressioni CSV/profilo: 530 JVM, 36 host Python, 14/14 mirati finali e due suite complete da 164/164 su API 31/35 passano; lint debug/release senza errori. Il primo run mirato ha sei failure: corretto il montaggio delle sole fixture da uno stesso callsite per conservare le chiavi saveable; failure export non riprodotto nei tre run successivi, causa separata non dimostrata. Nessuna modifica alla produzione. [Rapporto](docs/RECREATION_REGRESSION_VERIFICATION.md), 81 PNG per API, checksum e sorgenti conservati. V01 qualificata localmente, 32 verifiche aperte; emulatori spenti, nessuna CI, prova backend o nuova release pubblica.

Preparati otto nuovi casi Android per le patch SC01/SC02: opzioni BANK/USD e NORMALIZED con picker pendente dopo ricreazione, annullamento senza accesso ai dati, bozza profilo e salvataggio esplicito, isolamento fra utenti/login e controlli disabilitati durante caricamento o con profilo nullo/fallito. Rafforzato un caso Transfer esistente. Nessuna nuova modifica alla produzione; casi non compilati e non eseguiti, roadmap e [matrice](docs/STABLE_VERIFICATION_MATRIX.md) aggiornate. Test, build, CI ed emulatore restano rinviati.

Revisione statica successiva, sorgente `de53ac7`: opzioni import conservate con stato salvabile e bozza profilo non sovrascritta dal GET dopo ricreazione. Aggiunta la [matrice delle verifiche mancanti](docs/STABLE_VERIFICATION_MATRIX.md), con 33 verifiche aperte, fonti e mapping dei 50 casi comuni. Le patch non sono compilate o provate; nessun test, build, CI, emulatore, accesso backend o nuova release in questa attività.

Il [confronto osservato Android/web](docs/COMMON_VISUAL_PARITY_REVIEW.md) sul dataset comune registra una nuova esecuzione di 530 test JVM e un run completo di 28/28 test mirati su Android 12/API 31, con 81 PNG nativi e 114 PNG web appaiati. Corretti layout Sicurezza/storico e accesso a Dati con testo grande, tastiera dei grafici e percentuali/scala FIRE; aggiunta la distribuzione TCG per quantità. Lint debug senza errori; risultati circoscritti a fixture locali, senza CI, backend reale o nuova release. Il confronto di tutti i flussi e i gate della stabile restano aperti.

Fissato un dataset sintetico comune di 50 casi di base con attese e fonti per finanza, account e moduli. Tre subagent hanno rieseguito 528 JVM e 36 test host, tutti superati; gli output web FIRE/Fumo coincidono byte per byte con il golden. Il confronto dei chiamanti attivi distingue gli helper isolati dai form Coppia/Fumo; nessun nuovo difetto di produzione richiede una correzione. Roadmap e gate aggiornati: confronto visivo osservato e prove reali ancora aperti. [Rapporto](docs/COMMON_DATASET_PARITY_REVIEW.md), senza CI o nuova release.

Verifica autorizzata dell'audit: 528 JVM, 36 test host Python e due suite complete da 132 casi su Android 12/15 superati; lint debug/release senza errori. Corrette l'attesa del refresh I/O e lo scroll al risultato FIRE nelle fixture, senza indebolire gli assert. Sistemate le chiavi effettive del generatore EN, la frase Cookies, gli avvisi FIRE del riferimento e la nota Libreria sui metadati Google Books. Dodici file legali rigenerati coincidono con gli asset. Report in [STATIC_AUDIT_VERIFICATION.md](docs/STATIC_AUDIT_VERIFICATION.md); nessuna CI, nuova prova backend o release pubblica. La [checklist non di codice](docs/STABLE_NON_CODE_GATES.md) conserva i gate della 1.0.

Audit statico Android/web: bloccate nuove scritture dopo elaborazione ricorrenze parziale; vincolate al login iniziale operazioni account/sicurezza/Coppia, export e panoramiche collezioni. CardTrader aggiorna automaticamente soltanto record EUR, con avviso sui prezzi non-EUR precedenti; aggiunta percentuale mensile Rakeback. Completati i frammenti italiani nei documenti EN e aggiornati roadmap e matrice. Inizialmente le correzioni e nuove regressioni non erano eseguite o testate, come richiesto; la verifica successiva è registrata sopra. Dettagli in [STATIC_PARITY_AUDIT_2026_10_12.md](docs/STATIC_PARITY_AUDIT_2026_10_12.md).

Corretto il caricamento dei timestamp PostgREST con offset su Android 12: archivio Coppia e storico dei cambi usano un parser condiviso che conserva la precisione. Le operazioni Admin restano legate al login iniziale anche durante il preflight; il ViewModel conserva lo stato occupato fino al finalizzatore, evitando sovrapposizioni fra ripristino e challenge MFA.

Tre nuovi account QA permettono prove reali di password/TOTP/revoca sessioni, invito/quote/budget/revoca Coppia e diniego/lettura/ripristino permessi Admin. Registrazioni ripristinate disabilitate, credenziali cifrate fuori dalla repo, nessuna email inviata. Sul sorgente precedente `0714f39` passano 514 JVM, 36 test host Python e 132 casi ordinari Android 12 in un singolo run finale. I test ordinari e i limiti dei flussi sono in [QA_QUALIFICATION.md](docs/QA_QUALIFICATION.md); queste modifiche non sono ancora distribuite in un nuovo APK.

## 0.1.9-alpha — 12 ottobre 2026

Completate le lacune individuate nell'audit: movimenti condivisi e quote, cambio automatico/manuale, palette categorie e periodi budget; import CSV con investimenti ISIN, mapping, nuove categorie, keyword e note; grafico FIRE nominale/reale, input mensili/annuali e reset dal database. Fumo conserva i derivati legacy, Poker mantiene il mese durante la modifica. Aggiunti consenso alla registrazione, QR TOTP locale, chiusura delle altre sessioni, conferma del recupero Admin e documenti IT/EN con navigazione nativa.

Le scritture verificano utente e revisione della sessione iniziale. Mapping e importazioni conservano payload e UUID nelle risposte ambigue; le serie Manga conservano anche l'identità del login. Corretti il ripristino del form Manga e la chiusura prematura del dialogo mapping. La documentazione [PARITY_0_1_9.md](docs/PARITY_0_1_9.md) distingue implementazione, differenze intenzionali e prove reali ancora necessarie.

Rettifica dell'inventario precedente: il web importa CSV. XLS/XLSX, ripristino globale dell'account e convertitore PDF nativo non sono funzioni web da portare.

503 test JVM e due suite complete da 130 casi su Android 12 e 15 passano senza fallimenti o skip; lint debug/release senza errori. Firma e aggiornamento 0.1.8→0.1.9 verificati sul solo AVD anonimo, con inglese conservato. Backend: 41 contratti su 42, Google Books HTTP 429, pulizia completa. Nessuna CI GitHub o modifica web. La parità globale resta aperta.

## 0.1.8-alpha — 12 ottobre 2026

Conferma email e recupero password hanno callback nativi PKCE, con stato e verifier cifrati e consumo prima dello scambio; una sessione di recupero resta separata finché la nuova password non è salvata. Aggiunti archivio dei collegamenti Coppia revocati, donut categorie e collegamenti Dashboard, icona originale web, palette, Inter/Space Grotesk e cinque destinazioni di navigazione. Completate le traduzioni Show/Hide della password. Gli insert delle tabelle note generano UUID nel client nativo, senza dipendere da default mancanti nel servizio distribuito.

Gli aggiornamenti leggono una repository pubblica separata per APK: le alpha possono scoprire nuove prerelease. La prima installazione sopra la 0.1.7 resta manuale; nessuna installazione silenziosa. Backup della firma portabile, cifrato e verificato, ancora sullo stesso computer.

399 JVM e 108 casi Android 12 con esito finale positivo dopo riesecuzioni mirate; lint debug/release senza errori. La singola suite finale Android 15 passa 108/108, senza skip; l’APK definitivo e la verifica della firma sono registrati in TESTING.md e RELEASE.md. Passano 41 contratti backend su 42, CRUD QA isolato e riavvio reale della sessione. Google Books riceve HTTP 429. La prima pulizia è stata bloccata dal confronto booleano del solo guard di test: regressione aggiunta e recovery esatta completata, nessun dato temporaneo pendente. Conservati i report falliti. Nessuna CI GitHub, modifica alla repo web, migrazione o cambio di password QA.

La parità globale e i flussi reali con password, email/MFA, due utenti Coppia e ruolo Admin restano non certificati. Bank PDF è un’estensione separata; mapping ISIN/categorie e XLS/XLSX conservano i limiti dichiarati.

## 0.1.7-alpha — 11 ottobre 2026

Grafici e periodi Dashboard, calendario delle ricorrenze, storico Portafoglio/Patrimonio, chiusure CAS, panoramiche collezioni, condivisione/importazione FIRE e IT/EN. Insights copre otto rilevatori. Le valute restano separate e lo storico ricostruito usa i prezzi salvati, senza feed storico.

366 JVM e 87 Android superati, lint senza errori. APK firmato aggiornato sopra la 0.1.5 sul solo emulatore, senza account. Installazione e uso generale sul Doogee S100 Android 12 confermati dall’utente; parità globale aperta.

## 0.1.5-alpha — 11 ottobre 2026

Libreria rende facoltativi i prezzi di acquisto e rivendita: modificare note o quantità di un record senza prezzi conserva JSON null. Una fixture TLS verifica input malformato/negativo, singolo PATCH, metadati e isolamento della riga estranea.

Passano 232 test JVM e 44 Android, con lint debug/release senza errori. Lo smoke opt-in verifica CRUD dal form transazioni sul solo account QA con UUID aggiunto dal trasporto di test; riga, bootstrap e sessione temporanea risultano rimossi. Il primo tentativo fallito è stato pulito completamente; i selettori ambigui del test sono stati corretti prima del tentativo riuscito. APK firmato/versionCode 6 aggiornato sopra la 0.1.4 sul solo emulatore, senza account. Login con password, telefono e parità completa restano aperti.

## 0.1.4-alpha — 11 ottobre 2026

Admin rimuove utenti, controlli e dialoghi dopo un diniego locale o HTTP 403, conservando la sessione. Un errore temporaneo mantiene dati e dialogo con possibilità di retry esplicito. Insights aggiunge la variazione del prezzo ricorrente, con soglia esatta del 10% e limiti della pipeline dichiarati; copertura 5/8.

Passano 232 test JVM e 43 Android. Le nuove fixture verificano Admin e il flusso TCG con servizio prezzi HTTP 503, prezzo null e valore al costo di acquisto. Lint debug/release senza errori; APK firmato/versionCode 5 aggiornato sopra la 0.1.3 sul solo emulatore, senza account. Login con password, telefono e parità completa restano aperti.

## 0.1.3-alpha — 11 ottobre 2026

Gli endpoint recupero password e Admin usano il dominio canonico www per evitare il redirect 308 rifiutato dal client. Callback signup e Origin restano configurati come prima. Insights aggiunge possibili spese ricorrenti, con massimo tre candidati e copertura dichiarata 4/8.

Passano 226 test JVM e 39 Android, inclusi retry MFA, filtri/esclusioni delle ricorrenze e salvataggio/modifica/reset dei derivati Fumo. Lint debug/release senza errori; APK firmato con versionCode 4 aggiornato sopra la 0.1.2 sul solo emulatore, senza account. Invio email reale, Admin reale, login con password, telefono e parità completa restano aperti.

## 0.1.2-alpha — 11 ottobre 2026

Una sessione revocata o illeggibile azzera subito account e permessi anche da una chiamata diretta delle impostazioni; errori temporanei conservano la sessione. Eventi del vecchio login e risposte profilo tardive non sovrascrivono il nuovo stato. Insights aggiunge il mese di risparmio, severità e filtri, con copertura dichiarata 3/8. Guadagno orario Poker corregge ±0,125 in ±0,13 e conserva gli altri arrotondamenti del riferimento web.

Passano 212 test JVM, 36 Android e lint debug/release senza errori. Ripetuti sincronizzazione Android→sito e ripristino in un processo nuovo sul solo account E2E, con pulizia completa. APK firmato/versionCode 3 aggiornato sopra la 0.1.1 sul solo emulatore, senza account. Restano login con password, telefono e parità completa.

## 0.1.1-alpha — 11 ottobre 2026

Budget con mediana mensile globale e riepiloghi separati per valuta; arrotondamento monetario `HALF_UP` e soglia di perdita del portafoglio confrontata prima di arrotondare. Manga con intervalli fino a 100 record, prezzi unitari, UUID stabili, riconciliazione delle risposte ambigue e ripresa esplicita della serie dopo ripristino dello stato della composizione.

Passano 193 test JVM, 32 test Android e lint debug/release senza errori. Verificati i filtri Libreria/TCG su fixture TLS e il flusso reale della stessa transazione da Android al sito, con eliminazione e pulizia complete. APK firmato con versionCode 2; aggiornamento sopra la 0.1.0 verificato sull'AVD senza account. Login con password, telefono, sessione reale durante aggiornamento e parità completa restano aperti. Nessuna modifica al web o alla configurazione backend.

## 0.1.0-alpha — 11 ottobre 2026

Prima app nativa Kotlin/Compose, con backend Savely condiviso, sessioni cifrate, permessi, finanza e moduli. Aggiunti import/export CSV con anteprima, testi legali nativi, persistenza FIRE per account, test dei contratti e fixture numeriche del web.

APK alpha firmato e ottimizzato; 171 test JVM, 26 test Android e lint debug/release superati. Nessuna modifica alla repo web o alla configurazione Supabase. Le differenze e le funzioni incomplete sono registrate nei contratti e nella baseline di parità.

Gli smoke Android aggiuntivi sul backend reale passano con l'account E2E esistente: sessione cifrata riaperta anche in un processo nuovo, dashboard, lettura del movimento dopo ricreazione, modifica ed eliminazione del solo dato di prova. Verificata la pulizia dopo un'interruzione host. Restano login con password, lettura dal browser web, prova su telefono, aggiornamenti con sessione reale e parità avanzata. Conferma email e reset usano il callback web; lingua dell'interfaccia solo italiana.

Ricorrenze con suggerimenti, esclusioni per account, pausa e elaborazione esplicita delle scadenze; Portafoglio con valore TCG e liquidità/illiquidità; filtri delle collezioni; confronto budget Coppia; audit accessi e revoca tramite session ID. Persistenza FIRE verificata sul dispositivo per tutti i dieci parametri, isolamento, bozze invalide e reset.
