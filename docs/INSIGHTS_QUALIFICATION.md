# V08 — Qualifica Insights

**V08 è PASS LOCALE nel criterio delle fixture comuni e delle acquisizioni IT/EN.** Gli otto rilevatori hanno attese numeriche e presentazione osservata, con filtri di severità, stato vuoto ed errore di lettura distinti. Sono tredici i criteri chiusi localmente e venti quelli aperti; V06 conserva il canary prezzi. La candidata resta 0.1.17-alpha/code 18, senza nuova pubblicazione o parità globale.

## Fonti e correzioni

Il riferimento web è Savely `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, `src/lib/insights.ts`, `useInsights.ts` e `Insights.tsx`. Android usa `PortfolioModels.kt` e la schermata Finance reale, alimentata da TLS locale sintetico. [INSIGHTS_GAPS.md](INSIGHTS_GAPS.md) conserva formule, finestre e differenze dei due modelli.

La correzione V08 allinea il testo delle perdite ai pareggi negativi di `Math.round`: acquisto 200 e corrente 169 producono una variazione esatta −15,5%, mostrata come −15%. La soglia inclusiva del 15% resta un confronto esatto prima dell'arrotondamento. Corrette anche la concordanza «Spesa ricorrente aumentata/diminuita» e la traduzione del filtro «Positivi». Un nome utente come `QA Position {{percent}}` resta letterale, senza essere interpretato come template.

## Input e oracoli

Riferimento 15 giugno 2026. Gli importi seguenti sono EUR, salvo lo snapshot server privo di valuta.

| Rilevatore | Input e risultato letterale verificato |
| --- | --- |
| Anomalia | Storico 10 maggio 50, corrente 10 giugno 70: mediana 50, +40%, differenza 20, warning. |
| Budget | Limite 100 e uscite giugno 101: warning, differenza 1. La JVM prova anche 100 senza segnale, 100,01 con differenza 0,01 e isolamento USD/mese precedente/limite zero. |
| Perdita | Acquisto 200, corrente 169: testo −15%. Le JVM includono 167→−16%, 168,98→−16%, 169,02→−15%, 170→−15%; 170,01 non supera la soglia. |
| Trend | Importi 40 in ottobre/novembre/dicembre 2025 e 50 in aprile/maggio 2026: mediane individuali 40 e 50, +25%, info. |
| Nuova ricorrenza e variazione | Descrizione V08 Netflix, 10 aprile 10, 10 maggio 10, 10 giugno 11: mediana 10, tre occorrenze mensili, info; ultimo 11, aumento esatto 10%, warning separato. |
| Risparmio e milestone | Dataset comune: netto 140 contro media 45, testo +211%, positivo; snapshot 15 giugno −39,00, nuovo massimo almeno 1 sopra il precedente, positivo senza simbolo monetario. Le fixture precedenti provano anche +20% esatto, date future, valuta separata e snapshot failure. |

Il campione dei sei rilevatori produce sei segnali: quattro warning, zero positivi, due info. L'ordine atteso è anomalia, budget, variazione ricorrente, perdita, trend, nuova ricorrenza. Il filtro warning deve contenere esattamente i quattro ID; Info esattamente i due; Positivi mostra il vuoto del filtro, poi Tutti ripristina i sei. Un 503 degli snapshot mostra errore e «Riprova», senza conteggio zero o vuoto valido; soltanto il retry esplicito produce l'insieme vuoto corretto. Nessuna mutazione o destinazione di rete esterna è consentita dalla fixture.

## Esecuzione e binari

Run `artifacts/insights-qualification/328c03a1-3779-47e0-b1dd-c6e5470acea3`. La build iniziale conclude in **4m51s**: **593 JVM debug e 593 release**, zero failure/error/skip. Tre metodi JVM nuovi coprono pareggi percentuali, Budget e composizione/ordine dei sei segnali, senza mutare gli input. Il lint debug finale conclude in **1m3s**, zero errori/fatal, 33 warning e sei hint; XML e log copiati nel run.

Il gruppo finale API 31 passa **11/11 in 79,068 s instrumentation, 83,157 s host**. Comprende tre nuovi metodi `InsightsQualificationTest`, il caso Insights del dataset comune e sette `FinanceScreenTest`. I tre nuovi metodi percorrono IT chiaro/font 100% ed EN scuro/font 200%. È un gruppo mirato, non una suite completa Android né una nuova esecuzione API 35.

| Artefatto | SHA-256 |
| --- | --- |
| Debug congelato, invariato nei tentativi | `1d016c4d409430f43690e03c42b873794650481099c6c46b3dae56db4bccf108` |
| Test APK finale, `frozen/corrected-test.apk` | `ecf2e62ea9a33c762a17527a3c08546ccc163d2797993b528e183b90b2229941` |
| Test APK iniziale | `1d9828fec46d224acd2581517119fb613e38fd1fc55be9abb7b516ea9d8ba001` |
| Test APK diagnostico | `032b71c40d5cae615389aac70db2b393ed3727ffc4c010bf7108b9b1faf65ea9` |
| `PortfolioModels.kt` compilato | `9303471f24b4ef8f93c6eaa47238c75c4d0bee74236554dfd5b55fb1f0303eff` |
| `InsightsQualificationTest.kt` finale | `e5b937fe041135c98373ead4898bb8eab50c272a885873453b585c3949604ff3` |

`input-metadata.json` conserva l'APK test e la fixture iniziali. `final-evidence.json` registra separatamente quelli corretti, lint finale e gruppo riuscito. Le XML JVM sono conservate in `jvm-debug/` e `jvm-release/`. Nessuna nuova build release R8 è attribuita a V08.

## Tentativi conservati

Il primo gruppo `v08-api31.log` passa **8/11**: timeout del caso errore e due assert di overflow. Il selettore inglese cercava `Retry`, mentre il pulsante reale mostrava `Try again`: la diagnostica conserva `retryNodes=0` e l'immagine del pulsante. Il paragrafo errore comprende anche il suffisso sui dati non aggiornati; il controllo finale accetta il testo pubblico come sottostringa invece di richiedere l'intero paragrafo. La diagnostica successiva passa **0/3**: conserva questi problemi e un nome screenshot diagnostico invalido. Non sono run riusciti.

La prova del badge «Attenzione» misura una riga sagomata di 200,73046875 px entro 202 px, mentre il vincolo del paragrafo è 936 px. L'oracolo che confrontava la larghezza del paragrafo con il badge segnalava overflow anche con la riga contenuta. Il controllo finale verifica estremi delle righe ed ellissi, mantenendo la visibilità geometrica dei target e tutte le attese numeriche. La correzione è solo della fixture: nessun cambio di layout tra questi tentativi. Il gruppo corretto passa prima **3/3 in 81,106 s**, poi il finale **11/11** sullo stesso APK debug.

## Immagini e limiti

Revisionati singolarmente **18 PNG e 18 JSON** finali: nove stati per profilo, inclusi header, risparmio, milestone, anomalia, perdita, trend, filtro Info, filtro positivo vuoto ed errore. `visual-final-sha256.json` identifica tutti i 36 file; `visual-final-review.json` associa ogni immagine al proprio SHA, ai metadati e all'osservazione. SHA del manifest `95ea87353c97f79203829076da4723b8d988b4ff739a0d186e1e82ee28f0ea36`.

Viewport effettivi **1080×1731 px / 360×577 dp IT** e **960×1731 px / 320×577 dp EN**, density 3. Gli 800 dp nei nomi sono richiesti, non catturati. Titoli, badge e importi osservati sono leggibili; i paragrafi EN a font 200% possono superare il viewport e continuano con lo scorrimento. Header EN e milestone EN non mostrano l'intera prosa in un'unica immagine. Le porzioni adiacenti tagliate non dimostrano clipping del target.

Il confronto usa formule web fissate e attese letterali, senza nuovo browser o golden pixel. Android separa le valute e usa decimali; il web somma importi grezzi e li etichetta EUR. Mediane di anomalia/trend restano sui singoli importi, con mesi futuri e overflow `setMonth` conservati. La milestone usa i valori server grezzi senza valuta. Il clock iniettato riguarda date/anno; l'ora di ultimo aggiornamento resta runtime. Nessun backend QA, feed prezzi o provider reale viene qualificato da questi test. I flag globali rimangono invariati.
