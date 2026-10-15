# Qualifica Fumo V11

V11 resta aperto alle 16 invocazioni sul release firmato. La build locale passa 611 test JVM per variante; lint debug/release non segnala errori. Il confronto con il codice web fissato coincide in 72 confronti. Il gruppo finale passa **15/15 API 31 in 256,038s**, senza failure/skip; durata host 263,057s. La matrice conserva 15 criteri chiusi localmente e 18 aperti; nessuna nuova pubblicazione.

## Correzioni

CBD e THC gestiscono `data_finito` TIMESTAMPTZ, mentre liquido mantiene DATE. Il calcolo legacy usa gli istanti, tronca i microsecondi al millisecondo e applica ceil ai giorni trascorsi, come JavaScript Date. L'editor mostra il giorno locale e conserva offset e frazione originali quando quel giorno non viene cambiato. La validazione confronta gli istanti conservati. Modifica e rimozione esplicite inviano nuova data o null.

La lettura conserva derivati salvati, null, zero e alias quantità. Il salvataggio esplicito ricalcola i derivati; rimuovere la fine azzera tutti i campi derivati. Gli importi seguono l'arrotondamento binario del riferimento web, inclusi 0.125→0.13, 0.305→0.30 e 1.005→1.00.

Un INSERT/PATCH generico con esito incerto chiude il form e richiede una rilettura completa riuscita prima di altre modifiche. Un GET fallito mantiene il blocco; i 4xx espliciti, escluso 408, restano correggibili. La chiusura include Manga con intervallo vuoto, che usa l'INSERT ordinario. Il percorso batch con piano immutabile e ripresa esplicita resta distinto. Owner e revisione del login originario vincolano i payload.

## Build e confronto web

Run `c93519c0-ae72-4de6-b960-05952c07074a`, candidata locale 0.1.17-alpha/code 18, base produzione `772a1e33ed6d8be1a414879903aa25f671113552` più i sorgenti V11 congelati. La build termina in **14m19s**: 611 JVM debug e 611 release, zero failure/error/skip. Lint di entrambe le varianti: zero Error/Fatal, 31 Warning e 6 Hint. Il metadata iniziale ometteva gli Hint; gli XML conservati riportano il conteggio completo. Dopo la correzione della vecchia fixture, build test e lint debug passano in **3m46s**, con gli stessi conteggi lint.

