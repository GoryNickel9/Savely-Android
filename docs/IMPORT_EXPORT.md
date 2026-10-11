# Import/export Android

Sorgenti web verificati in sola lettura al commit `1cfcf6297e85d56bfafe3522362ceecef6f6b281`: `src/lib/constants.ts`, `src/lib/csv.ts`, `src/components/settings/ImportExportSection.tsx`, `RevolutImportDialog.tsx`, `BankImportDialog.tsx` e tipi Supabase. Nessun parser, endpoint o schema web è stato modificato.

## Flusso nativo

`TransferScreen(api)` usa il selettore documenti Android. L'importazione legge un CSV UTF-8 fino a 5 MiB e 10.000 righe, crea un'anteprima, legge categorie/mapping/dati esistenti e segnala righe invalide e duplicati. Non scrive transazioni, categorie o mapping durante lettura e anteprima.

L'utente seleziona le righe, assegna categorie coerenti con entrata/uscita e conferma con il dialog «Conferma e importa». Un secondo controllo dei duplicati precede la prima scrittura. Le richieste sono sequenziali; ciascuna riga mantiene il proprio esito. Il ViewModel conserva l'anteprima durante cambi di configurazione; la sua chiave include l'ID account. L'anteprima non è un checkpoint su disco e non sopravvive alla terminazione del processo Android.

I duplicati usano descrizione, data, importo assoluto arrotondato a due decimali, valuta e tipo. L'arrotondamento riprende il controllo Revolut del web; valuta e tipo evitano collisioni tra importi uguali di valute diverse o di segno economico diverso. Le ripetizioni nel file sono escluse insieme ai dati già presenti. Due transazioni reali identiche possono essere considerate duplicate: controllare l'anteprima prima di confermare.

Ogni inserimento include un UUID client stabile e il `user_id` verificato. Errori di rete, risposta o server producono «Esito da verificare» e fermano ulteriori scritture; il client non ritenta automaticamente. «Verifica gli esiti incerti» rilegge i dati e riconosce le righe presenti. Le righe ancora incerte restano escluse dai retry. Non è una transazione atomica dell'intero file: le righe già riuscite rimangono salvate e vengono mostrate separatamente dalle fallite.

## Formati supportati

| Formato | Contratto | Differenze dichiarate |
|---|---|---|
| Revolut CSV | Intestazioni esatte `Type,State,Completed Date,Description,Amount,Currency`; solo `Card Payment` con `COMPLETED`; importo assoluto a due decimali; data da `Completed Date` | Mantiene la valuta del file, mentre il web corrente imposta EUR; data mancante è errore, senza usare la data odierna |
| CSV normalizzato | `date,description,amount,currency,type`; opzionali `category_id,exchange_rate_eur,entity`; tipo `income` o `expense`, importo positivo | Con `entity` importa soltanto righe `transactions`; non ricrea l'intero account dall'export |
| BBVA/Trade Republic CSV | Intestazioni italiane con Data e Descrizione/Nota; Importo con segno oppure Entrata/Uscita; Tipo e Valuta opzionali | Righe TRASFERIMENTO e SPORT. AUT. escluse secondo web; investimenti/ISIN segnalati e non convertiti in spesa |

Per CSV bancari senza Valuta, l'utente sceglie esplicitamente la valuta del conto. Importi originali e valuta restano invariati. Per importare valute diverse da EUR serve il cambio «1 valuta in EUR» della data della transazione; viene salvato in `exchange_rate_eur`. EUR usa cambio 1. Il CSV normalizzato può già includere il cambio. Il client non usa il cambio corrente come sostituto storico e non assegna implicitamente 1 a una valuta estera.

Date accettate: `YYYY-MM-DD`, `DD/MM/YYYY`, timestamp ISO di cui si mantiene la data dichiarata e timestamp Revolut con data prima dello spazio. Date impossibili o assenti, importi non numerici, tipi/valute sconosciuti e precisioni non supportate rimangono errori nell'anteprima. Gli importi usano `BigDecimal`; formati italiani `1.234,56` e anglosassoni `1,234.56` sono coperti da fixture.

