# Allineamento dell’interfaccia Savely

Aggiornamento del 12 ottobre 2026, implementazione 0.1.8-alpha. Riferimento web: commit `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. La segnalazione dell’utente sulla 0.1.5 riguardava icona e UI/UX; l’uso generale della 0.1.7 sul Doogee S100 Android 12 è confermato, senza una prova dettagliata delle nuove schermate.

## Elementi implementati

| Area | Riferimento web | Android 0.1.8 |
|---|---|---|
| Icona | `public/icon-maskable-512.png` | Asset originale incluso in `drawable-nodpi/savely_brand.png`; icona adattiva con maschera del launcher Android. |
| Colori e forme | Token chiari/scuri di `src/index.css`, raggio base 0.75rem | Palette portata in Compose, forme coerenti, contrasto dei pulsanti mantenuto con testo scuro sul primario. |
| Tipografia | Inter e Space Grotesk | Font locali originali per testo e titoli, licenze OFL incluse nell’APK; nessun limite artificiale all’ingrandimento dei caratteri. |
| Navigazione mobile | Dashboard, Transazioni, Budget, Impostazioni e Altro | Stesse cinque destinazioni; Altro apre il drawer filtrato per permessi e conserva la schermata corrente. |
| Dashboard | Periodo, KPI, confronto, grafici, categorie, movimenti e budget | Periodi e confronto già presenti nella 0.1.7; aggiunti donut per categoria, numeri accessibili e collegamenti alle liste complete. |

Le valute restano separate. Lo storico ricostruito usa i prezzi salvati e gli acquisti ancora aperti, senza inventare un feed storico. La gerarchia del sito guida la schermata nativa; le fixture numeriche non dimostrano equivalenza visiva di ogni pagina.

## Verifica

La riesecuzione mirata su Android 12/API 31 passa i tre casi di `SavelyNavigationAccessibilityTest`: tap reali sulle cinque voci, conservazione della pagina quando si apre Altro, aree di tocco di almeno 48 dp, contrasto delle coppie di colori testate e reflow a 320 dp con font al 200%, in italiano e inglese. I permessi sono coperti separatamente da `AppRulesTest` e dal percorso drawer/indice di `NavigationScreenTest`; il cambio lingua e tema senza reset della rotta o della sessione appartiene a `NavigationLanguageTest`.

Sono stati generati e ispezionati tre PNG di fixture in `artifacts/screenshots-0.1.8/`: `roadmap-ui-nav-light.png`, `roadmap-ui-nav-dark.png` e `roadmap-ui-nav-font200-320dp.png`. Le prime due immagini mostrano gli stessi componenti nel tema chiaro e scuro; la terza mostra barra superiore, etichetta drawer e navigazione a font grandi. I componenti sono isolati: queste immagini non verificano la Dashboard popolata, l'icona nel launcher, tutte le schermate o TalkBack.

`DashboardCategoryDonutTest` contiene fixture per importi e percentuali, valute separate, etichette accessibili e dati invalidi. La verifica API 31 conta 108 casi ordinari con esito finale positivo dopo riesecuzioni mirate; non è una singola suite finale completa. Gli esiti API 35 sono in TESTING.md. Restano da confrontare screenshot della Dashboard con lo stesso dataset web, dialoghi e tastiera, oltre alla resa sul Doogee e alla maschera applicata dal suo launcher. Le prove locali non certificano la parità visiva globale.

Sono stati ispezionati anche `artifacts/release-launcher-0.1.8.png` (icona originale nel launcher dell’AVD Android 15) e `artifacts/release-auth-0.1.8-final.png` (APK firmato finale, inglese, tema scuro). Un tap reale verifica Show/Hide e la preferenza inglese resta presente dopo l’aggiornamento dalla 0.1.7. Un avviso ANR di System UI al riavvio dell’AVD è stato chiuso prima di acquisire la schermata; la prova non certifica il launcher Doogee.

Non è stata richiesta una nuova prova manuale sul telefono. L’icona monocromatica per i launcher Android 13 resta un suggerimento lint, distinto dall’icona adattiva a colori implementata.
