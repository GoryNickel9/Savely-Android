# Verifica Savely Android

## Risultati eseguiti il 11 ottobre 2026

Su Windows, JBR Android Studio 21, Gradle 8.13, SDK 36: APK debug e release ottimizzata compilati sull'AVD SavelyTest (Android 15/API 35, x86_64, Pixel 2, WHPX, GPU software). I 171 test JVM e 26 test Android integrati passano senza failure, errori o skip. Il test Python del mapping sorgenti passa; il report sul checkout web di riferimento indica zero file cambiati e lascia la parità non verificata.

La prima esecuzione dei cinque test Android ha passato quattro casi e fallito il caso del ciphertext corrotto: il test scriveva in device-protected storage mentre lo store ricavava l'application context e leggeva un'altra area. Il test usa ora un ContextWrapper con namespace isolato. La seconda esecuzione ha passato tutti i nove casi, inclusi quattro flussi HTTPS con Keystore e JSON Android, senza skip.

Il primo lint completo ha rilevato un errore nel percorso SDK del file locale: il separatore `C:` non era escaped nel formato properties. È stato corretto in `C\:`. La verifica successiva passa per debug e release: zero errori e 23 avvertenze (versioni dipendenze e stile KTX). Sono state aggiunte regole esplicite per escludere backup e trasferimento dati su Android 12+, seguendo la [documentazione Android](https://developer.android.com/identity/data/autobackup), oltre al flag allowBackup disabilitato.

Il cold start misurato una volta con `adb shell am start -W` ha restituito 4696 ms, prima della stabilizzazione dell'emulatore e con Gradle attivo. È un'osservazione del dispositivo virtuale, non un benchmark del telefono.

APK release: 1.784.528 byte, firma APK v2 verificata da apksigner, SHA-256 `ada5e306ef285191ce6949e7d2be01d1e87a97466251acf458c12481cb58c035`. L'installazione release e l'apertura senza account sono riuscite; una misura singola di cold start restituisce 1443 ms. Screenshot controllati in portrait, landscape e a 360×640 dp con font scale 1,5: campi e pulsanti restano raggiungibili scorrendo. Questa prova non copre le schermate dopo accesso o una tastiera su telefono reale.

L'APK aggiornato è stato installato sopra la precedente alpha interna con la stessa firma: entrambe le installazioni sono riuscite e l'accesso anonimo resta leggibile. Entrambe le alpha interne hanno versionCode 1 e non sono release pubblicate: questa sostituzione verifica la compatibilità della firma, senza certificare conservazione di una sessione reale o l'aggiornamento su telefono. Prima di distribuire una versione successiva occorre aumentare versionCode.

I nuovi test FIRE e ricorrenze hanno richiesto correzioni del solo harness: attesa di fine scroll e coordinate stabili prima del touch, attesa del controllo abilitato dopo refresh e composizione dell'header prima dei messaggi. I nove casi mirati passano con touch normale; la successiva suite completa passa tutti i 26 test. La produzione ha ricevuto una regressione separata per l'etichetta della fase di validazione di una ricorrenza successiva a un record già salvato.

## Comandi

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
python -m unittest discover -s tools -p 'test_web_parity_audit.py'
python tools/web_parity_audit.py --web-repo ../Savely --output artifacts/web-parity-audit.json
```

I report sono in `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/` e `app/build/reports/lint-results-debug.html`. Gli 26 test device ordinari usano preferenze isolate e un server HTTPS locale con certificato temporaneo; non creano utenti o transazioni in produzione.

## Smoke aggiuntivo sul backend reale

`RealBackendSmokeTest` è escluso dalla suite ordinaria e richiede un avvio esplicito, build debug e sessione privata dell'account E2E documentato nella repo web. Il tentativo di creare un nuovo account sintetico è stato respinto dal trigger delle registrazioni disabilitate (HTTP 500/P0001); non sono stati cambiati trigger, impostazioni o password. È stato riutilizzato l'account confermato `e2e-savely@example.com`, senza inviare email, con una sessione temporanea ottenuta tramite l'API amministrativa. La chiave amministrativa è rimasta nel processo host e non è entrata nell'app.

Il test eseguito sull'AVD Android 15 è passato in 17,092 secondi: identità server e permessi, sessione Keystore e nuovo client, dashboard nativa, creazione di una transazione con marker UUID da 0,01 EUR, visualizzazione in Movimenti dopo ricreazione dell'activity, modifica a 0,02 EUR, cancellazione normale e assenza dopo ricaricamento. Il risultato locale `artifacts/backend-smoke-result.json` riporta `stage=passed`, `hard_deleted=true`, `cleanup_pending=false`; input privato consumato e logout concluso. Nessun dato precedente è stato modificato.

Una seconda esecuzione, tramite l'helper, è passata in 13,359 secondi con una nuova sessione. Il marker e l'ID nel risultato vengono aggiornati a ogni esecuzione; il risultato corrente conferma ancora eliminazione normale e nessuna pulizia pendente.

Questo test verifica la build debug e la sessione temporanea: non copre login con password, CRUD dalla UI release ottimizzata o lettura dal browser web. Non rilanciare il test con la sessione revocata; serve una nuova sessione del medesimo account.

`tools/run-backend-smoke.ps1` prepara una nuova sessione per il solo ID/email E2E già esistente tramite CLI Supabase autorizzata, la trasferisce via stdin nello storage privato del debug APK, lancia il test e verifica la pulizia. È limitato all'AVD SavelyTest, non modifica password o configurazioni e non fa parte della CI. Richiede APK debug/test già compilati, configurazione pubblica e CLI autenticata. Non tenta disinstallazioni per risolvere firme differenti; il test rifiuta di sovrascrivere una sessione locale esistente.

### Arresto completo e ripristino della sessione

`tools/run-backend-smoke.ps1 -ProcessDeath` esegue due metodi opt-in di `RealProcessDeathSmokeTest` in instrumentation separate. La prima verifica l'identità reale e salva la sessione cifrata; l'host arresta il package e controlla che non esista più un processo. La seconda rilegge il Keystore senza ricevere nuovi token, verifica identità e dashboard nativa, poi esegue logout e cancellazione locale. La suite ordinaria esclude anche questa classe.

La prima prova dell'11 ottobre è passata in 1,723 e 8,702 secondi; la ripetizione dopo il fix di pulizia passa in 1,317 e 9,225 secondi. Il risultato corrente `artifacts/backend-process-death-result.json` registra PID 16350 e 16417, avvio del secondo processo successivo al checkpoint, `new_process_verified=true`, `native_dashboard_verified=true`, `cleanup_local=true`, `logout_server=true`, `cleanup_pending=false`. Non sono stati creati dati finanziari né cambiate password o impostazioni. Questa prova copre il riavvio del processo nella build debug; telefono, login con password e aggiornamento release restano verifiche distinte.

È stato verificato anche il ramo di interruzione host dopo la prima fase: persistenza in 1,543 secondi e pulizia opt-in in 1,571 secondi. Il cleanup richiede lo stesso run UUID, checkpoint, account E2E e session UUID; conserva qualsiasi sessione diversa. `artifacts/backend-process-death-cleanup-result.json` registra `stage=cleanup_passed`, `cleanup_local=true`, `logout_server=true`, `cleanup_pending=false`. Lo script temporaneo di fault injection è stato rimosso. Il trasferimento privato viene ripulito anche se la scrittura stdin fallisce; dispositivo offline o pulizia non confermata producono un avviso esplicito, senza cancellare preferenze alla cieca.

Su questo host Windows Java NIO non riesce a creare il socket locale Gradle. Il workaround verificato è una variabile limitata al processo PowerShell, che lascia ricadere Gradle sulla connessione TCP:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/savely-nonexistent-socket-dir'
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Non creare quella directory e non modificare opzioni Java globali. Il percorso non è necessario su CI Linux.

## Gate prima di dichiarare stabile M1

1. Account sintetico: login con password e lettura della transazione anche dal sito. Sessione temporanea, riavvio completo e logout reale sono verificati; il rinnovo con token scaduto è coperto da fixture.
2. Emulatore: test senza skip, errori e rete assente, tastiera, orientamento, font grandi e navigazione; lint senza errori bloccanti.
3. Telefono: installazione APK firmato e aggiornamento con la stessa chiave, senza perdita della sessione.

MFA, Coppia con due account, Admin e servizi esterni richiedono verifiche dedicate prima della parità completa. Il test locale del contratto non prova che schema, RLS e funzioni distribuite siano uguali al mock.
