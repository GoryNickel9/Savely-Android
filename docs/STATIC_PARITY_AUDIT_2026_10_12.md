# Audit statico Android/web — 12 ottobre 2026

Alla chiusura dell'audit le correzioni erano **da verificare**. Su richiesta dell'utente, l'audit non eseguiva test, build, lint, CI, emulatori o chiamate al backend. Dopo la successiva autorizzazione, la [verifica locale](STATIC_AUDIT_VERIFICATION.md) passa 528 JVM e due suite complete da 132 casi su API 31/35, con le correzioni del solo harness documentate. Nessun nuovo APK pubblico o certificazione globale deriva da questi risultati.

## Riferimenti e metodo

Android iniziale: `0714f3974cd29bcdbedada193dde70d5b1553b4a`, ramo `codex/native-android-alpha`. Web: `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, identico nel checkout e in `origin/main` al momento della lettura remota. La repo web viene soltanto letta. Nessuna modifica a schema, trigger, RLS, configurazioni o account del servizio.

Il confronto legge rotte, permessi, query, formule, validazioni e gestione degli errori. Le 41 rotte web sono rappresentate dalle destinazioni native raggruppate. Una corrispondenza di rotte o di formule non dimostra equivalenza completa: restano dati reali, UI, servizi distribuiti e concorrenza da verificare.

| Area letta | Riferimenti web principali | Riferimenti Android principali |
|---|---|---|
| Navigazione, lingua, documenti, aggiornamenti | `App.tsx`, `lib/permissions.ts`, `hooks/usePermissions.ts`, pagine Privacy/Cookies/Termini, catalogo EN | `SavelyApp.kt`, `Localization.kt`, `LegalScreen.kt`, `SavelyTheme.kt`, `release/`, manifest e asset legali |
| Account, sicurezza, Coppia, Admin | Hook auth/MFA/sessioni, componenti settings, `CoupleBudget.tsx`, hook Coppia e contratti Admin | `SupabaseApi.kt`, `AppViewModel.kt`, `SettingsScreen.kt`, `CoupleRepository.kt`, `CoupleScreen.kt`, `AdminScreen.kt` |
| Finanza | Dashboard, Transactions, RecurringExpenses, Budget, Categories, Charts, Portfolio, NetWorth, Insights e hook associati | `finance/`, schermate, repository, calcoli e ViewModel |
| Moduli e trasferimenti | FIRE Standard/Barista, Poker, Fumo, TCG, Libreria, dialog import/export e `useCardTraderPrices.ts` | `modules/`, `transfer/`, modelli, calcoli, persistenza e schermate |

## Lacune corrette

| ID | Causa e comportamento precedente | Correzione e limite |
|---|---|---|
| STATIC-REC-01 | Elaborazione ricorrenze: INSERT del movimento può riuscire e PATCH della scadenza fallire. `RecurringProcessingException` non attivava il blocco di riconciliazione del ViewModel. | `FinanceViewModel` blocca ulteriori scritture e replay fino a refresh riuscito. Il backend resta una sequenza di richieste, non una transazione atomica. |
| STATIC-AUTH-01 | `profile`/`updateProfile` preparavano il filtro dal proprietario iniziale, mentre il trasporto poteva selezionare una sessione successiva. Le conferme account/sicurezza non conservavano la revisione del login; la revoca corrente avviava anche un secondo logout dalla UI. | Query profilo e azioni account/TOTP/revoca/eliminazione ricevono proprietario e revisione attesi; Settings conserva il vincolo e rifiuta le conferme obsolete. Rimosso il logout duplicato: la revoca corrente usa l'invalidazione centrale. Non cambia le autorizzazioni server. |
| STATIC-AUTH-02 | Il catch AUTH/STORAGE del ViewModel poteva cancellare lo stato di un login nuovo dopo una risposta tardiva del lavoro precedente. | Il reset considera la revisione dell'azione e aggiorna lo stato sotto il lock del proprietario. Restano da eseguire le regressioni di invalidazione e sostituzione della sessione. |
| STATIC-CPL-01 | Invito, caricamenti e azioni Coppia potevano attraversare logout/login dello stesso utente perché verificavano solo UID o usavano chiamate non vincolate. | Snapshot iniziale unico, GET/RPC/scritture legati a proprietario e revisione; pubblicazione atomica e rifiuto delle conferme obsolete. |
| STATIC-EXP-01 | Export paginato e salvataggio del CSV controllavano soltanto UID: un nuovo login dello stesso account poteva utilizzare un export preparato prima del logout. | `ExportData` conserva la revisione; pagine, pubblicazione e autorizzazione al salvataggio verificano il login originario. La scrittura locale già avviata non può essere annullata retroattivamente da un successivo logout. |
| STATIC-COL-01 | La panoramica TCG/Libreria controllava solo UID durante le pagine; le altre letture dei moduli e la riconciliazione Manga avevano controlli esterni senza vincolo esplicito nella GET. | Letture legate alla revisione iniziale e pubblicazione atomica del risultato; una sessione sostitutiva non riceve il vecchio snapshot. La riconciliazione Manga conserva UUID e payload. |
| STATIC-TCG-01 | CardTrader restituisce EUR, ma il pulsante poteva salvare il numero su record in un'altra valuta; i campi mostravano comunque € nell'editor. | Aggiornamento automatico limitato a record EUR, etichette nella valuta dichiarata e avviso per verificare prezzi precedenti non-EUR. Nessuna conversione o modifica automatica dei record esistenti. |
| STATIC-POK-01 | La riga mensile Rakeback mostrava importi ma ometteva la percentuale presente nel web. | Aggiunta percentuale mensile con il calcolo esistente, incluso rake zero; traduzione IT/EN. |
| STATIC-DOC-01 | Gli asset legali EN ereditavano fallback italiani dal catalogo web: processori, infrastruttura, termini e frammenti «utilizza»/«necessario». | Tradotti i frammenti nella sola repo Android, senza cambiare contenuto o link. `tools/legal-en-overrides.json` conserva le traduzioni anche per future estrazioni; il generatore non viene eseguito in questo audit. |
| STATIC-PLAN-01 | Roadmap e matrice riportavano suite ancora in corso, un vecchio QA inutilizzabile, 42/43 contratti e lacune XLS/XLSX ormai smentite. | Registrati i risultati storici corretti, i tre QA dedicati e i gate residui. Mapping reale `source-map.json`; contratti storici 41/42. I nuovi fix non ereditano i PASS. |

## Differenze e prove ancora aperte

I prezzi TCG non hanno metadati di provenienza: un vecchio valore manuale USD e un prezzo CardTrader EUR salvato per errore nello stesso campo sono indistinguibili. Android conserva i prezzi manuali e segnala la verifica; non può riparare i valori preesistenti senza conferma o dati aggiuntivi. Questa restrizione è una differenza intenzionale dal web, necessaria a non mescolare valute.

Bank resta un convertitore esterno aperto nel browser; l'app importa il CSV risultante. XLS/XLSX, restore globale e PDF nativo non sono funzioni presenti nel riferimento da portare. FIRE conserva parametri locali per account; gli archivi import/Manga non promettono ripresa completa dopo process death. Storici e riepiloghi mantengono i limiti dichiarati nei contratti finanziari.

Nei percorsi letti non sono emerse altre omissioni dimostrabili per dashboard, grafici, budget, categorie, patrimonio, FIRE e Fumo. Questo è un esito di lettura, non una certificazione di tutte le combinazioni di dati, dei servizi distribuiti o del risultato visivo sul telefono.

## Verifica rinviata

Scritti 14 nuovi casi JVM: uno sulle ricorrenze, cinque su export/collezioni/valute e otto su account/inviti/risposte tardive. Estesa l'asserzione della fixture Android Rakeback esistente e adeguato il tipo d'errore atteso del cambio account Coppia. Le fixture Settings osservano la sessione prima del rendering, come fa il flusso reale; la revoca corrente attende l'invalidazione centrale e rifiuta un secondo logout. Nessuno eseguito.

| Fixture | Copertura aggiunta |
|---|---|
| `FinanceViewModelTest.kt` | Blocco dopo INSERT confermato/PATCH fallito, replay e refresh fallito |
| `AccountOwnerBoundaryTest.kt`, `AppViewModelLateFailureTest.kt` | Conferme obsolete, postflight profilo/fattori, invito interrotto e AUTH tardivo |
| `TransferExportSessionTest.kt`, `CollectionOverviewRepositoryTest.kt` | Nuovo login dello stesso UID, salvataggio rifiutato ed export Coppia con connessione appartenente all'utente |
| `ModuleRecordValuesTest.kt`, `PokerScreenTest.kt` | Prezzi manuali in valuta preservati e percentuale mensile Rakeback |

1. Eseguire le nuove regressioni di ricorrenze parziali, cambio login nei contratti account/Coppia/export/collezioni, valuta TCG e percentuale Poker.
2. Eseguire suite integrate e controlli Android 12/15 sul sorgente finale, inclusi conferme obsolete, refresh e ricreazione.
3. Completare i gate reali in [QA_QUALIFICATION.md](QA_QUALIFICATION.md): email/PKCE senza mailbox disponibile, modifiche account/eliminazione sacrificabile, percorsi Coppia/Admin esclusi, import e servizi esterni.
4. Verificare APK release firmato, aggiornamento autenticato, confronto finale web/Android e dispositivo fisico prima della 1.0.0.

I 514 JVM e 132 casi Android 12 riportati nella qualifica appartengono a `0714f39`. `parity/web-baseline.json` conserva `globalParityVerified=false`, `verifiedApkVersion=null` e un registro separato dell'audit senza test.

## Modifiche alla documentazione

Roadmap, matrice, README e changelog distinguono codice presente, prove storiche e verifiche rinviate. Corretti i numeri dei contratti, i percorsi di mapping e le informazioni sugli account QA. Il mapping legale include layout, catalogo EN, asset, generatore e fixture già presente. Nessuna scadenza o dichiarazione di stabilità viene dedotta dall'audit statico.
