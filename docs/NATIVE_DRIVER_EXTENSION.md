# Driver dei percorsi Fumo, file e cataloghi

Il secondo blocco aggiunge i percorsi opt-in `qualifyReservedTcgPrice`, `qualifyReservedMangaProcessDeath` e `qualifyReservedTransferPartial`: 352 righe aggiunte, nessuna rimossa. I 141 metodi di classe precedenti restano identici; diventano 150. Il selettore Manga usa il testo effettivo `Title: $title Vol. 3`.

Il 15 ottobre 2026 il driver completo compila con `:release-qualification:assembleDebug --max-workers=1` in 44 secondi. SHA-256 del sorgente completo: `42778508b4dd7144cc431f79551a4b7e342000ba87700a007833f749238b392b`. Review, diff, manifest e attestazione della compilazione sono in `artifacts/native-harness-integration-round2/`. La firma è ancora quella debug del modulo; nessuna di queste tre prove native è stata eseguita.

Il percorso prezzi aggiorna, osserva e cancella una sola carta EUR riservata dal coordinatore. I percorsi Manga e Transfer prevedono un solo submit, la terminazione del processo dopo una scrittura parziale osservata e il ritorno allo stesso task da Recents. Manga riprende gli UUID del piano; Transfer prepara una nuova preview senza confermare le righe restanti. Formati effettivi di Recents/DocumentsUI e timing della scrittura parziale restano da qualificare sul binario firmato.

Il driver di qualifica aggiunge tre entrypoint opt-in: `qualifyReservedFumoForm`, `qualifyReservedTransferForm` e `qualifyReservedCatalogRead`. Servono alle prove sui soli account QA riservati. Le aggiunte non avviano prove automaticamente.

L'integrazione modifica soltanto `ReleaseApkBlackBoxTest.kt`: 531 righe aggiunte, nessuna rimossa, senza nuove dipendenze. Rimuovendo i tre blocchi aggiunti si recuperano esattamente i byte precedenti; FIRE e tutti i 126 metodi preesistenti sono conservati. SHA-256 prima/dopo: `3bdee132f182e5c17a9db0eba8726222cc5d7acc1564a4168f749d01861bf83e` / `01314c3fcebd0ba7ca5a592551c0ea71650b67f7665e80b352f8977e24b3ba0f`.

Il 15 ottobre 2026 `:release-qualification:assembleDebug --max-workers=1` passa in 18 secondi; `git diff --check` passa. Manifest e log: `artifacts/native-harness-integration/manifest.json` e `compile-attempt1.log`. Questa compilazione usa la firma debug del modulo: non attesta ancora la firma di produzione né l'esecuzione sul release R8.

Fumo verifica modifiche esplicite e cancellazioni di righe adottate dal coordinatore. Transfer conserva il documento e il checkpoint prima del submit e limita il picker ai package DocumentsUI. Catalogo seleziona un risultato effettivamente restituito, confronta i campi visibili e annulla senza salvare. Errore, risposta vuota o indisponibilità del provider non costituiscono una ricerca positiva.

Restano da eseguire i protocolli nativi, con attestazione dei binari installati, identità/SID dal coordinatore, baseline e pulizia circoscritta. L'estensione non chiude criteri della matrice e non modifica i flag di parità globale.
