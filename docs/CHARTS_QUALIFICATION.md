# V05 — Qualifica dei Grafici

## Stato e fonti

**V05 PASS LOCALE nel criterio delle fixture comuni e acquisizioni IT/EN.** Produzione e suite finale `522051cca8248450ecb121e26b459632d3517a56`; estensione solo test `f84eb25f6537870d83d10c15b305bdc8ce51f4fe`. La [CI finale](https://github.com/GoryNickel9/Savely-Android/actions/runs/38558425255) passa entrambi i job. Undici criteri chiusi localmente, 22 aperti; nessuna pubblicazione o parità globale.

Il criterio V05 della [matrice](STABLE_VERIFICATION_MATRIX.md) richiede fixture comuni e acquisizioni IT/EN per filtri, granularità, confronto, disegno e tabella numerica, compresi stati vuoti, errori e tastiera. Non richiede nuove mutazioni sul backend QA.

Le fonti Android sono [FinanceCharts.kt](../app/src/main/java/cc/savely/android/finance/FinanceCharts.kt), [FinanceChartsScreen.kt](../app/src/main/java/cc/savely/android/finance/FinanceChartsScreen.kt), i test già presenti [FinanceChartsScreenTest.kt](../app/src/androidTest/java/cc/savely/android/finance/FinanceChartsScreenTest.kt) e la nuova [FinanceChartsQualificationTest.kt](../app/src/androidTest/java/cc/savely/android/finance/FinanceChartsQualificationTest.kt). Il riferimento web resta Savely `1cfcf6297e85d56bfafe3522362ceecef6f6b281`: `src/pages/ChartsIncomeExpense.tsx`, `src/components/charts/CategoryAnalysisChart.tsx`, `src/hooks/useFilteredTransactions.ts`, `src/hooks/useMonthlyAggregation.ts` e `src/lib/statistics/dashboardStats.ts`.

La revisione precedente del dataset è in [COMMON_DATASET_PARITY_REVIEW.md](COMMON_DATASET_PARITY_REVIEW.md). Le sue acquisizioni e i suoi esiti storici non sostituiscono l'esecuzione delle nuove fixture V05.

## Correzioni del codice

Quando un refresh elimina la valuta o la categoria selezionata, il filtro torna a «Tutte» prima di calcolare serie, selettori e riepiloghi. La normalizzazione viene anche salvata: la ricomparsa della voce non ripristina il filtro precedente. Le selezioni ancora valide e «Senza categoria» restano preservate. Il cambio di account continua a dipendere dall'isolamento già presente nella shell; queste fixture non ne ridefiniscono il contratto.

Il riepilogo aggiunge il tasso di risparmio per valuta, prima assente: entrate non positive danno `0,0%`, perdite danno zero e rimborsi possono produrre percentuali oltre 100. Tre nuove regressioni JVM coprono input filtrati, valute separate, segni, arrotondamento decimale e importi grandi. Il disegno delle barre temporali usa la larghezza disponibile per bucket senza il precedente minimo di mezzo pixel, che poteva far sovrapporre gruppi molto densi. I valori e il limite dei bucket restano invariati.

La revisione dei primi PNG rileva il pulsante inglese «Line» diviso in «Lin/e» a font 200%. `GraphicSelection` usa ora `FlowRow`: quando lo spazio manca, passa alla riga successiva l'intero pulsante. La fixture legge il `TextLayoutResult` di tutte e tre le etichette e richiede una riga per etichetta, nei due profili, negli stati Barre, Torta e Linea. L’assert aggiuntivo nello stato Torta e le sue due acquisizioni appartengono all’estensione `f84eb25`, eseguita su API 31.

## Fixture predisposte e attese letterali

La nuova classe contiene **tre metodi `@Test`**, ognuno eseguito sui due profili sotto indicati. Sono tre casi JUnit, non tre nuove classi né sei casi JUnit.

| Caso | Input sintetico | Controlli previsti |
| --- | --- | --- |
| Valute, filtri e serie temporali | Intervallo inclusivo 1–15 giugno 2026; EUR entrata 120 il giorno 1 e uscita 30 il giorno 15; USD entrata 8 il giorno 1 e uscita 2 il giorno 8. Due righe EUR da 999, il 31 maggio e il 16 giugno, devono restare escluse. | EUR 120/30/90 e USD 8/2/6 separati; risparmio EUR `75,0%`; filtro EUR elimina il riepilogo USD. Settimane del 1/8/15 giugno: EUR 120/0/120, 0/0/0, 0/30/−30. Mese di giugno e anno 2026: 120/30/90. Barre e linea mantengono questi dati. Tipo e categoria restringono i riepiloghi; cambiare tipo ripristina la categoria «Tutte». |
| Distribuzione e confronto per categoria | Due uscite EUR: `QA Food` 1 il 1 giugno e `QA Travel` 3 il 2 giugno; intervallo 1–2 giugno. | Dettagli individuali 1/25% e 3/75% in barre, torta e linea. La linea confronta accumuli positivi per categoria; selezionando Food, il dettaglio cumulativo è 1 in entrambi i giorni. Il cambio rappresentazione conserva gli importi individuali. |
| Errori, applicazione e filtro vuoto | EUR 120/30 nel range valido 1–15 giugno; draft `2026-02-30`, poi range invertito 16→1 giugno; categoria `QA Empty` senza movimenti. | Date invalide e intervallo invertito mostrano errori e conservano range applicato e totali 120/30/90. L'applicazione valida nasconde la tastiera. La categoria vuota mostra l'avviso e rimuove il grafico temporale; tornando a tutte le categorie, il riepilogo delle uscite è 0/30/−30. |

Il giorno iniettato è `2026-06-15`. La fixture passa direttamente `FinanceData` ai componenti di produzione: non crea sessioni, repository o trasporti HTTP. `clockInjected=true` descrive questo input di data; non significa che l'orologio del dispositivo o i timestamp runtime siano congelati.

## Acquisizioni e controlli sul disegno

I profili riusano [CommonVisualFixture.kt](../app/src/androidTest/java/cc/savely/android/visual/CommonVisualFixture.kt): IT chiaro, richiesta 360×800 dp, font 100%; EN scuro, richiesta 320×800 dp, font 200%. Sul campione API 31 la finestra reale limita l'altezza: i JSON dei 20 PNG finali registrano **360×577 dp IT e 320×577 dp EN**, density 3, rispettivamente 1080×1731 e 960×1731 pixel. Gli 800 dp nel suffisso descrivono la richiesta, non un'altezza effettivamente qualificata. Ogni profilo viene rimosso dalla composizione prima del successivo. Le preferenze usate dal supporto visuale hanno il namespace di test `common_visual_*`; la fixture non modifica le preferenze dell'applicazione.

Sono raccolti e revisionati **20 PNG e 20 JSON**, dieci coppie per profilo. Il gruppo completo di 13 casi produce 18 coppie; l’estensione del solo caso categorie aggiunge due catture dei controlli Torta. Le altre 18 immagini restano byte per byte identiche alla raccolta finale precedente. I nomi sono `v05-<stato>-<suffisso profilo>`, nella directory privata di test `files/common-dataset-visual`:

| Stati | Coppie per profilo | Evidenza prevista |
| --- | --- | --- |
| `category-pie-controls` | 1 | Tutte e tre le etichette visibili e intere anche nello stato Torta. |
| `weekly-bars`, `weekly-lines` | 2 | Canvas temporale e serie settimanali con totali invariati. |
| `category-bars`, `category-pie`, `category-pie-details`, `category-lines` | 4 | Rappresentazioni e dettagli individuali 1/3 e 25/75. |
| `valid-range`, `invalid-date`, `empty-filter` | 3 | Riepilogo valido, errore esplicito e assenza di dati dopo il filtro. |

I JSON del supporto esistente registrano profilo, lingua, tema, font, dimensioni effettive, bounds, SDK e ambito sintetico. La raccolta `visual-extended`, il manifest SHA-256 `visual-manifest-extended.json` e `visual-review.json` associano file, revisione e controllo visivo. Quindici PNG del gruppo finale sono identici ai primi originali revisionati; tre immagini categorie EN cambiano con la correzione del layout e sono ispezionate nuovamente, insieme alle due nuove catture Torta. I JSON non attestano il backend o il browser.

Per barre e linee temporali, torta e confronto a linee per categoria, le assertion richiedono il Canvas interamente nel viewport e almeno otto pixel campionati per ciascun colore dati atteso, con tolleranza RGB di otto unità. I tag sono cercati dentro la card della valuta e del tipo corrispondenti. Le righe numeriche devono essere visibili senza taglio; non si richiede che un'intera card entri in 800 dp con font 200%.

Questi controlli rilevano Canvas vuoti e contenuti fuori viewport. Non misurano l'angolo dei settori o ogni coordinata del tracciato e non costituiscono un confronto pixel per pixel con il browser. I valori letterali delle tabelle e le percentuali restano assertion separate. La cattura dell'errore segue un rilascio esplicito del focus da parte del test; soltanto l'applicazione del range valido verifica che la UI nasconda la tastiera.

## Differenze dal riferimento web lette nel codice

| Aspetto | Web fissato | Comportamento Android candidato |
| --- | --- | --- |
| Valute | `ChartsIncomeExpense.tsx` somma gli importi filtrati senza raggruppare la valuta e presenta i riepiloghi con il simbolo EUR. `CategoryAnalysisChart.tsx` aggrega analogamente per categoria. | Riepiloghi, serie e distribuzioni sono separati per valuta. Nessuna conversione o somma EUR/USD. Il caso misto usa quindi due riepiloghi distinti. |
| Applicazione del range | `useFilteredTransactions.ts` applica i valori di filtro durante la modifica; per «tra date» confronta i due estremi inclusivi quando entrambi sono disponibili. | Il pulsante Applica valida il draft. Un errore mantiene l'ultimo intervallo applicato; i dati non vengono ricalcolati sul draft invalido. Il range valido 1–15 giugno include entrambi gli estremi. |
| Granularità e presentazione | La pagina entrate/uscite usa aggregazione mensile e cumulativo; l'analisi per categoria offre barre, torta e linee cumulative. I punti cumulativi diventano mensili oltre 365 giorni di differenza. | La destinazione Grafici riunisce le viste, aggiunge selezione giornaliera/settimanale/mensile/annuale e tabelle numeriche. Il cumulativo usa giorni fino a 366 date inclusive, poi mesi; periodi più lunghi possono usare anni. Il limite di 2.400 bucket rifiuta la richiesta anziché troncarla. |
| Categorie assenti o rimosse | `CategoryAnalysisChart.tsx` include nella distribuzione soltanto transazioni con categoria ancora presente nel catalogo e importo aggregato positivo. | Il raggruppamento conserva anche movimenti senza categoria o con categoria rimossa. La torta richiede totale positivo e importi non negativi; gli altri dati restano consultabili in barre e dettaglio. |
| Risparmio e precisione | `ChartsIncomeExpense.tsx` calcola `max(0, (entrate − uscite) / entrate × 100)` con `Number` e `toFixed(1)`; con entrate zero mostra `0%`. | Il calcolo usa `BigDecimal`, `HALF_UP` e una cifra decimale, anche per `0,0%`. Il caso 120/30 mostra `75,0%` in IT e EN. Restano possibili differenze ai punti di arrotondamento rispetto al numero binario JavaScript. |

Le differenze della tabella sono documentate dalla lettura delle fonti. Le nuove fixture verificano un campione definito; non coprono automaticamente ogni differenza o l'intero dominio degli input.

## Esito finale e binari qualificati nel perimetro dichiarato

Build completa su `522051c`: **6m18s**, **570 JVM debug e 570 release**, zero failure/error/skip; lint entrambe zero errori/fatal, 33 warning e sei hint. La suite mirata API 31 passa **13/13 in 107,677 s** (`charts-api31-layout-final.log/json`), con tre metodi della nuova classe, nove di `FinanceChartsScreenTest` e un metodo del dataset comune. Non è una nuova suite completa locale dell’app.

| Binario finale congelato in `frozen-final` | Byte | SHA-256 |
| --- | ---: | --- |
| Release 0.1.16-alpha, code 17 | 2.763.990 | `ac23cb0cf58a8b664bdc3c5e5cc5940ab3ddca3def6a6313e21e252301e6f642` |
| Debug dei componenti | 20.419.844 | `c8efed6dd7b748e76916755585a6b894a24d8c9a6d7401e50dbfe003881d2560` |
| Test APK della suite e dei confronti precedenti | 2.317.598 | `d10797f4db8515e402b843a95679896fb85a6cdafdd1e7e62c92ea4da25c9b7d` |
| Test APK estensione controlli Torta | 2.317.598 | `e6e4447240603be45951c55143e10b25d29533938623d3b46678b05668f75441` |

Firma release e code/versione sono verificati in `signature-final.txt` e `badging-final.txt`; certificato invariato rispetto alla tabella storica sotto. `input-metadata-final.json` conserva hash delle fonti e degli APK. La suite e i confronti con le build precedenti usano lo stesso test APK `d10797f4…`: il filtro valuta fallisce come atteso sulla code 16 in 5,729 s, e «Line» risulta due righe sulla UI prima del layout in 19,148 s. I log `old-currency-regression-final-test` e `old-layout-regression-final-test` restano falliti; la candidata finale passa entrambi i casi nel gruppo 13/13.

La [CI finale](https://github.com/GoryNickel9/Savely-Android/actions/runs/38558425255) usa `522051c`: **570 JVM, zero failure/skip, 49,299 s** e suite completa **219/219 API 35, zero failure/skip, 11m1,56s**. Report scaricati in `artifacts/charts-qualification/ci-final-debug` e `ci-final-ui`, stato e SHA in `ci-final-state.json`. Gli APK del job UI non sono archiviati dal workflow: è attestata la stessa fonte, non l’identità dei byte con i binari locali.

L’estensione `f84eb25` cambia soltanto due chiamate nel test categorie: verifica e cattura i controlli anche nello stato Torta. Compilazione instrumentation e lint debug passano in **1m59s**; il metodo, su entrambi i profili, passa **1/1 API 31 in 37,797 s**. Non viene sommato ai 13 casi come nuova suite. Il log nativo riporta `OK (1 test)` e status zero; il parser host originale cercava erroneamente il plurale `tests` e conserva `success=false`. La riparsa separata `pie-controls-extended-parsed.json` verifica singolare, un avvio/un successo, zero errori/timeout e restituisce l’esito nativo corretto senza ripetere il test o riscrivere il primo risultato. Il parser è corretto. Questa acquisizione aggiuntiva non è stata eseguita in CI API 35; la produzione resta quella della CI completa riuscita.

I 20 PNG finali sono revisionati. Le catture dei Canvas non mostrano necessariamente tutti i controlli o l’intera tabella nello stesso viewport; le nuove catture `category-pie-controls` mostrano tutte e tre le etichette e la selezione Torta. Il cambio di riga conserva ogni parola intera. Tabelle e assert riportano separatamente gli importi esatti; non è una misura di contrasto WCAG, color blindness o geometria pixel per pixel. L’emulatore dedicato è spento dopo la raccolta; nessun account, sessione, email o dato backend è stato usato o modificato in V05.

## Provenienza e tentativi intermedi


Run locale: `artifacts/charts-qualification/31d05685-4fd7-4031-84a5-1e5a196c81af`. La build completa su `c36e54e6e5499c83f6851eabdfc9c7e7a441cf2c` passa in 8m36s: 570 JVM debug e 570 release, zero failure/error/skip, lint di entrambe zero errori/fatal, 33 warning e sei hint. Le successive modifiche riguardano soltanto i test Android; ricompilazione instrumentation e lint debug passano in 3m9s. Non richiedono una nuova esecuzione delle suite JVM invariate.

| Binario locale intermedio, prima della correzione del layout | Byte | SHA-256 |
| --- | ---: | --- |
| Release 0.1.16-alpha, code 17 | 2.763.990 | `f42845b15e313690c962cd46a38daea5830b05332d6d2e69a4cfea1eeacf6ba8` |
| Debug dei componenti | 21.276.348 | `5002a4c2f7785bc024cd0bbdaae1062b17739a31336ba08d6efe4586c0891713` |
| Test APK iniziale, fonte `c36e54e` | 2.317.598 | `f9a9f44832fd6a10d64186670e78a58dd5f1e289f9cba4e7431e91ab205f14bd` |
| Test APK corretto, fonte `1d43b85` | 2.317.598 | `9e66218febff5395102e956010e5048bab91ed4905a9843e4e09f24ee6741c12` |

La firma release è verificata con `apksigner`, certificato SHA-256 `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1`. I file `input-metadata.json` e `corrected-test-metadata.json` conservano sorgenti, hash e provenienza distinta dei due pacchetti di test. Debug e release restano invariati durante la correzione delle fixture.

La [prima CI](https://github.com/GoryNickel9/Savely-Android/actions/runs/38554516271), fonte `33f92639e7a8ce3a6624556357e193030a76a59e`, passa entrambi i job: 570 JVM, zero failure/skip, 46,715 s; suite completa API 35 di 219/219, zero failure/skip, 12m38,30s. I report scaricati sono in `ci-initial-debug` e `ci-initial-ui`. Questa esecuzione precede il confronto esatto dei riepiloghi e le due correzioni delle precondizioni Android; non sostituisce la CI finale sulla fixture corretta.

| Verifica finale | Stato |
| --- | --- |
| Build, JVM e lint locali | PASS nei limiti e sulle fonti sopra indicate. |
| Fixture Grafici intermedie, fonte `1d43b85`, dopo riavvio AVD | 13/13, zero failure/skip, 196,853 s instrumentation; log `charts-api31-after-reboot`. |
| Acquisizioni intermedie | 18 PNG + 18 JSON V05 raccolti e revisionati; rilevato il difetto «Lin/e», da correggere prima della chiusura. |
| Build e fixture dopo correzione layout `522051c` | PASS: esiti finali e binari nella sezione sopra. |
| CI finale `38558425255`, fonte `522051c` | PASS: 570 JVM e 219/219 API 35, dettagli sopra. |

### Tentativi conservati e recupero dell'ambiente

Il medesimo test del refresh valuta fallisce sulla build precedente code 16, debug SHA-256 `779e51957b5076cf2d63f22b6f10d8fb2a620abae24ac8cfb3e648ea9ddb3e13`: dopo la rimozione di USD non trova «Valuta: Tutte le valute». Risultato 0/1, 16,374 s instrumentation, in `old-currency-regression.log/json`; è una regressione attesa, non un PASS della vecchia build.

Il primo gruppo API 31 registra 13 avvii, dieci successi e tre errori in 103,285 s: reset categoria non osservato dopo il tap sul chip, precondizione IME visibile non raggiunta e inizializzazione di una classe con package host errato (`visual.CommonDatasetVisualTest` invece di `finance.CommonDatasetVisualTest`). Il comando host viene corretto. Il successivo diagnostico conserva 1/2 successi in 51,757 s; il caso comune passa e il tap del caso mensile fallisce ancora. I chip di quel test ora richiedono bounds completi e selezione osservata subito dopo il tap; i riepiloghi confrontano testi interi, compresa la valuta, anziché sottostringhe.

Le fixture corrette passano 1/2 casi mirati in 37,663 s: il caso mensile passa, mentre la tastiera non diventa visibile neppure dopo una richiesta esplicita. Il successivo diagnostico IME resta fallito. Durante l'osservazione, `dumpsys window displays` mostra il focus sulla finestra **«Application Not Responding: com.android.systemui»**, con l'Activity della fixture come app selezionata; l'IME conserva il precedente launcher, senza richiesta di apertura. È un blocco osservato dell'ambiente, non una prova di errore nel pulsante Applica. L'AVD viene riavviato; il runner host rifiuta ora preflight con ANR di sistema. Restano conservati i log iniziali, i diagnostici e `system-ui-anr-pre-reboot.txt`. La prima installazione debug sopra release viene rifiutata per firma diversa; soltanto sull'AVD dedicato la copia release è rimossa prima delle fixture, senza attribuire una prova di aggiornamento.

Il run CI `38557335804`, fonte `1d43b85`, è **annullato**, senza attribuire un esito ai job incompleti: la revisione visuale aveva trovato la parola spezzata e richiedeva una nuova produzione. La CI successiva usa la correzione del layout e l'assert aggiunto. Il risultato intermedio di 13/13 resta circoscritto alla precedente UI.

Il criterio riguarda componenti nativi con dati sintetici. Non prova interazioni con l'APK R8 firmato, autorizzazioni o dati del backend, né parità visuale eseguita nel browser. Non richiede account QA e non produce mutazioni remote. Non qualifica la stable, la parità globale o la pubblicazione.

V05 è chiusa nel perimetro di fixture dichiarato. Restano 22 criteri aperti della matrice; il prossimo blocco è V06, Portafoglio.
