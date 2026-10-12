# Changelog

## In sviluppo dopo la 0.1.9 — 12 ottobre 2026

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
