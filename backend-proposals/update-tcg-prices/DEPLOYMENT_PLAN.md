# update-tcg-prices: procedura applicata e vincoli di rollback

Deployment concluso il 14 ottobre 2026, versione 17 → 18 ACTIVE, dopo autorizzazione esplicita e distinta dell'utente. L'approvazione comprendeva il servizio condiviso e il suo uso nelle successive esecuzioni schedulate, readback, rifiuto senza secret e rollback circoscritto. Non comprendeva un'invocazione manuale autorizzata del cron o scritture di prova sulle carte cliente. Il [rapporto](DEPLOYMENT_REPORT.md) conserva esiti, hash e limiti.

## 1. Preimmagine e backup

Il run esclusivo `7f2e85e3-79ac-420b-8997-666ee87a2feb` ha confermato ID `5a4e22f8-4203-4f1f-a0ce-08df296a5125`, slug/nome update-tcg-prices, ACTIVE, versione 17, verify_jwt false ed ESZIP di 250834 byte, SHA-256 `d7dde8d167a814a05d21c5daad0ca810f943e1fa8bf9f5378a9676219adf577e`.

Due letture complete hanno conservato metadati e bundle con scritture esclusive e fsync, senza stampare il token Management. La preimmagine non aveva import map. I due tentativi preliminari fermati sui campi Management aggiuntivi/assenti restano conservati senza POST; il confronto del run conclusivo ha fissato esattamente la forma corrente, mantenendo gli hash approvati.

I nuovi backup sono in `artifacts/s02-deploy/7f2e85e3-79ac-420b-8997-666ee87a2feb/update-tcg-prices/`; quello storico resta in `artifacts/s02-backend-preparation/deployed-backup/`. Un ripristino deve usare il bundle completo, non ricostruire le dipendenze dal solo sorgente web.

## 2. Invio singolo

I soli index.ts e price-contract.ts sono stati confrontati con gli hash approvati e con il commit `ffd04b4968080cd0c59584f9a588fc3f6f208e06`. La versione distribuita comprende la sola correzione EOF già verificata nel pacchetto permanente. Metadati e corpo sono stati riletti immediatamente prima del POST, dopo la prenotazione durabile con hash del payload:

```text
POST /v1/projects/crqnfbahytzenisospcx/functions/deploy?slug=update-tcg-prices
metadata: {"entrypoint_path":"index.ts","name":"update-tcg-prices","verify_jwt":false}
file: index.ts
file: price-contract.ts
```

Un solo POST, HTTP 201; niente retry, redirect o bundleOnly. Nessun test, runner o documento nel payload. Management non offre CAS atomico tra lettura e invio: l'esclusiva locale non viene presentata come un blocco remoto delle modifiche concorrenti.

## 3. Readback e diniego senza cron autorizzato

Readback ACTIVE v18, stesso ID/slug/nome e verify_jwt false. Il parser ESZIP ha confrontato byte per byte gli originali dei due moduli nelle source map con i file approvati, conservando anche gli hash dei moduli compilati. La GET finale dopo il canary conferma gli stessi metadati.

Una GET senza Authorization e senza x-cron-secret ha restituito HTTP 401, Unauthorized. Il gestore distribuito ferma quel percorso prima della SELECT carte/provider. Nessun secret cron o chiave CardTrader è stato letto, rigenerato o inviato. Non è stata eseguita una chiamata con secret valido, neppure passando un user_id QA: il gestore rimane globale e non ha uno scope QA.

Il canary autenticato ricerca/prezzo appartiene al distinto cardtrader-proxy, non qualifica una scansione o una scrittura del cron. La sua sessione QA A è stata chiusa con logout locale HTTP 204. Lo snapshot comune finale conferma zero sessioni non scadute, fattori e carte dei tre QA, proiezioni identità/profili selezionate invariate e registrazioni disabilitate.

Le prove offline precedenti restano 24/24 Node e 12/12 casi puri Deno, non rieseguite durante il deployment. Non sono state eseguite prove offline aggiuntive sui sorgenti estratti: la prova è la loro identità esatta con i file già verificati.

## 4. Rollback predisposto e limiti

Il rollback era autorizzato soltanto per un difetto introdotto dal deployment; non è stato necessario o provato. Indisponibilità provider o rifiuto di quotazioni non EUR non giustificherebbero il ripristino del pricing senza guardie.

Prima di un eventuale ripristino occorre richiedere che versione e bundle correnti appartengano ancora a questo run. La preparazione conserva una PATCH al solo endpoint `/v1/projects/crqnfbahytzenisospcx/functions/update-tcg-prices`, corpo ESZIP originale completo, Content-Type application/vnd.denoland.eszip, verify_jwt false, entrypoint_path originale e parametri import map effettivi. Occorrerebbero prenotazione singola e readback: nessuna ripetizione automatica o sovrascrittura di un intervento successivo. Il ripristino genera una nuova versione e non annulla prezzi eventualmente scritti da processi schedulati.

Nessuna conversione, riparazione o cancellazione di dati cliente, modifica di schema, RLS, segreti o schedulazione è stata eseguita. Paginazione, scansioni globali effettive e prezzi legacy restano limiti distinti; SQL NULL conserva la convenzione legacy EUR senza ricostruirne la provenienza.
