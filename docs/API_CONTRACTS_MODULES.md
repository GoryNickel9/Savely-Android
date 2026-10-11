# Contratti dei moduli nativi

Riferimento letto: `Savely/main`, `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. Questa verifica riguarda i sorgenti e lo schema TypeScript della repository. Non conferma policy RLS, funzioni o dati effettivamente distribuiti.

## Lettura e scrittura

Il client usa PostgREST con JWT dell'utente. Le letture filtrano `user_id`; gli insert lo includono; update e delete usano l'identificativo del record. L'autorizzazione deve essere applicata dalle policy backend, anche quando l'interfaccia nasconde un modulo. Le schermate mostrano errore e retry, confermano le cancellazioni e aggiornano i dati dopo una scrittura riuscita. Non accodano scritture offline.

Le collezioni sono lette in pagine da massimo 500 righe, ordinate, fino alla pagina vuota. Il limite locale è 100.000 righe; oltre questo limite compare un errore. Non si promette una snapshot transazionale durante la lettura di un account modificato contemporaneamente.

| Tabella | Campi scritti | Vincoli nativi |
|---|---|---|
| `poker_next_cut` | `name`, `amount`, `deal`, `profit_loss` | Deal nell'intervallo `(0,1]`; importi finiti; record iniziale solo dopo Salva |
| `poker_manual_expenses` | `name`, `amount` | Nome obbligatorio; spesa non negativa |
| `poker_hourly_earnings` | `date`, `hours_played`, `profit_loss`, `hourly_rate`, `net_won_ev`, `hourly_rate_ev` | Mese convertito al primo giorno; minuti convertiti in ore; Net Won/EV possono essere negativi |
| `poker_rakeback` | `date`, `rake_generated`, `rakeback_received` | Mese convertito al primo giorno; importi non negativi |
| `cbd`, `thc` | `costo`, `grammi`, `data_acquisto`, `data_finito`, `giorni_durata`, `grammi_al_giorno`, `euro_al_giorno`, `costo_mensile` | Quantità/costo non negativi; fine facoltativa; durata zero lascia i derivati null |
| `liquido_sigaretta` | `costo`, `millilitri`, `data_arrivo`, `data_finito`, `giorni_durata`, `millilitri_al_giorno`, `euro_al_giorno`, `costo_mensile` | Stesse regole Fumo; campi con nomi differenti |
| `tgc_cards` | `name`, `category`, `set_code`, `collector_number`, `condition`, `language`, `quantity`, `purchase_price`, `purchase_date`, `current_price`, `notes` | Il nome reale è `tgc_cards`; categorie `magic/pokemon/yugioh`; quantità intera >=1; prezzo attuale vuoto è null |
| `library_items` | `title`, `category`, `author`, `publisher`, `year`, `quantity`, `purchase_price`, `reselling_value`, `notes` | Categorie `libri/fumetti/manga`; quantità intera >=1; anno intero facoltativo |

Le modifiche aggiornano soltanto i campi esposti nel form: identificativi API, immagini e metadati non esposti restano nel backend. Le stringhe facoltative vuote diventano null. I controlli di mese duplicato usano i record letti: non sostituiscono un vincolo univoco server in caso di due client concorrenti.

## Formule e convenzioni

FIRE replica `src/lib/fire/calculations.ts`: rendimento reale `(1+r)/(1+i)-1`; obiettivo `spese/prelievo`; contributi a fine anno; fallback prelievo 4% se non positivo; limite di 100 anni per `yearsToTarget`; proiezione nominale con valore corretto per inflazione, fino a 50 anni. I calcoli usano `Double`, come i `number` JavaScript; gli arrotondamenti seguono `Math.round`, inclusa la direzione delle parità. I test confrontano i valori monetari già arrotondati esattamente e i rapporti entro `1e-12`. Non vengono mostrati probabilità di successo o dati storici simulati.

Il pulsante FIRE “Usa i miei dati” legge `portfolio_assets` e `transactions`: esclude posizioni chiuse solo se hanno sia data sia prezzo di vendita; prezzo corrente null usa quello d'acquisto. Reddito e spesa degli ultimi 24 mesi sono divisi per 2 per annualizzare; contributo annuo `max(0, reddito-spese)`. Il pulsante sostituisce parametri soltanto dopo lettura riuscita. La selezione di date usa `LocalDate`; i casi esattamente al confine dei 24 mesi/730 giorni richiedono verifica rispetto al timestamp JavaScript del web.

Poker NextCut: `mediana(spese mensili negli ultimi 730 giorni) + somma(spese manuali)`, poi `spesa/deal-profit_loss`. Come il codice web, la mediana considera soltanto mesi con transazioni di spesa e non applica un limite superiore alle date. Questa scelta è osservata nel codice, anche se il commento web menziona valori zero. `amount` viene aggiornato con Salva, evitando scritture durante una semplice lettura.

Guadagno orario: `hours_played=minuti/60`; rate memorizzati lordi `NetWon/ore` e `NetWonEV/ore`, zero quando ore=0. L'interfaccia mostra rate moltiplicati per il deal, con fallback 0,55 come il web; i riepiloghi dividono la somma dei risultati per la somma delle ore. Rakeback è `somma(ricevuto)/somma(rake)*100` quando il rake è positivo.

Fumo: giorni tra date ISO; quantità/giorno e euro/giorno divisi per la durata positiva; costo mensile convenzionale `euro/giorno*30`; riepilogo annuo costo/12. THC e Liquido mostrano nel dettaglio solo l'anno corrente, mantenendo riepiloghi su tutti gli anni. CBD mostra tutte le righe.

TCG: costo e valore sono prezzi unitari moltiplicati per quantità; prezzo corrente null usa il prezzo d'acquisto. Libreria: acquisto e rivendita moltiplicati per quantità; null vale zero. Le valute esposte in questi moduli sono euro, come le schermate web auditabili.

## Servizi esterni e funzioni da completare

| Servizio | Contratto osservato | Stato Android |
|---|---|---|
| CardTrader | Edge Function `cardtrader-proxy`, GET `?action=search&name=...`, JWT Bearer; prezzi `?action=prices&id=...&condition=...`; `ctZeroLowPriceCents/100`, null se assente | Ricerca nativa, selezione carta, recupero prezzo all'aggiunta e aggiornamento manuale del prezzo presenti; il token CardTrader rimane server-side |
| Google Books | GET `/books/v1/volumes?q=intitle:...&langRestrict=it&maxResults=20&orderBy=relevance`; fumetti aggiunge `subject:fumetti`, poi fallback senza restrizioni | Ricerca/selezione native; titolo, primo autore, editore, anno, ID e URL copertina salvati; immagini native con Coil |
| Manga | Inserimento manuale; range di volumi nella pagina web | CRUD individuale e creazione di intervalli presenti; avanzamento, errore parziale e ripresa esplicita |
| Bank | Link `https://bank.savely.cc` da `ImportExportSection.tsx`; nessun contratto bancario in questa repository | Nessuna integrazione bancaria nativa dichiarata; servono API e ambito del servizio separato |

