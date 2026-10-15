# Dataset comune e regressioni locali — 12 ottobre 2026

Fissati input sintetici e risultati attesi per 50 casi di base del confronto Android/web: nove finanziari, 26 moduli/trasferimenti e quindici account. Le revisioni sono web `1cfcf6297e85d56bfafe3522362ceecef6f6b281` e Android `614bdbfa358b13726fe54399be502d744ba157d5`; produzione e test JVM corrispondono a `935c7ba`. La revisione dei sorgenti non ha richiesto nuove modifiche al codice dell'app.

Il [dataset master](../parity/fixtures/static-common-dataset.json) conserva input, ID dei casi, ruoli fittizi, IT/EN, EUR/USD, timezone Europe/Rome e riferimento 15 giugno 2026 alle 12. FIRE conserva separatamente il clock dell'oracolo, 11 ottobre 2026 alle 12 UTC: le proiezioni partono dallo stesso anno 2026. Le date dei CSV sono quelle esplicite nelle fixture. I profili non corrispondono agli account QA reali e il file non è un import per il backend.

## Confronto dei sorgenti

| Area | Rapporto | Cosa è fissato |
|---|---|---|
| Finanza | [STATIC_DATASET_FINANCE_REVIEW.md](STATIC_DATASET_FINANCE_REVIEW.md) | Nove casi: KPI, filtri, Budget, calendario, grafici, portafoglio attuale/storico, patrimonio e due detector Insights selezionati. |
| Account, sicurezza, Admin e Coppia | [STATIC_DATASET_ACCOUNT_REVIEW.md](STATIC_DATASET_ACCOUNT_REVIEW.md) | Quindici scenari: booleani dei permessi, revisioni della sessione, MFA/callback, account, inviti, quote, valute, revoca e Admin. |
| Moduli e trasferimenti | [STATIC_DATASET_MODULE_REVIEW.md](STATIC_DATASET_MODULE_REVIEW.md) | Input FIRE, Poker, Fumo, TCG, Libreria, CSV e Bank; attese valide, errori, null e servizi indisponibili. |

Gli attesi hanno fonti per caso. `observed=null` o `executed=false` riguarda il confronto sul dataset: nessun caso acquisisce un PASS dalla sola lettura. Le suite esistenti contengono anche input diversi; il loro esito resta distinto dalla futura qualifica di tutte le schermate con questo dataset.

Due punti richiedevano seguire i chiamanti effettivi:

1. **Coppia, centesimo dispari.** Per EUR 10,01 l'utility web isolata produce 5,01/5,00, ma non viene chiamata dai form attivi. La UI web equal non mostra una preview numerica; dopo il salvataggio legge la view SQL, che arrotonda entrambe le metà a 5,01. Android segue la view e avvisa dello scostamento di 0,01. Custom con partner 5,00 conserva 5,01/5,00. Non va usata l'utility isolata come prova di una differenza nel form web.
2. **Fumo, mensile zero.** L'helper di formato chiamato direttamente con `Derived(..., monthlyCost=0)` mostra 0,00 €. La schermata passa invece da `fumoDisplayedDerived`, che normalizza lo zero salvato con costo zero a null; il dettaglio mostra «-», come il web. La fixture dell'helper non dimostra una lacuna nel percorso della schermata.

Restano dichiarate le scelte native: calcoli monetari decimali, riepiloghi per valuta, input invalidi rifiutati prima delle scritture, interpolazione dello storico su giorni locali interi e snapshot server senza valuta. Il Budget del mese intero include la spesa futura del 30 giugno (totale 40), mentre i KPI al 15 giugno la escludono (uscite 30). Questo è il comportamento dei periodi nel riferimento, non una discordanza da correggere.

## Navigazione e presentazione

La lettura di `ui/SavelyApp.kt`, `ui/SavelyTheme.kt` e dei componenti web `layout/BottomNav.tsx` e `layout/Sidebar.tsx` conferma le cinque voci Dashboard, Transazioni, Budget, Impostazioni e Altro, con accessi ai moduli filtrati dai permessi. Altro apre il menu. Grafici raccoglie le viste web nella destinazione nativa; Categorie, Patrimonio e Aggiornamenti hanno accessi espliciti. La presenza di una voce non prova il funzionamento dei suoi percorsi.

