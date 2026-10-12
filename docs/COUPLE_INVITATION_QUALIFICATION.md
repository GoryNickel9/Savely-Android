# Qualifica inviti Coppia — C01

## Stato e sorgenti

**C01 è PASS LOCALE nel criterio della matrice:** campione nativo sul release firmato per rifiuto/cancellazione tra i soli QA A/B, codice invalido e blocco pending, riletture per ruolo e pulizia, più sette fixture mirate superate su ciascuna API 31 e 35. V01, R01 e C01 sono le tre righe chiuse localmente delle 33: ne restano 30 aperte. C01 non qualifica quote, budget o revoca della connessione, assegnati a C02, né tutte le funzioni della stabile.

Run `e8aafd09-c2bb-474d-a638-828cf12bde13`, API 31, interfaccia EN. Produzione `459bbac1a380f05b598f0712ee8e66bda3d4eae1`, candidata locale 0.1.10-alpha/code 11, APK release SHA-256 `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa`. L'APK e il codice applicativo conservano gli stessi byte della [qualifica firmata](SIGNED_RELEASE_QUALIFICATION.md). Le build successive riguardano fixture Android e driver separato, non una nuova produzione. Riferimento web invariato `1cfcf6297e85d56bfafe3522362ceecef6f6b281`.

Il codice in [CoupleScreen.kt](../app/src/main/java/cc/savely/android/ui/CoupleScreen.kt) aggiorna la richiesta con UUID esatto, stato `pending`, ruolo `receiver_id` per rifiuto o `sender_id` per cancellazione e owner/revision originari. [CoupleScreenTest.kt](../app/src/androidTest/java/cc/savely/android/ui/CoupleScreenTest.kt) aggiunge tre casi ai quattro esistenti: rifiuto, cancellazione e risposta PATCH tardiva dopo cambio account.

## Percorsi osservati sul release

| Passo | Osservato | Evidenza |
|---|---|---|
| Login A e codice invalido | Login dal form riuscito. `OABC2345` produce errore client e zero richieste. | `a1-login.json`, `c01-a-invalid-code.xml/png` |
| Invito A→B | Il form crea una richiesta pending; il helper adotta il suo UUID dopo confronto dei partecipanti. Con codice valido non vuoto `VTJKXZ29`, `Invite partner` è disabilitato mentre l'invito è pending. | `evidence/first-pending.json`, `c01-a-pending-blocks-new-code.xml/png` |
| Logout A1 | Il driver fallisce con `ACCOUNT_CONTENT_MISSING` perché Impostazioni conserva lo scroll. Logout manuale dalla UI e successiva lettura server confermano zero sessioni. Il fallimento resta conservato. | `a1-logout.json/log`, `a1-after-manual-logout.json`, `c01-a-manual-logout.xml/png` |
| Login B e rifiuto | Login dal form riuscito; B usa `Decline` sulla richiesta A→B. Stato `rejected`, audit `request_rejected` con attore B, invito non più mostrato. | `b1-login.json`, `evidence/first-terminal.json`, `c01-b-rejected.xml/png` |
| Nuovo invito B→A e cancellazione | B crea una richiesta inversa distinta e usa `Cancel invitation`. Stato `cancelled`, audit `request_cancelled` con attore B, invito non più mostrato. | `evidence/reverse-pending.json`, `evidence/reverse-terminal.json`, `c01-b-cancelled.xml/png` |
| Logout B e controllo A2 | Logout nativo B riuscito. Nuovo login nativo A, schermata Coppia senza inviti pending, logout nativo riuscito. | `b1-logout.json`, `a2-login.json`, `c01-a2-no-pending.xml/png`, `a2-logout.json` |

Il driver corretto in `2d7d70f` porta Impostazioni in cima dopo il click, senza modifiche all'app. I passi B/A2 usano il nuovo harness SHA-256 `b67ae850c0b357f0e1b7f7dbc9c2befd4a4562e5274b902eb2ec6033e03dce6e`. Il recupero manuale A1 e i passi successivi restano distinguibili; non costituiscono un unico run automatico privo di errori.

UUID delle sole richieste create:

| Richiesta | Mittente → destinatario | Stato terminale |
|---|---|---|
| `d9d58b0d-b21f-4772-90e4-72d5f300cf9b` | A → B | `rejected` |
| `551b2662-5c1f-4401-8a76-6151956f9742` | B → A | `cancelled` |

## Rilettura per ruolo e pulizia

`verify-role-reads.py` esegue un controllo REST host separato: login di ciascun QA, lettura di entrambe le richieste terminali e logout locale della propria sessione host. `visibility-result.json` registra quattro riletture con UUID, stato e tutti i campi coincidenti, entrambi i logout riusciti e nessuna pulizia pendente. Questa evidenza dimostra visibilità REST per i due ruoli; la UI nativa non visualizza righe terminali.

