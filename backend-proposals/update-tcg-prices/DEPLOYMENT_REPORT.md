# update-tcg-prices v18 — deployment e rifiuto senza secret

Distribuito il 14 ottobre 2026 con autorizzazione esplicita dell'utente, distinta da update-prices e cardtrader-proxy. L'autorizzazione comprendeva la sostituzione del servizio condiviso, quindi il suo impiego nelle successive esecuzioni schedulate; escludeva un'invocazione manuale autorizzata o scritture di prova sulle carte cliente. Il run `7f2e85e3-79ac-420b-8997-666ee87a2feb` ha eseguito un solo POST, HTTP 201.

## Sorgenti e readback

Il readback conferma versione 18 ACTIVE, stesso ID `5a4e22f8-4203-4f1f-a0ce-08df296a5125` e `verify_jwt:false`. I due file inviati coincidono con il commit revisionato `ffd04b4968080cd0c59584f9a588fc3f6f208e06`. Gli originali nelle source map ESZIP coincidono byte per byte; la prova conserva anche gli hash dei moduli compilati.

| Oggetto | Byte | SHA-256 |
| --- | ---: | --- |
| ESZIP v17, backup completo | 250834 | `d7dde8d167a814a05d21c5daad0ca810f943e1fa8bf9f5378a9676219adf577e` |
| ESZIP v18, readback | 274713 | `c329e1d7db44ce4927e00fcf415c3be88b2ea12a287b925f304aa68e726a6e2f` |
| index.ts distribuito | 7016 | `27890cd72ddea8a84d44dfa710340ff8e0ca334c3fea343500e8da7513a04219` |
| price-contract.ts distribuito | 4796 | `68c2971d96d5c68073e1f2b6745cb66436cadaf7454dba0fc65c3677c821961b` |

Il gestore è la copia permanente dopo la rimozione della sola LF finale, già verificata con 24/24 Node e Deno check. I log originali dello staging e i relativi hash rimangono distinti; il sorgente distribuito non viene dichiarato identico alla precedente copia con riga vuota aggiuntiva.

Due letture complete della preimmagine, backup fsync e prenotazione durevole precedono l'invio. Metadati e corpo sono stati riletti anche immediatamente prima del POST. Il payload multipart contiene soltanto index.ts e price-contract.ts. Nessun redirect, retry, bundleOnly o rollback. La GET finale dopo il campione conferma ancora gli stessi metadati v18. Rimane la finestra di concorrenza tra GET e POST: Management non offre CAS atomico di versione.

## Verifica limitata del servizio

Una GET senza Authorization e senza x-cron-secret ha ricevuto HTTP 401 con `Unauthorized`. Nel codice distribuito l'assenza dell'header ferma il gestore prima della SELECT delle carte e prima del provider. Il secret cron non è stato letto, modificato o inviato. Non sono state lette carte cliente né eseguito un aggiornamento prezzi dall'operatore.

Il canary autenticato ricerca/prezzo appartiene al distinto cardtrader-proxy: una ricerca Black Lotus e una quotazione EUR v2. Non costituisce un test di scansione, CAS o aggiornamento del cron globale. Lo snapshot comune finale del `2026-10-14T15:17:06.470903Z` conferma zero sessioni non scadute, fattori e tgc_cards dei tre QA fissi; proiezioni identità/profili selezionate invariate e registrazioni disabilitate. L'unica sessione QA A creata per il proxy è stata chiusa con logout locale HTTP 204.

## Arresti preliminari ed evidenze

I run `23e8aec9-fd9d-459b-9782-518945af88e9` e `b1d25d14-b152-40cc-80c7-9ea3977c9ada` si sono fermati prima di qualsiasi POST. La risposta Management includeva import_map false ed ezbr_sha256, assenti dal precedente manifesto selezionato, e ometteva import_map_path rappresentato come null nello storico. Il confronto successivo ha mantenuto gli hash ESZIP approvati e fissato esattamente la forma completa corrente; non ha tollerato variazioni del servizio.

Le prove ignorate sono sotto `artifacts/s02-deploy/7f2e85e3-79ac-420b-8997-666ee87a2feb/`: backup completi e metadati in `update-tcg-prices/`, prova del diniego in `qa-canary/cron-unauthorized-observed.json`, snapshot finale in `qa-canary/final-restored.json`. `completion.json` ha SHA-256 `ef892f5e6d1baee823ccb205c95763ac19874179d1cecf8e3f0b5052b0b3fea2`; `evidence-index.json` indicizza 56 file, SHA-256 `3f30c51411579f8f32000cdc944444a54cdffff34fa87aebc98da0fe298e0787`. I percorsi richiedono l'archivio locale delle evidenze. Token Management e credenziali QA sono rimasti in RAM; le evidenze non contengono password o token.

`verification.json` resta il manifesto predeployment. Le prove precedenti sono 24/24 Node e 12/12 Deno; i casi Deno ripetono quelli puri Node. Non sono state rieseguite durante il deployment.

## Limiti

Non sono qualificati un'esecuzione cron autorizzata, i prezzi globali effettivamente scritti, le esecuzioni schedulate successive, i rate limit provider o la paginazione globale. CAS e rifiuto non EUR sono verificati dal sorgente esatto e dalle fixture sintetiche, senza una prova di scrittura cliente live. SQL NULL mantiene la convenzione legacy EUR; il codice non ricostruisce la valuta originale dei prezzi salvati e non converte o ripara dati esistenti.

La verifica non ha modificato schema, RLS, schedulazione, segreti, web o app; non ha usato build, dispositivi o CI. Il rollback è predisposto ma non provato. Un eventuale ripristino richiede il controllo della versione e del corpo correnti e non annulla scritture già effettuate da altri processi. Non è dichiarata la parità globale TCG.
