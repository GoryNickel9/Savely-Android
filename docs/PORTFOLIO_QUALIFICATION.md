# V06 — Qualifica del Portafoglio

**V06 resta aperto sull'aggiornamento reale dei prezzi.** Passano le fixture locali e 17 verifiche dei form sul release firmato, con baseline QA ripristinato. Nessuna pubblicazione o parità globale deriva dai risultati sotto.

## Sorgenti e correzioni

La produzione qualificata è `41f209822813514b4e8eb409458cf9aef8a908f9`, candidata locale 0.1.17-alpha/code 18. Il riferimento web resta Savely `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, in particolare `Portfolio.tsx` e `usePortfolioPerformance.ts`. I cambi successivi del Patrimonio non appartengono a questi binari congelati.

Il riepilogo aggiunge il costo investito con immobili inclusi; il rendimento degli strumenti continua a escludere immobili, liquidità e altre attività. Due grafici mostrano distribuzione per tipo e liquidità, con importi e percentuali per valuta. TCG valorizzabile in EUR usa un colore distinto dalle azioni; dati negativi o totali nulli non producono una torta ingannevole. Il riuso di uno strumento chiuso copia l'identità, conservando il nuovo lotto separato.

La chiusura singola blocca il doppio invio e richiede riconciliazione dopo un risultato incerto. PATCH/DELETE degli asset con risposta persa o errore ambiguo bloccano ulteriori mutazioni fino a un refresh riuscito. Le chiusure collettive restano scritture sequenziali con preflight/CAS: un errore può lasciare una parte già chiusa, senza rollback automatico.

Il parser della risposta prezzi rifiuta conteggi non interi, negativi o fuori intervallo, errori malformati ed envelope d'errore. Il solo ramo vuoto noto può omettere `checked`. `updated` può superare `checked` perché comprende anche il cash FX.

## Invarianti e campione

| Area | Attesa verificata nelle fixture |
| --- | --- |
| Allocazioni | EUR 500: azioni 100, cash 50, immobili 200, altro 50, TCG 100; quote 20/10/40/10/20%. Liquido 150/30%, illiquido 350/70%. USD 500 separato. |
| Costi | Strumenti EUR 100, immobili inclusi 300, costo complessivo 380; USD 500 separato. |
| Storico ricostruito | Valori 250/256/285/312,5/340, costo 230; USD 550/costo 500 distinto. |
| Storico registrato | Valori 220/36/285/300, costi 200/30/230/200 e copertura 1/1/2/1 posizioni. Nessun riempimento dei prezzi mancanti. |
| Riuso e form | ETF USD chiuso riutilizzato solo come identità; quantità 2, prezzo 30, corrente 31 e nuovo draft conservati. Chiusura zero invalida, busy, ricreazione e riconciliazione controllati. |

Lo storico ricostruito usa le posizioni attualmente aperte e interpola prezzi salvati fino al corrente. Non ricostruisce ogni quantità storicamente detenuta né un feed di mercato. Android usa giorni di calendario e decimali; il web tempo trascorso in millisecondi. Non è dimostrata coincidenza dei punti intermedi con orari o cambi d'ora legale.

## Risultati eseguiti

Build locale completa: **586 JVM debug e 586 release**, zero failure/error/skip; lint senza errori. Il gruppo finale API 31 passa **14/14**, con 102,355 s host. Comprende `PortfolioQualificationTest`, `PortfolioPositionsScreenTest`, `PortfolioHistoryScreenTest`, due metodi `FinanceScreenTest` e il caso Portafoglio del dataset comune. Non è una suite completa dell'app.

| Binario congelato | SHA-256 |
| --- | --- |
| Release firmato | `bf86519c97c4b1dca8fb971800e6da6fec4f3b6b682a86f06f6f4b8b9947fe39` |
| Debug | `2539a3880191e38c63d80fd23bd4e9d420495ab4c5d52edf99e31b6a0a0ed630` |
| Test APK con guardia raster | `112a1fda7867ae04118a0fa85281739876b6c89d5e27b4806c4b0c44420256a9` |
| Harness dei form firmati | `e7d28ff36ae9e90258f0020bb46790985886de4850b6f04ac37649305d79e7cc` |

Il run locale è `artifacts/portfolio-form-ui/ec311ac9-85fe-492e-b360-f684ecea9c20`. Metadati, log, APK, manifest SHA e report restano separati per ogni tentativo. Le **14 guardie host offline** passano; verificano adozione degli UUID, owner, preimmagini, decimali, sessione e cleanup circoscritto.

Il protocollo reale `crud-only` passa **17 verifiche native API 31/EN**: login/logout e quindici operazioni, comprese creazione/modifica e annullamenti, chiusura singola, due acquisti con chiusura collettiva, lettura dei tre chiusi e cancellazioni. Tre POST, quattro PATCH e tre DELETE sono osservati sul server; gli annullamenti non mutano dati. Ogni transizione confronta i tre QA, la sessione, i record estranei e gli UUID adottati. Nessuna richiesta prezzi è eseguita.

Alle **13:40:19,710710 UTC del 14 ottobre 2026** il confronto finale coincide con il baseline: zero asset/storico/manual update/log prezzi del run e zero sessioni/fattori QA; **33 categorie e sei snapshot patrimonio preesistenti conservati**. Le cancellazioni sono effettuate dai form sui soli tre UUID del run. `native-result.json` riporta `passed=true`, `baseline_restored=true`, `prices_executed=false`. Gli APK installati sono riletti e confrontati con gli SHA congelati prima del login. Firma release v2 verificata, un firmatario, certificato SHA-256 `e103e92b4ab9576d4ecd1b27dd237f9cda697fd781cebce6585a308b8aaf93c1`. Il passaggio debug→release per la qualifica usa disinstallazione sull'AVD dedicato: non è una prova di aggiornamento autenticato.

Revisionati **22 PNG e 24 JSON originali**: due diagnostici glyph e 22 metadati. Viewport reali 360×577 dp IT/font 100% e 320×577 dp EN/font 200%, density 3; gli 800 dp nei nomi descrivono la richiesta. I riepiloghi USD 25/20/5/0 sono interamente leggibili prima e dopo due frame aggiuntivi. Le immagini sono stati scrollati: non mostrano ogni contenuto adiacente nello stesso viewport e non sono un confronto pixel con il browser.

La [CI `38567179309`](https://github.com/GoryNickel9/Savely-Android/actions/runs/38567179309) sulla produzione `41f2098` passa il job build ma fallisce il job UI: **222/224 PASS, due timeout, zero skip**. I timeout precedono le azioni target di Poker e Settings, sui primi caricamenti. I log non dimostrano la causa. Il workflow archivia report HTML, non APK UI/logcat: stessa fonte non attesta identità dei byte locali. La successiva diagnostica test-only conserva deadline e asserzioni, senza retry o modifiche alla produzione.

## Tentativi conservati

Il primo run `93f54276-eda8-43b0-980c-64e622d3b63f` passa 13/14: l'assert della chiusura zero attendeva il messaggio sbagliato; la produzione già rifiutava l'importo. La revisione delle 18 immagini rileva TCG e azioni dello stesso colore, corretto in `41f2098`, e prefissi USD mancanti nei pixel. La causa dei prefissi non è dimostrata: controllo isolato 2/2 e gruppo finale 14/14 sullo stesso main `41f2098` passano la nuova guardia per ciascun glyph, senza refactoring del layout. Il controllo non prova che due frame aggiuntivi abbiano corretto il difetto.

## Prezzi: difetto del servizio condiviso

Il bundle distribuito di `update-prices`, versione 16, è stato letto senza modificarlo: SHA-256 `1348961e3f0e8f62a38d2fd16277da38a97b0d55b17283cb32cf47b669d3b01d`. Il servizio salva la quotazione Yahoo senza verificare la valuta e prova ticker su altre borse. Può quindi applicare un prezzo USD a una posizione EUR. La ricerca crypto può selezionare il primo risultato diverso dall'identità richiesta. Un successo HTTP non qualifica questi prezzi.

La [proposta revisionabile](../backend-proposals/update-prices/README.md) verifica simbolo/valuta, rifiuta mismatch, elimina fallback ambigui e conserva cash FX e autorizzazione JWT/cron. I test offline non qualificano il servizio distribuito. Non sono stati modificati prezzi reali per aggirare il controllo. V06 richiede ancora distribuzione autorizzata e canary QA verificato, oppure una decisione esplicita sul perimetro escluso.
