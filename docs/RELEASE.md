# APK e aggiornamenti

## Candidata 0.1.9-alpha — 12 ottobre 2026

APK immutabile: `artifacts/savely-0.1.9-alpha-release-candidate.apk`, versionCode 10, 2.718.365 byte. SHA-256: `a994ae06b3919753c8b6d9547f05586000e9242a0816b70ab0f628c740c931f1`. Il file destinato alla distribuzione si chiama `savely-0.1.9-alpha.apk`, con gli stessi byte. I due precedenti candidati 0.1.9 rimangono locali e non sono distribuiti.

Firma v2 RSA 3072 verificata, certificato SHA-256 `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1`, identico alle versioni precedenti; min SDK 26, target 36. La scansione ZIP non trova i pattern delle chiavi `sb_secret_` o JWT `service_role`: non è un audit universale dei segreti.

503 JVM e due singole suite complete 130/130 su API 31 e 35 passano; lint debug/release senza errori, con 32 avvertenze e 6 suggerimenti ciascuno. L'aggiornamento firmato 0.1.8/code 9→0.1.9/code 10 sul solo SavelyTest/API 35 conserva l'inglese senza disinstallazione. Avvio anonimo e screenshot finale sono verificati dopo aver selezionato Wait nell'avviso osservato di System UI. Non è verificato l'upgrade con sessione autenticata.

Il nuovo smoke QA conferma 41 contratti su 42 e la pulizia completa; Google Books risponde HTTP 429. Il conteggio precedente 42/43 era errato: anche il report sorgente della 0.1.8 contiene 41 PASS su 42. Gli asset pubblicati precedentemente restano immutabili. I gate reali e le condizioni della 1.0 sono in [PARITY_0_1_9.md](PARITY_0_1_9.md). Nessuna CI GitHub, modifica web o release stabile.

La pubblicazione della candidata deve ancora essere verificata anonimamente; il download corrente resta quello della 0.1.8 finché tale controllo non è completato.

## Evidenza storica 0.1.8

La 0.1.8-alpha è una prerelease. Le prove locali e QA sono in TESTING.md e ROADMAP_CLOSURE.md; login reale con password, email/PKCE, MFA positivo, due utenti Coppia e Admin autorizzato non sono certificati. Nessuna release stabile è dichiarata.

## APK e continuità della firma

APK: `artifacts/savely-0.1.8-alpha-final.apk`, versionCode 9, 2.582.341 byte. SHA-256: `247950fadb10e93cb844a48b22d2792d011eca10e92afb83bb4cb56496801482`. Il nome del file distribuito è `savely-0.1.8-alpha.apk` (stessi byte).

Firma v2 verificata, RSA 3072, certificato SHA-256 `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1`: stesso certificato delle 0.1.5 e 0.1.7. Min SDK 26 e target 36. L’APK contiene la chiave pubblicabile Supabase; il controllo sul contenuto decompresso non trova le due chiavi server controllate. Non è una prova universale dell’assenza di qualsiasi segreto.

Sul solo AVD Android 15, l’aggiornamento firmato 0.1.7/code 8 → 0.1.8/code 9 conserva la preferenza inglese. L’APK finale è installato e la schermata Accedi ispezionata, inclusi i pulsanti Show/Hide. Non verifica una sessione autenticata conservata durante l’aggiornamento. Il primo candidato non distribuito resta immutabile; il confronto ZIP conferma che il finale cambia soltanto le due etichette inglesi nel catalogo JSON e mantiene `classes.dex` byte per byte. Le precedenti prove backend restano associate alla stessa logica Kotlin.

La 0.1.7 installata dall’utente rimane un’evidenza distinta: uso generale corretto sul Doogee S100 Android 12, senza una nuova prova dettagliata dei flussi 0.1.8.

## Download e aggiornamenti

