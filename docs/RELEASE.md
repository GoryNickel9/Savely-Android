# APK e aggiornamenti

La versione iniziale è un'alpha. Build e test locali non sostituiscono i gate backend e telefono in `TESTING.md`. Non è stata pubblicata una release stabile.

Ultimo APK locale verificato: `artifacts/savely-0.1.1-alpha.apk`, versionCode 2, 1.800.912 byte, SHA-256 `2a53238a2a46cb96b0af0f5163675e7e87d2c27e4bfa58b3fff1f69135eb4e04`. Firma v2 verificata, certificato SHA-256 `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1`. Aggiornamento sopra la 0.1.0-alpha riuscito sul solo AVD SavelyTest, senza account; sessione reale e telefono restano aperti. La precedente alpha e i due checksum sono conservati in `artifacts/`, esclusa da Git.

## Debug

`./gradlew :app:assembleDebug` produce `app/build/outputs/apk/debug/app-debug.apk`, firmato dalla chiave debug locale. Serve alle prove; una firma diversa richiede disinstallazione e può rimuovere la sessione e le preferenze. Non usare la chiave debug per la distribuzione definitiva.

## Firma di distribuzione

Il build legge la chiave solo attraverso queste variabili:

```text
SAVELY_KEYSTORE
SAVELY_STORE_PASSWORD
SAVELY_KEY_ALIAS
SAVELY_KEY_PASSWORD
```

Con le variabili configurate, `./gradlew :app:assembleRelease` genera un APK ottimizzato firmato. Senza chiave genera un APK unsigned, che non va offerto come installabile. La configurazione backend deve includere esclusivamente la chiave pubblica, mai service role, token CardTrader o credenziali server.

Conservare un backup protetto della chiave e della password fuori da Git. Per tutti gli aggiornamenti dello stesso applicationId mantenere la firma e aumentare versionCode. Verificare con `apksigner verify --verbose --print-certs` e pubblicare il checksum SHA-256 insieme all'APK.

Su questo checkout è stata generata una chiave locale RSA 3072 in `signing/savely-release.jks`. La password casuale è protetta per l'utente Windows con DPAPI in `signing/password.dpapi`; entrambi sono esclusi da Git. `tools/build-release.ps1` carica la password senza stamparla e ripristina le variabili del processo dopo la build. Il file DPAPI non è trasferibile a un altro utente o computer: prima della distribuzione definitiva serve un backup della chiave e della password in un archivio protetto recuperabile. La chiave non è stata caricata su GitHub.

## Distribuzione

I workflow Android vivono solo in questa repo. Sono presenti build/test, audit sorgenti e `release.yml` per una build firmata manuale; build/test della versione 0.1.0-alpha passa su GitHub ([CI 38154018127](https://github.com/GoryNickel9/Savely-Android/actions/runs/38154018127)); firma e audit manuali non sono stati eseguiti. La prima CI ha fallito il setup SDK perché chiedeva il pacchetto rimosso tools; il workflow ora richiede esplicitamente i pacchetti Android 36 supportati. Il workflow di firma verifica APK e checksum e carica artifact, senza pubblicare una GitHub Release. Richiede variabili pubbliche Supabase e i secret `SAVELY_KEYSTORE_BASE64`, `SAVELY_STORE_PASSWORD`, `SAVELY_KEY_ALIAS`, `SAVELY_KEY_PASSWORD`; non sono ancora configurati. L'audit non cambia Savely o la baseline. Se Savely è privata, necessita di `SAVELY_WEB_READ_TOKEN` con accesso in sola lettura a quella repo.

L'installazione diretta richiede il consenso Android per la sorgente usata per aprire l'APK. La schermata nativa Versione e aggiornamenti consulta le release stabili pubbliche e apre note e download nel browser; l'utente conferma l'installazione. Un controllo pubblico all'apertura della sessione mostra un collegamento quando esiste una versione stabile più recente; un errore non interrompe le funzioni finanziarie e può essere ritentato dalla schermata. Il controllo usa la [REST API GitHub](https://docs.github.com/en/rest/releases/releases#get-the-latest-release) senza credenziali; le prerelease non sono proposte automaticamente. Il download e l'installazione restano gestiti dal browser e da Android secondo il [normale intent web](https://developer.android.com/guide/components/intents-common#Browser).

La repository Android è attualmente privata. Il controllo pubblico non può distinguerla da una repository senza release pubbliche e non dichiara l'app aggiornata quando riceve HTTP 404. Il link apre comunque le release nel browser, dove il proprietario può autenticarsi a GitHub. Per aggiornamenti automatici distribuibili ad altri utenti serviranno metadati pubblici o una destinazione pubblica concordata; la visibilità della repo non è stata cambiata e nessun token GitHub viene incluso nell'APK.
