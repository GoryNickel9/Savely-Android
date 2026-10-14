# Deployment proposto: update-tcg-prices

Non eseguito. Serve un'autorizzazione esplicita e distinta per sostituire il cron condiviso `update-tcg-prices`. Quella relativa a update-prices non comprende questo servizio; cardtrader-proxy ha un secondo pacchetto e una seconda autorizzazione. Nessuna modifica di segreti, schema, RLS o schedulazione è proposta.

## 1. Preimmagine e backup

Dopo l'autorizzazione, riservare una nuova directory esclusiva del run e leggere soltanto metadati e corpo tramite GET `/v1/projects/crqnfbahytzenisospcx/functions/update-tcg-prices` e `/body`. Richiedere ID `5a4e22f8-4203-4f1f-a0ce-08df296a5125`, slug/nome `update-tcg-prices`, ACTIVE, versione 17, verify_jwt false e corpo ESZIP SHA-256 `d7dde8d167a814a05d21c5daad0ca810f943e1fa8bf9f5378a9676219adf577e`. Se uno dei valori cambia, fermarsi e rivedere il nuovo stato.

Il backup originale, con 250834 byte, resta in `artifacts/s02-backend-preparation/deployed-backup/`. La nuova lettura deve conservare metadata completi, entrypoint_path, eventuali parametri import map effettivi, corpo e hash, senza stampare il token Management. La preimmagine osservata non ha import map. Non ricostruire un rollback dal solo sorgente web: il bundle conserva le dipendenze distribuite.

## 2. Un solo invio

Verificare gli hash di `backend-proposals/update-tcg-prices/index.ts` e `price-contract.ts` contro verification.json, poi rileggere immediatamente metadati/corpo per confermare la stessa preimmagine. Coordinare un solo operatore: Management non offre un CAS atomico di versione sul deployment.

Prenotare durevolmente un singolo POST con preimmagine e hash. Inviare POST `/v1/projects/crqnfbahytzenisospcx/functions/deploy?slug=update-tcg-prices`, multipart con i soli due file index.ts e price-contract.ts, metadata `{"entrypoint_path":"index.ts","name":"update-tcg-prices","verify_jwt":false}`. Mantenere l'import relativo `./price-contract.ts`. Non inviare test, runner o documenti. Token solo RAM, redirect vietati, nessun retry, bundleOnly o altro slug.

La verifica cron `x-cron-secret` resta nel gestore e verify_jwt conserva false. Non leggere né rigenerare il secret cron o la chiave CardTrader. La funzione continuerà ad avere scope globale: il codice aggiornato sarà usato anche dalle future esecuzioni già schedulate. Questo effetto deve essere approvato dall'utente prima dell'invio.

## 3. Readback senza eseguire il cron

Rileggere metadati e corpo della sola funzione: stesso ID/slug/nome, ACTIVE, versione successiva e verify_jwt false. Verificare i due moduli del pacchetto nel bundle e il mantenimento del controllo cron prima della SELECT delle carte/provider. Conservare hash e fonte distribuita effettivi. Dopo risposta persa o 5xx non ripetere il POST: osservare lo stato e fermarsi se la riconciliazione resta incerta.

Il campione di verifica proposto comprende readback del codice e prove offline dei moduli effettivamente ricavati dal bundle. Non invocare la funzione con `x-cron-secret`, nemmeno passando user_id QA nel body: il gestore non ha un filtro di scope QA e leggerebbe/aggiornerebbe carte di tutti gli utenti. Nessun provider live, SELECT cliente o scrittura di prezzo è inclusa in questo piano.

Solo se esplicitamente autorizzato insieme al deployment, si può aggiungere una richiesta senza JWT e senza x-cron-secret per osservare il rifiuto. Con le variabili provider configurate il codice restituisce 401 prima della SELECT carte; una risposta diversa richiede analisi e non autorizza a riprovare con il secret. Il test non qualifica i prezzi provider o una scansione globale. Il canary autenticato ricerca/prezzo appartiene al distinto piano cardtrader-proxy e non scrive tgc_cards.

## 4. Rollback revisionabile

Nessun rollback automatico. Prima di un rollback autorizzato, richiedere che versione/corpo attuali corrispondano al deployment di questo run, per non sovrascrivere modifiche successive. Prenotare una sola PATCH `/v1/projects/crqnfbahytzenisospcx/functions/update-tcg-prices`, corpo ESZIP originale, Content-Type application/vnd.denoland.eszip, verify_jwt=false ed entrypoint_path originale nei parametri, preservando eventuali parametri import map effettivi. Rileggere poi metadati/corpo e confrontare col backup. Non promettere il ritorno al numero 17: un rollback genera una nuova versione.

Il ripristino del codice non annulla eventuali prezzi scritti da esecuzioni schedulate già avvenute. Nessuna conversione, riparazione o cancellazione di dati cliente è prevista. Origine e unità dei prezzi legacy restano un limite esplicito.

La forma delle richieste Management segue il piano già revisionato di update-prices; l'operatore deve verificarla contro il contratto API corrente prima dell'invio autorizzato. Il presente documento è una proposta per revisione, non un'autorizzazione né un log di esecuzione.
