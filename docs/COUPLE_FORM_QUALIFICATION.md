# Qualifica form Coppia — C02

## Stato e sorgenti

**C02 è PASS LOCALE nel criterio della matrice.** Il campione sul release conclude invito/accettazione, quote EUR e USD con validazioni, modifica del budget, quattro rimozioni, revoca, archivio, isolamento per ruolo e pulizia. Passano 14 fixture mirate per ciascuna API 31/35; il crash iniziale API 35 resta conservato, con causa non dimostrata. V01/R01/C01/C02 sono quattro righe chiuse localmente delle 33: ne restano **29 aperte**. Il risultato non qualifica tutte le funzioni della stabile.

Run `c6e879e4-9ec4-46b3-9940-1a01cefcad94`, interfaccia EN su API 31. Produzione `459bbac1a380f05b598f0712ee8e66bda3d4eae1`, candidata locale 0.1.10-alpha/code 11, APK release SHA-256 `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa`. Il codice applicativo e l'APK restano quelli della [qualifica firmata](SIGNED_RELEASE_QUALIFICATION.md). Sono cambiati soltanto fixture e strumenti di osservazione; riferimento web invariato `1cfcf6297e85d56bfafe3522362ceecef6f6b281`.

Le evidenze sono sotto [artifacts/couple-form-ui/c6e879e4-9ec4-46b3-9940-1a01cefcad94](../artifacts/couple-form-ui/c6e879e4-9ec4-46b3-9940-1a01cefcad94/). I journal `evidence/*-reservation.json` precedono le operazioni; gli `*-observed.json` adottano gli UUID dopo confronto con il ledger QA. Il [result.json immutabile](../artifacts/couple-form-ui/c6e879e4-9ec4-46b3-9940-1a01cefcad94/result.json), SHA-256 `bc5c3b22ed5cc1e1ecb6d3dfee87bb0502bc6b99251ddce6c790997992510778`, registra 16 operazioni, pulizia, sette fasi REST, risultati Android e hash di 45 PNG e 45 XML. Il finalizzatore offline convalida queste evidenze; non esegue altre operazioni backend.

## Form osservati sul release

| Percorso | Osservato finora | Evidenze presenti nel run |
|---|---|---|
| Invito e accettazione | A invita B; B accetta. La connessione viene adottata con UUID e partecipanti QA confrontati. | `evidence/invite-observed.json`, `evidence/accept-observed.json`, `c02-a-real-pending.xml/png`, `c02-b-incoming.xml/png`, `c02-b-connected.xml/png` |
| Originale e quota EUR | A crea una spesa EUR 10,01 e condivide a metà. La vista espone 5,01 per ciascun partner; la UI avverte dello scarto di 0,01. | `evidence/equal-tx-observed.json`, `evidence/equal-share-observed.json`, `c02-a-equal-preview.xml/png`, `c02-a-equal-saved.xml/png` |
| Originale e quota USD | B crea una spesa USD 7,00. La quota custom del partner è 2,01 e quella del creatore 4,99. I tentativi con 0, importo pari al totale 7 e precisione 2,001 sono rifiutati prima della condivisione. | `evidence/custom-tx-observed.json`, `evidence/custom-share-observed.json`, `c02-b-custom-zero-rejected.xml/png`, `c02-b-custom-total-rejected.xml/png`, `c02-b-custom-precision-rejected.xml/png`, `c02-b-custom-saved.xml/png` |
| Budget e modifica da altro partner | Il coordinatore osserva il rifiuto del limite 0 e assenza di scrittura; il relativo capture non mostra il campo/errore nel viewport. A crea poi un budget EUR 3,00. B annulla una modifica, quindi salva EUR 4,00 sullo stesso budget. | `evidence/budget-observed.json`, `evidence/budget-update-observed.json`, `c02-a-budget-created.xml/png`, `c02-b-budget-edit-cancelled.xml/png`, `c02-b-budget-updated.xml/png`; validazione zero verificata anche dalla fixture dedicata. |
| Rimozioni da Coppia | B rimuove come creatore la propria quota USD e come partner la quota EUR creata da A. La rimozione partner è preceduta da annulla. Gli originali restano invariati. | `evidence/remove-custom-observed.json`, `evidence/remove-equal-observed.json`, `c02-b-custom-creator-removed.xml/png`, `c02-b-equal-partner-remove-cancelled.xml/png`, `c02-b-equal-partner-removed.xml/png` |
| Rimozioni da Transazioni | Dopo ricondivisione con UUID distinti, A rimuove come partner la quota USD creata da B e come creatore la quota EUR propria. I journal adottati confermano gli originali invariati; l'annulla della rimozione partner conserva la quota. | `evidence/remove-custom-partner-observed.json`, `evidence/remove-equal-creator-observed.json`, `c02-a-custom-partner-transactions-cancelled.xml/png`, `c02-a-custom-partner-transactions-removed.xml/png`, `c02-a-equal-creator-transactions-removed.xml/png` |
| Preparazione della verifica dopo revoca | Cinque UUID di quote distinti durante il run, quattro rimossi. Prima della revoca restano una nuova quota custom USD e il budget EUR 4,00, così il controllo di privacy non parte da collezioni già vuote. | `evidence/reshare-custom-observed.json`, `evidence/reshare-equal-observed.json`, `evidence/final-custom-observed.json`, `c02-b-final-custom-saved.xml/png` |
| Revoca e archivio | A annulla la revoca, quindi la conferma. Il run adotta la connessione revocata. Le acquisizioni A6 e B7 mostrano `Created`, `Revoked` e `Read-only` nell'archivio per entrambi i partner. | `evidence/revoke-observed.json`, `c02-a-revoke-cancelled.xml/png`, `c02-a-archive-details.xml/png`, `c02-b7-archive-complete.xml/png` |