Icona, palette chiara/scura e font locali sono documentati in [UI_ALIGNMENT.md](UI_ALIGNMENT.md). I componenti nativi usano icone da 24 dp, testo della barra da 12 sp, altezza minima 64 dp e stato selezionato con contenitore; il web usa icone da 20 px e testo da 10 px. Sono scelte di presentazione Android. I profili 360×800 dp/font 1 e 320×800 dp/font 2 nel dataset sono parametri della futura acquisizione, non dimensioni già osservate su nuove schermate.

I tre screenshot storici restano limitati alla navigazione. Non sono state acquisite nuove schermate popolate, IT/EN o a font ingrandito con il dataset master. Il gate visivo resta aperto.

## Esecuzioni autorizzate dopo la revisione

L'utente ha successivamente autorizzato i test e richiesto i subagent. Un solo agente esegue Gradle; gli altri verificano gli strumenti host e l'oracolo web offline. Questa separazione evita che due runner sovrascrivano i risultati JVM.

| Prova | Risultato | Evidenza locale |
|---|---|---|
| Host Python | 36/36, exit 0 | `artifacts/common-dataset-verification/host-python.log` |
| Oracolo TypeScript web | Tre gruppi di output Standard FIRE, Barista FIRE e Fumo identici byte per byte al golden | `artifacts/common-dataset-verification/web-oracle/run-20261012-01/comparison.json` |
| JVM Android | 528 test in 58 suite; zero fallimenti/errori/skip, nuova esecuzione completa | `artifacts/common-dataset-verification/jvm-rerun.log`, `jvm-summary.json` e `jvm-results/testDebugUnitTest/` |

L'oracolo usa Node 24.21.0, gli export TypeScript originali del checkout fissato e una copia byte-identica di `parity/generate-web-fixtures.mjs` in una directory artifact. Il golden e i sorgenti web restano invariati. SHA-256 golden e generato: `03167ad4787c282983f64b4c694fed11adc6bab72ea984357395efbdfc26d705`. Le 36 proiezioni Standard e 36 Barista sono elementi degli output confrontati, non 72 test separati. Fumo restituisce durata 10, quantità/giorno 2, costo/giorno 5 e mensile 150.

Il primo comando Gradle è terminato prima di eseguire test: Windows ha espanso `--tests '*'` nei nomi dei file, producendo `Task '.gitattributes' not found`. Il log `jvm.log` e gli XML preesistenti in `jvm-results-preexisting/` sono conservati come errore operativo e non contati come una nuova esecuzione. Il comando corretto ha forzato soltanto il task dei test con `:app:testDebugUnitTest --rerun --no-daemon --console=plain`: un task eseguito e 25 prerequisiti UP-TO-DATE. Durata osservata Gradle 27,51 s; somma dei tempi XML 7,14 s, distinta dal tempo del comando. Le nuove XML hanno timestamp dal 12 ottobre 2026, 10:17:26.154 UTC al 10:17:33.422 UTC.

Passano anche la regressione del blocco Ricorrenze dopo una scrittura parziale e il template degli otto detector Insights. L'esito di dominio del template non qualifica da solo icone, badge o rendering completo della schermata. Nessun test nuovo è stato aggiunto per questa revisione; i 528 sono la suite esistente, non i 50 casi appena fissati nel dataset.

## Stato dei gate

Completata la preparazione del dataset e la lettura dei risultati attesi. Le prove locali qui registrate verificano il client e gli output delle formule nel loro ambito. Nessuna nuova CI GitHub, sessione di emulatore, prova su telefono, chiamata backend, email o modifica di account è stata eseguita. Non è stato prodotto o pubblicato un nuovo APK release.

Restano aperti il confronto osservato di tutte le schermate con gli stessi input, i percorsi QA esclusi, email/PKCE con mailbox controllata, picker/provider e servizi reali, aggiornamento firmato autenticato e telefono fisico. [STABLE_NON_CODE_GATES.md](STABLE_NON_CODE_GATES.md) mantiene queste prove distinte. `globalParityVerified=false` e `verifiedApkVersion=null` restano invariati.