La richiesta libri pubblica non riceve token Supabase o chiavi dell'account. Entrambi i servizi mostrano HTTP failure come errore, anziché collezione vuota. All'aggiunta TCG il prezzo assente o il servizio prezzi indisponibile lascia `current_price` null, come il web; il costo d'acquisto resta il fallback per il valore. Il pulsante separato Aggiorna prezzo comunica indisponibilità e preserva il valore precedente. Nessun prezzo viene inventato.

La risposta Google Books è limitata a 2 MiB e viene validata come oggetto JSON con limiti di profondità e precisione prima di estrarre i venti risultati. Una risposta troppo grande o non valida produce un errore e non sostituisce i dati con una lista vuota.

Carte e copertine vengono mostrate nativamente con Coil, dimensioni massime esplicite e descrizioni accessibili. Non viene caricato HTML; i token Supabase non vengono aggiunti alle richieste delle immagini. URL HTTP di copertina sono convertiti in HTTPS come nel client web.

FIRE salva tutti i dieci parametri Standard/Barista in SharedPreferences private, in una snapshot JSON con versione 1. Ogni account usa un file distinto, derivato tramite SHA-256 dall'ID: questa scelta differisce intenzionalmente dal localStorage web condiviso nel browser e impedisce di riutilizzare i parametri di un altro account. I token non entrano nella snapshot. L'app disabilita il backup Android.

