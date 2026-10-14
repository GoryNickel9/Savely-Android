# V06 — Qualifica del Portafoglio

**V06 è PASS LOCALE nel perimetro Portafoglio.** Alle fixture e alle 17 verifiche CRUD sul release firmato si aggiunge il canary prezzi: AAPL USD aggiornato dal form, BTC USD rifiutato per valuta incompatibile, baseline QA ripristinato. I due run restano distinti. Sono chiusi localmente 14 criteri, 19 restano aperti; nessuna pubblicazione o parità globale deriva dai risultati sotto.

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

Il bundle distribuito di `update-prices`, versione 16, è stato letto senza modificarlo: SHA-256 `1348961e3f0e8f62a38d2fd16277da38a97b0d55b17283cb32cf47b669d3b01d`. Quella versione salvava la quotazione Yahoo senza verificare la valuta e provava ticker su altre borse. Poteva quindi applicare un prezzo USD a una posizione EUR. La ricerca crypto poteva selezionare il primo risultato diverso dall'identità richiesta. Un successo HTTP non qualifica questi prezzi.

La [proposta revisionabile](../backend-proposals/update-prices/README.md) verifica simbolo/valuta, rifiuta mismatch, elimina fallback ambigui e conserva cash FX e autorizzazione JWT/cron. Il [deployment autorizzato e il canary](../backend-proposals/update-prices/DEPLOYMENT_REPORT.md) sono conclusi: versione 17 ACTIVE, `verify_jwt=false`, moduli del bundle identici alla proposta. Il campione reale seguente verifica una quotazione accettata e un rifiuto; i test offline conservano il proprio perimetro sintetico.

## Canary prezzi reale — 14 ottobre 2026

Run separato `artifacts/v06-price-canary/1216fd9d-fa37-409f-afff-00280e208259`, sullo stesso release `bf86519c…` e harness `e7d28ff3…` della tabella, riletti dopo l'installazione. L'AVD dedicato è SavelyReleaseQA31/API 31; lingua EN. Il primo aggiornamento del pacchetto debug non è riuscito prima di qualsiasi login o chiamata prezzi: il motivo non è stato acquisito. Conservata la failure, il solo debug sintetico è stato identificato tramite SHA/flag e sostituito con il release congelato. L'harness e i report precedenti sono rimasti presenti. È una nuova installazione, non una prova di upgrade autenticato.

Le 13 guardie host finali passano in 0,204 s, senza rete. I primi 11 controlli e la successiva correzione del ramo con `assets_updated=0` nel manual update restano conservati. Quel ramo consente cleanup solo con asset immutato, storico assente e log d'errore esattamente riferito al seed; il percorso positivo richiede sempre aggiornamento 1 ed errori vuoti. La revisione indipendente identifica gli hash del runner e delle guardie prima del run reale.

| Passaggio | Osservato |
| --- | --- |
| Baseline, 14:23:58,889417 UTC | Tre QA verificati; zero sessioni/fattori, asset/storico/manual update/log prezzi. 33 categorie e sei snapshot preesistenti da conservare. |
| Seed host | Due soli nuovi UUID riservati prima dell'INSERT: A stock AAPL USD, B crypto BTC USD; quantità 1, acquisto 0,01, corrente 0,02. Nessun retry. |
| A, form nativo | Login 12,923 s, singola azione prezzi 23,360 s e logout 17,004 s: tre metodi PASS, 53,287 s complessivi di instrumentation. Cooldown visibile. |
| A, server alle 14:24:47,964580 UTC | Asset e unica riga storico entrambi **345,53 USD**; quantità 1. Log `checked=1`, `updated=1`, `errors=[]`; un manual update. B ancora immutato. Il servizio arrotonda HALF_UP a due decimali; la coincidenza osservata non è una verifica indipendente del tick grezzo Yahoo. |
| B, client host in RAM | Identità e sessione B verificate; una sola chiamata JWT alla funzione. HTTP 200, versione contratto 2, `checked=1`, `updated=0`, unico errore `QUOTE_CURRENCY_MISMATCH` per l'esatto UUID BTC. Asset ancora 0,02: nessuna scrittura B su asset, storico o manual update. È presente il solo log prezzi B atteso. Il login B e questo rifiuto non sono prove del form Android. |
| Cleanup, 14:25:22,917393 UTC | Sei DELETE con preimmagine completa e prenotazione durevole: due asset, uno storico, un manual update e due log. Verificata l'assenza di ciascun UUID; baseline ripristinato, zero sessioni/fattori QA, 33 categorie e sei snapshot conservati. |

`evidence/result.json` riporta `passed=true`, `baseline_restored=true`, `cleanup_pending=false`, `native_positive=true`, `negative_mismatch=true`; nessun rollback eseguito. Il logout B è locale e vincolato alla sola sessione creata dal runner. Non sono state modificate registrazioni, schema, cron, segreti, ruoli o fattori esistenti. Ogni seed, chiamata prezzi e cancellazione ha una prenotazione precedente; nessuna mutazione viene ripetuta dopo un esito ambiguo.

| Evidenza locale ignorata | SHA-256 |
| --- | --- |
| `evidence/result.json` | `f7784c397790cd0125fa41e6a9bf32f6b695c80805e701b3a8732b82cce5241c` |
| `evidence/a-prices-observed.json` | `70f1076e5466f7fc0492f1837af62a6b83e306783027ad3cd669f8388a9950b7` |
| `evidence/b-prices-observed.json` | `5580e729a22d45c2d38afe5379d76b3480a621be19eefa1e07f60376baf971f0` |
| Ultimo `cleanup-price_update_logs-…-observed.json` | `519e772b5272bd5fac290938c4773a0cbe59accd442857b79cc0456f7d0482a9` |
| Runner `run-canary.py` | `0c601cd144cd7b7dbb781e43f82947c8c2afeef4c268cb0b4ce130a57be4d993` |
| Guardie `canary_guards.py` | `3e6e5eab0080800360a8973cbad999ad5b71cc2fcf687f807de216c9237d782f` |

I tre metodi del canary si aggiungono alle 17 verifiche CRUD storiche: non costituiscono un unico run continuo del protocollo originale. Il campione non qualifica tutti i simboli, valute, provider, disponibilità futura o cron. Yahoo può fornire la chiusura precedente; i vecchi prezzi errati non sono riparati. La sorgente web resta da allineare prima di un futuro deployment. La CI storica 222/224 resta fallita; la nuova [CI 38580288372](https://github.com/GoryNickel9/Savely-Android/actions/runs/38580288372) ha il job build fallito e il job UI ancora in corso. Il canary locale non modifica questi esiti. Flag globali e release pubblica invariati.
