# S02 — update-tcg-prices

Distribuito come versione 18 ACTIVE il 14 ottobre 2026, dopo autorizzazione esplicita dell’utente. Readback dei due sorgenti esatto; richiesta senza JWT e senza x-cron-secret rifiutata con HTTP 401. Il cron non è stato invocato con autorizzazione e nessuna carta cliente è stata letta o modificata dall’operatore. Il [rapporto di deployment](DEPLOYMENT_REPORT.md) distingue questo perimetro dai controlli sintetici già passati: 24/24 Node, 12/12 casi puri Deno, typecheck strict dell’helper e `deno check` del gestore.

Questo è il pacchetto di revisione tracciato. Helper, test, runner e configurazione TypeScript conservano gli stessi byte verificati in `artifacts/s02-backend-preparation`. Dal gestore permanente è stata rimossa una riga vuota finale e la patch è stata rigenerata: questi due file hanno nuovi hash. I percorsi relativi del runner non cambiano e risolvono lo stesso TypeScript del checkout fratello. Node e Deno check sono stati ripetuti sul gestore corretto. `.gitattributes` mantiene LF, come nel pacchetto update-prices esistente; `verification.json` distingue le prove storiche da quelle del pacchetto permanente.

## Sorgente osservato

Nell’audit iniziale due GET Management autorizzate hanno letto esclusivamente metadati e corpo di `update-tcg-prices` nel progetto atteso. La funzione allora osservata era ACTIVE, versione 17, ID `5a4e22f8-4203-4f1f-a0ce-08df296a5125`, `verify_jwt: false`. Identità e flag sono rimasti invariati nella versione 18. Il gestore applica una propria verifica del secret cron: il flag non rende autorizzata una chiamata anonima.