Le modifiche valide vengono salvate in ordine con commit su thread I/O; i campi temporaneamente vuoti o non validi lasciano disponibile l'ultima snapshot valida. La schermata distingue salvataggio in corso, successo ed errore con retry. La ricreazione Android conserva inoltre la bozza tramite `rememberSaveable`. Al riavvio vengono letti e validati tutti i dieci campi; file assente usa gli stessi default web. JSON corrotto, incompleto o con versione sconosciuta mostra un avviso e i valori di esempio, preservando il dato originale e sospendendo il salvataggio automatico. Il pulsante Ripristina sostituisce esplicitamente la snapshot con tutti i default di entrambi i calcolatori; non modifica il backend. Il reset invalida le scritture ancora in coda della bozza precedente.

Non è ancora presente condivisione FIRE tramite URL. I test JVM non dimostrano flussi backend con account o accessibilità su dispositivo.

## Filtri delle collezioni

Il dominio `CollectionFilters` replica i predicati di `src/components/tcg/TcgCollectionPage.tsx`, `src/components/libreria/LibraryPage.tsx` e `src/pages/libreria/Manga.tsx` della revisione di riferimento. Le schermate espongono i selettori nativi e Azzera filtri, il numero di risultati e uno stato distinto per una collezione vuota o per filtri senza corrispondenze.

TCG combina testo e set con AND. Magic normalizza i set in maiuscolo sia nelle opzioni sia nel confronto; Pokémon conserva il valore originale. Il testo ricerca nome e set per Magic/Pokémon, soltanto nome per Yu-Gi-Oh. La selezione del set è esatta e distingue maiuscole/minuscole dopo la normalizzazione di Magic. Il dialogo CardTrader consente anche di filtrare i risultati per nome esatto dell'espansione restituita dall'API.

Libreria, inclusi i manga, ricerca il testo soltanto nel titolo; autore, editore e anno sono selettori indipendenti combinati con AND. Autori/editori vengono deduplicati e ordinati, ignorando i metadati null o vuoti. Gli anni sono deduplicati e ordinati in ordine decrescente; zero non genera un'opzione, come nel web. I selettori sono ricavati dall'intera collezione, non dal sottoinsieme già filtrato.

Filtrare non modifica i record, non scrive nel backend e non ricalcola i totali su un insieme ridotto: i riepiloghi mantengono il costo e il valore dell'intera collezione, come gli hook web. L'ordine delle righe resta quello della lettura. Nei componenti web auditati non è presente un ordinamento interattivo delle collezioni.

## Intervalli Manga

Il form di aggiunta replica `parseVolumeRange` e `handleSubmit` di `src/pages/libreria/Manga.tsx`, insieme al payload di `src/hooks/useLibraryItems.ts`. Campo vuoto conserva l'inserimento individuale; `3`, `1-5` e `2–4` creano rispettivamente uno, cinque e tre record. Il limite è di 100 record, non il numero massimo del volume: `1000-1002` è valido. Android accetta solo interi positivi fino a `Int.MAX_VALUE`, rifiuta intervalli decrescenti e impedisce il salvataggio di input non validi. Questa validazione differisce intenzionalmente dal `parseInt` web che interpreta `3x` come volume 3.

Ogni record ha titolo `Titolo Vol. N` e quantità 1. Autore, editore, anno, note, eventuali identificativi API/copertina e prezzi unitari vengono copiati. Il form nasconde la quantità durante l'espansione; modificare un record esistente resta un'operazione individuale. I prezzi della serie rispettano lo schema auditato `20260405000000_create_library_items_table.sql`: valori non negativi, massimo due decimali e massimo `99999999.99`, compatibile con `numeric(10,2)`.

La serie usa un UUID distinto e stabile per ogni record e congela payload e proprietario prima del primo POST. Il salvataggio è sequenziale, senza transazione bulk: la schermata mostra il numero di volumi confermati e conserva quelli già salvati se una richiesta fallisce. Prima di ogni POST viene pubblicato lo stato del volume in corso. Dopo una risposta ambigua, un conflitto o una rappresentazione non verificabile, il client legge soltanto quell'UUID, filtrato per proprietario, e confronta tutti i campi inviati. Una corrispondenza conferma il volume senza un secondo POST; un'assenza verificata interrompe il tentativo e permette soltanto una ripresa esplicita con lo stesso UUID. Errori di lettura o payload diversi impediscono nuove scritture. La chiave primaria UUID evita un duplicato anche se la prima richiesta viene completata in ritardo. I volumi già confermati non vengono reinseriti; un cambio account blocca la ripresa.

