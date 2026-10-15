# Deployment verificato — update-prices

Il 14 ottobre 2026 l'utente ha autorizzato distribuzione, prova QA e rollback circoscritto della proposta `ca9754784c8c15f5588e1797e3a60d8597cc37eb`. **Deployment e canary QA sono conclusi; nessun rollback eseguito.**

| Controllo | Osservato |
| --- | --- |
| Preimmagine | Due letture indipendenti: funzione `ebab8b27-d721-4b3b-80a9-c8945de4691f`, versione 16, ACTIVE, `verify_jwt=false`. |
| Backup ESZIP | 271341 byte, SHA-256 `1348961e3f0e8f62a38d2fd16277da38a97b0d55b17283cb32cf47b669d3b01d`; metadati e bundle salvati con fsync prima della scrittura. |
| Invio | Un solo POST multipart, HTTP 201; nessun redirect o retry. Solo `index.ts` e `quote-contract.ts`, senza modifiche a segreti, cron o altre funzioni. |
| Stato finale | Stesso ID, versione **17**, ACTIVE, `verify_jwt=false`; seconda lettura finale stabile. |
| Bundle finale | 293813 byte, SHA-256 `74801a62b657a8f4bad0490538e2d8ad2321049d67238987b087dffa2ee274c4`. |

I `sourcesContent` delle source map ESZIP coincidono byte per byte con i moduli della proposta: `index.ts` SHA-256 `edb0e48b02a5cae884f256f2a635ad0522452086ce63d4e26a04e042f5376f6f`, helper `a231e311459cdcc18282c961586a18ae9efb54e0c86387b7a5bbdeeb0c7ac050`. Questa verifica identifica il codice distribuito; non dimostra che i provider abbiano risposto correttamente.

Run locale `artifacts/v06-price-deploy/4d0fdd26-0a67-418a-b1cf-9a44f6116105`: prenotazione durevole, doppia preimmagine, richiesta, metadati, bundle, moduli e risultato separati. La successiva verifica del responsabile ha ricalcolato gli hash dei due bundle e dei moduli locali.

Durante la sola fase di deployment non sono state invocate la funzione, il cron o le API dei provider, né creati login o record QA. Non è stato eseguito rollback. Il ripristino preparato resta vincolato al bundle di questo run, con riconciliazione prima di qualsiasi nuova scrittura. I prezzi precedenti non vengono riparati retroattivamente.

## Canary QA successivo

Il run `artifacts/v06-price-canary/1216fd9d-fa37-409f-afff-00280e208259` passa sul release firmato code 18/SHA `bf86519c97c4b1dca8fb971800e6da6fec4f3b6b682a86f06f6f4b8b9947fe39`, harness `e7d28ff36ae9e90258f0020bb46790985886de4850b6f04ac37649305d79e7cc`, AVD dedicato API 31. I binari sono riletti dopo l'installazione; il servizio è attestato come v17 prima delle chiamate.

A esegue login, una sola azione nativa prezzi e logout: tre metodi PASS in 12,923/23,360/17,004 s. La quotazione stock **AAPL USD** aggiorna a **345,53** sia l'asset sia l'unico storico, con un log `checked=1`, `updated=1`, `errors=[]` e un manual update. Simbolo e valuta devono coincidere con quelli del provider; questo prezzo può essere la chiusura precedente. La prova osserva il valore salvato a due decimali e la coerenza asset/storico, senza confrontarlo con un feed indipendente.

B usa un client host con credenziali e token soltanto in RAM: un'unica chiamata restituisce HTTP 200, contratto 2, `checked=1`, `updated=0` e `QUOTE_CURRENCY_MISMATCH` per il solo BTC USD riservato. L'asset resta 0,02; nessuna scrittura B su asset/storico/manual update, con il solo log di rifiuto atteso. Non è una prova del form Android B. La selezione cron e le altre combinazioni strumento/valuta non sono state invocate.

Dopo i logout delle sole sessioni create dal run, sei cancellazioni full-row CAS eliminano i due asset, uno storico, un manual update e due log adottati. Alle **14:25:22,917393 UTC del 14 ottobre 2026** il baseline coincide: zero sessioni/fattori QA, asset/storico/manual update/log prezzi zero; 33 categorie e sei snapshot preesistenti conservati. Prenotazioni durevoli precedono seed, richieste e cleanup; nessun retry o rollback automatico.

Le 13 guardie offline finali passano; la revisione indipendente precede il canary e ne identifica runner e guardie. `evidence/result.json` SHA-256 `f7784c397790cd0125fa41e6a9bf32f6b695c80805e701b3a8732b82cce5241c` conferma `passed=true`, `baseline_restored=true`, `cleanup_pending=false`. Le due osservazioni prezzi sono identificate da `70f1076e5466f7fc0492f1837af62a6b83e306783027ad3cd669f8388a9950b7` e `5580e729a22d45c2d38afe5379d76b3480a621be19eefa1e07f60376baf971f0`; il confronto finale da `519e772b5272bd5fac290938c4773a0cbe59accd442857b79cc0456f7d0482a9`. [Rapporto Portafoglio](../../docs/PORTFOLIO_QUALIFICATION.md) conserva fasi, tentativi di setup e limiti.

Il repository web di riferimento non è stato ancora modificato; la patch sorgente e le prove sono versionate nel repository Android. Occorre mantenere allineata la sorgente prima di un futuro deployment dal repository web.