`artifacts/s02-backend-preparation/deployed-backup/body.eszip` conserva 250834 byte, SHA-256 `d7dde8d167a814a05d21c5daad0ca810f943e1fa8bf9f5378a9676219adf577e`. Il modulo estratto da `sourcesContent` nella source map coincide byte per byte con `../Savely/supabase/functions/update-tcg-prices/index.ts`, SHA-256 `3ea21a177a8184de608e1ba71f4edbb8873d6682850a2cc4c05352f8144ecfd7`. Il checkout web è fermo a `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. Metadati, offset e hash sono nei JSON del backup ignorato. Nuove letture hanno confermato questa stessa preimmagine prima del POST; le GET non costituiscono un CAS atomico del deployment.

## Difetti dimostrati dal codice

| Punto del sorgente web/deployed | Caso concreto | Comportamento precedente | Proposta |
| --- | --- | --- | --- |
| SELECT intorno a riga 151; assegnazione 179–185 | Record USD, `current_price=99.99`; offerta `price_cents=1250`, `price_currency=EUR` | Scrive 12.50 nel campo interpretato dal client come USD; la SELECT non legge currency. | Salta record non EUR prima del provider e conserva il prezzo. SQL NULL mantiene la convenzione legacy EUR dei client. |
| Selezione prodotto 84–106 | Chiave risposta 42 contiene `blueprint_id=43`, oppure centesimi dichiarati USD | Non verifica identità o valuta prima della divisione per 100. | Richiede ID blueprint numerico canonico e prodotto dello stesso blueprint; usa importo e valuta della stessa coppia. Nessuna ricerca o conversione implicita. |
| UPDATE 182–185 | Dopo la lettura cambiano owner, blueprint, condizione, valuta, prezzo manuale o updated_at | Filtra soltanto UUID e può sovrascrivere la nuova preimmagine. | CAS su UUID, owner e tutti i campi selezionati che determinano quotazione/preimmagine; zero righe modificate è un salto concorrente, non un aggiornamento riuscito. |
| Lettura centesimi 101–106 e SQL DECIMAL(10,2) | Centesimi frazionari, negativi, stringa o oltre 9999999999 | Nessuna validazione locale; la scrittura dipende da coercizioni/errori DB. | Solo interi rappresentabili, inclusi zero e massimo SQL. Stringa decimale esatta da BigInt; nessun arrotondamento di valori monetari già espressi in centesimi. |

I casi della tabella sono deduzioni dal sorgente, non prove eseguite su dati remoti.

## Contratto della proposta

`price-contract.ts` contiene le sole guardie di identità/unità, la selezione esistente CT Zero/condizione e il CAS. `index.ts` conserva controllo cron, scope globale, endpoint e contatori `updated/skipped/failed/total`. Aggiunge i contatori aggregati `skippedCurrency`, `skippedInvalid`, `skippedConcurrent` e una versione del contratto. Non stampa righe, utenti, offerte o risposte remote. Un errore su una carta incrementa failed e non attiva retry.

Il provider può esporre `price_cents/price_currency` e `price.cents/price.currency` diversi: la proposta conserva la coppia raw precedente quando presente, richiedendone EUR. Usa la coppia nested documentata soltanto quando entrambi i campi raw sono assenti. Una coppia incompleta o una risposta con prodotto/valuta non validi fallisce la carta intera. Non combina centesimi raw con valuta nested, né deduce la valuta da `formatted` o dal simbolo visuale. La fonte ufficiale descrive [i campi marketplace](https://www.cardtrader.com/en-US/docs/api/full/reference) e mostra [un esempio con le due coppie differenti](https://www.cardtrader.com/en-US/docs/api/full/how-to-buy?embedded=true). Nell’audit preparatorio sono state lette le pagine di documentazione senza chiamare endpoint provider. Il successivo campione provider live appartiene al proxy separato, non a un’esecuzione autorizzata di questo cron.

Lettura HTTP provider limitata a 1 MiB, timeout 10 secondi, redirect vietati. La pausa esistente di 200 ms resta; la proposta non qualifica i rate limit effettivi. Le categorie e gli UUID del record restano invariati. Record legacy con card_id non numerico vengono saltati, senza tentare un resolver per nomi o simboli.

## Verifica locale eseguita

Dodici casi puri in `price-contract_test.ts` e dodici casi gestore in `run-offline.mjs`: 24/24 superati al primo tentativo Node, zero fallimenti. I fake verificano EUR/NULL/USD, centesimi/limiti, identità blueprint, coppie valuta distinte, fallback CT Zero/condizione, auth cron, SELECT fallita prima del provider, CAS per sei cambi concorrenti, errore UPDATE, 503 e risposta troppo grande senza retry. Nessun token reale o client Supabase viene importato dal runner; il caricamento remoto createClient viene sostituito prima della compilazione TypeScript locale. I timer da 200 ms vengono ridotti soltanto nel fake.

Comandi locali eseguiti dopo l'autorizzazione ai test:

```powershell
node artifacts/s02-backend-preparation/run-offline.mjs
node ../Savely/node_modules/typescript/bin/tsc --project artifacts/s02-backend-preparation/tsconfig.offline.json --pretty false
artifacts/runtime/deno-v2.9.7/deno.exe check --no-config --no-lock --no-npm --node-modules-dir=none --cached-only --allow-import=esm.sh artifacts/s02-backend-preparation/index.ts
artifacts/runtime/deno-v2.9.7/deno.exe test --no-config --no-lock --no-npm --node-modules-dir=none --cached-only --deny-net --deny-env --deny-read --deny-write --deny-run --deny-ffi --deny-sys --deny-import --no-prompt artifacts/s02-backend-preparation/price-contract_test.ts
```

Quelli sopra sono i comandi storici realmente eseguiti in staging. Gli equivalenti del pacchetto permanente sono sotto. Durante la copia iniziale non sono stati rieseguiti; Node e Deno check sono stati eseguiti successivamente alla correzione EOF descritta nella sezione seguente. Le prove strict e Deno puro restano riferite agli stessi byte dell'helper e dei suoi test.

```powershell
node backend-proposals/update-tcg-prices/run-offline.mjs
node ../Savely/node_modules/typescript/bin/tsc --project backend-proposals/update-tcg-prices/tsconfig.offline.json --pretty false
artifacts/runtime/deno-v2.9.7/deno.exe check --no-config --no-lock --no-npm --node-modules-dir=none --cached-only --allow-import=esm.sh backend-proposals/update-tcg-prices/index.ts
artifacts/runtime/deno-v2.9.7/deno.exe test --no-config --no-lock --no-npm --node-modules-dir=none --cached-only --deny-net --deny-env --deny-read --deny-write --deny-run --deny-ffi --deny-sys --deny-import --no-prompt backend-proposals/update-tcg-prices/price-contract_test.ts
```

Il typecheck strict usa `types: []` ed esamina soltanto `price-contract.ts`, senza tipi ambientali dei pacchetti installati. Deno 2.9.7 ha eseguito gli stessi 12 casi puri con tutti i permessi runtime negati: non sono 12 nuovi casi distinti dai 24 Node. Il test Deno riporta 71 ms. `deno check` ha controllato il gestore senza eseguirlo, con import limitati a esm.sh e `--cached-only`; il log conserva le righe di risoluzione del SDK 2.117.3. Questi controlli non identificano il runtime Edge effettivamente distribuito. Tutti e quattro i comandi hanno exit code 0 al primo tentativo; nessuna correzione ai sorgenti o alle fixture è stata necessaria.

## Correzione EOF e verifica del pacchetto permanente

Il controllo precommit ha segnalato `index.ts:176: new blank line at EOF`, exit 2. È stata rimossa esattamente l'ultima LF, conservando la newline che termina il file. Helper, test, runner e configurazione non sono cambiati; staging e log originali restano immutati. `upstream.patch` è stata rigenerata dal web pinned e dallo stesso helper.

Il nuovo index.ts ha SHA-256 `27890cd72ddea8a84d44dfa710340ff8e0ca334c3fea343500e8da7513a04219`; la nuova patch ha SHA-256 `46a33bf2efb21e3c15a8ff0d6613ecdf4c19e7394e8f95c36f5ea5054068566b`. Non sono dichiarati byte-identici alle copie di staging. Sui percorsi permanenti Node ha rieseguito 24/24 casi con zero fallimenti e Deno check è terminato con exit 0. Non sono stati ripetuti i 12 test puri Deno o il typecheck strict dell'helper immutato.

Le nuove evidenze sono `artifacts/s02-package-review/cron-eof/whitespace-initial.log`, `formatting-delta.json`, `node-final.log` e `deno-check-final.log`; verification.json registra i percorsi completi e gli hash. La correzione non modifica il comportamento del gestore e non costituisce un deploy o un'invocazione remota.

`upstream.patch` modifica soltanto il gestore e aggiunge l'helper nella directory web della funzione. Non è stato applicato. `verification.json` conserva comandi, esiti e hash; `artifacts/s02-backend-preparation/verification-pre-execution.json` mantiene lo stato precedente. Tutti i log e il backup restano nei percorsi ignorati originali, non nel pacchetto da distribuire. Le prove non sostituiscono quelle separate di `backend-proposals/update-prices`.

## Deployment autorizzato e limiti

I prezzi precedenti non hanno provenienza memorizzata: nessuna correzione, conversione o riscrittura automatica può stabilire se un current_price legacy sia già EUR o USD. Le righe non EUR restano escluse dagli aggiornamenti automatici e richiedono revisione manuale. NULL è un'assunzione legacy esplicita condivisa dai client, non una ricostruzione della valuta originale.

Il servizio resta cron globale con chiave privilegiata; questo pacchetto non introduce una modalità QA o autorizzazione utente. CAS impedisce la sovrascrittura delle preimmagini selezionate, non rende atomica l'intera scansione. Paginazione, quote live, disponibilità del secret, tempi reali, condizioni/lingue/foil e prezzi minimi effettivi non sono qualificati. Rimane il fallback precedente ad altre condizioni o venditori quando mancano corrispondenze; non viene presentato come prezzo garantito per una specifica condizione.

L’utente ha poi autorizzato esplicitamente questo deployment, readback e rifiuto senza secret, insieme a un rollback circoscritto se fosse emerso un difetto introdotto. Un solo POST ha portato il servizio da v17 a v18; nessun rollback è stato necessario o provato. Nessuna modifica di segreti, schema, RLS, schedulazione o app è stata eseguita. `verification.json` resta il manifesto storico antecedente al deployment; il consuntivo operativo e i nuovi hash sono in `DEPLOYMENT_REPORT.md`.

Il proxy diretto ha un pacchetto separato in `backend-proposals/cardtrader-proxy`: Android chiama quel servizio per i prezzi, non questo cron. Correggere uno solo dei due non qualifica l'intero percorso TCG. Distribuire questa funzione modifica il comportamento delle successive esecuzioni schedulate per tutti gli utenti, anche se la verifica proposta non invoca il cron. Questo effetto sulle successive esecuzioni schedulate era incluso nell’autorizzazione. La verifica eseguita, descritta in `DEPLOYMENT_PLAN.md`, comprende readback e rifiuto senza secret; non comprende un’esecuzione manuale autorizzata del cron o scritture su carte cliente.
