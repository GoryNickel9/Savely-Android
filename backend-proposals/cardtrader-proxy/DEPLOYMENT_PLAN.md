# cardtrader-proxy: procedura applicata e vincoli di rollback

Deployment concluso il 14 ottobre 2026, versione 17 → 18 ACTIVE. L'utente ha approvato esplicitamente questo servizio, il campione QA di sola lettura e un rollback circoscritto per un difetto introdotto. Ha approvato separatamente update-tcg-prices; la precedente autorizzazione update-prices non comprendeva i due servizi S02. Esiti e hash sono nel [rapporto](DEPLOYMENT_REPORT.md).

## 1. Preimmagine e prenotazione

Il run esclusivo `7f2e85e3-79ac-420b-8997-666ee87a2feb` ha confermato ID `23c851ba-f056-4fe2-a04d-52a8536a266a`, nome/slug cardtrader-proxy, ACTIVE, versione 17, verify_jwt false ed ESZIP di 262925 byte, SHA-256 `abe60a7cef81679b9b4aee5734a66dee784e4e2a65b2f7be4a699de4bb4dbb65`.

Due letture hanno conservato metadati completi e bundle con scritture esclusive e fsync. La risposta Management effettiva espone import_map false ed ezbr_sha256, omettendo import_map_path; i due tentativi preliminari fermati dal confronto sono conservati, entrambi senza POST. Il run conclusivo ha fissato questa forma completa e gli stessi byte approvati. La preimmagine non aveva import map.

La prenotazione durabile contiene preimmagine, hash dei sorgenti e payload. I backup del run sono in `artifacts/s02-deploy/7f2e85e3-79ac-420b-8997-666ee87a2feb/cardtrader-proxy/`; il primo backup storico resta in `artifacts/s02-proxy-preparation/deployed-backup/`. Token Management solo RAM; redirect vietati.

## 2. Invio singolo e readback

I soli file index.ts e quote-contract.ts sono stati confrontati con gli hash revisionati e con il commit `ffd04b4968080cd0c59584f9a588fc3f6f208e06`. Metadati e corpo sono stati riletti immediatamente prima del POST multipart:

```text
POST /v1/projects/crqnfbahytzenisospcx/functions/deploy?slug=cardtrader-proxy
metadata: {"entrypoint_path":"index.ts","name":"cardtrader-proxy","verify_jwt":false}
file: index.ts
file: quote-contract.ts
```

Un POST, HTTP 201, nessun retry o bundleOnly. Readback ACTIVE v18, stesso ID e flag; gli originali dei due moduli nelle source map ESZIP coincidono byte per byte con i sorgenti inviati. La GET finale dopo il canary conferma gli stessi metadati. La sequenza GET/POST non offre un CAS atomico di versione: l'operatore ha mantenuto l'esclusiva locale, senza dichiarare un blocco remoto.

## 3. Campione QA concluso

Il preflight ha richiesto zero sessioni non scadute, fattori e carte dei tre QA fissi. Un solo login QA A ha verificato identità e SID, poi una ricerca Black Lotus e una quotazione near_mint del blueprint canonico restituito 37789. La risposta è HTTP 200, contratto v2, EUR, minorUnit 2 e 350063 centesimi interi. Una richiesta proxy senza Authorization è stata rifiutata con HTTP 401.

Logout della sola sessione creata, scope local, HTTP 204. Lo snapshot finale conferma baseline identica nelle proiezioni selezionate, zero sessioni/fattori/carte QA e registrazioni disabilitate. Nessuna scrittura tgc_cards, lista clienti o lettura di chiavi provider/cron. Il distinto cron ha ricevuto soltanto il diniego senza JWT/secret descritto nel suo piano.

## 4. Parser Android separato

Il proxy v2 è verificato. La proposta Android V12 resta in `artifacts/s02-proxy-preparation/native-v12/`, senza integrazione o build eseguita da questo run. La sua qualificazione rimane distinta; il campione non dimostra interoperabilità di tutti i client o correttezza dei prezzi legacy. I tre campi consumati dal web precedente restano presenti, mentre ricerca e verifica JWT applicativa conservano il contratto precedente.

## 5. Rollback predisposto, non eseguito

Il rollback era autorizzato soltanto per un difetto introdotto dal deployment. Nessun difetto osservato ne ha richiesto l'esecuzione. Provider indisponibile o rifiuto di offerte con valuta non EUR non giustificherebbero il ritorno al pricing senza unità.

Prima di qualsiasi ripristino occorre rileggere metadati e corpo, richiedendo che corrispondano ancora alla v18 e al bundle di questo run, senza sovrascrivere modifiche successive. La preparazione conserva una PATCH al solo endpoint `/v1/projects/crqnfbahytzenisospcx/functions/cardtrader-proxy`, ESZIP originale completo, Content-Type application/vnd.denoland.eszip, entrypoint_path originale e verify_jwt false, con i parametri import map effettivi. Un eventuale invio richiede prenotazione singola e readback; non è un comando da ripetere automaticamente. Il numero di versione aumenterebbe, senza tornare a 17. Il proxy non scrive prezzi DB; nessun rollback di dati è stato predisposto.
