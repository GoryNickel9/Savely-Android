# Driver dei percorsi Fumo, file e cataloghi

Il driver di qualifica aggiunge tre entrypoint opt-in: `qualifyReservedFumoForm`, `qualifyReservedTransferForm` e `qualifyReservedCatalogRead`. Servono alle prove sui soli account QA riservati. Le aggiunte non avviano prove automaticamente.

L'integrazione modifica soltanto `ReleaseApkBlackBoxTest.kt`: 531 righe aggiunte, nessuna rimossa, senza nuove dipendenze. Rimuovendo i tre blocchi aggiunti si recuperano esattamente i byte precedenti; FIRE e tutti i 126 metodi preesistenti sono conservati. SHA-256 prima/dopo: `3bdee132f182e5c17a9db0eba8726222cc5d7acc1564a4168f749d01861bf83e` / `01314c3fcebd0ba7ca5a592551c0ea71650b67f7665e80b352f8977e24b3ba0f`.

Il 15 ottobre 2026 `:release-qualification:assembleDebug --max-workers=1` passa in 18 secondi; `git diff --check` passa. Manifest e log: `artifacts/native-harness-integration/manifest.json` e `compile-attempt1.log`. Questa compilazione usa la firma debug del modulo: non attesta ancora la firma di produzione né l'esecuzione sul release R8.

Fumo verifica modifiche esplicite e cancellazioni di righe adottate dal coordinatore. Transfer conserva il documento e il checkpoint prima del submit e limita il picker ai package DocumentsUI. Catalogo seleziona un risultato effettivamente restituito, confronta i campi visibili e annulla senza salvare. Errore, risposta vuota o indisponibilità del provider non costituiscono una ricerca positiva.

Restano da eseguire i protocolli nativi, con attestazione dei binari installati, identità/SID dal coordinatore, baseline e pulizia circoscritta. L'estensione non chiude criteri della matrice e non modifica i flag di parità globale.