Il rounding della vista SQL resta invariato: due quote da 5,01 su EUR 10,01 non vengono corrette modificando i dati. Le quote USD restano USD; questo campione non somma EUR e USD né applica una conversione valutaria. Le transazioni originali restano visibili soltanto al proprietario anche quando la quota condivisa è visibile a entrambi. Il PNG della preview EUR mostra valuta e importi leggibili; alcuni simboli nell'XML hanno caratteri sostitutivi, senza dimostrare un difetto del rendering. I tre XML custom invalidi contengono invece gli errori effettivi. Il nome `c02-a-budget-zero-rejected` non viene usato come prova visiva dell'errore fuori viewport.

## Letture REST per ruolo

[couple_role_observation.py](../tools/couple_role_observation.py) usa descriptor confinati alla directory del run, tabelle consentite, UUID adottati e aspettative per ruolo. Confronta i campi attesi e i numeri con semantica decimale; una risposta inattesa non viene salvata integralmente. Il controllo host è separato dalla UI e non dimostra da solo il numero globale delle sessioni server.

| Fase conclusa | Esito | Scope |
|---|---|---|
| `active-shares` | 22 letture, `passed` | `role-active-shares-result.json`: quote/vista, originali, connessione, richiesta e audit secondo i due ruoli; logout delle sole sessioni host create. |
| `active-budget` | 24 letture, `passed` | `role-active-budget-result.json`: stesso confronto con budget modificato incluso; logout delle sole sessioni host create. |
| `post-removal-one` | 26 letture, `passed` | `role-post-removal-one-result.json`: quota USD rimossa, vista e originali confrontati per ruolo. |
| `post-removal-two` | 26 letture, `passed` | `role-post-removal-two-result.json`: anche quota EUR rimossa; originali ancora isolati per proprietario. |
| `post-removal-three` | 34 letture, `passed` | `role-post-removal-three-result.json`: quota USD ricondivisa poi rimossa da Transazioni, con vecchi UUID ancora controllati. |
| `post-removal-four` | 40 letture, `passed` | `role-post-removal-four-result.json`: quota EUR ricondivisa poi rimossa da Transazioni, originali conservati. |
| `post-revoke` | 48 letture, `passed` | `role-post-revoke-result.json`: quote/vista/budget/audit della connessione non visibili ai due ruoli; metadata della connessione revocata e richiesta accepted visibili, originali soltanto al proprietario. |

Sono sette fasi, 220 letture con ripetizioni, non 220 test distinti. Ogni fase conclude entrambi i logout host e registra `cleanup_pending=false` per le sessioni dei rispettivi probe.

I 13 abbinamenti login/logout nativi — A1…A6 e B1…B7 — riportano `stage=passed` in tutte le 26 invocazioni, cui si aggiungono tre setter whitelist riusciti. A1…A4 e B1…B3 usano il precedente harness C01, auth source `2d7d70f`/APK SHA-256 `b67ae850c0b357f0e1b7f7dbc9c2befd4a4562e5274b902eb2ec6033e03dce6e`; A5/A6 e B4…B7 usano l'estensione `4def664`/APK `cc28312b…`. I metodi login/logout restano invariati nell'estensione; i tre setter appartengono soltanto a `4def664`. Questi risultati riguardano le invocazioni del driver: non cancellano i tentativi errati descritti sotto e non diventano 29 casi Android.

