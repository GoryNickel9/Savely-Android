# Verifica Savely Android

## Risultati eseguiti il 11 ottobre 2026

La build integrata 0.1.3-alpha passa 226 test JVM e 39 Android, zero failure/errori/skip, lint debug/release zero errori e 24 avvertenze. I sorgenti app corrispondono allo snapshot prima della build. Aggiunge 14 JVM e tre Android: origine API canonica con callback/Origin invariati e redirect rifiutati, retry MFA, quarto segnale Insights e creazione/modifica Fumo con derivati null al reset. Suite Android: 281.334 secondi, osservazione locale sull’AVD Android 15. Report conservati in `artifacts/verified-0.1.3/`.

APK 0.1.3-alpha: versionCode 4, 1.800.916 byte, SHA-256 `beb12d54d9660e18ae08ed01fac7177e2ca1f9d334a071a5edbd71ec38f5aaaf`, firma v2 e stessa chiave. Aggiornamento 0.1.2→0.1.3 e login anonimo verificati sull’AVD. Scansione entry APK senza credenziali privilegiate rilevate. Non sono stati ripetuti gli smoke finanziari reali del blocco 0.1.2. La prova HTTP anonima del routing ha osservato 308 dall’apex e 400/403 dalle API canoniche con body vuoto; non verifica email o ruoli Admin reali.

Su Windows, JBR Android Studio 21, Gradle 8.13, SDK 36: APK debug e release ottimizzata 0.1.2-alpha compilati e testati sull'AVD SavelyTest (Android 15/API 35, x86_64, Pixel 2, WHPX, GPU software). I 212 test JVM e 36 Android integrati passano senza failure, errori o skip. Lint debug/release: zero errori e 23 avvertenze note. Il test Python del mapping sorgenti passa; il report sul checkout web indica zero file cambiati e lascia la parità non verificata. I report sono conservati in `artifacts/verified-0.1.2/`; quelli della 0.1.1 restano separati.

La 0.1.2-alpha aggiunge 19 test JVM e quattro Android: invalidazione globale della sessione da chiamate esterne al ViewModel, protezione da risposte tardive e nuovi login, segnale saving_month con severità/filtri e Guadagno orario Poker con validazione, singolo submit e arrotondamento binario coerente con il web. La prima verifica completa passa in 10m31s; dopo la correzione dell'avvertenza Kotlin sul copy della data class, la verifica finale passa in 8m36s. XML finale: 212 JVM e 36 Android, zero failure/errori/skip; tempo della suite Android 212,027 secondi. Questi tempi descrivono esecuzioni locali, non benchmark del telefono.

APK release 0.1.2-alpha: 1.800.912 byte, versionCode 3, SHA-256 `080ed2c9da69779d5e64f2e19186bee537a4e4ade4c93c3edceead7cbbbdd384`, firma v2 con lo stesso certificato delle alpha precedenti. Scansione delle entry APK senza credenziali privilegiate rilevate. Aggiornamento sopra la 0.1.1/versionCode 2 riuscito sul solo AVD, senza account; schermata login controllata. Cold start singolo `am start -W`: 1938 ms, riferito al primo frame. Non verifica sessione conservata nell'aggiornamento o prestazioni del telefono.

Ripetuti sulla build debug 0.1.2 anche gli smoke reali: run Android→web `c9e470f8-c3fe-4d39-a436-0217155fe3f0`, preparazione instrumentation 12,620 s e cleanup 3,378 s; riga EUR 0,01 letta nell'app e nel browser, screenshot controllato, rimozione/logout/clear confermati e nessuna pulizia pendente. Run process death `768413e0-8311-4ef8-ba7b-f9279a36a7b2`, 2,089/9,670 s, PID 25289→25343, dashboard e cleanup confermati. Il primo avvio dell'helper Android-web aveva trovato il package rimosso da Gradle: nessuna creazione finanziaria, sessione temporanea revocata. Il helper ora verifica package debug/test e debuggability prima di preparare credenziali; il rifiuto per test APK assente è stato eseguito e conserva il report riuscito.

La prima esecuzione dei cinque test Android ha passato quattro casi e fallito il caso del ciphertext corrotto: il test scriveva in device-protected storage mentre lo store ricavava l'application context e leggeva un'altra area. Il test usa ora un ContextWrapper con namespace isolato. La seconda esecuzione ha passato tutti i nove casi, inclusi quattro flussi HTTPS con Keystore e JSON Android, senza skip.

