# Roadmap Savely Android

Data: 12 ottobre 2026. Stato: 0.1.9-alpha pubblicata con APK firmato, 503 JVM e due suite Android complete da 130 casi (API 31 e 35). Il codice successivo corregge timestamp, cambio account Admin e sovrapposizione del recupero MFA; 514 JVM e la suite finale Android 12 da 132 casi passano. Creati tre account QA dopo autorizzazione, con registrazioni ripristinate disabilitate. Accesso nativo con password, TOTP/revoca sessioni, flusso Coppia e lettura/diniego/ripristino permessi Admin hanno prove reali con scope dichiarato in [QA_QUALIFICATION.md](docs/QA_QUALIFICATION.md). Nessuna nuova release pubblica o parità globale deriva automaticamente da questi PASS. La 1.0.0 richiede ancora i gate in [PARITY_0_1_9.md](docs/PARITY_0_1_9.md). I cinque blocchi precedenti conservano l’evidenza storica in docs/ROADMAP_CLOSURE.md.

## 1. Risultato richiesto e vincoli

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
| `parity/source-map.yml` | Mapping sorgenti-funzioni | Fasi 1-2 |
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
3. **Backend:** 42 contratti su 43 passano; 12 inserimenti, 8 modifiche, due chiusure CAS e letture native verificati. Google Books risponde HTTP 429 per quota esaurita. Recovery della stessa esecuzione conferma eliminazione dei dati temporanei, logout e rimozione dei file privati, senza pulizia pendente.
4. **Lifecycle e file:** prove su emulatori Android 12 e 15, test di rete, ricreazione, tastiera e documenti. Suite finale Android 15 108/108; Android 12 ha 108 esiti finali positivi dopo riesecuzioni mirate. Sei casi SAF/import/export passano su entrambe le API; l’interfaccia del selettore di file non è automatizzata. Risultati completi e correzioni del solo harness sono in TESTING.md.
5. **Distribuzione:** APK 0.1.8 con firma continua, repository pubblico separato per APK e metadati, backup portabile cifrato verificato. Riferimento web distribuito legato al deployment e bundle pubblico confrontato. La copia della firma su un dispositivo diverso resta da verificare.

## 11. Gate esterni ancora aperti

Le registrazioni sono disabilitate anche dal trigger. L’unico account QA documentato ha password ignota e una sessione precedente: non vengono alterati password, MFA o sessioni preesistenti per completare una prova. Questo limita login reale con password, email/PKCE completo, MFA positivo, cambio email/password, Coppia con due utenti, Admin autorizzato ed eliminazione di un account sintetico. I contratti simulati non sostituiscono queste verifiche.

L’utente conferma installazione e uso generale della 0.1.7 sul Doogee S100 Android 12, ma non proverà direttamente i nuovi flussi. Le prove su emulatori non vengono attribuite al telefono. I gate originari relativi al dispositivo restano quindi non verificati, salvo le conferme già documentate.

`parity/web-baseline.json` conserva `globalParityVerified=false`. Il porting completo mantiene le differenze dichiarate in FEATURE_PARITY.md e IMPORT_EXPORT.md: mapping ISIN/categorie, formati XLS/XLSX, equivalenza visiva globale e casi reali dei ruoli non sono certificati da questa alpha. Non viene dichiarata completata la roadmap di parità stabile.
