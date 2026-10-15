# Verifica delle correzioni dell'audit — 12 ottobre 2026

Sorgente finale: `4d6a37667961acf91e042a457c0af6b0e572975a`, successivo all'audit statico `75d30ed`. La produzione e i test JVM sono identici a `935c7ba`, sul quale sono stati eseguiti i 528 casi; il commit successivo modifica soltanto lo scroll della fixture Android FIRE. Gradle conferma gli input JVM invariati e non ne ripete l'esecuzione. Verifica locale su Windows, JBR di Android Studio, 8 CPU logiche e 31,9 GiB RAM; nessuna CI GitHub. Il riferimento web resta `1cfcf6297e85d56bfafe3522362ceecef6f6b281`.

## Esiti

| Controllo | Risultato | Evidenza locale |
|---|---|---|
| JVM finali | 528/528, zero fallimenti/errori/skip | `artifacts/static-audit-verification/integrated-final.log`, `final-jvm-results/` |
| Host Python | 36/36 | `artifacts/static-audit-verification/host-python.log` |
| Build debug e APK test | Superata; esecuzione integrata 6m18s | `artifacts/static-audit-verification/build-result.json` |
| Lint debug/release | Zero errori, 32 avvertenze e 6 suggerimenti per variante | XML in `artifacts/static-audit-verification/` |
| Android 12/API 31 | 132/132, zero fallimenti/skip; singolo run completo, 666,191 s | `artifacts/static-audit-verification/android12-full-final.log` |
| Android 15/API 35 | 132/132, zero fallimenti/skip; singolo run completo, 644,736 s | `artifacts/static-audit-verification/android15-full-final.log` |
| FIRE mirati dopo il fix | 4/4 su entrambe le API; 43,443/45,001 s | `artifacts/static-audit-verification/android12-fire-targeted.log`, `android15-fire-targeted.log` |
| Riproduzione documenti legali | 12/12 file coincidono, dopo normalizzazione CRLF/LF | `artifacts/static-audit-verification/legal-comparison-final.json` |

I tempi sono osservazioni dell'host, non benchmark del telefono. Gli emulatori dedicati usano due core e 2 GiB ciascuno; le suite si eseguono in parallelo e si sovrappongono in parte a lint. Le otto classi remote opt-in sono escluse esplicitamente dal runner ADB; nessuna prova accede a dati o account del backend reale.

## Fallimento conservato e correzioni

Il primo run JVM conclude 528 casi con un fallimento: `FinanceViewModelTest.kt:52` controllava `loading=false` subito dopo `runCurrent()`, prima che terminasse la lettura dal dispatcher I/O. La fixture attende ora il completamento tramite `StateFlow`, con timeout reale di 10 secondi sul dispatcher I/O. Gli assert su replay bloccato, errore, riconciliazione e timestamp restano invariati. Il primo XML fallito e il log restano in `initial-jvm-results/` e `integrated-initial.log`; il successivo run completo passa tutti i 528 casi.

Il primo run Android 12 conclude 131/132 in 730,554 s: il test di importazione FIRE conferma i dieci parametri salvati, ma l'assert su «Obiettivo Barista» fallisce dopo uno scroll all'indice fisso 15. Il componente esiste ma non è nel viewport. Android 15 passa 132/132 in 680,334 s. La fixture usa ora `performScrollToNode` con il testo del risultato e scroll dei due figli, mantenendo tutti gli assert su parametri, account e richieste. I quattro casi FIRE passano su entrambe le API; le due nuove esecuzioni complete passano 132/132 ciascuna, senza aggregare i run precedenti.

La revisione editoriale scopre due chiavi del generatore EN non corrispondenti ai frammenti di `Termini.tsx`: il nome dell'app è un placeholder e il paragrafo del codice è diviso dal link GitHub. Gli override usano ora le chiavi effettive; l'estrazione in una cartella separata riproduce gli asset senza toccare il checkout web. Corretta anche la frase Cookies EN.

FIRE riporta i tre avvisi sulle proiezioni presenti in `components/fire/ui/Disclaimer.tsx`; la nota Libreria distingue i metadati Google Books dai prezzi manuali, senza citare CardTrader. Sono modifiche di presentazione incluse nel sorgente finale, nei test e nel manifest.

## Integrità e limiti

I 202 file monitorati, inclusi sorgenti, test, asset e configurazione Gradle, hanno hash invariati prima/dopo la build integrata e prima/dopo le suite finali. `source-final-before.json`, `source-final-after-build.json`, `source-suite-final-before.json` e `source-suite-final-after.json` registrano i confronti separati. `final-qualification-result.json` conserva conteggi, scope e hash dei due APK debug; il successivo fix del solo harness lascia l'APK applicativo identico. Lint debug sul pacchetto test finale passa; lint release e JVM restano quelli degli input invariati della build integrata.

Gli emulatori dedicati vengono arrestati al termine delle prove. Il sorgente web è invariato; nessuna CI o operazione sul backend viene avviata in questa verifica.

La verifica riguarda il client e fixture locali. Non certifica nuove prove backend, email/PKCE, tutti i percorsi Coppia/Admin, provider cloud, aggiornamento release autenticato o telefono fisico. Nessun nuovo APK release viene firmato o pubblicato; la 0.1.9 pubblica resta immutabile e la parità globale resta falsa.

La checklist delle attività residue senza nuove funzionalità è in [STABLE_NON_CODE_GATES.md](STABLE_NON_CODE_GATES.md). La revisione testuale dei documenti è tracciata come completata, separata da validità giuridica e infrastruttura distribuita.
