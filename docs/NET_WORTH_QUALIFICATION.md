# V07 — Qualifica del Patrimonio

**V07 PASS LOCALE nel criterio delle fixture e del confronto numerico con le fonti web.** Sono verificati periodi, dettagli, snapshot negativi/singoli/vuoti e componenti per valuta. Non richiede nuove scritture QA. Dodici criteri della matrice sono chiusi localmente, 21 restano aperti, compreso V06 sui prezzi reali.

## Fonti e correzione

Il riferimento web resta Savely `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, `NetWorth.tsx`, `useNetWorthHistory.ts` e `netWorth.ts`. Android usa `PortfolioModels.kt`, `PortfolioHistory.kt` e `PortfolioHistoryScreen.kt`. La nuova correzione rifiuta `components` presente ma non-oggetto, invece di trasformarlo silenziosamente in dati mancanti. Assenza e null restano compatibili; numeri malformati nei componenti producono già un errore visibile.

Il totale corrente somma flussi, plusvalenze degli strumenti aperti e immobili al 75%, mantenendo le valute separate. Cash del portafoglio, altre attività, TCG e posizioni chiuse non aggiungono un secondo contributo. Gli snapshot server non hanno valuta: nessun simbolo EUR/USD né riconciliazione artificiale con il corrente. Il web etichetta invece questi valori EUR e somma importi grezzi nel corrente; questa differenza resta dichiarata.

Il valore assoluto della base negativa nel calcolo percentuale **coincide con `NetWorth.tsx:35`**. Non è una deviazione Android. Base zero rende la percentuale indisponibile; uno snapshot singolo non misura una tendenza. Periodi e dettagli usano punti effettivi, senza interpolare dati mancanti.

## Input e risultati delle nuove fixture

Clock 15 giugno 2026. Snapshot: 31 dicembre −200; 30 aprile −160; 17 maggio −120; 31 maggio −100; 1 giugno −90; 15 giugno −50; 1 luglio +25.

| Caso | Risultato verificato |
| --- | --- |
| Tutto / anno / mese precedente | Tutto include il punto futuro: +225/112,5%. Anno fino al clock: +110/68,8%. Mese precedente: +20/16,7%. |
| Personalizzato e confronto | 15→1 giugno viene normalizzato a 1–15; +40/44,4%, minimo −90 e massimo −50. Precedente 17–31 maggio: +20; ultimi valori +50/50%. Data 30 febbraio non cambia il periodo applicato. Agosto mostra vuoto. |
| Dettagli | Navigazione 1↔15 giugno conserva componenti −100/10/0 e −80/30/0. Snapshot −0.305 mostra −0,31 IT/−0.31 EN; mancanti/null, zero e malformati restano distinti. |
| Corrente e server | EUR 130 = −100 +80 +150; USD 70 =50 +20. Snapshot server −39 resta grezzo, senza valuta e senza sostituzione con 130 o 70. |

Sono **quattro nuovi metodi JVM** e **tre Android**, ciascuno Android percorre IT chiaro/font 100% ed EN scuro/font 200%. Le asserzioni numeriche e quelle di visibilità sono distinte; l'assenza di simboli monetari è controllata nel solo sottoalbero server.

## Esecuzione e binari

Build debug e instrumentation, JVM debug/release: **5m11s**, **590 test per variante**, zero failure/error/skip. Il gruppo API 31 passa **10/10 in 67,506 s** (70,186 s host): tre nuovi V07, cinque `PortfolioHistoryScreenTest` e i due casi Poker/Settings andati in timeout nella CI precedente. Questo run locale riuscito non sostituisce né corregge l'esito di quella CI.

| Binario congelato | SHA-256 |
| --- | --- |
| Debug 0.1.17-alpha/code 18 | `dd203855e2ffb0238edaaf53bf56692954401a439c3c87b07f8acb7ad294ebd2` |
| Instrumentation | `61543e92e34704e2667c40efedf044f0e95dc1ee2373c1fb5ccab14c1e6eeec9` |

Run `artifacts/net-worth-qualification/6502d110-8dd8-479c-8736-60582bbd3040`; `input-metadata.json` conserva SHA delle fonti e dei binari, XML JVM e log. Il `PortfolioModels.kt` compilato ha SHA-256 `fe4e3904b9cd94455cee1ae4eec92502abcab58f8edde13b5134f1c64ca6059c`. Lint debug supera il controllo: zero errori/fatal, 33 warning e 0 hint. Nessun nuovo release R8 viene costruito per questo campione: il release V06 conserva il proprio hash e il proprio perimetro.

## Immagini e limiti

Revisionati tutti i **10 PNG e 10 JSON**, cinque coppie per profilo: riepilogo negativo, dettagli, vuoto, errore componenti e componenti correnti. Viewport reali 360×577 dp IT e 320×577 dp EN, density 3, rispetto alla richiesta 800 dp nei nomi. I target numerici e gli errori risultano leggibili; i contenuti adiacenti possono essere fuori dal viewport. La cattura corrente EN mostra il dettaglio EUR e parte della card USD; il valore USD 70 e i suoi componenti sono verificati separatamente dalle asserzioni.

Il confronto usa le formule delle fonti web fissate e attese letterali; non aggiunge nuove catture browser o confronto pixel. Non qualifica un feed di mercato, storage, provider reali, telefono fisico o APK finale. Nessun account o dato backend è usato dal gruppo V07. I flag globali restano invariati e la candidata rimane alpha locale.