La pulizia finale riserva e confronta 16 righe del ledger prima della cancellazione CAS: una quota, dieci audit, un budget, due transazioni, una connessione e una richiesta. [cleanup-complete.json](../artifacts/couple-form-ui/c6e879e4-9ec4-46b3-9940-1a01cefcad94/evidence/cleanup-complete.json) registra il completamento; `server-final-records.json` riporta zero righe nelle sei tabelle QA interessate. `server-final-cleanup.json` conferma zero sessioni QA non scadute (`not_after` nullo o futuro), fattori e transazioni QA, tre identità e registrazioni ancora disabilitate. Restano invariati i campi osservati delle identità — ID/email/conferma/`qa_run` — e codice/permessi dei profili A/B. Non sono confrontati tutti i campi utente, il profilo Admin o record estranei al run.

Il snapshot della reservation di pulizia conserva intenzionalmente lo stato dell'ultima osservazione di revoca, con una sessione UI ancora presente. Il guard prima delle DELETE verifica su lettura fresca zero sessioni attive. Il primo controllo offline del finalizzatore attendeva erroneamente zero anche nella reservation storica; è stato corretto soltanto il helper offline, senza cambiare il ledger o retrodatare la pulizia.

## Fixture e guard offline

Source delle cinque nuove fixture Android: `b069756`. [CoupleScreenTest.kt](../app/src/androidTest/java/cc/savely/android/ui/CoupleScreenTest.kt) contiene 11 casi; [FinanceSharingScreenTest.kt](../app/src/androidTest/java/cc/savely/android/finance/FinanceSharingScreenTest.kt) ne contiene tre. Il piano mirato comprende 14 casi per API, cinque nuovi e nove preesistenti. API 31 passa 14/14 in 47,894 s e il run finale API 35 passa 14/14 in 56,859 s, zero failure/skip. Sono 28 esecuzioni degli stessi 14 casi sui due OS, non una nuova suite completa da 175 casi.

| API finale | Log | SHA-256 |
|---|---|---|
| 31 | `android31-couple-fixtures.log` | `93b6793cb12604831ab3ebee5c40103ede11f07d5f9257ddebcec3af085bcef5` |
| 35 | `android35-couple-fixtures-qualified.log` | `ef0fcb6625a5371ee7b8f9853746207af96ef4fbfafad6303ac54f29d728c465` |

Il test APK `qualified-androidTest.apk` ha SHA-256 `a878fe68e18f8f4041f63bb79dadbc3fda969c9fa8e1d35b224e41e4304feae8`. L'app debug invariata ha SHA-256 `cac39f0af66a2a32d269f5094de6a565306cdc4405aa7731a71b021b02da99e5`. Il primo API 35 termina con crash Compose `SlotWriter` durante distruzione dell'Activity nel caso preesistente `lateRejectResponseFromOldOwnerClearsStaleInvitationsWithoutWritingAsNewOwner`: `ArrayIndexOutOfBoundsException`, lunghezza 2560, indice −2056. Log e crash sono conservati. Lo stesso caso isolato passa 1/1 in 6,821 s (`android35-couple-isolated.log`), poi lo stesso APK/sorgente passa i 14 casi completi senza edit. Non viene attribuita una causa all'app o alla fixture, né dichiarato un fix del crash o sommato l'isolato al run finale.

Le cinque regressioni aggiunte coprono quota USD uguale con centesimo dispari, modifica/validazione/cancellazione del budget, rimozione creatore e partner da Coppia, conferma di revoca con archivio e rimozione creatore dalle Transazioni. Conservano le asserzioni su valuta, originali, ruolo, owner/revision e singola mutazione; non costituiscono prove su ogni policy del servizio.

| Verifica host | Esito documentato | Evidenza |
|---|---|---|
| Guard dei form | 16 test, zero failure, 5,884 s | `host-form-guard.log` |
| Guard delle letture per ruolo, primo tentativo | 14/15; un'attesa errata nella fixture | `host-role-guard.log` |
| Guard delle letture per ruolo, dopo correzione | 15 test, zero failure, 0,639 s | `host-role-guard-qualified.log` |

Nel primo tentativo la fixture inseriva un testo non numerico nel campo `amount`: il guard produceva correttamente `NUMERIC_INVALID`, mentre l'asserzione attendeva `REST_EXPECTATION_MISMATCH`. È cambiato soltanto il codice atteso; le asserzioni su riservatezza dell'output e logout sono rimaste. Il fallimento iniziale resta conservato. I self-check usano risposte sintetiche e non leggono credenziali né eseguono operazioni backend.

