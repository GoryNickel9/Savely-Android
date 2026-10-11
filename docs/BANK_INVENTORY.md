# Bank2CSV e importazioni Android

Audit del 12 ottobre 2026. Savely web collega il servizio esterno `https://bank.savely.cc`; il convertitore appartiene alla repository privata `GoryNickel9/Bank2CSV`, commit `3310e022f51e509b08b2036a1da1501e4799e437`. La home ha risposto HTTP 200. Nessun sorgente del sito o del convertitore è stato modificato.

## Contratto disponibile

`App.tsx` legge un PDF scelto dall'utente, chiama `services/pdfParser.ts`, mostra i movimenti estratti, consente di eliminare righe e scarica il CSV tramite `utils/exportUtils.ts`. Il parser usa PDF.js nel browser; `services/localParser.ts` seleziona i parser BBVA e Trade Republic. L'albero della repository non contiene endpoint di conversione `api/` né un servizio server da richiamare dall'app Android.

Android importa i CSV BBVA/Trade Republic e Revolut con anteprima, validazione e deduplica in `transfer/TransferCsv.kt`, `TransferRepository.kt` e `TransferScreen.kt`. Il contratto di interoperabilità è il file CSV: l'utente può produrlo con Bank2CSV e selezionarlo in **Impostazioni → Dati → Scegli CSV e prepara anteprima**. I test dei parser non dimostrano che ogni estratto conto PDF sia convertibile dal servizio esterno.

Il percorso nativo conserva importo e valuta del file e richiede un cambio storico per le valute estere. Restano mancanti XLS/XLSX, mapping e creazione di posizioni ISIN, salvataggio dei nuovi mapping categoria e riscrittura automatica delle descrizioni bancarie. Le differenze e i limiti di import/export sono registrati in [IMPORT_EXPORT.md](IMPORT_EXPORT.md); l'interoperabilità CSV non equivale a parità completa con il dialog bancario web.

## Verifica locale

Sono presenti sei test Android in `TransferDocumentScreenTest`, con backend simulato HTTPS locale e documenti sintetici nel provider MediaStore Downloads. Coprono annullamento, file Excel/UTF-8 non validi, anteprima e conferma, cambio con tastiera, ricreazione dell'Activity, commit seguito da HTTP 503, rilettura esplicita e salvataggio dell'export completo o interruzione per errore di tabella.

I risultati SAF vengono consegnati da un `ActivityResultRegistry` di test: metadati e stream passano dal provider Android reale, mentre l'interfaccia del selettore di sistema non viene navigata. La fixture richiede API 29+, quindi su API 26–28 questi sei casi vengono esclusi. Il primo run mirato API 31 ha passato tre casi e fallito gli altri tre negli helper di scorrimento/composizione della LazyColumn. Dopo correzioni del solo harness, tutti e sei passano in 27,616 secondi: `artifacts/android12-0.1.8-transfer-final.txt`. Il log originale fallito resta conservato. Tutti e sei passano anche nella singola suite finale API 35 di 108 casi, descritta in TESTING.md.

Provider cloud, selettore di sistema, ripristino dell'anteprima dopo terminazione del processo ed estratti conto PDF reali restano fuori da questa prova. Non sono state importate transazioni in produzione.

## Ambito

L'audit di parità Savely comprende il percorso di importazione dei CSV; il convertitore PDF è un prodotto separato. L'app non offre una conversione PDF nativa e l'inventario del convertitore non espone un'API remota. Portare anche Bank2CSV richiederebbe un requisito distinto, fixture PDF autorizzate e un parser Android verificato. Questa estensione non è inclusa nei cinque blocchi di chiusura della roadmap Savely.