La distribuzione della 0.1.8 usa il tag [v0.1.8-alpha](https://github.com/GoryNickel9/Savely-Android-Releases/releases/tag/v0.1.8-alpha), con quattro asset: APK, `SHA256SUMS.txt`, `RELEASE_NOTES.md` e `VALIDATION.json`. Il rapporto pubblico non contiene identificativi QA o credenziali.

Sorgenti privati in [Savely-Android](https://github.com/GoryNickel9/Savely-Android); APK, checksum e note in [Savely-Android-Releases](https://github.com/GoryNickel9/Savely-Android-Releases), repository pubblico separato. Nessun token GitHub è incluso nell’app.

La 0.1.8 consulta quella destinazione senza autenticazione. Un’installazione alpha cerca la versione numericamente più recente tra le prime 20 release non draft, comprese le prerelease; una versione stabile consulta soltanto la release stabile più recente. Risposte e note hanno limiti di dimensione; tag, versione e URL della release vengono validati. Un errore del controllo non interrompe le funzioni finanziarie e resta ritentabile dalla schermata Versione e aggiornamenti.

**Il primo passaggio dalla 0.1.7 alla 0.1.8 richiede il download manuale:** il vecchio APK conserva l’indirizzo della repository privata. Dalla 0.1.8 i metadati pubblici sono raggiungibili. Il controllo propone un collegamento; download e conferma dell’installazione spettano al browser e ad Android. Non esiste installazione silenziosa. I dati backend possono cambiare senza APK, ma modifiche a UI e logica Kotlin richiedono una nuova versione.

## Build locale

`./gradlew :app:assembleDebug` genera un APK firmato con la chiave debug. Non distribuire questa firma come release. Non disinstallare un’app con dati dell’utente per aggirare una firma differente.

Il build release legge esclusivamente `SAVELY_KEYSTORE`, `SAVELY_STORE_PASSWORD`, `SAVELY_KEY_ALIAS` e `SAVELY_KEY_PASSWORD`. Senza configurazione produce un APK unsigned. `tools/build-release.ps1` carica la password DPAPI in memoria, genera la build e ripristina le variabili del processo; nessuna password viene stampata.

La chiave locale è in `signing/savely-release.jks`, la password DPAPI in `signing/password.dpapi`; directory e credenziali escluse da Git. La configurazione dell’APK deve contenere solo URL e chiave pubblica Supabase. Credenziali amministrative, CardTrader e server restano fuori dalla build.

## Backup portabile

`tools/backup-signing.ps1` crea un archivio AES-256-GCM della chiave, alias e password, con chiave di recupero separata e ACL limitate all’utente Windows. Il backup è fuori da Git in `C:/Users/GoryNickel/Documents/Savely-Signing-Backup`. Sono passati decifratura senza DPAPI, confronto byte per byte e rifiuto di un archivio alterato. Non pubblicare nessuno dei due file.

Entrambi i file sono ancora sullo stesso computer: `offDeviceBackupVerified=false`. Il backup è portabile, ma la sopravvivenza alla perdita del computer richiede una copia protetta altrove, non ancora verificata. La chiave di recupero va conservata separatamente dall’archivio.

## CI e pubblicazione

Per questa versione build, lint, test e firma sono locali. I workflow Android sono tutti manuali (`workflow_dispatch`); nessuna CI GitHub è stata avviata. Le vecchie esecuzioni 0.1.5 rimangono evidenza storica, non validazione della 0.1.8. Nessun workflow o deploy del sito è stato modificato.

Prima di pubblicare: conservare APK immutabile e checksum, verificare firma e aggiornamento, associare le note al commit dei sorgenti, caricare solo APK/checksum/note e un rapporto pubblico privo di dati QA. Dopo la pubblicazione verificare metadati e download senza autenticazione, e confrontare il SHA-256 scaricato.

Verifica della pubblicazione eseguita il 12 ottobre 2026: metadati alpha e tutti e quattro gli asset raggiungibili senza autenticazione; APK scaricato di 2.582.341 byte, SHA-256 uguale al finale firmato e al checksum pubblico. `VALIDATION.json` associa il codice APK al commit `404af10e7cc23d1ae80689eb618b28adb671da55`. `/releases/latest` risponde 404 perché è presente solo la prerelease; il canale alpha consulta l’elenco pubblico. Evidenza locale: `artifacts/public-release-verification-0.1.8.json`, copiata nel bundle `verified-0.1.8`. Nessuna CI nuova dopo il push.
