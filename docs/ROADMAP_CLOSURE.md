# Verifica dei cinque blocchi

La candidata 0.1.9 completa le ulteriori lacune di codice identificate nell’audit; implementazione e condizioni della 1.0 sono in [PARITY_0_1_9.md](PARITY_0_1_9.md), risultati attuali in [TESTING.md](TESTING.md). La tabella e il run seguenti conservano l’evidenza storica della 0.1.8.

Il lavoro richiesto riguarda Savely Android. I cinque blocchi sono implementati; la qualifica di release stabile e la parità globale restano separate dalle prove locali. L’utente non effettuerà una nuova verifica dettagliata sul dispositivo: le prove aggiuntive usano emulatori e contratti controllati, con i limiti riportati qui.

| Blocco | Risultato | Verifica e limite |
|---|---|---|
| Funzioni mancanti | Callback nativi PKCE per conferma email e recupero password; archivio dei collegamenti Coppia revocati; donut categorie e navigazione Dashboard. | Test di parsing, API, Keystore e UI. L’archivio espone i metadati consentiti dalle RLS: non recupera spese private dopo la revoca. Il recapito email e il completamento PKCE sul servizio reale non sono verificati. |
| Identità e navigazione | Icona originale, Inter/Space Grotesk locali, palette chiara/scura e cinque destinazioni come nel sito. | Screenshot delle fixture ispezionati; tap, contrasto, aree di tocco e font al 200% su 320 dp verificati. Nessuna certificazione di tutte le pagine con TalkBack o del launcher Doogee. |
| Backend | Letture finanza/moduli, import/export, catalogo e prezzo CardTrader; 12 inserimenti isolati, 8 modifiche e chiusura di due posizioni con CAS. | 41 contratti su 42 superati. Google Books risponde HTTP 429 per quota esaurita. Pulizia della stessa esecuzione completata dopo una correzione del solo test; nessun dato precedente modificato. |
| Lifecycle e documenti | Prove di ricreazione, background, input da tastiera, errori rete e file import/export. | Le fixture SAF forniscono risultati del contratto ActivityResult; non azionano il selettore di file del sistema. Export su MediaStore con lettura dei byte e metadati. 108/108 nella singola suite finale Android 15; 108 casi Android 12 con esito finale positivo dopo riesecuzioni mirate. Risultati e correzioni del harness registrati in TESTING.md. |
| Distribuzione e riferimento web | APK firmato; sorgenti private e repository pubblico separato per APK; aggiornamenti alpha pubblici; backup portabile cifrato della firma. | Revisione web distribuita associata al deployment e bundle pubblico confrontato. Backup decifrato e corruzione rifiutata; la copia protetta su un altro dispositivo non è verificata. APK, checksum e release sono descritti in RELEASE.md. |

## Backend: esecuzione e pulizia

Run `8f56e075-5f3a-49eb-87f1-0edbcebe8fe2`, account sintetico preesistente, Android 12. Ogni scrittura aveva un UUID registrato, proprietario fisso e contenuto atteso. Le DELETE usavano i valori letti come condizioni CAS. Un confronto del test tra Boolean `false` e stringa `"false"` ha impedito la prima pulizia prima dell’invio: corretto con una regressione offline, senza indebolire owner, payload, prenotazione del trasporto o divieto di ritentare una scrittura ambigua.

La recovery esatta passa in un nuovo processo: `records_removed`, `logout_server`, `cleanup_local`, `input_removed` e `cleanup_complete` sono veri; `cleanup_pending` e `checkpoint_retained` sono falsi. Il report originale fallito resta conservato accanto al report di recovery. La recovery verifica la pulizia; non trasforma la ricerca Google Books fallita in un contratto superato.

La credenziale amministrativa è rimasta sul computer host. Solo la sessione temporanea dell’account QA è entrata nello storage privato della build debug. Nessun cambio di password, invito a terzi, migrazione, trigger, policy o apertura delle registrazioni. L’unico cambiamento di configurazione è l’aggiunta del callback `savely://auth/callback?state=*`, preservando redirect esistenti, SMTP, URL del sito e stato delle registrazioni.

## Verifiche che non possono essere sostituite dai mock

Restano non verificati sul servizio reale: login con la password dell’account QA, email di conferma/recupero con PKCE, MFA positivo, revoca di altre sessioni, cambio email/password, Coppia con due account, operazioni Admin autorizzate ed eliminazione di un account sintetico. L’account disponibile ha password ignota e una sessione precedente; modificarne password o fattori per far passare una prova altererebbe quello stato. Le registrazioni sono disabilitate anche dal trigger backend. Questi flussi hanno contratti e fixture locali, ma non una prova reale completa.

Bank è stato inventariato: il convertitore PDF lavora nel browser e non espone un’API da richiamare in Android. L’importazione CSV risultante è inclusa; un convertitore PDF nativo resta un’estensione separata, come descritto in BANK_INVENTORY.md.

Il registro `parity/web-baseline.json` mantiene `globalParityVerified=false`. La conferma di uso generale della 0.1.7 sul Doogee S100 Android 12 non viene estesa alle nuove funzioni della 0.1.8.
