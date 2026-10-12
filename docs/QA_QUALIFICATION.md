# Qualifica con account dedicati — 12 ottobre 2026

Queste prove riguardano il codice successivo alla release pubblica 0.1.9-alpha. L'APK pubblico e i suoi risultati restano immutabili. I test remoti sono opt-in, separati dalla suite ordinaria; nessuna CI GitHub.

## Account

Il run `5ef2838c-ae5b-47d9-ac05-72dc5362b6a9` ha creato, dopo autorizzazione dell'utente, due utenti QA e un Admin con indirizzi `example.invalid`. Apertura temporanea, creazione e ripristino delle registrazioni: 2,249 secondi. Stato finale disabilitato verificato. Accesso host e ruoli confermati 3/3, seguiti dal logout delle tre sessioni di prova.

Le password sono cifrate con Windows CurrentUser DPAPI fuori dalla repository, con ACL dell'utente corrente e SYSTEM. `tools/qa_credentials.py` accetta soltanto il progetto, il run e le tre identità riservate. Nessuna chiave amministrativa viene trasferita all'APK. L'Admin ha privilegi globali sul backend: il trasporto dei test limita le scritture ai soli UUID QA autorizzati.

Gli indirizzi non hanno mailbox. L'utente non dispone di una casella dedicata: nessuna email viene inviata e consegna, conferma, recupero e callback PKCE reali restano da verificare.

## Autenticazione reale

Run `9cd19c6e-bbdd-4a06-b1db-dbf368297c58`, Android 12/API 31, debug APK:

1. Accesso con password tramite il client nativo e sessione cifrata Android Keystore.
2. Creazione di un nuovo fattore TOTP, challenge e verifica positiva fino ad `aal2`.
3. Chiusura delle altre sessioni: refresh della seconda sessione respinto dal server; refresh della corrente riuscito.
4. Rimozione del solo fattore creato, logout e pulizia locale verificati; `cleanup_pending=false`.

Evidenza: `artifacts/auth-qualification-9cd19c6e-bbdd-4a06-b1db-dbf368297c58.json`. Il test usa API native e storage reale; non verifica il submit della schermata di login, email o PKCE. Credenziali e segreto TOTP non compaiono nel report. Dopo la pulizia è stato rimosso soltanto il file di preferenze sessione osservato vuoto, per soddisfare il preflight Coppia; nessun reset generale dei dati dell'app.

## Regressioni locali

La prima suite integrata ha scoperto una sovrapposizione fra il ripristino del ViewModel e una nuova verifica MFA: una pubblicazione anticipata di `busy=false` consentiva l'avvio del secondo lavoro prima del finalizzatore del primo. Il ViewModel conserva ora `busy` fino al finalizzatore. Una regressione deterministica copre l'osservatore che avvia subito la challenge.

Le operazioni Admin conservano utente e revisione della sessione letta all'apertura. Cambio account durante il preflight, risposta tardiva e reset email legato al proprietario sono coperti da fixture; il reset mantiene il contratto pubblico senza header di autenticazione.

La prima prova reale Coppia, run `75789397-93d6-4774-9db2-8a75f262de73`, ha raggiunto invito, accettazione, due quote EUR/USD, isolamento dei movimenti, budget e revoca. Il caricamento dell'archivio ha poi fallito. Un diagnostico in sola lettura ha confermato una riga visibile per ciascun utente e `DateTimeParseException` sui timestamp con offset. La fixture su API 31 dimostra che `Instant.parse` accetta `Z` ma rifiuta gli offset; `OffsetDateTime.parse(...).toInstant()` conserva invece l'istante e i microsecondi. Il parser condiviso viene usato nell'archivio e nei due controlli dello storico cambi.

La prima pulizia host si è fermata su un filtro `eq` con virgolette aggiuntive. GET con lo stesso filtro: HTTP 400/SQLSTATE 22P02; la riga riservata è ancora presente e invariata. I filtri standalone usano ora il valore grezzo codificato nell'URL. Il recovery legacy resta limitato al run e UUID esatti, con due letture di prova e una prenotazione distinta prima del DELETE; le risposte perse non autorizzano un retry generico. Gli esiti falliti e le letture diagnostiche sono conservati separatamente.

Il recovery del primo run ha rimosso le righe registrate, chiuso entrambe le sessioni e completato la pulizia locale. Il risultato nativo originale resta fallito; `cleanup_complete=true` non lo trasforma in PASS. I report locali non segreti sono stati esportati e rimossi dal dispositivo solo dopo questa conferma, senza cancellare dati generali dell'app.

## Coppia reale dopo la correzione

