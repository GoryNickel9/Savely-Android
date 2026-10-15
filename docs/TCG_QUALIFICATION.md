# Qualifica TCG V12

Le fixture locali V12 passano **8/8 API 31 in 110,582s**, con 111,942s host, dopo la correzione del solo test TCG. Passano 623 JVM per variante e lint debug/release senza errori; root ha visto tutti i 16 PNG finali e verificato i 16 JSON. Il primo 6/8 resta conservato. **V12 resta aperto alla prova sul release firmato con CardTrader reale**; la matrice conserva 15 criteri chiusi localmente e 18 aperti.

## Modifica di produzione

`CatalogRepository.cardPriceFromResponse` richiede un DTO CardTrader esplicito: versione2, blueprint stringa identico all'ID richiesto, valuta EUR, minorUnit2, centesimi e listing interi bounded. Rifiuta risposte legacy prive di unità, versioni sconosciute, identità discordanti, valuta diversa, campi mancanti, stringhe numeriche, booleani, importi negativi, frazioni e overflow. Il testo formattato del provider non stabilisce il valore o la valuta.

Un prezzo null è valido soltanto quando il campo centesimi è presente e i listing sono zero; un prezzo numerico richiede almeno un listing. Il massimo accettato è 9.999.999.999 centesimi, pari a 99.999.999,99 EUR. Il parser verifica l'intero con BigDecimal prima della conversione in euro e nel tipo Double già usato dal catalogo; non dichiara che tutti i calcoli del modulo usino decimali esatti. Non converte né riscrive prezzi già persistiti.

La patch V12 comprende cinque file: CatalogRepository, i suoi test, CardTraderPriceContractTest, TcgQualificationModelsTest e TcgQualificationTest. Aggiunge 12 metodi JVM e tre Android; form, filtri, panoramica e torta di produzione restano quelli esistenti. Non modifica ModuleScreen, RLS, schema, cron o le guardie di scrittura V11/V14.

Il riferimento web fissato è `1cfcf6297e85d56bfafe3522362ceecef6f6b281`: `src/hooks/useCardTraderPrices.ts:8–27` divide i centesimi per100 e restituisce null se la quotazione non è disponibile; `src/components/tcg/TcgCollectionPage.tsx:115–128` conserva identità/condizione/lingua e alle righe386–387 usa il costo come fallback del prezzo null. `src/pages/tcg/Index.tsx:20–24` pesa la distribuzione per quantità. La risposta nativa v2 aggiunge prove di unità e identità al contratto legacy: il rifiuto dei vecchi DTO è intenzionale.

## Build e sorgenti congelati

Run `669d3361-b9c1-4131-8f30-89fd797408fd`, candidata locale 0.1.17-alpha/code 18, base `7883370bc16f70c3eba5889916da429f37a90f2a` più i cinque sorgenti V12 congelati. La build termina in 9m5s: assembleDebug e assembleDebugAndroidTest, 623 JVM debug e 623 release senza failure/error/skip, lint entrambe con 0 Error/Fatal, 31 Warning e 6 Hint. Questo log non attesta una nuova build R8 firmata.

Le 12 nuove JVM verificano il seed senza importare owner/valuta/prezzo, distribuzione quantitativa e vuoto, contratto v2, valori zero/0,01/12,50/massimo SQL, null/listing, unità, ID e precisione oltre Double. La differenza 611→623 riguarda metodi JUnit, non il numero di input contenuti nei singoli metodi.

| Oggetto iniziale | SHA-256 |
|---|---|
| Metadata iniziale | `6511810a34cfc82bb03caf6873b7fd37ddb869d9bec0fd9bb667003646a8cdd1` |
| Log build | `62c929ed773d65b23a177cc6b9678d0a87b36928b0b69af42524efa6716ebbaa` |
| App debug,21.298.821byte | `8f1998a18e65867df41e44a8ab0f05276aa7c207ec051c37179cba98a1951ca8` |
| Test APK iniziale,2.781.171byte | `1e2fe170d1528e5f081cff908c34a7f26da56a01e2fea35e0589e958eb6bcff7` |
| Risultato Android iniziale | `7f03f1366c8a8e406450f28c2fbaf6d57a9a0dee685f262718ac0f2e850c2822` |
| Log Android iniziale | `79ba6149113f726d4035084848af04f2f433609338cc90dbe8cb2fa98baf6b78` |

`input-metadata.json` conserva raw/LF e dimensioni di tutti i sorgenti congelati. Per CatalogRepository: raw `c35913bb0084e16211008909e15a4603d7c45fe4cfa62661e47e33412674f9ee`, LF `9ad4e07a1e2ee70212d44d147a578528941830f8c186c2a5b6784726b473dd04`. Il test TCG iniziale è raw `7bc9278027c8ee551ab9fcd9122c185d07f1e9b1866ccf61c437eff817882714`, LF `175afef09c87161a35a784e668853e93978b029d2d3f746ca75b1d4b40cfad0a`. Gli hash del test riparato e del suo APK sono distinti nell'attestazione finale sotto.