Il helper valida le due richieste e i quattro audit rispetto al ledger, quindi elimina esclusivamente quei sei record con confronto completo del JSON atteso e verifica l'assenza per UUID. Il confronto finale riporta zero richieste, audit, sessioni, transazioni, fattori e connessioni QA, registrazioni ancora disabilitate, tre identità con metadata invariati e profili invariati. La pulizia non estende il PASS ad altri utenti o ad altre operazioni.

Evidenze sotto [artifacts/couple-invitation-ui/e8aafd09-c2bb-474d-a638-828cf12bde13](../artifacts/couple-invitation-ui/e8aafd09-c2bb-474d-a638-828cf12bde13/): journal `evidence/`, `visibility-result.json`, `server-final-cleanup.json`, log dei singoli login/logout e 22 acquisizioni `c01-*.xml/png`, spostate conservando i byte. Il [result.json immutabile](../artifacts/couple-invitation-ui/e8aafd09-c2bb-474d-a638-828cf12bde13/result.json) registra il consuntivo e gli hash dei singoli file; esclude i log modificabili dell'emulatore.

## Fixture locali e tentativi conservati

Le 18 verifiche host offline passano in 0,983 s, senza leggere segreti nel self-check: [host-guard-selfcheck.log](../artifacts/couple-invitation-ui/host-guard-selfcheck.log). Non sono 18 prove backend o Android.

| Esecuzione mirata | Esito | Causa e trattamento |
|---|---|---|
| Prima API 31 | 6/7 | Il guard della fixture non ammetteva la query `select=id` con mittente, pending e limit 1. Corretto nel test; log conservato `android31-couple-fixtures.log`. |
| Seconda API 31 | 6/7 | Il contatore atteso 1 era 2 perché includeva una RPC di lettura. Corretto l'atteso distinguendo lookup RPC e INSERT; log conservato `android31-couple-fixtures-final.log`. |
| Finale API 31 | 7/7, zero failure/skip, 19,716 s | `android31-couple-fixtures-qualified.log`, esecuzione completa della sola classe. |
| Finale API 35 | 7/7, zero failure/skip, 40,444 s | `android35-couple-fixtures-qualified.log`, esecuzione completa della sola classe. |

Fixture source `b5860c30c5730a745227e1ecf97eb8c24dc16616`; test APK finale `qualified-androidTest-final.apk`, SHA-256 `180d746d8a85f82e80c3d9463705b79fecfa3b62ae154e8c8604ea4342277722`. App debug invariata sul sorgente `459bbac`, SHA-256 `cac39f0af66a2a32d269f5094de6a565306cdc4405aa7731a71b021b02da99e5`.

Hash SHA-256 dei log finali:

| API | SHA-256 |
|---|---|
| 31 | `0ab22a251b173da540f2e817e3c96eb6636782d9a849e00744c063d0e1f3a56f` |
| 35 | `4f0fb467dca430982051920a27e3df007f4b20861cee4529cb364d14e3cf6384` |

Le tre nuove fixture conservano le asserzioni su ruolo/pending/UUID, refresh, singola mutazione e risposta tardiva di un vecchio account. Le due classi finali rappresentano 14 esecuzioni degli stessi sette casi su due OS, tre nuovi e quattro preesistenti. Le 536 JVM per variante e le suite complete debug da 170 casi per API restano associate all'app `459bbac` e alle fixture `74d7d0d`. Non viene dichiarata una nuova suite completa da 173 casi né sommati i tentativi mirati.

Gli AVD sono stati usati uno alla volta. L'integratore conferma arresto di API 31 prima dell'avvio di API 35 e arresto finale di API 35 dopo reset di dimensioni/densità/font; elenco ADB vuoto, processi parent e QEMU assenti. Nessuna nuova operazione dispositivo è stata eseguita durante questo aggiornamento documentale.

## Criterio C01 e limiti

Il campione nativo rifiuto/cancellazione con rilettura e pulizia QA, il controllo codice invalido/pending e le fixture finali su entrambe le API per la risposta tardiva/cambio account soddisfano C01 nel criterio della [matrice](STABLE_VERIFICATION_MATRIX.md). La chiusura è circoscritta a queste richieste e a questi casi; il perimetro restante della stabile resta aperto. Il primo logout driver fallito e i due 6/7 iniziali conservano il proprio esito.

Nessuna email, modifica schema/RLS/configurazione globale o sorgente web, CI o release pubblica. Il run usa identità QA autorizzate; non prova assenza di modifiche su ogni record del servizio e non qualifica tutte le politiche backend. Password e token non sono riportati in questo documento.

La stima precedente di 36–60 ore di lavoro tecnico residuo è pianificazione, pari a circa 4,5–7,5 giornate da otto ore, non una data della stabile. Presuppone riuso delle fixture, servizio QA disponibile e nessun nuovo difetto sostanziale. Tempi di mailbox, telefono, provider cloud, disponibilità Books e recovery della firma su altro computer restano separati; il completamento di C01 non permette di sottrarre automaticamente una durata nominale dalla stima.
