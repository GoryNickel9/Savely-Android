# cardtrader-proxy v18 — deployment e campione QA

Distribuito il 14 ottobre 2026 con autorizzazione esplicita dell'utente per questo servizio, campione QA di sola lettura e rollback circoscritto. Il run `7f2e85e3-79ac-420b-8997-666ee87a2feb` ha eseguito un solo POST, HTTP 201. Il readback conferma versione 18 ACTIVE, stesso ID `23c851ba-f056-4fe2-a04d-52a8536a266a` e `verify_jwt:false`.

## Sorgenti e bundle

Il pacchetto revisionato è il commit `ffd04b4968080cd0c59584f9a588fc3f6f208e06`. Prima dell'invio i due sorgenti sono stati confrontati sia con quel commit sia con gli hash approvati. Il parser ESZIP2.3 ha poi verificato gli originali `sourcesContent` byte per byte, registrando anche hash dei moduli compilati e delle source map.

| Oggetto | Byte | SHA-256 |
| --- | ---: | --- |
| ESZIP v17, backup completo | 262925 | `abe60a7cef81679b9b4aee5734a66dee784e4e2a65b2f7be4a699de4bb4dbb65` |
| ESZIP v18, readback | 280208 | `ef09a62aa2722d966b31511d4550c9c1a4eb300d9366bde26810cc5d36aaf67e` |
| index.ts distribuito | 8434 | `7cf548a09c79384083e85017b31bc71104695e6683bd6f827e6a5248f536ec98` |
| quote-contract.ts distribuito | 5406 | `8d9c48e045af778ca448f4ab0415143d8646e82f496220f2e0580f9db7d1fee8` |

La preimmagine è stata letta due volte e conservata con scritture esclusive e fsync; metadati e corpo sono stati riletti immediatamente prima dell'unico POST multipart. Prenotazione e hash del payload precedono l'invio. Le richieste rifiutano redirect e non ripetono il POST. La GET finale dopo il campione QA conferma ancora gli stessi metadati v18. Management non offre un CAS atomico tra GET e deploy: queste verifiche non equivalgono a un blocco remoto delle modifiche concorrenti.

## Campione eseguito

| Operazione | Esito osservato |
| --- | --- |
| Preflight sui tre UUID QA fissi | Zero sessioni non scadute, fattori e righe tgc_cards; registrazioni disabilitate |
| Un login QA A | Identità confermata dal login, da GET auth/user e dal SID presente nello snapshot Management; AAL1 |
| Una ricerca autenticata `Black Lotus` | HTTP 200, otto risultati; scelto l'ID canonico 37789 restituito dalla ricerca |
| Una quotazione autenticata, blueprint 37789, near_mint | HTTP 200; quoteContractVersion 2, blueprintId `37789`, currency EUR, minorUnit 2, cents interi 350063, testo `€3500.63`, totalListings 2 |
| Una richiesta proxy senza Authorization | HTTP 401, `Unauthorized` |
| Logout della sola sessione creata, scope local | HTTP 204 |
| Snapshot finale, `2026-10-14T15:17:06.470903Z` | Zero sessioni non scadute, fattori e carte QA; proiezioni identità/profili selezionate invariate; registrazioni disabilitate |

Nessuna carta è stata inserita, modificata o eliminata. Le richieste provider sono passate soltanto dal proxy autorizzato. Password, JWT e token Management sono rimasti in RAM; chiave CardTrader e cron secret non sono stati letti. Nello stesso run il distinto cron update-tcg-prices ha ricevuto soltanto la richiesta senza JWT/secret descritta nel suo rapporto.

## Tentativi preliminari conservati

| Run | Arresto osservato | POST |
| --- | --- | ---: |
| `23e8aec9-fd9d-459b-9782-518945af88e9` | Il confronto con i metadati storici selezionati ha rilevato i campi aggiuntivi import_map/ezbr_sha256 | 0 |
| `b1d25d14-b152-40cc-80c7-9ea3977c9ada` | Il confronto completo ha distinto import_map_path assente dalla precedente rappresentazione null | 0 |

Entrambi gli ESZIP correnti erano identici alle preimmagini approvate. Il run conclusivo ha accettato soltanto la forma completa osservata: import_map false, ezbr_sha256 fissato e import_map_path assente, conservando tutti gli altri campi approvati. Non sono stati ignorati un cambio di versione o di sorgente, né ritentato un POST incerto.

## Evidenze e limiti

Le prove ignorate sono sotto `artifacts/s02-deploy/7f2e85e3-79ac-420b-8997-666ee87a2feb/`: script, backup, reservation, risposte, prove ESZIP e `qa-canary/*.json`. `completion.json` ha SHA-256 `ef892f5e6d1baee823ccb205c95763ac19874179d1cecf8e3f0b5052b0b3fea2`; `evidence-index.json` indicizza 56 file, SHA-256 `3f30c51411579f8f32000cdc944444a54cdffff34fa87aebc98da0fe298e0787`. I due run preliminari rimangono nelle rispettive directory sorelle. L'archivio locale è necessario per consultare questi percorsi; non contiene password o token.

Le prove sintetiche precedenti restano 28/28 Node e 16/16 Deno, questi ultimi ripetizioni dei casi puri Node. Non sono state rieseguite durante il deployment. `verification.json` conserva il manifesto storico del pacchetto e non viene riscritto come risultato operativo.

Il campione non verifica tutti i blueprint, condizioni, set, lingue/foil, prezzi legacy o client web/Android. Il parser nativo stretto V12 rimane separato e non è stato integrato da questa operazione. Nessun build, emulatore o CI è stato usato. Il rollback è stato preparato, non eseguito; ripristinare il vecchio proxy senza unità richiederebbe controllare prima che versione e corpo correnti appartengano ancora a questo run. Non è dichiarata la parità globale TCG.