## Tentativi Android e correzione della fixture

Il gruppo esegue otto metodi: tre TcgQualificationTest, uno TcgScreenTest, due metodi TCG di CollectionOverviewScreenTest e due letture TCG CommonModuleVisualTest. I profili interni IT light360/font100 ed EN dark320/font200 non moltiplicano il conteggio JUnit.

| Tentativo | Esito | Osservazione |
|---|---|---|
| Initial API31 |6/8,116,217s instrumentation;119,043s host | Due failure in TcgQualificationTest; gli altri sei metodi passano. |
| Ricompilazione fixture, primo tentativo | Fallita prima della compilazione | `Unable to establish loopback connection`; log operativo conservato. |
| Ricompilazione fixture e lint debug, secondo tentativo | Riuscita in 4m6s | Applicato il workaround Java già usato nel progetto; main invariato, solo test TCG corretto. |
| Gruppo completo corretto | **8/8, 110,582s instrumentation; 111,942s host** | Stessi otto metodi, zero failure/skip/timeout/overflow, `OK (8 tests)` e codice finale -1. |
| Raccolta e review finali | **16 PNG e 16 JSON** | Tutte le coppie legate al run corretto e viste da root; geometria e hash verificati. Nessuna diagnostica inclusa. |

La prima failure cerca il sottototale «Investimento:100,00€» dopo Update mentre la lettura è asincrona. Il messaggio `No node found` include il suffisso multilinea `Semantics of the node:`; il catch originario riconosceva soltanto il prefisso esatto e non applicava l'attesa bounded. La Lazy osservata ha maxScroll0, compatibile con il precedente stato empty/loading: il log non dimostra un totale errato.

La seconda attesa registra writes1/priceReads1/imageReads2/denied0. Richiede anche Add abilitato, ma l'header Lazy può non essere composto dopo lo scroll alla carta. La correzione applicata riporta l'item0 in vista e conserva lo stesso predicato di read-count e pulsante abilitato. Il contatore della richiesta dimostra l'arrivo al mock, non il completamento della risposta o la pubblicazione UI; l'assenza esatta dell'header nel primo timeout non è stata dimostrata dal log.

La correzione conserva parser, dati, asserzioni, timeout e whitelist HTTP. Aggiunge diagnostica sintetica bounded del lifecycle delle chiamate e della geometria. Eventuali immagini `diagnostic-v12-*` restano fuori dall'allowlist delle 16 coppie finali. Il successivo run riuscito non cancella i due fallimenti né prova una causa ulteriore non osservata.

Il primo tentativo di build della fixture non modifica l'app e resta nel log `fixture-repair-build.log`. Il secondo, `fixture-repair-build-attempt2.log`, usa `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/savely-nonexistent-socket-dir` e passa build test più lint debug in 4m6s. Il workaround riguarda l'ambiente Java; non costituisce una correzione di produzione. Lint conserva zero Error/Fatal, 31 Warning e 6 Hint.

## Copertura locale osservata

Le fixture usano composable e API di produzione, TLS localhost con richieste esterne rifiutate e un PNG sintetico realmente decodificato. Il metodo delle tre categorie, passato nel primo run, attraversa Magic/Pokémon/Yu-Gi-Oh, condizioni/lingue, prezzo positivo/null/503 e cancellazione confermata conservando le altre righe.

Il gruppo finale completa panoramica vuota e filtri, distribuzione 90/9/1 da 100 pezzi con investimento 100 EUR e valore 10.990 EUR, modifica USD 13,75 con metadati preservati e refresh automatico EUR soltanto. Quattro DTO non sicuri danno errore senza PATCH; null non equivale a zero o a errore. Le letture comuni mantengono 9 pezzi totali, EUR 35/45/10 e USD 10/12/2 separati. La paginazione fallita nasconde i totali invece di pubblicare uno snapshot incompleto.

Sono raccolte dieci coppie nuove V12 e sei coppie comuni: EUR, USD e torta nei due profili. Il collector verifica i 32 nomi esatti, freshness rispetto al run, dimensioni PNG/JSON, profili e safeDrawing. I viewport reali sono **360×577dp IT** e **320×577dp EN**, rispettivamente 1080×1731px e 960×1731px; non sono alti 800dp. La review di root comprende tutti i 16 PNG e tutti i JSON.