Il primo lint completo ha rilevato un errore nel percorso SDK del file locale: il separatore `C:` non era escaped nel formato properties. È stato corretto in `C\:`. La verifica successiva passa per debug e release: zero errori e 23 avvertenze (versioni dipendenze e stile KTX). Sono state aggiunte regole esplicite per escludere backup e trasferimento dati su Android 12+, seguendo la [documentazione Android](https://developer.android.com/identity/data/autobackup), oltre al flag allowBackup disabilitato.

Il cold start misurato una volta con `adb shell am start -W` ha restituito 4696 ms, prima della stabilizzazione dell'emulatore e con Gradle attivo. È un'osservazione del dispositivo virtuale, non un benchmark del telefono.

APK release 0.1.1-alpha: 1.800.912 byte, versionCode 2, firma APK v2 verificata da apksigner, SHA-256 `2a53238a2a46cb96b0af0f5163675e7e87d2c27e4bfa58b3fff1f69135eb4e04`. L'installazione sopra la 0.1.0-alpha con versionCode 1 e la stessa firma passa; avvio senza account e screenshot controllati, cold start singolo 1636 ms. Non verifica conservazione di una sessione reale né prestazioni del telefono. Sulla precedente 0.1.0, screenshot portrait, landscape e a 360×640 dp con font scale 1,5 mostrano campi e pulsanti raggiungibili scorrendo.

La prima sostituzione fra alpha interne 0.1.0 usava versionCode 1 per entrambe. La prova successiva 0.1.0→0.1.1 verifica anche l'aumento reale a versionCode 2. Non sono release stabili pubblicate.

I nuovi test FIRE e ricorrenze hanno richiesto correzioni del solo harness: attesa di fine scroll e coordinate stabili prima del touch, attesa del controllo abilitato dopo refresh e composizione dell'header prima dei messaggi. I nove casi mirati passano con touch normale; la successiva suite completa passa tutti i 26 test. La produzione ha ricevuto una regressione separata per l'etichetta della fase di validazione di una ricorrenza successiva a un record già salvato.

La 0.1.1 aggiunge 22 test JVM e sei test Android: mediana globale Budget e mezzo centesimo, soglia perdita esatta, parser/persistenza/ripresa Manga e filtri Libreria/TCG. Il primo run collezioni si bloccava durante lo scroll perché il dispatcher manuale non eseguiva la coroutine accodata: corretto usando la regola Compose standard. Un matcher del totale Libreria selezionava anche i singoli record: ora identifica il riepilogo completo. La suite finale passa tutti i 32 casi con touch normali e timeout dei nuovi test, senza cambiamenti di produzione per queste due correzioni del harness.

## Comandi

La CI della 0.1.2-alpha, commit `8e6dd4ec9c013f9df7880491b3447ad43edd1f7f`, passa entrambi i job: [esecuzione 38160631288](https://github.com/GoryNickel9/Savely-Android/actions/runs/38160631288). Il report Android scaricato conferma 36 test, zero failure, zero skip e 100% di successo; report conservato in `artifacts/ci-38160631288-ui/`. Questa revisione precede il quarto rilevatore Insights e la correzione dell'origine delle API web.

Anche la CI della 0.1.1-alpha, commit `3b086777d2104514e0cd7a797ffbe0c1d8dea723`, passa entrambi i job: [esecuzione 38157386878](https://github.com/GoryNickel9/Savely-Android/actions/runs/38157386878). Il report Android scaricato conferma 32 test, zero failure, zero skip e 100% di successo. Questo risultato precede il blocco 0.1.2 con invalidazione foreground e nuovo segnale Insights.

La CI GitHub del commit `020ac8161fcaeba3b2e1c75cdc6ae79edb90a7c8` passa sia build/lint/JVM sia instrumentation Android API 35: [esecuzione 38154018127](https://github.com/GoryNickel9/Savely-Android/actions/runs/38154018127). Questa revisione copre l'alpha 0.1.0, prima dei nuovi blocchi Budget/Manga. La prima esecuzione aveva fallito il setup SDK richiedendo il pacchetto rimosso `tools`; il workflow ora specifica platform-tools, platform Android 36 e build-tools 36.0.0.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
python -m unittest discover -s tools -p 'test_web_parity_audit.py'
python tools/web_parity_audit.py --web-repo ../Savely --output artifacts/web-parity-audit.json
```

I report sono in `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/` e `app/build/reports/lint-results-debug.html`. I 39 test device ordinari usano preferenze isolate e un server HTTPS locale con certificato temporaneo; non creano utenti o transazioni in produzione. Le tre classi smoke reali sono escluse da questa suite e richiedono un avvio esplicito.

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

## Lettura sul sito distribuito

Lo [smoke web](TESTING_WEB_READ.md) passa su `https://www.savely.cc/transactions`, origine canonica osservata tramite redirect HTTP 308. Edge usa un processo e contesto temporanei separati. Un movimento host da EUR 0,01 compare una sola volta con marker UUID e importo corretto; riga rimossa e sola sessione QA revocata, senza pulizia pendente. Non sono consentite scritture dal browser.

È passato anche il [flusso unico Android→sito](TESTING_ANDROID_WEB.md), run `99480d6e-a832-4e35-a72b-5f6057e9599f`: creazione tramite repository nel processo Android, lettura nella vera MainActivity e lettura della stessa riga nel browser. Preparazione instrumentation 12,456 secondi, cleanup 2,821 secondi; questi tempi non misurano l'intero flusso. Il report finale conferma eliminazione, logout della sola sessione temporanea, clear locale e `cleanup_pending=false`. Screenshot della riga controllato. Submit della form Android, login con password, telefono e SHA del deploy restano non verificati.

## Gate prima di dichiarare stabile M1

1. Account sintetico: login con password. CRUD repository nativo, flusso unico Android→sito, sessione temporanea, riavvio completo e logout reale sono verificati; il rinnovo con token scaduto è coperto da fixture. Il submit della form sul backend reale resta distinto dallo smoke repository.
2. Emulatore: test senza skip, errori e rete assente, tastiera, orientamento, font grandi e navigazione; lint senza errori bloccanti.
3. Telefono: installazione APK firmato e aggiornamento con la stessa chiave, senza perdita della sessione.

MFA, Coppia con due account, Admin e servizi esterni richiedono verifiche dedicate prima della parità completa. Il test locale del contratto non prova che schema, RLS e funzioni distribuite siano uguali al mock.
