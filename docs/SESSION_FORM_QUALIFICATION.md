# Qualifica form sessioni — A05

## Stato e provenienza

**A05 è PASS LOCALE** nel criterio dell'app corrente con client secondari host in RAM. Il campione nativo conclude nove checkpoint nello stesso run; le sette fixture SettingsSecurity passano su API 31 e API 35, con pulizia server verificata dopo le fixture. Il consuntivo porta a **sei righe chiuse localmente e 27 aperte** nella matrice: non qualifica stabile, parità globale o un secondo smartphone. I tentativi falliti restano conservati; lo spegnimento finale di entrambi gli emulatori è verificato.

Run `4e4acd74-4a55-40bd-a3c4-7b944969e4f9`, evidenze sotto [artifacts/session-form-ui](../artifacts/session-form-ui/4e4acd74-4a55-40bd-a3c4-7b944969e4f9/). Produzione `44274e7438a89d91c0216eb7c9fe23831efeb8a7`, release locale **0.1.11-alpha/code 12** invariato rispetto ad A04. Riferimento web readonly `1cfcf6297e85d56bfafe3522362ceecef6f6b281`.

`input-metadata.json` distingue i quattro APK frozen e conferma i byte del release installato:

| Componente | Sorgente | SHA-256 | Byte |
|---|---|---|---:|
| App release firmata | `44274e7` | `5cdaca53d41d67d42cb05c0d2753f2611d6ba4d2f830067cde094d3355c757cd` | 2.747.518 |
| App debug per fixture | Stessa produzione | `3c0c90d2a96e4e3f8cfd43ab7c59701d07dd55d1b0716361d38de9da8da85f51` | 20.403.376 |
| Test APK finale | Fixture `b3a4d7d` | `19383fcb1c85dba893e77aa34e403d207401411c76c82d1eb25f71fef89a888e` | 2.191.718 |
| APK di qualificazione separato | `15ae1fe` | `defe734652c9ef05dd678baf0443383537a099adcaf25d5e7cbc075a1c2687f9` | 1.762.698 |

Il ledger [session_form_evidence.py](../tools/session_form_evidence.py) è source `95daeb3`. Le qualifiche precedenti mantengono i propri APK, sorgenti e criteri: questo rapporto non estende i loro esiti a una qualifica globale di A05.

## Verifiche locali e tentativi conservati

| Verifica | Esito e fonte |
|---|---|
| Ledger Python offline | 20/20, 49,624 s; FakeExecutor, nessun vault, HTTP o dispositivo. Sorgente test [test_session_form_evidence.py](../tools/test_session_form_evidence.py). |
| Prima build/JVM | `local-build.log`: 543 test release, un fallimento in TransferNotice con `UncaughtExceptionsBeforeTest` e Main dispatcher non impostato; build fallita in 2m 59s. L'errore precedente è conservato anche nell'XML TransferExportSession. |
| Secondo tentativo prima della correzione | `local-build-attempt2.log`: debug/release 543 senza errori, build riuscita in 2m 30s. Questo tentativo riuscito non dimostra da solo la causa del precedente fallimento. |
| Correzione teardown e controllo isolato | Source `30266d2`, solo test: attesa `cancelledJob.join` prima di chiudere Fixture e resettare Main. `jvm-teardown-isolated.log`: 11/11, build in 36s. |
| Consuntivo locale finale | `local-build-final.log`: build riuscita in 1m 51s, JVM **543 per variante**, zero failure/error/skip; lint zero errori, 32 warning e 6 hint. Le due varianti non vengono sommate. |
| Form sessioni sul release firmato | Nove checkpoint, un metodo nativo, 253,075 s; `native-session-result.json`, log sanitizzato `native-session.log`. |
| SettingsSecurity API 35 | **7/7, 56,665 s**, sette start e sette successi, zero failure/skip; `android35-session-final.log`. |
| SettingsSecurity API 31 finale | **7/7, 32,511 s**, sette start e sette successi, zero failure/skip; `android31-session-final.log`. Stesso APK finale, nessun edit tra tentativi. |

