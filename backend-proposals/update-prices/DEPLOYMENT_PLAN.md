# Deployment proposto di update-prices

Piano non eseguito. Serve autorizzazione separata per sostituire il servizio condiviso del progetto `crqnfbahytzenisospcx`. Il pacchetto non cambia schema, segreti, cron o configurazione delle altre funzioni.

## 1. Bloccare la preimmagine e conservare il backup

Prima di qualsiasi scrittura, rileggere tramite Management API solo `update-prices`: `GET /v1/projects/{ref}/functions/update-prices` e `GET /v1/projects/{ref}/functions/update-prices/body`. Richiedere ID `ebab8b27-d721-4b3b-80a9-c8945de4691f`, slug/nome `update-prices`, stato `ACTIVE`, versione **16** e `verify_jwt: false`. Se uno di questi valori è cambiato, fermarsi e rivedere il nuovo stato.

Salvare metadati completi e corpo ESZIP, con creazione esclusiva e SHA-256, in una nuova directory ignorata del run. Il backup già osservato contiene 271341 byte e ha SHA-256 `1348961e3f0e8f62a38d2fd16277da38a97b0d55b17283cb32cf47b669d3b01d`; confrontarlo con la nuova lettura prima di proseguire. Conservare anche `entrypoint_path`, eventuale import map e relativi metadati. Non ricostruire il rollback dal solo sorgente web: l'ESZIP conserva le dipendenze effettivamente distribuite.

La versione 16 resta una preimmagine archiviata. Un deployment, anche di rollback, genera una nuova versione: non si promette di mantenere il numero 16 sul servizio aggiornato. Le API documentate non espongono un compare-and-swap sulla versione; eseguire una seconda lettura immediatamente prima dell'invio e interrompere in caso di differenze. Rimane una finestra di concorrenza tra lettura e deployment, da evitare coordinando l'unico operatore.

## 2. Preparare un unico invio

Verificare nuovamente gli hash `files` di `verification.json`. Preparare `POST /v1/projects/{ref}/functions/deploy?slug=update-prices`, multipart con due campi `file`: filename `index.ts` e filename `quote-contract.ts`, entrambi con i byte LF del pacchetto. Non inviare test, runner o documentazione. Il campo JSON `metadata` deve essere:

```json
{
  "entrypoint_path": "index.ts",
  "name": "update-prices",
  "verify_jwt": false
}
```

La risoluzione relativa dell'helper deve restare `./quote-contract.ts`. Non modificare i segreti e non sostituire il blocco JWT/cron. Il token Management rimane nella memoria del processo host, senza argomenti shell, log o file. Vietare redirect HTTP e retry automatici del POST. Preparare una prenotazione durevole del singolo invio, con hash dei file e preimmagine, prima di trasmetterlo. Il parametro documentato `bundleOnly=1` permette di preparare un bundle senza persisterlo, ma richiede comunque una chiamata Management: non è stato usato in questa verifica e non è autorizzato implicitamente dal presente piano.

## 3. Riconciliare senza ripetere il deployment

Attendere HTTP 201, quindi rileggere metadati e corpo della sola funzione. Richiedere stesso ID/slug/nome, stato `ACTIVE`, versione successiva alla preimmagine, `verify_jwt: false` e un bundle che contenga i due moduli previsti. Registrare versione e SHA-256 effettivi, senza assumere un incremento numerico specifico. Una risposta persa o 5xx può seguire un deployment riuscito: rileggere lo stato prima di decidere; non ritentare il POST.

I controlli Management non chiamano il gestore e non aggiornano prezzi. Un successivo smoke live deve essere autorizzato separatamente, limitato a righe QA riservate e proprietario QA con JWT utente. Non usare il percorso cron globale per la qualifica. Gli esiti attuali sono 28/28 offline Node, 18/18 Deno e check Deno del gestore; non dimostrano disponibilità dei provider o correttezza del bundle remoto.

## 4. Ripristino del servizio

Preparare prima dell'invio la richiesta di rollback al medesimo ID/slug: `PATCH /v1/projects/{ref}/functions/update-prices`, corpo binario ESZIP del backup con `Content-Type: application/vnd.denoland.eszip`, `verify_jwt=false` esplicito nei parametri e `entrypoint_path` originale codificato come parametro. Conservare gli eventuali parametri di import map realmente presenti nel backup. Il rollback richiede autorizzazione operativa; non eseguirlo automaticamente dopo una risposta ambigua.

Prima del rollback, verificare che la versione e il corpo attuali corrispondano al deployment di questo run, così da non sovrascrivere un successivo intervento altrui. Prenotare una sola richiesta e non ripeterla dopo un esito incerto. Dopo, rileggere corpo/metadati e verificare la corrispondenza al backup e `verify_jwt: false`. Il ripristino del codice non annulla prezzi, storico o log già confermati: nessun cleanup automatico dei dati è incluso.

## Fonti

Contratti ufficiali: [deploy multipart e bundleOnly](https://supabase.com/docs/reference/api/v1-deploy-a-function), [metadati della funzione](https://supabase.com/docs/reference/api/v1-get-a-function), [download del corpo](https://supabase.com/docs/reference/api/v1-get-a-function-body), [aggiornamento della funzione](https://supabase.com/docs/reference/api/v1-update-a-function). Il tipo binario `application/vnd.denoland.eszip` e i campi multipart sono verificati anche nello [schema OpenAPI ufficiale](https://github.com/supabase/supabase/blob/master/apps/docs/spec/api_v1_openapi.json).