Run `2eafa3d8-a68d-43dd-9331-1314329eeedd`, Android 12/API 31: invito via codice fra A e B, accettazione, spese EUR 10,01 e USD 7,00, split uguale e quota personalizzata 2,01, letture e mediane su entrambi gli account. Movimenti privati invisibili al partner e aggiornamento del movimento altrui respinto. Budget comune modificato da 3,00 a 4,00, revoca e caricamento dell'archivio riusciti. Spese, budget e audit della connessione revocata non sono più leggibili dai due token utente.

`artifacts/couple-qualification/2eafa3d8-a68d-43dd-9331-1314329eeedd/result.json` conferma l'esito nativo, la rimozione dei soli record registrati, logout A/B e pulizia locale completa. La chiave service-role resta in memoria sul computer e serve solo a rimuovere quei record, invisibili agli utenti dopo la revoca. Nessuna modifica a account, schema, trigger o RLS. Il CAS confronta tutti i campi scalari; i metadata JSON degli audit sono validati prima della cancellazione e non inclusi nel filtro CAS. I test non coprono modifiche privilegiate concorrenti dei metadata.

Il run verifica client e repository sul servizio reale. Non copre tutte le schermate, rifiuto/cancellazione dell'invito o ogni percorso di rimozione delle quote.

## Admin reale

Run `77485cf6-305d-4739-8f37-1013de64cea7`, Android 12/API 31: A e B ricevono HTTP 403 dall'endpoint Admin; il login Admin legge gli utenti e lo stato disabilitato delle registrazioni. L'elenco completo resta solo in memoria; il report conserva esclusivamente contatori e booleani. Il solo permesso `tcg` di B viene modificato e poi ripristinato con confronto dello stato precedente. Gli snapshot dei permessi dei tre QA e lo stato delle registrazioni coincidono alla fine; logout confermato.

Evidenza: `artifacts/admin-qualification-77485cf6-305d-4739-8f37-1013de64cea7.json`: due PATCH, tre audit di accesso QA, zero invii email, eliminazioni utenti, cambi registrazioni o scritture su altri utenti. Un preflight precedente si era fermato prima delle credenziali perché la proprietà Android del nome AVD era vuota; il runner usa ora `adb emu avd name`, verificato sul dispositivo. Questo PASS non certifica reset via email, eliminazione account o toggle globale delle registrazioni dalla schermata.

Il controllo finale in sola lettura `artifacts/qa-final-server-read-only-check.json` conferma tre identità QA, zero sessioni attive e fattori MFA per queste identità, permesso TCG attivo per tutti e un solo Admin. Registrazioni disabilitate; zero movimenti, richieste e collegamenti Coppia fra A e B rimasti. Gli audit di login dei QA sono dati del funzionamento ordinario, separati dai record finanziari temporanei.

| Prova | Risultato | Evidenza |
|---|---|---|
| JVM integrati dopo il fix timestamp | 514/514, zero fallimenti/errori/skip | `artifacts/qa-final-integrated-build.txt`, XML in `artifacts/qa-final-proof/jvm/` |
| Host Python | 36/36 | `artifacts/qa-host-all-tests-final.txt` |
| Lint debug | Zero errori, 32 avvertenze e 6 suggerimenti | `artifacts/qa-final-proof/lint-debug.xml` |
| UI Admin Android 12 | 4/4; 10,850 s | `artifacts/admin-owner-ui-qa12-final.txt` |
| Suite Android 12 prima del fix timestamp | 131/131; 350,851 s, singolo run completo | `artifacts/android12-qa-qualified-full.txt` |
| Suite finale Android 12 dopo il fix timestamp | 132/132; 373,054 s, singolo run completo | `artifacts/android12-qa-final-full.txt` |

I tempi sono osservazioni locali sull'AVD, non benchmark del telefono. Le otto classi remote opt-in sono escluse esplicitamente dalla suite ADB. La prima esecuzione UI Admin ha fallito per uso concorrente dello stato Compose nel dispatcher del test: corretta con il dispatcher di test standard, senza indebolire gli assert. I log falliti restano conservati. Gli hash dei sorgenti Kotlin e Gradle coincidono prima/dopo la build e la suite finale; report e hash dei due APK debug sono in `artifacts/qa-final-proof/`. API 35 non è stata rieseguita sui nuovi sorgenti. Nessun nuovo APK release è stato firmato o pubblicato; l'emulatore dedicato è stato spento dopo le prove.

## Gate ancora aperti

I PASS reali coprono soltanto le operazioni descritte. Restano aperti email/PKCE, modifiche account, eliminazione di un account sacrificabile, i percorsi Coppia/Admin esclusi, aggiornamento firmato con sessione autenticata e confronto finale della matrice. Google Books aveva restituito HTTP 429. Le nuove funzioni non sono state provate sul telefono fisico; la parità globale resta falsa e la 1.0.0 non è ancora qualificata.
