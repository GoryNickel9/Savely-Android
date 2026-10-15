# Osservazioni visuali web sul dataset comune

Il 12 ottobre 2026 sono state renderizzate le pagine React originali di Savely, dalla revisione `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, con risposte sintetiche locali. Le acquisizioni indipendenti comprendono 35 combinazioni schermata/profilo e 105 PNG, oltre ai 54 PNG originali relativi a 18 combinazioni. Il successivo riallineamento agli input nativi ha prodotto altri 114 PNG per 38 combinazioni, in directory separate. Questi risultati documentano il riferimento web; non assegnano PASS Android o parità globale.

Il dataset è [static-common-dataset.json](../parity/fixtures/static-common-dataset.json), SHA-256 `40a18e403c3020910a8762bad716ec3f76490b3db2ed90a1c25a221221048774`. Il clock di pagina è `2026-06-15T12:00:00+02:00`, fuso `Europe/Rome`.

## Acquisizioni e riproduzione

| Run conservato | Combinazioni | PNG | Profili e ambito |
| --- | ---: | ---: | --- |
| [Originale](../artifacts/visual-parity/web/capture-summary.json) | 18 | 54 | IT chiaro 360×800: 13 pagine; EN scuro 320×800/font200%: 5 pagine |
| [content-clean](../artifacts/visual-parity/web/content-clean/capture-summary.json) | 21 | 63 | Stesse 13 pagine IT; 8 pagine EN, aggiungendo Portfolio, Patrimonio e Insights |
| [account](../artifacts/visual-parity/web/account/capture-summary.json) | 14 | 42 | Login, registrazione, Settings, Security, Coppia, Admin permessi e Admin opzioni, nei due profili |
| [content-clean/native-paired](../artifacts/visual-parity/web/content-clean/native-paired/capture-summary.json) | 23 | 69 | 13 pagine IT e 10 EN; Fumo legacy, date Poker e TCG allineati alle fixture native |
| [account/native-paired](../artifacts/visual-parity/web/account/native-paired/capture-summary.json) | 14 | 42 | Security con gli stessi fattori/sessioni nativi; registrazioni Admin disabilitate |
| [content-clean/native-paired-charts](../artifacts/visual-parity/web/content-clean/native-paired-charts/capture-summary.json) | 1 | 3 | Grafici IT, intervallo 01–15 giugno scelto nella UI originale |

Ogni combinazione produce `*-viewport.png`, `*-full.png` e `*-content.png`. Viewport e full conservano la shell originale. Nei nuovi content PNG lo stile temporaneo della cattura nasconde soltanto menu, sidebar e barra inferiore; padding, tipografia e contenuto restano quelli web. Security cattura il contenitore della sua sezione in Settings. I bounds del ritaglio, gli offset nel documento e il testo osservato sono nei rispettivi `capture-results.json`.

Due acquisizioni diagnostiche aggiuntive, con 6 PNG, sono escluse dal conteggio 105: [shell-style-observation](../artifacts/visual-parity/web/content-clean/shell-style-observation/capture-results.json) e [auth-style-observation](../artifacts/visual-parity/web/account/auth-style-observation/capture-results.json). I tentativi in `invalid-prototype/` e `styled-partial-before-adapter-fix/` non qualificano le schermate: il primo mancava delle utility Tailwind, il secondo aveva un record Portfolio privo di `updated_at`.

I run nuovi hanno usato questi comandi dalla radice Android:

```powershell
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web.mjs ../Savely --reuse-source --scope content-clean
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web-summary.mjs --scope content-clean
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web.mjs ../Savely --reuse-source --scope account
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web-summary.mjs --scope account
```

Per una nuova acquisizione senza sovrascrivere le prove conservate, usare una directory distinta:

```powershell
$captureLabel = Get-Date -Format 'yyyyMMdd-HHmmss'
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web.mjs ../Savely --reuse-source --scope content-clean --run-label $captureLabel
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web.mjs ../Savely --reuse-source --scope account --run-label $captureLabel
```

I run appaiati hanno usato `--run-label native-paired` per i due scope. Il flag `--native-paired` permette di ripetere gli stessi adapter con una nuova label:

```powershell
$captureLabel = 'native-paired-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web.mjs ../Savely --reuse-source --scope content-clean --native-paired --run-label $captureLabel
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web-summary.mjs --scope content-clean --run-label $captureLabel
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web.mjs ../Savely --reuse-source --scope account --native-paired --run-label $captureLabel
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web-summary.mjs --scope account --run-label $captureLabel
```

Per Grafici è stato eseguito il comando seguente, con label conservata `native-paired-charts`; per ripeterlo usare una nuova label con gli stessi argomenti:

```powershell
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web.mjs ../Savely --reuse-source --scope content-clean --native-paired --run-label native-paired-charts --profile it-light-360 --scenario charts-income-expense
& $env:CODEX_MCP_NODE_PATH tools/visual-parity-web-summary.mjs --scope content-clean --run-label native-paired-charts
```

Servono il checkout web pinned, le sue dipendenze già installate, il runtime Node indicato da `CODEX_MCP_NODE_PATH` e Chromium locale nel percorso registrato dal manifest. `--reuse-source` verifica l'archivio isolato esistente. Su una directory artifact nuova il primo run va eseguito senza quel flag. I due run nuovi durano circa 2 minuti su questa macchina; non è un benchmark dell'app.

## Fonte, adattamenti e rete

L'archivio isolato contiene i file tracked web, senza `.env`. I manifest registrano gli SHA-256 dei sorgenti e dei font. Entrambi i nuovi summary riportano `sourceChanged: []`; il checkout web è rimasto pulito. La lettura supplementare delle dipendenze ha confermato nove versioni uguali al lock pinned, incluse React 19.2.8, Vite 8.1.2 e Tailwind 4.3.3.

La configurazione Vite originale usa `__dirname` e non carica con `configLoader: runner`. Nei nuovi run `configFile: false` fornisce in memoria gli stessi plugin React SWC e Tailwind PostCSS e l'alias `@`, con host loopback e cache negli artifact. La direttiva Tailwind `@source` è aggiunta in memoria per scansionare `src/` anche dentro una directory ignorata da Git. Nessun CSS su disco viene riscritto. Il primo caricamento della configurazione aveva creato la directory vuota `../Savely/node_modules/.vite-temp`; nei run nuovi non sono comparsi file al suo interno. La cache `.vite` preesistente del checkout web non è stata aggiornata.

I font Inter e Space Grotesk provengono dagli asset locali Android, con hash nel manifest. La richiesta della stylesheet Google Fonts viene soddisfatta localmente. Il profilo font200% imposta `html { font-size: 200% }`: modifica anche gli spazi espressi in rem e non equivale a `fontScale=2` Android.

Gli adapter convertono gli importi in numeri JSON, aggiungono metadata sintetici espliciti e join categoria. Le pagine finanziarie usano `finance.tcgCards=[]`; soltanto `/tcg` usa il caso multivaluta. Standard e Barista caricano i rispettivi parametri baseline. I permessi del run finance/moduli sono un override visuale che abilita le pagine richieste. Il run account usa i permessi esatti degli attori A/B/Admin; Auth parte senza sessione. Nessun dato o token reale è utilizzato.

Le richieste esterne della pagina vengono bloccate, i service worker disabilitati e i WebSocket chiusi. Nei nuovi run sono stati osservati zero errori di pagina, zero endpoint non supportati e zero richieste a backend reale. `content-clean` ha negato 21 POST di audit login sintetiche. `account` ha negato 10 POST di audit login sintetiche e soddisfatto 12 POST di sola lettura: `get_registrations_enabled`, `get_active_sessions` e `/api/admin` con l'unica azione `get-users`. Altre mutazioni sono vietate. Aprire le tab signup/opzioni non invia registrazioni, modifiche permessi, recovery o cancellazioni.

## Valori web osservati

Le tre tabelle seguenti descrivono i run indipendenti conservati. I link finance puntano al contenuto IT senza shell. Le controparti EN hanno gli stessi basename sotto `content-clean/screenshots/en-dark-320-font2/`. Gli stati modificati nei run appaiati sono indicati nella sezione successiva.

| Pagina | Valori renderizzati | PNG |
| --- | --- | --- |
| Dashboard | Saldo €180,00; entrate €120,00; uscite €30,00; risparmio €90,00. Donut Cibo €30,00/100%; widget budget €40,00/66,7%. Periodo 1–15 giugno. | [dashboard-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/dashboard-content.png) |
| Transazioni | Quattro righe del mese, comprese quelle future: −€10,00 il 30/06; +€60,00 il 16/06; −€30,00 il 15/06; +€120,00 il 01/06. | [transactions-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/transactions-content.png) |
| Budget | Previsto €60,00; mediana reale €40,00; differenza €−20,00. | [budget-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/budget-content.png) |
| Portfolio | Valore €325,00; liquido €265,00; illiquido €60,00; investito €270,00; profitto +€55,00; rendimento +20,45%; P/L realizzato −€10,00. | [portfolio-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/portfolio-content.png) |
| Patrimonio | Attuale €320,00; variazione +€11,00/+22,0%; cashflow €230,00; P/L investimenti €45,00; immobili scontati €45,00. | [net-worth-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/net-worth-content.png) |
| Insights | Due segnali positivi: risparmio €140,00, +211% rispetto a media €45,00; massimo patrimonio −€39,00. | [insights-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/insights-content.png) |

| Modulo/caso | Valori renderizzati | PNG IT |
| --- | --- | --- |
| FIRE Standard baseline | Risparmio 100.000 €; target 1.200.000 €; progresso 8,3%; circa 24,4 anni; savings rate 40,0%. Spese annuali input 48.000 €, SWR 4%. | [fire-standard-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/fire-standard-content.png) |
| FIRE Barista baseline | Risparmio 50.000 €; target 600.000 € contro Standard 900.000 €; circa 24,3 anni; prelievi 24.000 €/anno e part-time 12.000 €/anno. | [fire-barista-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/fire-barista-content.png) |
| Poker hourly, deal 0,55 | Due righe: 100/80 € in 120 minuti e −10/40 € in 60 minuti. Annuale: net 90/120 €, 3 ore, mediana 1h30m, display 16,50/22,00 €/h. | [poker-hourly-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/poker-hourly-content.png) |
| Poker rakeback | Totali 300 € rake e 85 € rakeback, 28,3%. Dettaglio 2026: 100/25 €, 25%; annuale 2025: 200/60 €, 30%. | [poker-rakeback-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/poker-rakeback-content.png) |
| Fumo liquido | Costo 12,50 €, 25 ml, 01–11/06: 10 giorni, 2,50 ml/d, 1,25 €/d e 37,50 € mensili nella riga. Riepilogo annuale: 12,50 € e media mensile 1,04 €. | [fumo-liquid-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/fumo-liquid-content.png) |
| TCG multivaluta | 9 carte; investimento visualizzato €45,00; valore €57,00; profitto +€12,00. Categorie 30/15/12 e quantità 2/3/4. Questi totali web mescolano EUR e USD. | [tcg-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/tcg-content.png) |
| Libreria prezzi nullable | 6 pezzi; investimento €23,50; reselling €24,00; profitto +€0,50. Libri 2 pezzi: 0/12 €; fumetti 3: 13,50/0 €; manga 1: 10/12 €. | [library-content.png](../artifacts/visual-parity/web/content-clean/screenshots/it-light-360/library-content.png) |

| Account/scenario | Stato renderizzato | PNG IT |
| --- | --- | --- |
| Auth login/signup | Nessuna sessione; campi login e tab registrazione con checkbox legale non selezionata; nessun submit. | [auth-login-viewport.png](../artifacts/visual-parity/web/account/screenshots/it-light-360/auth-login-viewport.png), [auth-signup-content.png](../artifacts/visual-parity/web/account/screenshots/it-light-360/auth-signup-content.png) |
| Settings, A | Profilo sintetico A; EUR; Italiano/tema chiaro; codice ABCD2345 e connessione attiva. | [settings-content.png](../artifacts/visual-parity/web/account/screenshots/it-light-360/settings-content.png) |
| Security, A | 2FA non configurata, AAL1; due sessioni sintetiche Windows/Android, una marcata attuale. | [security-content.png](../artifacts/visual-parity/web/account/screenshots/it-light-360/security-content.png) |
| Coppia, B | Quote fixture 5,01 EUR e 2,01 USD. La pagina web mostra previsto €4,00, mediana €7,02 e differenza +€3,02, sommando le valute. | [couple-b-content.png](../artifacts/visual-parity/web/account/screenshots/it-light-360/couple-b-content.png) |
| Admin sintetico | Solo tre profili A/B/Admin e i loro permessi; tab opzioni con registrazioni abilitate. Nessun switch o azione account eseguito. | [admin-permissions-content.png](../artifacts/visual-parity/web/account/screenshots/it-light-360/admin-permissions-content.png), [admin-options-content.png](../artifacts/visual-parity/web/account/screenshots/it-light-360/admin-options-content.png) |

## Campioni riallineati alle fixture native

Gli adapter appaiati leggono il dataset comune e riproducono gli stati di [CommonModuleVisualTest.kt](../app/src/androidTest/java/cc/savely/android/modules/CommonModuleVisualTest.kt), [CommonAccountVisualTest.kt](../app/src/androidTest/java/cc/savely/android/ui/CommonAccountVisualTest.kt) e `CommonVisualBackend` in [CommonVisualFixture.kt](../app/src/androidTest/java/cc/savely/android/visual/CommonVisualFixture.kt). I manifest conservano gli hash di queste fonti. I test e i sorgenti di produzione nativi non sono stati modificati.

| Campione | Input appaiato e risultato web osservato | PNG/metadata |
| --- | --- | --- |
| Fumo legacy, B | Stesse date 01–11/06, ma derivati salvati 20 giorni, 1,25 ml/d, 0,625 €/d, 18,75 €/mese, senza ricalcolo. Il web mostra `20`, `1.25`, `€0.63`, `€18.75`; annuale 1,25 ml/d. Il vecchio run da 10 giorni resta una fixture diversa. | [fumo-liquid-content.png](../artifacts/visual-parity/web/content-clean/native-paired/screenshots/it-light-360/fumo-liquid-content.png), [wire-fixture.json](../artifacts/visual-parity/web/content-clean/native-paired/wire-fixture.json) |
| Poker hourly, A | Prima riga 01/06/2026, seconda 01/05/2026, come native. Il web separa giugno 100/80 € e maggio −10/40 €. Annuale invariato 90/120 €, 3h, 16,50/22,00 €/h dopo deal 0,55. | [poker-hourly-content.png](../artifacts/visual-parity/web/content-clean/native-paired/screenshots/it-light-360/poker-hourly-content.png) |
| TCG, A / Libreria, B | TCG acquisto 15/06/2026 e nomi `Dataset …`; Libreria owner B, nomi `Dataset …`, autore/editore null. Quantità, prezzi nullable e totali restano quelli del dataset. L'aggregazione TCG web tra valute rimane la differenza indicata sopra. | [tcg-content.png](../artifacts/visual-parity/web/content-clean/native-paired/screenshots/it-light-360/tcg-content.png), [library-content.png](../artifacts/visual-parity/web/content-clean/native-paired/screenshots/it-light-360/library-content.png) |
| Security, A | Un fattore verified e uno unverified, stessi UUID derivati dalle fixture native; JWT AAL2; due sessioni canoniche con UA `Synthetic Android A`/`Synthetic Browser A` e date 15/06 e 14/06. Il web mostra AAL2 e un solo autenticatore: l'hook usa i fattori TOTP verificati. Non mostra il fattore pendente né il badge dispositivo corrente, perché confronta lo UA con quello del browser invece del session ID. | [security-content.png](../artifacts/visual-parity/web/account/native-paired/screenshots/it-light-360/security-content.png), [wire-fixture.json](../artifacts/visual-parity/web/account/native-paired/wire-fixture.json) |
| Admin | `get_registrations_enabled=false`, come native; switch web non selezionato. Profili A/B/Admin ed email sintetiche corrispondenti, senza mutazioni. | [admin-options-content.png](../artifacts/visual-parity/web/account/native-paired/screenshots/it-light-360/admin-options-content.png) |
| Grafici IT | UI iniziale `Anno`; scelta reale `Tra date`, input 01/06–15/06. Web: entrate €120,00, uscite €30,00, cashflow +€90,00, saving rate 75,0%. Nessun helper sostituito. La controparte nativa è il caso BetweenDates di `CommonDatasetVisualTest.kt`. | [charts-income-expense-content.png](../artifacts/visual-parity/web/content-clean/native-paired-charts/screenshots/it-light-360/charts-income-expense-content.png), [filterInteraction](../artifacts/visual-parity/web/content-clean/native-paired-charts/capture-results.json) |

Sono stati ispezionati i nuovi PNG IT Fumo, Security, Admin opzioni e Grafici. La tabella Fumo originale richiede scorrimento orizzontale: il PNG del contenitore mostra le prime colonne, mentre testo DOM e fixture registrano anche i derivati fuori dal ritaglio. Le controparti EN Fumo/TCG sono disponibili nel nuovo scope; il grafico BetweenDates è stato acquisito soltanto in IT.

I nuovi summary riportano fonte web invariata, zero errori di pagina, zero endpoint non supportati e zero backend reale. Il run account appaiato conserva 10 POST di audit negate e 12 POST di lettura soddisfatte. Gli owner B Fumo/Libreria, i JWT e gli stati Security sono adapter sintetici per il confronto, senza prova delle policy di autorizzazione. Auth signup mantiene la fixture pubblica registrazioni abilitate e non qualifica lo stato della form nativa. Le schermate ripetute nei nuovi scope non estendono automaticamente la copertura nativa.

## Differenze viste e verifiche aperte

TCG e Coppia mostrano nel web somme tra valute sotto il simbolo euro. La separazione delle valute in Android è una differenza intenzionale; questi valori web non sono controvalori EUR. Le quote uguali 5,01/5,01 sono risposte sintetiche della view SQL, non una qualificazione del database deployed.

Sono stati ispezionati direttamente Dashboard, Portfolio e Barista IT, Dashboard/Portfolio EN, Auth IT, Security IT, Coppia IT e le due tab Admin IT. Security IT ha sovrapposizioni fra pulsante e righe sessione; Admin taglia orizzontalmente parte dei permessi. Il titolo Auth “Savely” appare come rettangolo sfumato: l'osservazione CSS supplementare misura `background-clip: border-box` e colore trasparente. Nel sorgente `src/index.css:192–199`, `background` segue `bg-clip-text` e ne reimposta il clip. Queste catture conservano il comportamento originale.

I summary rilevano overflow orizzontale già a 360 px in Portfolio, Insights e nei due FIRE. Il profilo EN/font200% ne rileva anche in Dashboard, Transazioni, Budget, Libreria, Settings, Coppia e Admin. Il cambio della dimensione rem limita il confronto diretto con l'ingrandimento font Android. Nel Barista IT restano testi inglesi del riferimento, tra cui “Portfolio Projection” e “Benefits of Barista FIRE”. Poker e Fumo usano il punto decimale nel testo renderizzato (`€16.50`, `2.50`, `€1.25`); le tabelle sopra riportano gli stessi importi con la notazione italiana.

Il [confronto successivo con Android](COMMON_VISUAL_PARITY_REVIEW.md) registra i campioni nativi, le correzioni emerse e i risultati finali. Restano le prove dei flussi interattivi esclusi da queste acquisizioni. Questo rapporto web non verifica login/MFA, CRUD, mutazioni Admin, policy/RLS, servizi prezzi o import/export. I server e i browser di acquisizione sono chiusi; il collector non ha modificato la produzione Android o web.
