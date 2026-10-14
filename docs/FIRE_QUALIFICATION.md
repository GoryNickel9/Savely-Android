# V09 — FIRE: fixture locali qualificate, percorso firmato aperto

**V09 resta aperto.** Passano 593 JVM per variante e il gruppo locale di **24/24 test FIRE su API 31**. Sono verificati form EN, draft, reset, import confermato e confini del login nelle fixture. Le 21 invocazioni del percorso firmato, inclusi force-stop con nuovo PID e annullamento del chooser Android, non sono state eseguite. La matrice conserva **14 criteri chiusi localmente e 19 aperti**; nessuna nuova qualifica globale o pubblicazione.

## Sorgenti e binari

La modifica V09 è registrata in `eb2ffed374e08affbf89291b6e7644d3036b3535`. Il build iniziale usa il main `ModuleScreen.kt` SHA-256 `0cd655afb15e2442f301ba5d0437706658d7ebcb4b34310e7b8b816ef77ce494`, poi conservato nel commit. Il riferimento web rimane `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. I cambi V10 successivi non appartengono all'APK qualificato qui.

| Artefatto congelato | SHA-256 |
| --- | --- |
| App debug, code 18 / 0.1.17-alpha | `923037398ae000df2e3226da0250fd35ecf1cb3ef0dc1f27762408b9decc1363` |
| Test APK iniziale | `9f608e24ade2aa645067e0a7e959f4f8c614e71c7910f4599fd08fc8e60eb3cc` |
| Test APK corretto | `86215b335f6ccff5b0307146d2abf6c2f31557bb7b782306bac6fcb4f6ba59b5` |

L'app installata è stata estratta e confrontata con il main congelato. La correzione successiva compila soltanto `:app:assembleDebugAndroidTest`: build riuscita in 1m48s, main invariato, installato soltanto l'APK test. Il dispositivo è l'AVD dedicato SavelyReleaseQA31/API 31, 1080×1920 px, density 480, font scale 1.0. Non è una prova sul release R8 né sul telefono fisico.

## Correzione e invarianti

FIRE conserva i parametri per utente ma vincola il form e le azioni alla revisione del login che lo ha aperto. Un nuovo SID dello stesso utente non può applicare una vecchia anteprima, ripristinare o condividere il vecchio form, né pubblicare letture iniziate dalla sessione precedente. La normale rotazione dei token con lo stesso SID conserva la revisione e consente import e reset espliciti. Il commit locale ricontrolla il proprietario prima di scrivere le preferenze.

| Perimetro | Osservato nelle fixture |
| --- | --- |
| Form EN Standard/Barista | Tutte le dieci etichette e i valori; Standard target 1.200.000 €, risparmio 40,0%, 24,4 anni; Barista target 600.000 € e FIRE completo 900.000 €. Passaggio fra tab senza perdere i parametri. |
| Draft e persistenza | Ultimo snapshot valido conservato con `abc` o draft incompleto; ripristino esplicito, importi mensili/annuali, dati corrotti e ricreazione della schermata. Preferenze dell'altro utente invariate. |
| Default DB e sessione | Letture TLS locali dei 24 mesi, reset selettivo, errore 503 e controlli busy; cambio UID o SID durante la prima lettura impedisce pubblicazione e richiesta transazioni. Il refresh dello stesso SID esegue un solo POST auth locale e permette import/reset. |
| Import e condivisione | Anteprima e annulla senza scritture; applicazione esplicita di dieci parametri, query invalide e anteprima invalidata dopo modifica. Il monitor intercetta un `ACTION_CHOOSER` con `ACTION_SEND`/`text/plain`; nessuna applicazione destinataria viene aperta. |
| Proiezione | Nominale/reale, selezione anno e contributi; zero rendimento irraggiungibile, valori non finiti rifiutati. Percentuale reale 150,0% mantenuta nel testo mentre la barra accessibile è limitata a 100%. |

`FireQualificationTest` contiene cinque nuovi casi; `FireSessionBoundaryTest` altri cinque. Il gruppo include inoltre sette `FirePersistenceTest`, quattro `FireSharingScreenTest`, un `FireDatabaseScreenTest` e due `FireProjectionScreenTest`. Sono 24 casi selezionati, non una nuova suite Android completa dell'app.

## Tentativi e risultati conservati

Run `artifacts/fire-qualification/50fdda61-c97e-42ee-9d13-5e502d3bf703`; file locali ignorati. Il build iniziale registra **593/593 JVM debug e 593/593 release**, zero failure/error/skip; lint debug senza errori. Le correzioni seguenti toccano solo le fixture e non richiedono un nuovo main.

| Esecuzione | Esito | Tempo instrumentation |
| --- | --- | ---: |
| Gruppo iniziale, `v09-api31.log` | 19/24 PASS, cinque failure conservate | 354,718 s |
| Proiezione isolata invariata, `fixture-repair/projection-before.log` | 0/1, stesso errore `assertIsDisplayed` sul testo finale | 9,976 s |
| Cinque casi corretti, `fixture-repair/failed-five-attempt1.log` | 5/5 PASS | 58,992 s |
| Gruppo completo corretto, `fixture-repair/full-24-attempt1.log` | **24/24 PASS**, zero failure/skip | **261,134 s** |

Il draft EN non chiudeva focus/tastiera prima di verificare il messaggio. Due fixture chiamavano `performScrollTo` sui pulsanti action di `AlertDialog`, senza un antenato scrollabile. Il test isolato del grafico montava tutta la Card senza scorrimento e pretendeva visibile il testo sotto il grafico nel viewport disponibile. Le correzioni nascondono l'IME dopo l'input, verificano e toccano direttamente i pulsanti del dialogo e montano il solo grafico isolato in una Column scrollabile, scorrendo ai target. Asserzioni, numeri, store e guardie owner rimangono presenti; nessuna correzione di produzione deriva da queste cinque failure.

Il manifest `fixture-repair/test-attempt1-manifest.json` identifica hash di test/sorgenti, main e profilo del dispositivo. Il riepilogo `fixture-repair/summary.json` SHA-256 `fa5e29c32839fd0f80386d555fdc9df88a535255c3835e2d1272a0581ec26b87` conserva le quattro esecuzioni; log finale SHA-256 `a3e0e79b599bca9b38081b71e4298e4722df6389940de252074329581624d029`.

## Gate ancora aperto

Queste fixture usano UUID e preferenze sintetici, trasporto negato o MockWebServer TLS. Non usano credenziali reali, provider o scritture backend. Il chooser intercettato non dimostra il comportamento del chooser Android reale; la ricreazione Compose non dimostra da sola il force-stop del processo.

Restano il percorso firmato da 21 invocazioni con due QA, nuovo PID dopo force-stop e ultimo draft valido conservato, separazione delle preferenze A/B dopo login reali e annullamento del chooser senza destinatari. Anche l'harness preparato non è prova eseguita. Nessun PASS viene esteso ad API 35, agli APK V10 successivi o alla CI sul precedente sorgente. Dispositivo e slot build sono stati rilasciati al responsabile dopo il gruppo finale.
