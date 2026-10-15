# S02 — contratto diretto cardtrader-proxy v2

Distribuito come versione 18 ACTIVE il 14 ottobre 2026, dopo autorizzazione esplicita dell’utente. Readback dei due sorgenti esatto; campione QA A ricerca/quotazione e rifiuto senza JWT superati, logout locale confermato e baseline QA ripristinata. Il [rapporto di deployment](DEPLOYMENT_REPORT.md) distingue queste prove dai controlli sintetici precedenti: 28/28 Node, 16/16 casi puri Deno, typecheck strict dell’helper e Deno check del gestore. Le modifiche Android V12 restano soltanto sorgenti proposti, non compilati o eseguiti.

Questo è il pacchetto di revisione tracciato. Gestore, helper, test, runner, configurazione TypeScript e patch hanno gli stessi byte dei file verificati in `artifacts/s02-proxy-preparation`. Il trasferimento non ha richiesto modifiche ai percorsi del runner: entrambe le directory risolvono `../../../Savely/node_modules/typescript` allo stesso checkout fratello. Non sono stati ripetuti test sui percorsi permanenti. `.gitattributes` mantiene LF come nel pacchetto update-prices esistente. Gli hash delle copie e i riferimenti alle prove originali sono in `verification.json`.

`CatalogRepository.cardPrice` chiama `cardtrader-proxy?action=prices`. Il cron `update-tcg-prices` è un secondo servizio, distribuito nello stesso run con autorizzazione distinta. La precedente autorizzazione di update-prices non comprendeva questi due servizi. Il campione proxy non ha scritto carte; il cron ha ricevuto soltanto una richiesta senza JWT e senza secret, rifiutata prima della lettura carte.

## Preimmagine e difetto

L’audit iniziale ha effettuato due GET Management, metadati e corpo di `cardtrader-proxy`, senza letture di clienti, chiamate CardTrader, lookup della chiave provider o scritture remote. La versione allora osservata era 17 ACTIVE, ID `23c851ba-f056-4fe2-a04d-52a8536a266a`, `verify_jwt: false`. Il codice verifica il JWT tramite `auth.getUser()`; questa verifica resta invariata nella versione 18.