Il CSV supporta virgola o punto e virgola, BOM UTF-8, CRLF, virgolette escape e newline in celle quotate. Intestazioni duplicate, celle non chiuse e numero di colonne diverso non vengono accettati silenziosamente.

**Non ancora supportato:** XLS/XLSX, creazione di posizioni da ISIN, modifica mapping ISIN, creazione automatica categorie, salvataggio mapping delle nuove categorie, riscrittura automatica delle descrizioni bancarie in keyword o «Interessi», ripristino di un intero account con chiavi esterne. Questi casi impediscono di dichiarare parità completa con l'import bancario web.

## Export dell'account

L'export include tutte le 25 tabelle di `USER_TABLES` del commit verificato, anche senza una schermata Android dedicata. Ogni riga ha `entity` e tutti i campi restituiti dal backend. Le colonne sono l'unione dei campi; null/assenti diventano celle vuote; oggetti e array vengono serializzati come JSON. Le regole CSV preservano virgole, punti e virgola, virgolette, newline e spazi significativi.

| Gruppo | Scope esplicito, oltre a RLS |
|---|---|
| 21 tabelle con `user_id` | `user_id=eq.<utente corrente>` |
| `couple_connection_requests` | Mittente oppure destinatario uguale all'utente corrente |
| `couple_connections` | `user_a` oppure `user_b` uguale all'utente corrente |
| `couple_budgets`, `shared_expenses` | `connection_id` nei collegamenti del medesimo utente, in gruppi di 100 |

Il client verifica di nuovo l'appartenenza di ogni riga. Anche un account admin non riceve un export globale per effetto dei suoi permessi. Le tabelle Coppia non hanno `user_id`, nonostante il commento generale in `constants.ts`: gli scope seguono lo schema effettivo.

Paginazione: ordine `id.asc`, limite richiesto 500, cursore `id=gt.<ultimo ID>`, fino a pagina vuota. Una pagina più corta di 500 non termina la lettura: il backend può avere `max_rows` inferiore. Un errore di tabella, un ID non crescente, un cambio account o limiti superati annullano l'intero export. Limiti: 50.000 righe aggregate, 25 MiB CSV e limite di memoria stimata per le singole tabelle. La UI mostra numero di tabelle e righe soltanto dopo raccolta riuscita.

Il file si salva tramite `CreateDocument` del Storage Access Framework. Se la scrittura nel documento appena creato fallisce, il client tenta di rimuovere solo quel documento incompleto e comunica l'errore. Non richiede accesso generale allo storage.

Le letture paginate non costituiscono uno snapshot transazionale del database. Dati inseriti o modificati da un altro dispositivo durante la raccolta possono influenzare il contenuto; il backend esistente non espone un export atomico. I record nascosti da RLS, inclusi eventuali record soft-deleted, non sono recuperabili dal client. L'export conserva il testo delle celle: fogli di calcolo possono interpretare descrizioni che iniziano con `=` come formule; importare queste colonne come testo.

## Verifica

`TransferCsvTest` copre quoting round-trip, JSON annidato, filtri Revolut, valute, precisione, date, formati bancari, righe ISIN escluse, limiti, duplicati e scope account/Coppia. `TransferRepositoryTest` usa MockWebServer per simulare `max_rows=1` e un errore di tabella che annulla l'export. Non effettua scritture reali.

La build integrata ha eseguito `:app:testDebugUnitTest` l'11 ottobre 2026: 12 test `TransferCsvTest` e 2 `TransferRepositoryTest`, zero errori e zero fallimenti nei report XML. Restano da verificare sul dispositivo: selezione documenti, salvataggio tramite provider locale/cloud, rotazione durante import, estratti effettivi di ogni banca e importazione confermata con account sintetici. Non sono state importate transazioni sul backend di produzione.