La correzione JVM riguarda il teardown delle fixture, senza modificare la produzione. Le sette fixture [SettingsSecurityTest.kt](../app/src/androidTest/java/cc/savely/android/ui/SettingsSecurityTest.kt) hanno i due consuntivi Android sopra riportati. `android-status-attestation.json` controlla sette start, sette successi, zero failure/skip per API; `final-qualification-metadata.json` conserva sorgenti, tempi e hash degli script host, mentre `input-metadata.json` identifica gli APK. Sono due esecuzioni degli stessi sette casi mirati, distinte dal metodo nativo e dalla suite completa dell'app, che non viene qualificata qui.

Il primo tentativo API 31, `android31-session-attempt1.log`, conclude tre casi prima del crash nel quarto, `revocationUsesSessionIdDisablesLegacyRowsAndClosesCurrentDevice`. Lo stack comprende `ActivityThread.performDestroyActivity` e Compose `SlotWriter`, con `ArrayIndexOutOfBoundsException`, length 10240/index −416. Il caso isolato sullo **stesso APK senza edit** passa 1/1 in 7,44 s (`android31-session-isolated.log`); il successivo run completo dei sette casi passa in 32,511 s. Il crash resta conservato: la causa definitiva Compose, fixture o produzione non è dimostrata e il retry riuscito non viene descritto come una correzione verificata. I tre successi iniziali e il caso isolato non vengono sommati al consuntivo finale.

Il primo login nativo `21cf9a10-189d-45e4-88ce-ce17bd78ff52` termina `DASHBOARD_NAVIGATION_MISSING` (`a1-login.json`): la UI mostra connessione non disponibile; lo snapshot server successivo osserva zero fattori/sessioni QA. Dopo cold boot e percorso dati cellulari con Wi-Fi disabilitato, il secondo login `6c89f9c1-efb5-46b6-8029-91c13bf474ff` passa in 9,648 s, con Dashboard e identità/profilo controllati (`a2-login.json`, `a2-login.log`). Il recupero non converte il primo tentativo in PASS e non prova da solo la causa della connessione non disponibile.

## Campione nativo e client secondario

Il campione usa il release firmato su **API 31/EN**, con il solo QA A autorizzato. Revoca e annullamento vengono azionati dai form Android. La sessione secondaria è un **client host con credenziali e token in RAM**, non un secondo dispositivo Android: esegue login password per QA A e associa l'audit tag `Savely QA A05 · session <UUID>`. La sessione `4f776ac0-6934-4fec-92e8-5fd15c2b4daf` esegue refresh HTTP 200 prima della revoca e riceve HTTP 400, `refresh_token_not_found`, dopo la conferma dal form nativo; l'identità QA principale nella UI rimane conservata. Gli eventi di login QA nello storico `login_activity` vengono conservati.

La terza sessione survivor `b904fb0a-54da-456e-a654-fdf013ad5289` esegue refresh HTTP 200 prima della revoca della sessione corrente. La conferma dal form Android rimuove la principale e riporta l'app al form Auth; il successivo refresh survivor riceve HTTP 200 con lo stesso UUID. Il logout locale del client host riceve HTTP 204. Il refresh diretto della principale dopo revoca **non viene eseguito**: l'app ha cancellato le proprie credenziali; l'evidenza su questa sessione è la sua assenza server e la transizione della UI locale.

I file `host-secondary-refresh-1-observed.json` e `host-secondary-refresh-4-observed.json` registrano i due esiti della secondaria; `host-survivor-refresh-5-observed.json`, `host-survivor-refresh-7-observed.json` e `host-survivor-logout-observed.json` registrano quelli survivor. Le reservation precedono gli esiti. `native-session-result.json` conclude **PASSED, 9/9 checkpoint**, tutti osservati sotto `evidence/` nello stesso run, con ACK dopo la lettura validata. `native-session.log` registra **OK, un test, 253,075 s**; nove checkpoint non sono nove casi JUnit.

