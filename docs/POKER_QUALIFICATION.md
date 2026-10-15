# V10 — Qualifica Poker

**V10 è PASS LOCALE nel criterio della matrice:** fixture, confronto web fissato e conflitto concorrente documentato. Sono ora **15 i criteri chiusi localmente e 18 quelli aperti**. Il blocco verifica Next Cut, mediana delle spese, arrotondamenti al quarto, riepiloghi annuali pesati, CRUD e mese immutabile. La candidata è 0.1.17-alpha/code 18. Le prove Compose/TLS, il confronto web offline e la concorrenza sul backend hanno perimetri distinti.

## Contratto e correzioni

Il riferimento web è Savely `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. La mediana raggruppa le sole spese per mese SQL DATE locale, senza aggiungere mesi vuoti né escludere mesi futuri. Il limite inferiore conserva l'ora locale di riferimento e sottrae 730 giorni di calendario, come `Date.setDate`: la data di confine entra a mezzanotte esatta ed esce un millisecondo dopo. Android ora riceve un `Clock` per verificare questo contratto, inclusi timezone e cambio d'ora.

Next Cut rifiuta risultati non finiti prima dell'HTTP. Il pulsante ha un guard sincrono contro submit concorrenti. Una risposta ambigua o una cancellazione dopo l'inizio della scrittura blocca nuovi salvataggi fino a un aggiornamento completo riuscito; un GET fallito mantiene il blocco. Nessuna scrittura viene ritentata automaticamente. La traduzione inglese della metrica è «Amount needed for the next cut».

Le attese letterali includono mediana 250 più costi manuali 25, deal 0,5 e P/L −350: obiettivo 900. Con P/L 600 diventa −50. Sessanta e 180 minuti, NetWon 100 e −40, EV 200 e −80 producono quattro ore, NetWon 60, EV 120 e tariffe annuali pesate 15/30; la mediana delle ore mensili è due. Rake 100+300 e rakeback 10+90 producono 25%. I pareggi ±0,125 sono mostrati come ±0,13.

## JVM e confronto con il codice web

La build finale passa in **6m39s**, con **601 test JVM debug e 601 release**, zero failure/error/skip, e lint debug senza errori. Comprende otto nuovi metodi JVM Poker. Log e XML sono conservati nel run `artifacts/poker-qualification/018d0bba-1cf2-4d2a-96d6-9c1b394bc18a` e in `artifacts/v10-preparation/build-final-clock-safeviewport-attempt1.log`.

Il confronto offline esegue le funzioni TypeScript effettive della revisione web fissata, con Node 24.19 e TypeScript 5.8.3, clock e timezone isolati per processo. Sei risultati coincidono con le attese native: **200, 300, 300, 300, 50, 200**. Coprono UTC a mezzanotte e +1 ms, Roma a 00:30 e a mezzogiorno, Los Angeles nel mese locale precedente e sottrazione dei giorni attraverso il cambio d'ora. Non è una prova browser o backend. Il primo controllo si arresta prima dei casi per differenze CRLF; il confronto finale usa i blob Git esatti e verifica separatamente l'equivalenza del checkout dopo normalizzazione dei soli fine riga.

Risultato web SHA-256 `7131062ebcf1a1f8dafef4194e5335089f100f07c30e40bb30254f73407c0f7d`; indice delle 23 evidenze `2ad6889c72073f5076b40f956127644a881f531e7543f4b34ccec69861c035b3`, in `artifacts/v10-date-review/web-differential/`.

## Binari e percorso Android

| Artefatto finale | SHA-256 |
| --- | --- |
| Main debug congelato | `6a48ccb3181281d72bbf0611c99bc2b1028ddbf10426ff906abd784e4671a330` |
| Test APK, correzione quarter-fragment | `edfd3f744835106fa687fd441239cbf12a3a967bf67c4e549ff2750c6c9e3835` |
| `PokerQualificationTest.kt` compilato | `3ce43e54736eafc6d63fe3680b597530ff146a16ec2da1b41f6b666efaba7d8f` |

Main e test installati sono stati estratti dal dispositivo e confrontati con gli APK congelati. Le cinque nuove prove Android percorrono IT chiaro/font 100% ed EN scuro/font 200% dove previsto, attraverso `ModuleScreen` e API reali su TLS localhost. Sono affiancate dai sei casi `PokerScreenTest` e dal caso comune sulle ore pesate. La fixture rifiuta trasporti esterni, token, owner e query estranei; verifica anche una sola scrittura dopo risposta incerta, riconciliazione esplicita e confine tra refresh dello stesso SID e login sostitutivo.

Il gruppo finale passa **12/12 in 442,269 s instrumentation, 444,785 s host**, zero failure/skip. Revisionati singolarmente **nove PNG e nove JSON**: mediana/obiettivo, avviso di riconciliazione, pareggi al quarto e riepiloghi rakeback nei due profili, più il caso comune pesato IT. Tutti i JSON attestano `safeDrawingVerified=true`; viewport reali **360×577 dp IT e 320×577 dp EN**, density 3, entro gli inset di sistema. Gli 800 dp nei nomi sono richiesti, non catturati. In EN il contenuto lungo richiede scroll: l’avviso e i valori target sono leggibili, mentre porzioni adiacenti e il pulsante sotto l’avviso possono proseguire oltre il bordo. Il controllo automatico verifica separatamente stato e visibilità del pulsante durante le azioni. `final-evidence.json` e `visual-final-review.json` identificano il gruppo e la revisione; SHA del manifest visivo `2681563b304109c6b93178dea6f3e17ef4f373a32bd866757efcff25385ac0af`.

## Tentativi conservati

Il run precedente `cc0019af-44de-425c-999d-b975f0105e25` usa un main precedente al contratto completo del clock e al viewport sicuro. Il primo gruppo passa 7/12; i tentativi successivi conservano errori di selezione, scroll, focus e timeout. Il dispatcher della fixture richiede di drenare le operazioni accodate fra passi di scorrimento: `ScrollToNode` e lo scroll orizzontale sincrono potevano attendere effetti ancora accodati. Il pulsante inglese corretto è `Update`. Le query Records richiedono `created_at.desc`, mentre Next Cut usa `id.asc`.

Sul main finale il primo gruppo passa 10/12. Le correzioni della fixture riconoscono i nodi Lazy non posizionati, attendono geometria stabile e non usano coordinate obsolete come prova di visibilità. Un lungo paragrafo record EN supera l'altezza del viewport: per l'arrotondamento viene verificato il rettangolo di ogni carattere della sottostringa letterale richiesta, senza pretendere di mostrare tutto il paragrafo in una schermata. Il caso manuale corretto passa isolato **1/1 in 77,376 s**. Le attese numeriche e le asserzioni sulle scritture non sono state ridotte. Log e APK intermedi restano conservati; i tentativi falliti non sono conteggiati come PASS.

## Due client sullo stesso mese: limite osservato

Il run reale `2f1fa27d-a584-411e-a967-d02891203df0` usa due sessioni nuove della sola QA A. Entrambe leggono il mese vuoto prima della barriera; i due POST ricevono **201** e creano due UUID distinti per `2026-10-01`. Il backend quindi **non garantisce l'unicità del mese** nel comportamento osservato. Il controllo preventivo del client non costituisce atomicità server. Non sono introdotte migrazioni, deduplicazioni o modifiche RLS.

Le due righe sintetiche (un'ora, NetWon 0,01, EV 0,02) sono eliminate con due CAS su tutti i valori osservati; entrambi i logout locali sono confermati. Il baseline finale, alle **14:27:39,092266 UTC del 14 ottobre 2026**, conserva identità, profili e permessi selezionati, registrazioni disabilitate e zero righe nelle quattro tabelle Poker, sessioni e fattori QA. Il risultato registra `passed=true`, `baseline_restored=true`, `native_ui_verified=false` e `month_atomicity_claimed=false`: è concorrenza tramite client host, non un secondo form Android. Le 14 guardie host offline passano in 0,818 s.

## Limiti

Non sono qualificati da questo gruppo un nuovo release R8, Android API 35, il telefono fisico o tutte le funzioni web. La prova concorrente documenta il limite richiesto dalla matrice e non lo risolve. Nessuna nuova pubblicazione; `globalParityVerified=false`, `verifiedApkVersion=null` e `datasetCasesExecuted=false` restano invariati.