Il backup `artifacts/s02-proxy-preparation/deployed-backup/body.eszip` contiene 262925 byte, SHA-256 `abe60a7cef81679b9b4aee5734a66dee784e4e2a65b2f7be4a699de4bb4dbb65`. Il modulo estratto dalla source map coincide byte per byte con il web pinned `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, SHA-256 sorgente `715c2e5b6e940de1efec3ea4cdbc15f8b415c46b56086e2207fe531f7348bfbb`. Metadati, timestamp, offset e hash sono nei JSON del backup ignorato. Le due letture non costituiscono una transazione o un blocco dei deployment concorrenti.

Il ramo prezzi precedente seleziona il minimo `price_cents`, senza validare `blueprint_id` o valuta. Restituisce `ctZeroLowPriceCents` senza unità e può affiancargli `price.formatted` appartenente a una diversa valuta. Il parser nativo usa `getDouble`, accetta centesimi frazionari e divide per 100 assumendo EUR. Con `price_cents=1250`, `price_currency=USD`, l'app ottiene 12.50 come prezzo automatico EUR. È una deduzione dal codice, non una mutazione o una prova provider eseguita.

La [documentazione ufficiale marketplace](https://www.cardtrader.com/en-US/docs/api/full/reference) descrive identità blueprint e prezzo; l'[esempio ufficiale](https://www.cardtrader.com/en-US/docs/api/full/how-to-buy?embedded=true) contiene raw EUR e nested USD nello stesso prodotto. Le due coppie non sono intercambiabili.

## DTO v2

```json
{
  "quoteContractVersion": 2,
  "blueprintId": "42",
  "currency": "EUR",
  "minorUnit": 2,
  "ctZeroLowPrice": "€12.50",
  "ctZeroLowPriceCents": 1250,
  "totalListings": 1
}
```

Il blueprint richiesto deve essere una stringa numerica positiva canonica, massimo 15 cifre. Ogni prodotto sotto la chiave richiesta deve dichiarare lo stesso blueprint numerico. La coppia raw `price_cents/price_currency`, quando presente, deve essere completa ed EUR. La coppia nested `price.cents/price.currency` viene usata soltanto quando entrambi i campi raw sono assenti. Non si combinano importo di una coppia e valuta dell'altra. Un prodotto non valido o con valuta diversa rifiuta la quotazione intera; non viene scartato in silenzio per scegliere un'altra offerta.

I centesimi devono essere interi numerici tra 0 e 9999999999, coerenti con NUMERIC(10,2). L'helper passa per BigInt e non arrotonda centesimi frazionari. `ctZeroLowPrice` viene rigenerato dai centesimi EUR validati, ignorando il testo formattato provider. La selezione CT Zero, condizione esatta e fallback precedenti restano invariati. La condizione omessa conserva il significato precedente; condizioni sconosciute sono ora rifiutate prima del provider.

Solo l'array vuoto esplicito del blueprint richiesto produce cents e testo null, totalListings 0, conservando versione, identità e unità. Campo mancante, risposta malformata, unità ignota, 503 o esito di rete incerto sono errori, senza retry. Il ramo prezzi limita il JSON a 1 MiB, timeout 10 secondi e vieta redirect. Restituisce codici fissi con HTTP 502 senza includere corpo provider o credenziali.

I tre campi consumati dal web precedente restano presenti. L'array ricerca, mapping immagine/set, cache espansioni, paginazione e verifica JWT non vengono modificati. Il proxy non scrive sul database. Le fixture coprono mapping e pagine; il campione live ha cercato soltanto Black Lotus e non qualifica l’intero catalogo.

## Verifica locale eseguita

| Controllo | Esito | Evidenza |
| --- | --- | --- |
| Node: 16 casi puri + 12 gestore | 28/28, zero failure, exit 0 | `artifacts/s02-proxy-preparation/offline-final.log` |
| TypeScript 5.8.3, helper strict, types [] | Exit 0, nessuna diagnostica | `artifacts/s02-proxy-preparation/strict-typecheck-attempt1.log` |
| Deno 2.9.7 check del gestore | Exit 0, nessuna esecuzione del gestore | `artifacts/s02-proxy-preparation/deno-check-attempt1.log` |
| Deno, stessi 16 casi puri, permessi runtime tutti negati | 16/16, zero failure, 464 ms | `artifacts/s02-proxy-preparation/deno-test-final.log` |

I 16 casi Deno ripetono quelli puri Node: non sono 16 nuovi requisiti distinti. I fake coprono auth negata, nessuna chiamata provider prima dell'autenticazione, blueprint e condizioni invalidi, unità e centesimi, 503/risposte malformate/troppo grandi senza retry, compatibilità della ricerca. `createClient` viene sostituito prima di importare il gestore Node; nessun modulo remoto viene eseguito dal runner. Deno check usa `--cached-only` e consente soltanto import esm.sh; il SDK si risolve a 2.117.3. Questo runtime locale non identifica la versione Edge deployed.

Il primo Deno test si è fermato al typecheck della fixture (`TS18048`, options possibilmente undefined), prima di eseguire casi. La sola correzione è `options.headers` → `options?.headers`, mantenendo l'uguaglianza esatta dell'header atteso. Il log iniziale e il primo Node 28/28 restano conservati; Node e Deno sono stati rieseguiti dopo la modifica della fixture. Il contratto non è stato cambiato per ottenere il pass.

Comandi riproducibili:

```powershell
node artifacts/s02-proxy-preparation/run-offline.mjs
node ../Savely/node_modules/typescript/bin/tsc --project artifacts/s02-proxy-preparation/tsconfig.offline.json --pretty false
artifacts/runtime/deno-v2.9.7/deno.exe check --no-config --no-lock --no-npm --node-modules-dir=none --cached-only --allow-import=esm.sh artifacts/s02-proxy-preparation/index.ts
artifacts/runtime/deno-v2.9.7/deno.exe test --no-config --no-lock --no-npm --node-modules-dir=none --cached-only --deny-net --deny-env --deny-read --deny-write --deny-run --deny-ffi --deny-sys --deny-import --no-prompt artifacts/s02-proxy-preparation/quote-contract_test.ts
```

Quelli sopra sono i comandi storici effettivamente eseguiti in staging. Gli equivalenti dal repository, predisposti per il pacchetto permanente ma non rieseguiti durante la copia, sono:

```powershell
node backend-proposals/cardtrader-proxy/run-offline.mjs
node ../Savely/node_modules/typescript/bin/tsc --project backend-proposals/cardtrader-proxy/tsconfig.offline.json --pretty false
artifacts/runtime/deno-v2.9.7/deno.exe check --no-config --no-lock --no-npm --node-modules-dir=none --cached-only --allow-import=esm.sh backend-proposals/cardtrader-proxy/index.ts
artifacts/runtime/deno-v2.9.7/deno.exe test --no-config --no-lock --no-npm --node-modules-dir=none --cached-only --deny-net --deny-env --deny-read --deny-write --deny-run --deny-ffi --deny-sys --deny-import --no-prompt backend-proposals/cardtrader-proxy/quote-contract_test.ts
```

Il backup ESZIP, i metadati, i log e le proposte native restano soltanto nelle directory ignorate originali; non sono inclusi nel pacchetto da distribuire. I percorsi delle prove in verification.json sono relativi alla radice del repository e richiedono l'archivio locale delle evidenze.

## Android V12 proposto

`artifacts/s02-proxy-preparation/native-v12/native-active.patch` modifica soltanto CatalogRepository e i suoi test: DTO v2 obbligatorio, blueprint stringa esatta, EUR, minorUnit 2, cents numerici integrali nel limite SQL, conteggio listing coerente e null esplicito. Il parser riusa ExactJson/BigDecimal per non perdere frazioni minuscole attraverso Double. Mantiene la firma `Double?`, i messaggi tradotti già esistenti e gli altri percorsi Books/search. DTO legacy senza unità, stringhe numeriche, versioni ignote e blueprint diverso falliscono prima di ottenere un prezzo da salvare; non vengono convertiti in EUR.

Predisposti otto nuovi casi JVM del parser e due nuovi casi repository HTTP; aggiornati i due payload positivi/null del test esistente. Sono sorgenti non compilati e non eseguiti. `artifacts/s02-proxy-preparation/native-v12/v12-fixture-delta.patch` è un delta sulla copia congelata `artifacts/v12-preparation/TcgQualificationTest.kt`, non sul file attivo. Mantiene i tre metodi Android e aggiunge nel caso prezzo manuale/automatico quattro risposte unsafe (USD, blueprint diverso, centesimo frazionario e versione assente): errore visibile IT/EN, un solo GET per azione, zero PATCH e righe EUR/USD immutate; poi null e positivo v2. I risultati Android restano da acquisire dopo l'integrazione V12.

Il parser stretto rifiuterà il vecchio proxy v17. Perciò la sequenza proposta è prima proxy v2 autorizzato e verificato, poi parser Android; un rollback del solo proxy riporta gli aggiornamenti automatici della nuova app a errore esplicito, senza autorizzare quote legacy. Il prezzo manuale resta una funzione distinta. Nessun file attivo o staging V12 originale è stato modificato.

## Deployment autorizzato e limiti

Questo pacchetto riguarda soltanto cardtrader-proxy. Il pacchetto `backend-proposals/update-tcg-prices` riguarda il cron globale e conserva 24/24 Node e 12/12 Deno come prove separate, con origine in `artifacts/s02-backend-preparation`. L’utente ha approvato entrambi i deployment e i rispettivi campioni, separatamente dalla precedente autorizzazione update-prices. Non ha autorizzato un’invocazione manuale del cron globale.

`DEPLOYMENT_PLAN.md` riporta la procedura applicata e i vincoli del rollback, preparato ma non eseguito. Un solo POST ha portato il proxy da v17 a v18; il readback ESZIP contiene esattamente index.ts e quote-contract.ts revisionati nel commit `ffd04b4`. `verification.json` conserva il manifesto e le prove antecedenti al deployment; stato operativo e hash delle nuove evidenze sono nel rapporto. Token Management, password e JWT QA sono rimasti in RAM; nessuna chiave CardTrader o cron secret è stata letta.

Il campione live comprende una ricerca Black Lotus e una quotazione near_mint del blueprint restituito 37789: DTO v2, EUR, minorUnit 2, 350063 centesimi. Non qualifica tutti i prezzi/disponibilità, set, lingua/foil, eventuale condizione sostitutiva, rate limit, interoperabilità dei client, RLS o le esecuzioni cron successive. Anche il cron è ora v18, ma non è stato invocato con autorizzazione. I prezzi già salvati hanno provenienza ignota: nessuna conversione o riparazione dei dati esistenti è inclusa.
