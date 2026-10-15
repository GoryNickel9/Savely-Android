# Verifica delle regressioni CSV e profilo

12 ottobre 2026. Verifica locale autorizzata dopo la preparazione degli otto nuovi casi in `5f058dc`. Produzione con le patch SC01/SC02 di `de53ac7`; fixture corrette in `f60ab38cbb50f650cd2cf5e26fe054cbe308e267`. Nessuna modifica alla produzione durante questa verifica. Il checkout web di riferimento resta `1cfcf6297e85d56bfafe3522362ceecef6f6b281`.

## Risultati e sorgenti

| Verifica | Esito | Evidenza locale |
|---|---|---|
| Build debug e APK test | PASS; compilate le otto nuove regressioni | `artifacts/stable-regressions/build-attempt1.log`, `build-attempt2.log` |
| JVM | 530 casi, zero failure/errori/skip | `build-evidence-initial/jvm-results/`, `jvm-summary.json` |
| Host Python | 36 casi in sei moduli, zero failure/errori/skip | `host/run-20261012-135040/host-python.log` e riepilogo |
| Oracolo web FIRE/Fumo | Standard, Barista e Fumo identici byte per byte al golden | `host/run-20261012-135040/web-oracle/comparison.json` |
| Android 12 mirati | 14/14, singolo run finale, 50,804 s | `android31-target-attempt2.log` |
| Android 12 completi | 164/164, singolo run completo, zero failure/skip, 483,804 s | `android31-full-attempt1.log` |
| Android 15 completi | 164/164, singolo run completo, zero failure/skip, 598,852 s | `android35-full-attempt1.log` |
| Lint debug/release | Zero errori, 32 warning e 6 hint per variante | `lint-final.log`, `lint-summary.json` e XML conservati |

Tutte le evidenze della tabella appartengono a `artifacts/stable-regressions/`. Il run JVM parte da `5f058dc`; il successivo `f60ab38` cambia soltanto due fixture Android. Produzione e test JVM sono identici fra i due sorgenti. Lint viene richiamato dopo la correzione delle fixture; release e parte dei task senza modifiche risultano up-to-date. Le suite Android complete includono 140 casi ordinari e 24 casi del campione visuale. Gli otto nuovi casi appartengono ai 140 ordinari; non sono sommati una seconda volta.

Il confronto host usa Python 3.13.2 e Node 24.21.0. Le 36 proiezioni Standard e le 36 Barista sono elementi del JSON, non 72 test aggiuntivi. Il golden conserva SHA-256 `03167ad4787c282983f64b4c694fed11adc6bab72ea984357395efbdfc26d705`.

## Fallimenti conservati e correzione

Il primo run mirato termina con 14 casi e sei failure in 75,043 s. Quattro assert Transfer perdono il formato scelto dopo ricreazione; il profilo mostra `Profile A` invece della bozza `Draft A`. Entrambe le fixture montavano inizialmente la UI con `compose.setContent`, ma dopo ricreazione con `activity.setContent` da un altro callsite. Questa differenza cambiava le chiavi di `rememberSaveable` usate dal test.

Le due classi ora chiamano lo stesso `installContent(activity)` per primo montaggio e ricreazione. Gli assert su formato, valuta, nome, proprietario, numero di richieste, conferme e dati salvati restano attivi. La produzione usa già un unico callsite in `MainActivity.onCreate` e non viene modificata. Il run mirato finale passa 14/14, compresi il nuovo GET dopo ricreazione e il valore della bozza conservato.

Il restante fallimento riguarda export: `No compose hierarchies found` durante l'attesa del messaggio di salvataggio, senza ricreazione nello stesso caso. Non viene attribuito con certezza alle chiavi saveable. Il logcat iniziale conserva anche un ANR di SystemUI, senza eccezione fatale Savely osservata; non dimostra la causa del failure export. Il caso passa nel run mirato finale e nelle suite complete API 31/35. Log originale e logcat restano separati dai PASS.

## Scope delle due patch

- SC01: BANK/USD con risultato OpenDocument differito fino a dopo ricreazione, anteprima USD, seconda ricreazione della preview e annullamento della conferma; NORMALIZED con risultato differito; cancellazione del picker senza GET/INSERT. Il caso esistente controlla anche il formato NORMALIZED della preview ricreata e importa soltanto dopo una nuova conferma.
- SC02: nome/valuta modificati senza salvare, ricreazione reale dell'Activity, nuovo GET, bozza conservata e password/conferma vuote. Un solo PATCH con proprietario e payload esatti dopo salvataggio esplicito. Nuovo session ID dello stesso UID e cambio UID azzerano la bozza; caricamento, profilo assente e HTTP 503 disabilitano i controlli senza scritture.

Le fixture usano TLS loopback, sessioni fittizie e richieste consentite per l'account osservato. Il registry del picker è controllato e riusato fra Activity; i click Transfer sono semantici. Non sono prove del registry di sistema, provider cloud, processo terminato, credenziali reali o policy backend. Restano da qualificare GET nullo/503 dopo ricreazione con una bozza esistente e lo stato dei controlli durante un PATCH trattenuto dal server.

## APK, emulatori e limiti

I test usano APK debug `0.1.9-alpha`/versionCode 10, non una nuova release pubblica. APK e manifest con hash dei sorgenti sono conservati in `final-apks/` e `final-source-manifest.json`.

| APK | SHA-256 |
|---|---|
| App debug | `f46c5c1bfa9a6bcf3b93db5c1c39e272228ba4658641eb937677d189a31c76a3` |
| Test APK finale | `04525fdd43cf8233bd6da15811d7592203541685988387f4df896ae2d5e61259` |

AVD dedicati x86_64: SavelyAndroid12/API 31 e SavelyVerify35/API 35, avviati uno alla volta, due core e 2048 MiB, senza finestra o snapshot. Display dei test 1080×2600 a 480 dpi, font di sistema 1; i profili visuali applicano IT/light/360×800/font 1 e EN/dark/320×800/font 2 nella composizione. Ambiente effettivo registrato per API nei JSON dedicati. Ogni API produce 81 PNG; non vengono attribuite nuove osservazioni al sito distribuito o al telefono fisico. Alla fine ripristinati display/densità e font, spenti entrambi gli emulatori; inventario ADB vuoto e nessun processo emulator/QEMU residuo osservato.

Tre subagent hanno curato revisione account, revisione import e test host/confronto golden/revisione del rapporto. Il responsabile ha gestito tutte le build, gli emulatori e le suite Android, evitando due AVD in esecuzione contemporanea. I riepiloghi derivati dai log conservano ogni risultato individuale in `*-summary.json` e l'inventario in `verification-summary.json`.

Le otto classi opt-in `Real*` restano escluse dalla suite ordinaria tramite `notClass`. Nessuna CI, accesso al backend reale, invio email, modifica web/RLS/schema/account o pubblicazione. `globalParityVerified=false`, `verifiedApkVersion=null` e `datasetCasesExecuted=false` restano invariati: eseguire le suite locali non chiude l'intero dataset master o i gate reali della [matrice](STABLE_VERIFICATION_MATRIX.md).