| Immagini per profilo | Osservazione e limite |
|---|---|
| Catalogo creato | PNG magenta realmente decodificato; nome, set, condizione e lingua visibili. In EN/font200 il resto del record continua sotto il viewport. |
| Overview vuota | Zero e messaggio vuoto visibili; l'indicazione EN sulla distribuzione continua sotto. |
| Torta quantitativa e dettagli | IT mostra il grafico e le quote 90/9/1. In EN la cattura `quantity-pie` mostra il grafico intero e la legenda continua sotto; `quantity-pie-details` mostra tutte le tre righe 90/9/1 ma taglia la parte superiore del grafico. |
| USD manuale / EUR automatico | IT mostra 13,75 USD, avviso completo e refresh disabilitato. EN mostra la coda dell'avviso e il pulsante disabilitato; il valore e l'inizio dell'avviso sono sopra il viewport. L'asserzione UI/HTTP sul prezzo è distinta dalla sua presenza nel PNG EN. |
| Totali comuni EUR e USD | Nei due profili sono leggibili EUR 35/45/10 e rendimento 28,57%, poi USD 10/12/2 e rendimento 20%, in catture separate. |
| Torta comune | Legende 2/3/4 pezzi e 22,2/33,3/44,4% leggibili. In EN la parte superiore del grafico è fuori dal viewport. |

I tab scorrono orizzontalmente e possono restare parziali ai bordi. Il taglio di contenuto adiacente durante lo scroll non viene presentato come un form interamente visibile. Non sono golden né confronto pixel con il browser; l'immagine verificata è locale, non una copertina remota.

## Attestazione finale locale

Il receipt `fixture-repair/header-and-node-waits/repair-metadata.json` lega la correzione ai metadata e ai tre reperti iniziali, conserva gli XML JVM byte-identici e rilegge app/test installati. Il main rimane `8f1998a18e65867df41e44a8ab0f05276aa7c207ec051c37179cba98a1951ca8`. Il test APK finale, 2.785.363 byte, è `5c1648243a1ca3b0e3d98d0992e260ee65ddfc2f47ea463ec0912f0bf54f2e80`; il solo sorgente TCG corretto è raw `5ddb6e3a989420a0c33ac393875e89a761ede39d113286b7b11ab460547034e3`, LF `4f57e6390d731f6cc6af0b4c26c5aae1771d1cbbfabee15541ffd56dfe2abd32`. Gli altri sorgenti originali restano invariati.

| Reperto finale nel run | SHA-256 |
|---|---|
| `corrected.json` | `40fe1aa882275b5b2962e39d3628bed18777ebddbd6f9c333b25a7144e41f5a3` |
| `corrected.log` | `d6b09c033494c3e9f7ea89c67b15cdbf57988698252382d8a909c11062254417` |
| Receipt repair | `c48ac118fe3da067695a4e520d8e92285f7e0598e32e06e9fb45db338953fbac` |
| `visual-corrected-manifest.json` | `6c288ecc758872f71db88545f9562af72dd7fa481d8418a0650576f2ab09853f` |
| `visual-corrected-root-review.json` | `0a8961424efaff4522ab75950719acf7ca4ff1d2849f1fd84f0f42a75a773b21` |
| `final-local-evidence.json` | `1664e08d3d23b85fef5577f43eb593a84898fb053cefaea585239dc639e71b08` |

Il tar finale è `3c790b99ee0c419d4d5fb443ea5f9aa25adf56a7f2f5511a27631b9bc75556ca`. Il manifest conserva gli hash di ciascuno dei 32 file; la review riferisce la visione di root e i limiti sopra. Questa finalizzazione legge evidenze esistenti, senza eseguire altre prove.

## Servizio reale e limiti

Il deploy CardTrader v18 e il precedente canary host S02 sono documentati in `backend-proposals/cardtrader-proxy/DEPLOYMENT_REPORT.md`, run `7f2e85e3-79ac-420b-8997-666ee87a2feb`. Il campione autenticato di sola lettura aveva restituito blueprint37789, EUR/minorUnit2 e350.063 centesimi; non aveva inserito, modificato o eliminato carte. Questo è un reperto storico distinto, non una nuova osservazione o un refresh dal form firmato.

Restano l'azione singola dal form CardTrader sul release firmato, la rilettura del solo record QA riservato e il cleanup con baseline ripristinato. Le fixture non qualificano disponibilità continua del provider, immagini remote, RLS distribuite, cron, quotazioni legacy USD, API35 o telefono. Il token sintetico del nuovo test non contiene SID: non dichiara un nuovo campione di sostituzione login o refresh.

L'8/8 e la review visiva qualificano le fixture locali descritte; V12 e il residuo nativo S02 restano aperti finché manca quel percorso firmato. Conteggi 15/18, nessuna nuova pubblicazione e nessuna parità globale. Il lavoro si ferma a V12; V13 non viene avviato.