`final-server-clean.json`, snapshot fresco alle **06:35:55,542416 UTC del 13 ottobre 2026**, osserva zero fattori/sessioni QA, registrazioni disabilitate, proiezioni selezionate d'identità/profili invariate e attività QA zero per transazioni, connessioni, richieste, audit Coppia, quote e budget. Il successivo `post-fixture-server-clean.json` alle **06:42:06,764433 UTC** conferma gli stessi vincoli dopo tutte le fixture. Sono controlli distinti, senza estendere l'assenza di sessioni a utenti estranei. `emulators-stopped.json` conferma entrambi i seriali posseduti assenti e `owned_qemu_count=0`; il controllo finale del coordinatore trova elenco ADB vuoto e nessun processo qemu posseduto tramite CIM.

## Criteri dei nove checkpoint

Il preflight richiede zero fattori/sessioni QA, registrazioni disabilitate, proiezioni selezionate d'identità/profili invariate e attività QA zero nelle tabelle controllate. Ogni checkpoint mantiene questi vincoli, salvo il numero di sessioni A previsto. Il ledger usa il `SNAPSHOT_SQL` e `validate_snapshot` di [mfa_form_evidence.py](../tools/mfa_form_evidence.py): letture Management fisse, nessuna mutazione o pulizia nel ledger.

| Checkpoint | Criterio e stato nel campione |
|---|---|
| `primary_ready`, `secondary_ready`, `revocation_cancelled` | Osservati: prima sessione A AAL1; aggiunta di una seconda AAL1 distinta; annullamento conserva entrambe e l'identità della principale. |
| `secondary_revoked`, `secondary_refresh_rejected` | Osservati: resta solo la principale; il refresh del client revocato è respinto senza far ricomparire la secondaria. |
| `survivor_ready`, `primary_revoked`, `survivor_refreshed` | Osservati: terza sessione AAL1 distinta dalle prime due; revoca della principale lascia solo survivor; il refresh conserva il suo UUID. |
| `completed` | Osservato: zero fattori/sessioni QA e vincoli iniziali ancora soddisfatti. |

I binding `primary`, `secondary`, `survivor` fissano UUID, owner e `created_at`; ogni nuova sessione nasce dopo il preflight e non precede il checkpoint precedente. `updated_at` può avanzare con il refresh, ma non arretrare; AAL deve restare AAL1. Il ledger rifiuta sessioni B/Admin, sostituzioni, ricomparsa di UUID revocati, campi segreti e replay dopo reservation incompleta o errore. Scrive l'osservazione in modo esclusivo e durabile prima che il coordinatore invii l'ACK.

Sono **nove checkpoint**, non nove test JUnit. I 20 test Python, le sette fixture Android per sistema e il metodo nativo hanno perimetri separati. Le sessioni conteggiate sono soltanto quelle dei tre QA noti con `not_after` nullo o futuro; le proiezioni selezionate non rappresentano l'intero contenuto di auth.users o dei profili.

## Limiti

La revoca e il refresh respinto non dimostrano l'invalidazione immediata di un access token già valido. La lista RPC derivata da `login_activity` è storica: una riga nella lista non prova da sola che una sessione sia ancora attiva. Il confronto decisivo usa UUID e snapshot auth delle sessioni QA, insieme al risultato del refresh e alla transizione del form corrente.

Il client secondario host non qualifica un secondo telefono, un browser o un'interoperabilità tra dispositivi fisici. Il criterio A05 unisce form nativi, esiti refresh host, letture server e fixture mirate; i rispettivi conteggi rimangono separati. Non viene qualificato il refresh diretto della principale revocata, né stabile, parità globale o suite completa. Nessuna CI, pubblicazione, modifica a configurazione/schema/RLS/web o lettura di account estranei. Il rapporto non contiene password, token o dati privati di autenticazione. Questa redazione modifica soltanto il documento, senza eseguire test, build, dispositivi o backend.
