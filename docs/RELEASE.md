# APK e aggiornamenti

## Candidata locale 0.1.10-alpha — 12 ottobre 2026

APK immutabile: `artifacts/release-qualification/qualified-apks/savely-0.1.10-alpha-release.apk`, code 11, 2.747.518 byte, produzione `459bbac1a380f05b598f0712ee8e66bda3d4eae1`. SHA-256 `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa`. Non è una nuova release pubblica; il download pubblico resta la 0.1.9-alpha.

Firma v2 verificata, unico signer, certificato SHA-256 `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1`, continuo con gli APK precedenti. Manifest non debuggable/non testOnly, min SDK 26, target 36. Marker DEX R8 release/full 8.13.19 coerente con il mapping. Tutti i 19 asset coincidono byte per byte con il sorgente, incluse sette licenze; NOTICE/MPL upstream coincidono solo dopo normalizzazione CRLF→LF dichiarata. [Ispezione statica](../artifacts/release-qualification/apk-static-qualified.json), [licenze](DEPENDENCY_LICENSES.md).

536 JVM per variante passano; lint entrambe zero errori/32 warning/6 hint. Debug API 35 passa 170/170 in 562,616 s con fixture `74d7d0d`; API 31 passa 170/170 in 551,778 s, senza failure/skip. Il campione release R8 verifica upgrade autenticato API 35 code10→11, sessione/preferenze, export locale/annulla e recovery dopo process death. Sul nuovo hash il nuovo run API 31 passa login, upgrade, profilo, English/Light e logout con sessione server conservata e pulizia finale. Export/BANK/FX appartengono al precedente run separato, chiuso con logout manuale UI/server zero; quattro tentativi driver falliti conservati. [Qualifica firmata](SIGNED_RELEASE_QUALIFICATION.md). R01 è **PASS LOCALE** nel criterio originale; il campione R8 non è una suite di 170 casi sul release.

Conservati 81 PNG e 81 JSON per API; raccolta conclusa ed emulatori spenti, come confermato dal responsabile dell’integrazione.

Il backup AES esistente è stato recuperato per firmare un APK di test con certificato conforme e v2 valida, senza DPAPI o password originale. Tamper respinto e file privati temporanei rimossi; stesso computer, recupero fuori dispositivo ancora non qualificato. Il precedente candidato code11/hash `ac914bc580091ae435afa7f15a3a6ff4e50be7715e0574b0ba472a4b165ea5bd` conserva il failure export dimostrato; non è sostituito o dichiarato riuscito dal nuovo hash. Nessuna CI o dichiarazione di parità globale.

Il [consuntivo API 31](../artifacts/release-qualification/android31-signed-final-summary.json) separa l’upgrade `b6e80895-2fb4-4a31-8e9f-fcb28fa74064` (harness `f59fe64`, SHA-256 `4009c7f62b869842c368dc09fd3f346800db2c55b3c7d4c8122970690850603b`) dal precedente export/BANK/FX `abd8d2dd-d137-484a-9d6e-b42690d757e5`: sono sessioni server diverse. Il nuovo run termina con tre identità QA, zero sessioni/transazioni/fattori e registrazioni disabilitate. API 35 mantiene il proprio harness originale, SHA `a0f3c2a41674ff3af1970d4bbefac5205310f63586981be424da02cc5d0e59c9`. Quattro tentativi API 31 falliti restano tali; corretti solo navigazione/bounds e ritorno al profilo visibile prima del logout nel driver, senza cambi di produzione.

## Candidata 0.1.9-alpha — 12 ottobre 2026

APK immutabile: `artifacts/savely-0.1.9-alpha-release-candidate.apk`, versionCode 10, 2.718.365 byte. SHA-256: `a994ae06b3919753c8b6d9547f05586000e9242a0816b70ab0f628c740c931f1`. Il file destinato alla distribuzione si chiama `savely-0.1.9-alpha.apk`, con gli stessi byte. I due precedenti candidati 0.1.9 rimangono locali e non sono distribuiti.

Firma v2 RSA 3072 verificata, certificato SHA-256 `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1`, identico alle versioni precedenti; min SDK 26, target 36. La scansione ZIP non trova i pattern delle chiavi `sb_secret_` o JWT `service_role`: non è un audit universale dei segreti.

503 JVM e due singole suite complete 130/130 su API 31 e 35 passano; lint debug/release senza errori, con 32 avvertenze e 6 suggerimenti ciascuno. L'aggiornamento firmato 0.1.8/code 9→0.1.9/code 10 sul solo SavelyTest/API 35 conserva l'inglese senza disinstallazione. Avvio anonimo e screenshot finale sono verificati dopo aver selezionato Wait nell'avviso osservato di System UI. Non è verificato l'upgrade con sessione autenticata.

Il nuovo smoke QA conferma 41 contratti su 42 e la pulizia completa; Google Books risponde HTTP 429. Il conteggio precedente 42/43 era errato: anche il report sorgente della 0.1.8 contiene 41 PASS su 42. Gli asset pubblicati precedentemente restano immutabili. I gate reali e le condizioni della 1.0 sono in [PARITY_0_1_9.md](PARITY_0_1_9.md). Nessuna CI GitHub, modifica web o release stabile.

Pubblicazione [v0.1.9-alpha](https://github.com/GoryNickel9/Savely-Android-Releases/releases/tag/v0.1.9-alpha) verificata senza autenticazione il 12 ottobre 2026: quattro asset raggiungibili, SHA-256 dell’APK scaricato e checksum coincidono, VALIDATION.json associa il sorgente al commit `c470258e6be6241fc3dae05915a49667a92a2a0a`. Il canale alpha legge i metadati pubblici; `/releases/latest` risponde 404 perché non è presente una stabile. Evidenza: `artifacts/public-release-verification-0.1.9.json`. La vecchia 0.1.8 e i suoi asset restano invariati.

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
