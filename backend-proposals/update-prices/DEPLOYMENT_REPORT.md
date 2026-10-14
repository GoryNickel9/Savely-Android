# Deployment verificato — update-prices

Il 14 ottobre 2026 l'utente ha autorizzato distribuzione, prova QA e rollback circoscritto della proposta `ca9754784c8c15f5588e1797e3a60d8597cc37eb`. **Il deployment è concluso; la prova QA dei prezzi resta da eseguire.**

| Controllo | Osservato |
| --- | --- |
| Preimmagine | Due letture indipendenti: funzione `ebab8b27-d721-4b3b-80a9-c8945de4691f`, versione 16, ACTIVE, `verify_jwt=false`. |
| Backup ESZIP | 271341 byte, SHA-256 `1348961e3f0e8f62a38d2fd16277da38a97b0d55b17283cb32cf47b669d3b01d`; metadati e bundle salvati con fsync prima della scrittura. |
| Invio | Un solo POST multipart, HTTP 201; nessun redirect o retry. Solo `index.ts` e `quote-contract.ts`, senza modifiche a segreti, cron o altre funzioni. |
| Stato finale | Stesso ID, versione **17**, ACTIVE, `verify_jwt=false`; seconda lettura finale stabile. |
| Bundle finale | 293813 byte, SHA-256 `74801a62b657a8f4bad0490538e2d8ad2321049d67238987b087dffa2ee274c4`. |

I `sourcesContent` delle source map ESZIP coincidono byte per byte con i moduli della proposta: `index.ts` SHA-256 `edb0e48b02a5cae884f256f2a635ad0522452086ce63d4e26a04e042f5376f6f`, helper `a231e311459cdcc18282c961586a18ae9efb54e0c86387b7a5bbdeeb0c7ac050`. Questa verifica identifica il codice distribuito; non dimostra che i provider abbiano risposto correttamente.

Run locale `artifacts/v06-price-deploy/4d0fdd26-0a67-418a-b1cf-9a44f6116105`: prenotazione durevole, doppia preimmagine, richiesta, metadati, bundle, moduli e risultato separati. La successiva verifica del responsabile ha ricalcolato gli hash dei due bundle e dei moduli locali.

Nel deployment non sono state invocate la funzione, il cron o le API dei provider, né creati login o record QA. Non è stato eseguito rollback. Il ripristino preparato resta vincolato al bundle di questo run, con riconciliazione prima di qualsiasi nuova scrittura. I prezzi precedenti non vengono riparati retroattivamente.

Il repository web di riferimento non è stato ancora modificato; la patch sorgente e le prove sono versionate nel repository Android. Occorre mantenere allineata la sorgente prima di un futuro deployment dal repository web.
