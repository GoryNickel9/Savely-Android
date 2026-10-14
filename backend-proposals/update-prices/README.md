# Proposta per il contratto delle quotazioni

**28/28 test offline, 18/18 Deno e controllo del gestore superati. Deployment autorizzato e verificato: versione 17 ACTIVE, `verify_jwt=false`, moduli identici alla proposta. Il canary QA passa: AAPL USD dal form e rifiuto BTC USD dal client host, con baseline ripristinato; [rapporto](DEPLOYMENT_REPORT.md).**

La versione 16 precedente salvava il prezzo Yahoo senza leggerne la valuta e poteva cambiare borsa tramite fallback. Un prezzo USD poteva quindi finire in una posizione EUR. Anche il fallback CoinGecko alla prima ricerca poteva scegliere un'altra criptovaluta. La baseline web è `1cfcf6297e85d56bfafe3522362ceecef6f6b281`; il responsabile dell'integrazione ha verificato separatamente la stessa semantica nel bundle distribuito.

## Contenuto

- `upstream.patch`: diff per `supabase/functions/update-prices/index.ts`, con i nuovi `quote-contract.ts` e `quote-contract_test.ts` nello stesso percorso.
- I tre file TypeScript: proposta corrente, con lettura cash anticipata.
- `tsconfig.offline.json`: typecheck strict del solo `quote-contract.ts`, senza tipi ambientali dei pacchetti installati.
- `.gitattributes`: mantiene LF nel pacchetto, anche sui checkout Windows.
- `run-offline.mjs`: runner senza rete con provider, database, JWT e secret cron sintetici. Usa il TypeScript già installato nel repository fratello `Savely`, in sola lettura, e transpila in memoria.
- `verification.json`: esiti, runtime, hash dei file e riferimenti ai log locali conservati.
- `DEPLOYMENT_PLAN.md`: preimmagine versione 16, backup ESZIP, invio singolo eseguito e rollback preparato ma non eseguito.

## Comportamento proposto

Yahoo deve restituire il simbolo richiesto e una valuta esplicita uguale a quella della posizione. Il suffisso è parte del simbolo: non viene sostituito in caso di errore. `GBp` e `GBX` sono rifiutate senza convertirle in `GBP`. CoinGecko usa il mapping noto o un ID minuscolo esplicito; non esegue ricerche. Il suo prezzo EUR è ammesso solo per posizioni EUR. Una valuta DB esplicitamente nulla conserva la convenzione legacy EUR; campo mancante, valore vuoto e codici sconosciuti sono rifiutati.

Il servizio arrotonda HALF_UP a due decimali e invia lo stesso valore decimale all'asset e allo storico. Rifiuta prezzi che arrotondano a zero o superano `NUMERIC(15,2)`. La scrittura confronta UUID, proprietario, simbolo, tipo, valuta e `updated_at`, per evitare di applicare una quotazione a una riga modificata durante la lettura. Un errore dello storico resta un esito parziale dopo il possibile commit dell'asset; un errore del log restituisce HTTP 500. La funzione non ripete la mutazione.

Il blocco di autorizzazione JWT/cron è conservato testualmente. Il cash FX rimane separato, come valore EUR di un'unità della valuta, e viene elaborato anche quando non ci sono titoli con simbolo. La precisione resta quella della colonna a due decimali; un tasso che diventa zero è rifiutato.

Le SELECT titoli e cash ora precedono ogni richiesta ai provider e ogni scrittura. Se la lettura cash fallisce, la funzione restituisce HTTP500 senza aver aggiornato asset o storico: evita il precedente percorso che poteva confermare titoli e poi interrompersi prima del log.

La risposta conserva `message`, `checked`, `updated` ed `errors`; aggiunge `quote_contract_version: 2`. Gli errori conservano `symbol` ed `error` e aggiungono `asset_id`. `checked` conta stock/ETF/crypto; `updated` include anche il cash e può essere maggiore. Il ramo vuoto mantiene `No assets to update` e include `checked: 0`. Il contratto Android corrente accetta questi campi e richiede riconciliazione per errori non vuoti. Non sono proposte migrazioni o modifiche di configurazione.

## Verifica offline

Il responsabile dell'integrazione ha eseguito il runner staging; dopo la normalizzazione LF, è stata verificata anche la copia del pacchetto:

```powershell
node backend-proposals/update-prices/run-offline.mjs
```

Risultato della copia LF nel log `artifacts/v06-price-contract-preparation/package-lf-offline-run.log`: **28 eseguiti, 28 superati, zero fallimenti**, composti da 18 casi puri e 10 casi del gestore HTTP con database fittizio. Coprono valute incompatibili, simboli diversi, GBp/GBX, rounding, limiti, singola richiesta senza fallback, cash-only, cambio valuta prima del CAS, errori storico/log e isolamento JWT/cron. Questo run Node usa provider e database fittizi; non è una prova Supabase o provider reale.