Il riferimento web è `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. Node 24.19.0 e TypeScript 5.8.3 eseguono il vero helper e i frammenti del renderer: **24 input × tre fusi (UTC, Roma, Los Angeles) = 72 confronti coincidenti**, rappresentativi di sette metodi JVM. La normalizzazione riguarda soltanto valuta, virgola e NBSP. Risultato SHA-256 `90f5897167c164e7e592fcfb4ef4f87cd6703e106b604844141d773db3ee23c2`; indice dei 16 file `dc3dab72e5c5b57cce12f311b7d4b0dd4c72b9609efb4b337dc0b24da2f73285`.

Il confronto non esegue 72 test JUnit e non qualifica editor, input malformati, HTTP, RLS, layout browser o persistenza remota. XML, sorgenti, APK e log sono conservati in `artifacts/fumo-qualification/`; il confronto web è in `artifacts/v11-web-differential/`.

## Tentativi Android conservati

| Tentativo | Esito | Osservazione |
|---|---|---|
| Primo gruppo | 13/15, 279.412s | Root Compose assenti nel caso risposta persa e dettaglio EN fuori viewport. |
| Isolato risposta persa | 1/1, 21.826s | Non dimostra la causa della precedente sparizione delle root. |
| Isolato EN / diagnostico EN | 0/1, 9.32s / 0/1, 8.064s | Il Text era sotto il bordo della lista; tutti gli antenati erano placed. |
| Due casi dopo scroll | 1/2, 49.367s | EN passa; frame Compose su worker provoca layout rientrante nell'altro caso. |
| Cinque Fumo con dispatcher accodato | 5/5, 98.840s | Scope e asserzioni HTTP invariati. |
| Gruppo successivo | 14/15, 224.578s | Timeout nel puntatore Save del Manga ordinario 503, prima del POST. |
| Isolato Manga 503 | 1/1, 31.673s | Stesso APK, senza edit. |
| Conferma completa | 14/15, 301.328s | Entrambi i casi Manga passano; la vecchia classe Fumo fallisce con layout rientrante e frame su worker. |
| Vecchia fixture corretta, dopo riavvio | 14/15, 310.231s | CBD perde le root; dialogo SIM concomitante e ANR SystemUI osservato al termine. Causa definitiva non dimostrata. |
| Stesso APK dopo chiusura ANR | 14/15, 195.950s | Gli altri 14 casi passano; il caso 503 si ferma alla cattura, che non rispetta ancora gli inset reali. Nove coppie conservate senza immagini precedenti. |
| Finale con attesa geometria | **15/15, 256.038s** | Stesso main, test corretto; nessuna failure/skip e 263,057s host. |

La fixture comune ora porta in vista il Text dopo l'item Lazy. Le due classi Fumo usano `StandardTestDispatcher`, drenato sul thread UI, e scroll bounded. Non aumentano timeout né riducono le asserzioni. La prova cambio login usa due SID sintetici distinti e verifica il cambio di revisione. Il timeout Manga isolato conserva una causa non dimostrata; i successivi PASS non lo cancellano.

Prima della cattura, la fixture Fumo attende IME nascosta e viewport compreso negli inset reali della finestra, con geometria stabile per due frame. La cattura comune conserva i controlli originali e viene chiamata una sola volta; eventuali errori riportano bounds e inset osservati. Build test e lint debug di questo ultimo sorgente passano in **2m56s**, con zero errori, 31 Warning e 6 Hint. Il log precedente non registrava gli inset del fallimento: non dimostra che la tastiera fosse la causa.

Il gruppo comprende cinque nuovi casi Fumo, due Manga ordinari, un parser platform, quattro Fumo preesistenti, due letture visuali comuni e un batch Manga dedicato. Sono fixture Compose/TLS sintetiche. L'app debug congelata è `00835d2a753b07b90fc94a2e31a9828b46df9d4340d6e9b67567659e9526e359`; il test iniziale `244626703390fe66f1db06062a0dc30ac47183b41570d91a160dfb45f76e69d9` resta distinto dai test corretti successivi.

## Immagini e residui

Dieci PNG e dieci JSON in `visual-corrected` sono stati visti e verificati per hash: legacy/detail, record aperti e riepiloghi annuali IT/EN. Viewport reali 360/320×577dp, safeDrawing verificato. A font 200%, i record richiedono scroll; la cattura del salvataggio incerto mostra per intero il primo avviso e una parte del secondo, che continua sotto il viewport. I tab EN scorrono orizzontalmente. Non si dichiarano form o tutti i tab interamente visibili nello stesso fotogramma. La review SHA-256 è `95aa37ec3951b6570326bc9a20623cc0b67a2025539ccad4b3c4345e156d4832`; il set finale richiede confronto degli hash o nuova review.

Restano le 16 invocazioni native firmate su tre righe QA riservate, con annulla, modifica, rimozione, timestamp backend e ripristino del baseline. Queste fixture non qualificano RLS, storage, API 35, telefono o nuovo R8. `globalParityVerified=false`, `verifiedApkVersion=null` e `datasetCasesExecuted=false` restano invariati.

## Attestazione finale locale

Test APK finale: `45ca15ec52adc4c4afa477b4ec379e081e7c8519cde13a4cca613f7acb355e56`, installazione riletta byte per byte. `fixture-repair/capture-insets/metadata.json` conserva i quattro sorgenti delle fixture e i rispettivi hash raw; il manifest finale aggiunge gli hash LF. Il main resta invariato rispetto alla build da 611 JVM per variante.

Prima del gruppo finale sono state archiviate e rimosse soltanto le 18 catture sintetiche esistenti; gli altri due nomi attesi erano già assenti. Tutti e venti i nomi sono stati verificati assenti prima del run. Le nuove dieci coppie finali coincidono byte per byte con quelle già viste da root; la review finale esplicita questa equivalenza. Il precedente gruppo fallito conserva soltanto nove coppie.

Indice immagini finale SHA-256 `54038afabf96696841de965578f6a33370d69d5b1efe3c0b0c0c26ff1e0dc12a`; review `6d4dd12f8141744e381408dee181b356be3d3174daf2b1a567e82869e9bd86e6`; attestazione finale `f9ec7c964a4bf985a175123d7d04db8c1a1ceb7916b9ccf41f4df1dbc54faa32`. File: `artifacts/v11-documentation-preparation/final-local/final-evidence.json` e run immutabile sopra. L'attestazione verifica anche JSON/log 15/15, receipt degli APK e tutte le coppie PNG/JSON; non esegue nuove prove né chiude V11.