La build delle fixture incontra due errori Java NIO prima del compilatore, conservati in `fixture-build.log` e `fixture-build-jbr.log`. Il tentativo con il workaround process-scoped descritto in [TESTING.md](TESTING.md) termina `BUILD SUCCESSFUL in 2m 6s`, come registrato in `fixture-build-qualified.log`, includendo `assembleDebugAndroidTest` e `lintDebug`: zero errori, 32 warning e 6 hint. Compilazione e PASS mirati sono evidenze distinte. Le suite storiche da 536 JVM per variante e 170 casi debug per API restano associate ai rispettivi sorgenti già qualificati; non vengono ricalcolate o estese da questo run.

## Tentativi del driver

La cronologia seguente proviene dalle osservazioni del coordinatore durante l'esecuzione; non viene presentata come contenuto di log inesistenti né come difetto applicativo. Il run non è un'unica automazione priva di errori.

| Tentativo | Causa osservata e trattamento |
|---|---|
| Codice partner | Il driver cercava una label inglese diversa da `Partner's code`. I primi capture `c02-a-invite-prepared` e `c02-a-invite-pending` mostrano un form non inviato: i nomi non provano una richiesta pending. Il successivo `invite-observed.json` e `c02-a-real-pending` costituiscono la prova adottata. |
| Tap rifiutato e osservazione | Un tap del driver rifiutato non inserisce la riga; l'osservazione restituisce `NEW_ROW_NOT_UNIQUE`. La sequenza viene ripresa dal form, senza dedurre successo dal nome del capture. |
| Budget | Il driver cerca il campo nella direzione di scroll errata e usa inizialmente `Cancel Editing`; la label effettiva è `Cancel changes`. I tentativi vengono corretti prima di proseguire. |
| Rimozione da Finance e stdout | La descrizione effettiva è `Remove sharing for {{name}}`; il tentativo senza `for` rifiuta il click senza scrittura. Un output Unicode fallisce su stdout cp1252, poi viene configurato UTF-8. |
| Batch concorrenti | La rimozione creatore in Finance viene avviata prima del termine del typing per la ricondivisione EUR. Due dump UI concorrenti causano `ADB_FAILURE` e campo non trovato; entrambi i batch si interrompono prima delle rispettive reservation di ricondivisione/rimozione. Il coordinatore riprende in sequenza, completando Coppia prima di Finance. |

La tastiera emoji rimasta aperta dopo il tentativo concorrente intercettava gesture e typing. Il nuovo harness separato `4def664` aggiunge il solo setter opt-in `fillDedicatedQaCouplePublicField()`: whitelist e regex del valore vengono validate prima dell'azione; `ACTION_SET_TEXT` evita la tastiera e il report confronta il valore completo. Non viene qualificato un submit con valore parziale. Tre report `field-*.json` registrano `stage=passed` e `value_verified=true`; il recupero della ricondivisione EUR precede la successiva rimozione Finance eseguita in sequenza.

Il test helper `public-field-harness.apk` nel run ha SHA-256 `cc28312b6a17360cfd14e6d86b3062b173cb0f0ee9a4dfdc9f60da0dff4f68b2`, build separata da sette secondi (`public-field-harness-build.log`). Non è l'APK dell'app e non cambia la produzione `459bbac`.

Questi tentativi restano nella storia degli strumenti, distinta dai risultati e hash registrati nel consuntivo finale. Operazioni non inviate, recuperi e passi conclusi conservano la propria provenienza; non vengono convertiti retroattivamente in PASS.

## Criterio C02 e limiti

C02 chiude localmente quattro percorsi di rimozione dai form, modifica del limite, revoca/archivio, validazioni e valuta, isolamento degli originali e metadata dopo revoca nel campione QA descritto, con fixture mirate finali su entrambe le API. I 16 passi nativi, le 220 letture, i self-check e le fixture restano conteggi distinti. I tentativi falliti e il crash iniziale conservano i propri esiti. La cancellazione del budget è coperta dalla fixture, senza nuovo campione nativo reale. La spesa USD usa cambio manuale 1: non qualifica il servizio FX. Il campione release reale è API 31; API 35 usa fixture sintetiche debug.

Entrambi gli AVD sono spenti, come registrato in `emulators-final-shutdown.json`: processi owned assenti ed elenco ADB vuoto. Nessuna qualifica globale, nuova release pubblica o CI. Nessuna modifica all'app, al web, allo schema/RLS o alla configurazione globale deriva da questa qualifica. Password e token non vengono riportati.