Il nuovo caso del gestore simula il fallimento della SELECT cash e verifica zero richieste ai provider, zero UPDATE e zero scritture storico/log, con preimmagini immutate e HTTP500. I log originali 27/27 e 28/28 del preflight cash restano prove storiche separate. Il nuovo run 28/28 e il typecheck strict sono stati eseguiti sui file LF del pacchetto, i cui SHA-256 sono registrati in `verification.json`.

La copia revisionabile si esegue dalla radice Android con:

```powershell
node backend-proposals/update-prices/run-offline.mjs
```

Il percorso relativo del TypeScript è invariato perché entrambe le directory hanno due livelli sotto la radice Android. Non sono necessarie installazioni.

Il typecheck locale è stato eseguito con TypeScript 5.8.3 già presente nel repository web:

```powershell
node ../Savely/node_modules/typescript/lib/tsc.js --project backend-proposals/update-prices/tsconfig.offline.json --pretty false
```

Esito: exit code 0, zero diagnostiche. `strict: true`, `types: []` e `files: ["quote-contract.ts"]` isolano il controllo dai tipi ambientali Babel/undici. Il controllo comprende le API standard ES2022 e DOM; non qualifica i tipi Deno, il gestore `index.ts` o il client Supabase remoto.

Anche `git -C ../Savely apply --check -- <percorso-assoluto>/upstream.patch` è terminato con exit code 0 sulla baseline indicata, senza applicare la patch. Log e SHA-256 di entrambi i controlli sono in `verification.json`. Gli hash `files` sono calcolati sui byte LF del pacchetto; la regola locale `.gitattributes` ne conserva le terminazioni al checkout.

## Verifica Deno portabile

È stata scaricata una copia portabile di [Deno 2.9.7 dalla release ufficiale](https://github.com/denoland/deno/releases/tag/v2.9.7), senza installazione globale. Il digest dell'archivio coincide con quello pubblicato da GitHub; versione, SHA-256 dell'archivio e dell'eseguibile sono registrati in `verification.json`. Runtime e cache restano sotto `artifacts/runtime/deno-v2.9.7/`.

`deno check` del gestore `index.ts` è terminato con **exit code 0**, senza eseguirlo. Sono stati consentiti solo import remoti da `esm.sh`, con configurazione e lock discovery disabilitate e senza installazioni npm. L'import preesistente `@supabase/supabase-js@2` si è risolto a `2.117.3`; il sorgente mantiene lo stesso specifier non fissato della baseline. Questo controllo usa Deno 2.9.7 (TypeScript 6.0.3), non identifica la versione del runtime Supabase distribuito e non ne esegue il gestore.

`deno test quote-contract_test.ts` ha eseguito **18 test puri, 18 superati, zero fallimenti**. Il processo aveva rete, ambiente, lettura/scrittura di file, sottoprocessi, FFI, informazioni di sistema e import remoti negati; gli import locali erano caricati dal runtime. I provider erano funzioni sintetiche. I 10 casi del gestore con database fittizio appartengono al distinto run Node 28/28: non sono stati eseguiti nel runtime Edge reale.

I comandi completi e gli hash dei log sono in `verification.json`. Un primo invio di `deno check` ha rifiutato l'opzione CLI `--no-prompt`, prima di analizzare i sorgenti; il secondo, senza quella opzione non supportata da `check`, è passato. Il log iniziale è conservato.

## Limiti e approvazione

La proposta non ripara prezzi o storici già salvati nella valuta errata. Lo storico non ha una colonna valuta e non consente di ricostruire automaticamente la provenienza. Asset, storico e log restano scritture separate: un errore non implica rollback. Yahoo può fornire la chiusura precedente anziché un prezzo corrente, sempre dello stesso simbolo e nella valuta verificata. La lettura cron conserva la paginazione del servizio esistente; i dataset oltre il limite REST del progetto non sono qualificati.

Il deployment autorizzato e il canary QA sono conclusi; bundle, prezzo AAPL USD 345,53 e rifiuto BTC USD sono identificati nel [rapporto](DEPLOYMENT_REPORT.md). Il cleanup elimina soltanto sei record adottati e lascia zero sessioni/fattori QA, conservando 33 categorie e sei snapshot. Il campione non qualifica altri strumenti/valute, disponibilità futura o cron. Il piano operativo conserva `verify_jwt: false`, il backup della versione 16 e il confine JWT/cron esistente. La patch non è ancora applicata al repository web.