La snapshot JSON versione 1 conserva payload, UUID, proprietario, prefisso confermato e richiesta incerta tramite `rememberSaveable`; il limite è 131.072 caratteri. Snapshot corrotte o incompatibili bloccano il pulsante di ripresa. La ricreazione Android può restaurare la serie, ma la ripresa richiede il pulsante dell'utente: non esiste una coda offline o una ripresa automatica. Chiudere il dialogo, navigare altrove o effettuare un nuovo avvio senza stato Android restaurato perde il piano locale; il form avvisa di controllare la collezione prima di creare nuovamente la serie. Non è un journal persistente per un arresto arbitrario del processo. Non sono state aggiunte migrazioni né eseguite scritture su backend reale.

## Evidenza riproducibile

`node --experimental-strip-types parity/generate-web-fixtures.mjs ../Savely` esegue le esportazioni TypeScript web originali per Standard/Barista/Fumo e scrive solo `parity/fixtures/web-calculations.json` nella repository Android. Lo script rifiuta una SHA diversa dalla baseline e usa una data fissa per le proiezioni. `ModuleCalculationsTest` confronta il dominio Kotlin con questa fixture; i casi Poker verificano formule auditabili e non costituiscono una prova end-to-end sul sito distribuito. `CatalogRepositoryTest` usa MockWebServer per GET autenticata CardTrader, conversione centesimi/null, query/fallback/deduplica libri, assenza di token nel servizio pubblico ed errori HTTP.

`FireParametersTest` copre round-trip dei dieci parametri, frazioni percentuali, importi e zero, default di reset, distinzione dei file per account, JSON incompleto/corrotto, tipi errati, versione sconosciuta ed esclusione dei numeri non finiti. La verifica del codec non sostituisce una prova su dispositivo di chiusura/riavvio, logout/accesso con due account, failure del disco e ricreazione del form.

I quattro scenari `FirePersistenceTest` passano sull'AVD Android 15: modificano tutti i dieci parametri, confrontano il JSON in memoria e l'XML effettivamente scritto, ricreano schermata/client/store, cambiano tra due account sintetici e verificano reset, dati corrotti/versioni sconosciute e bozze invalide. Ogni richiesta di rete è bloccata e il conteggio deve restare zero. I test usano touch normale dopo aver atteso coordinate stabili al termine dello scroll. Questa ricreazione della composizione non arresta il processo; restano da verificare process death specifico di FIRE, errore del disco e passaggio tra due login reali.

`CollectionFiltersTest` contiene casi sintetici per set Magic normalizzati, set Pokémon con confronto esatto, testo Yu-Gi-Oh limitato al nome, metadati null, combinazioni titolo/autore/editore/anno, ordine/deduplica delle opzioni, assenza di mutazioni e filtro dei risultati CardTrader. Questi casi verificano il dominio e non dichiarano già verificati i dropdown su dispositivo.

Il parser originale Manga della revisione indicata è stato eseguito con Node su input vuoto, `3`, `1-5`, `2–4`, `1-100`, `1-101`, `1000-1002`, `0`, `5-3` e `3x`: conferma il limite di 100 record e la differenza di validazione descritta sopra. I 14 test JVM di `MangaVolumesTest` passano nella build integrata: parser, payload e prezzi, snapshot/versione/UUID, progressione prima del POST, risposta ambigua, conflitto, risposta malformata, ripresa dopo assenza verificata, errori di lettura, mismatch e cambio account. Le richieste repository usano solo MockWebServer e account sintetici; questi risultati non verificano il form Manga su dispositivo.

I quattro scenari di `CollectionScreenTest` passano nella suite integrata di 32 test Android, con certificato TLS locale, MockWebServer e account sintetico: input Manga invalido senza POST; tre volumi con commit ambiguo, errore di verifica e ripresa esplicita senza duplicati; filtri autore/editore/anno della Libreria con totali invariati; normalizzazione e ricerca dei set TCG. Il caso Manga ricrea la composizione tramite `StateRestorationTester` e conserva il piano locale, senza dichiarare una ricreazione dell'Activity o un arresto del processo. I click usano touch normali dopo aver atteso bounds e viewport stabili; gli errori di attesa riportano stato della fixture e semantica UI. Questi scenari non dimostrano policy o scritture sul backend reale.
