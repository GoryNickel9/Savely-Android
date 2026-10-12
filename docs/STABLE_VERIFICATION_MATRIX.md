# Matrice delle verifiche mancanti per la stabile

12 ottobre 2026. Revisione statica, senza test, build, CI, emulatore o accesso al backend. Base documentale `5b79e75`; correzioni di codice `de53ac70d46ab567766f57298501dbdf5d7fdf55`, non compilate né eseguite. Web letto nel checkout invariato `1cfcf6297e85d56bfafe3522362ceecef6f6b281`.

La matrice collega 33 verifiche aperte a implementazioni, fixture esistenti e criteri di chiusura. Non è un inventario di 33 funzioni mancanti. Una fixture citata indica dove riprendere la verifica; la sua presenza non dimostra l'esito sul nuovo sorgente.

## Evidenze disponibili e stato corrente

Il [confronto visuale precedente](COMMON_VISUAL_PARITY_REVIEW.md) riguarda il sorgente `16c7011`: 530 JVM, 28/28 casi mirati API 31, lint senza errori, 81 PNG nativi e 114 web appaiati. Sono campioni di lettura e presentazione, più quattro regressioni locali Sicurezza. Le suite complete API 31/35 dell'[audit precedente](STATIC_AUDIT_VERIFICATION.md) e i percorsi reali di [QA_QUALIFICATION.md](QA_QUALIFICATION.md) conservano i propri sorgenti e limiti.

Le due patch successive interessano Impostazioni e Dati: quei PASS non ne certificano il comportamento. L'audit iniziale non aggiungeva test; la preparazione successiva aggiunge otto casi Android, senza compilarli o eseguirli. `globalParityVerified=false`, `verifiedApkVersion=null` e `datasetCasesExecuted=false` restano invariati.

Ogni riga seguente è aperta. Per chiuderla occorre registrare sorgente, APK/checksum quando applicabile, input e ruolo, atteso, osservato, errori, pulizia ed evidenza. Una indisponibilità o un'esclusione si documenta separatamente, senza convertirla in PASS.

## 1. Schermate, dati e parità funzionale

| ID / perimetro | Codice e fixture da riprendere | Verifica mancante e criterio di chiusura | Dipendenza |
|---|---|---|---|
| V01 — Suite sul sorgente finale | `app/src/test/`, `app/src/androidTest/`; [TESTING.md](TESTING.md) | Rieseguire JVM, suite Android ordinarie e campione visuale sul codice con le due patch; associare risultati separati API 31/35 e lint alla stessa candidata. | Test/build nuovamente autorizzati. |
| V02 — Navigazione e permessi | [SavelyApp.kt](../app/src/main/java/cc/savely/android/ui/SavelyApp.kt), `NavigationScreenTest`, `SavelyNavigationAccessibilityTest` | Percorrere le destinazioni del mapping [FEATURE_PARITY.md](FEATURE_PARITY.md), back e cambio account; moduli negati non mostrano dati precedenti. IT/EN, font grandi e lingua/tema durante la navigazione. | Fixture, A/B/Admin per le policy reali. |
| V03 — Dashboard, Transazioni, Categorie, Budget | [FinanceScreen.kt](../app/src/main/java/cc/savely/android/finance/FinanceScreen.kt), `FinanceEditor.kt`; `FinanceScreenTest`, `CommonDatasetVisualTest` | Confrontare form CRUD, categorie eliminate/condivise, periodi custom invalidi/invertiti, ricerca combinata e filtri; verificare valori/errore prima del submit e rilettura dopo le scritture. | Fixture e record QA isolati. |
| V04 — Ricorrenze | `FinanceScreen.kt`, `RecurringCalendar.kt`, `RecurringSuggestions.kt`, `FinanceViewModel.kt`; `RecurringCalendarTest` | Calendario, suggerimenti/ignore per account, pausa/ripresa, elaborazione e deduplica; fallimento dopo una scrittura blocca ulteriori modifiche fino alla riconciliazione, senza replay. | Fixture più backend QA per le scritture. |
| V05 — Grafici | [FinanceChartsScreen.kt](../app/src/main/java/cc/savely/android/finance/FinanceChartsScreen.kt), `FinanceCharts.kt`; `FinanceChartsScreenTest` | Estendere il range osservato a tipo/categoria/valuta, granularità, confronto e grafici; stati vuoti/errori e tastiera. Tabella numerica e disegno devono rappresentare la stessa serie. | Fixture comuni; acquisizioni IT/EN. |
| V06 — Portafoglio | `PortfolioScreen.kt`, `PortfolioPositions.kt`, `PortfolioHistoryScreen.kt`; `PortfolioPositionsScreenTest`, `PortfolioHistoryScreenTest` | Storico registrato e ricostruito, copertura parziale, allocazioni, editor, chiusure singole/collettive e risultati incerti. Distinguere giornate intere native e tempo frazionario web. | Fixture; QA e prezzi per i flussi reali. |
| V07 — Patrimonio | `PortfolioModels.kt`, `PortfolioHistory.kt`, `PortfolioHistoryScreen.kt`; `PortfolioHistoryTest` | Tutti i periodi e dettagli, snapshot negativi/singoli/vuoti, variazioni e componenti per valuta. Non attribuire una valuta agli snapshot server né riconciliarli artificialmente con i totali correnti. | Fixture; confronto web. |
| V08 — Insights | `PortfolioModels.kt`, `FinanceScreen.kt`; `PortfolioModelsTest` | Gli altri sei detector oltre a risparmio/milestone osservati, soglie limite, filtri/severità, testi IT/EN e assenza di segnali. | Fixture comuni e acquisizioni. |
| V09 — FIRE | [ModuleScreen.kt](../app/src/main/java/cc/savely/android/modules/ModuleScreen.kt), `FireParameters.kt`, `FireDatabaseDefaults.kt`, `FireSharing.kt`; `FirePersistenceTest`, `FireSharingScreenTest` | Form intero EN, draft validi/invalidi, reset, default DB, nominale/reale, import/condivisione e passaggio Standard/Barista; dati e preferenze isolati per login. La condivisione richiede conferma prima di applicare. | Fixture; account QA, storage e chooser per i flussi reali. |
| V10 — Poker | `ModuleScreen.kt`, `ModuleCalculations.kt`, `ModuleRecordValues.kt`; `PokerScreenTest` | Next Cut, quarter rounding, mediana, riepiloghi per anno, CRUD e mese non modificabile. Due client sullo stesso mese: documentare conflitto/vincoli, senza dichiarare atomicità non dimostrata. | Fixture più backend QA concorrente. |
| V11 — Fumo | `ModuleScreen.kt`, `ModuleRecordValues.kt`; `FumoScreenTest` | Liquido/CBD/THC, record aperti, derivati legacy/null/zero, fine rimossa e date invalide. Conservare i derivati salvati in sola lettura; confrontare form e ricalcolo al salvataggio. | Fixture; record QA legacy controllati. |
| V12 — TCG | `ModuleScreen.kt`, `CollectionOverview.kt`, `CatalogRepository.kt`; `TcgScreenTest`, `CollectionOverviewScreenTest` | Form delle tre categorie, condizioni/lingue, immagini, filtri, panoramica vuota e prezzi manuali USD. Quote della torta da quantità; aggiornamento automatico CardTrader solo EUR. | Fixture; servizio reale in S02. |
| V13 — Libreria/Manga | `ModuleScreen.kt`, `MangaVolumes.kt`, `CatalogRepository.kt`; `LibraryScreenTest`, `CollectionScreenTest` | Tutte le categorie, prezzi null e form EN, copertine/volumi, serie parziali e ripresa dopo nuovo login. Verificare ricerca e selezione oltre al riepilogo IT osservato. | Fixture; Google Books in S01. |
| V14 — Stati trasversali | Schermate sopra; test locali delle rispettive aree | Errori HTTP, rete assente, caricamento, dati vuoti, focus/tastiera, annulla e doppio submit. Ogni errore resta visibile e non produce retry automatico di una scrittura. | Fixture controllate; servizi reali distinti. |

## 2. Account, Coppia e Admin

| ID / perimetro | Codice e fixture da riprendere | Verifica mancante e criterio di chiusura | Dipendenza |
|---|---|---|---|
| A01 — Form Auth | [AuthScreen.kt](../app/src/main/java/cc/savely/android/ui/AuthScreen.kt), `AppViewModel.kt`; `AuthScreenTest` | Submit UI con credenziali QA, ripristino/logout, registrazioni disabilitate e consenso; errori/policy non avviano richieste indebite. Il login via API QA già osservato non prova il submit del form. | QA; registrazione positiva richiede condizioni dedicate. |
| A02 — Email/PKCE | `AuthCallbacks.kt`, `AndroidAuthLinkStore.kt`, `AuthCallbackInbox.kt`, `PasswordRecoveryScreen.kt`; `NativeAuthCallbackTest`, `PasswordRecoveryScreenTest` | Consegna, conferma signup, recupero completo e callback sul dispositivo; link scaduto/usato, state errato, riapertura e cancellazione preservano la sessione corretta. | **Bloccata:** nessuna mailbox controllata; destinatari autorizzati necessari. |
| A03 — Profilo e credenziali | [SettingsScreen.kt](../app/src/main/java/cc/savely/android/ui/SettingsScreen.kt), `SupabaseApi.updateAccount/deleteAccount`; `SettingsProfileRecreationTest` (nuovo, non eseguito), `SettingsSecurityTest`, `AccountOwnerBoundaryTest` | Nome/valuta prima e dopo ricreazione, caricamento fallito e cambio login; cambio password/email reali, errori e conferme. Eliminazione solo di un account creato per essere sacrificato. | Patch SC02 non verificata; mailbox per email, identità sacrificabile per delete. |
| A04 — MFA | `SettingsScreen.kt`, `SupabaseApi.kt`; `SettingsMfaTest`, `AuthSecurityTest` | Enrollment/challenge UI, codice errato, fattore pendente, rimozione e annulla; QR locale senza cache/segreti nei log. Qualificare il ruolo SDK dei fattori pending/verified. | Fixture e fattori QA creati per il run. |
| A05 — Sessioni | `AuthSessionIds.kt`, `SupabaseApi.revokeSession`, `SettingsScreen.kt`; `SettingsSecurityTest` | Revoca individuale reale, corrente/altro dispositivo, righe legacy non revocabili, sessione sostitutiva e server failure. Verificare refresh respinto della sola sessione revocata. | Due sessioni QA; UUID registrati, nessuna sessione di utenti reali. |
| C01 — Inviti | [CoupleScreen.kt](../app/src/main/java/cc/savely/android/ui/CoupleScreen.kt), [CoupleRepository.kt](../app/src/main/java/cc/savely/android/data/CoupleRepository.kt); `CoupleScreenTest`, `CoupleRepositoryTest` | Rifiuto/cancellazione via schermata, codice invalido, richieste già pending e risposte tardive; rilettura su entrambi gli account e pulizia dei soli record creati. | A/B QA. Invito/accettazione storici non chiudono questi rami. |
| C02 — Quote, Budget e archivio | `CoupleScreen.kt`, `FinanceSharedTransactions.kt`, `CoupleRepository.kt`; `FinanceSharingScreenTest` | Altri percorsi di rimozione quote, modifica limite, revoca e archivio dai form; validazioni split e valuta, isolamento e metadata dopo revoca. Non cambiare il rounding della view SQL. | A/B QA più fixture. Scope storico in QA_QUALIFICATION.md. |
| D01 — Permessi Admin | [AdminScreen.kt](../app/src/main/java/cc/savely/android/ui/AdminScreen.kt); `AdminScreenTest`, `AdminOperationOwnerTest` | Dialog completo IT/EN, selezione/salvataggio e perdita ruolo durante il preflight; diniego non mostra cache. Rileggere e ripristinare solo permessi QA modificati. | Admin QA; utenti bersaglio A/B dedicati. |
| D02 — Azioni Admin escluse | `AdminScreen.kt`, `SupabaseApi.webPost/requestPasswordReset`; `AdminScreenTest` | Reset email reale, delete di identità sacrificabile e toggle globale dalla UI, con annulla/errori e stato finale registrato. La precedente finestra di provisioning non qualifica lo switch UI. | Mailbox/identità dedicate; toggle globale solo entro autorizzazione specifica al run. |

## 3. File, interruzioni e servizi

| ID / perimetro | Codice e fixture da riprendere | Verifica mancante e criterio di chiusura | Dipendenza |
|---|---|---|---|
| F01 — Picker e opzioni import | [TransferScreen.kt](../app/src/main/java/cc/savely/android/transfer/TransferScreen.kt); `TransferDocumentScreenTest` | BANK/USD prima della ricreazione mentre OpenDocument è aperto: al ritorno mantenere formato/valuta e produrre anteprima senza INSERT. Annulla non legge backend. Nessuna conferma import ripristinata automaticamente. | Patch SC01 non verificata; picker reale locale/cloud. |
| F02 — Import e riconciliazione | [TransferViewModel.kt](../app/src/main/java/cc/savely/android/transfer/TransferViewModel.kt), `TransferRepository.kt`, `TransferCsv.kt`, `TransferRules.kt`; `TransferMutationRepositoryTest`, `TransferParityScreenTest` | CSV QA Revolut/BBVA/TR, ISIN, categorie/keyword/note, duplicati e risposta persa. Un UUID incerto richiede rilettura/confronto completo prima di conferma; nessun replay dei confermati. Documentare scritture parziali. | Fixture; record QA, provider; Bank esterno per estratto→CSV→import. |
| F03 — Export | `TransferRepository.export`, `TransferViewModel.saveExport`, `TransferScreen.kt`; `TransferExportSessionTest`, `TransferDocumentScreenTest` | Tutte le 25 tabelle/pagine e account-bound snapshot; CreateDocument locale/cloud, annulla, errore a metà scrittura e cambio login. Dopo process death non promettere snapshot ripristinato; verificare documento creato senza dati e recovery. | Provider reali, QA e storage. |
| F04 — Process death/disco | `TransferViewModel.kt`, `FireParameters.kt`, `MangaVolumes.kt`, `AndroidSessionStore.kt`; `FirePersistenceTest`, `CollectionScreenTest` | Terminazione reale del processo, riapertura e scrittura disco fallita: separare draft, risultati incerti e dati già persistiti. Import senza checkpoint su disco; nessun completamento automatico inventato. | Ambiente dedicato con controllo processo/storage. |
| S01 — Google Books | [CatalogRepository.kt](../app/src/main/java/cc/savely/android/modules/CatalogRepository.kt), `ModuleScreen.kt`; `CatalogRepositoryTest` | Ricerca reale riuscita e selezione con metadata/copertina; distinguere successo da errore/rate limit gestito. La prova storica HTTP 429 resta fallita per il percorso positivo. | Rete e disponibilità del fornitore. |
| S02 — CardTrader e FX | `CatalogRepository.kt`, `ModuleScreen.kt`, `FinanceExchangeRates.kt`; `TcgScreenTest`, `FinanceExchangeRatesTest` | Quotazione EUR e cambi nel flusso reale, null/503, data/cambio e persistenza. USD manuale non viene sovrascritto con una quotazione EUR. | Servizi attivi e record QA. |

## 4. Candidata firmata e rilascio

| ID / perimetro | Codice e documenti | Verifica mancante e criterio di chiusura | Dipendenza |
|---|---|---|---|
| R01 — APK release finale | `app/build.gradle.kts`, `AndroidManifest.xml`; [RELEASE.md](RELEASE.md) | Build ottimizzata firmata, versione/checksum/certificato, lint e suite su API 31/35 sullo stesso sorgente; verificare comportamento dopo shrink/minify. | Build/test autorizzati, firma disponibile. |
| R02 — Aggiornamento autenticato | `ReleaseRepository.kt`, `ReleaseScreen.kt`, `AndroidSessionStore.kt`; `ReleaseTest` | Vecchio APK→candidata con stessa firma e sessione attiva, dati locali e accesso conservati. Qualificare recupero del backup della firma su altro computer. | Due APK firmati, QA autenticato e ambiente dedicato. |
| R03 — Telefono | [QA_QUALIFICATION.md](QA_QUALIFICATION.md), [STABLE_NON_CODE_GATES.md](STABLE_NON_CODE_GATES.md) | Registrare prova della candidata su dispositivo fisico separatamente dall'AVD. La conferma generale della 0.1.7 sul Doogee S100 non qualifica queste patch. | **Non disponibile:** utente ha escluso ulteriori prove dettagliate sul proprio telefono. |
| R04 — Documenti e decisione release | `LegalScreen.kt`, asset `legal/`, [FEATURE_PARITY.md](FEATURE_PARITY.md), baseline e note release | Revisione completa IT/EN, addendum Android, licenze/link, prove e differenze intenzionali consolidate. Assegnare baseline/APK verificato e pubblicare 1.0 solo dopo chiusura del perimetro o esclusioni esplicitamente accettate. | Evidenze finali; questa matrice non è approvazione della stabile. |

## 5. Collegamento ai 50 casi del dataset comune

Gli input/attesi e i riferimenti per caso restano nel [master](../parity/fixtures/static-common-dataset.json) e nei tre rapporti statici [finanza](STATIC_DATASET_FINANCE_REVIEW.md), [moduli](STATIC_DATASET_MODULE_REVIEW.md), [account](STATIC_DATASET_ACCOUNT_REVIEW.md). Il master non è modificato.

«Parziale» nella tabella indica soltanto il campione del confronto `16c7011`, non l'esecuzione integrale del caso. «Assente» indica assenza nel nuovo campione visuale, non assenza di implementazione o di test storici. I 24 test visuali non corrispondono uno a uno ai 50 casi. Le verifiche dinamiche elencate restano tutte da completare nel perimetro della stabile.

| Caso master | Campione visuale già disponibile | Verifica residua / riga matrice |
|---|---|---|
| FIN-01 | Parziale: KPI, donut e Budget IT/EN | Altri periodi/confronti e collegamenti, V03. |
| FIN-02 | Parziale: mese e ricerca CIBO | Range custom, filtri combinati, ricerca Cena isolata e form, V03. |
| FIN-03 | Parziale: mediana e widget | Mesi vuoti, categoria/valuta e modifiche Budget, V03. |
| FIN-04 | Assente | Calendario e transizioni, elaborazione distinta dalla proiezione, V04. |
| FIN-05 | Parziale: daily 120→90 e range | Weekly/altre modalità, filtri e confronti, V05. |
| FIN-06 | Parziale: totali per valuta e realizzato | Allocazioni/rendimento e CRUD, V06. |
| FIN-07 | Parziale: endpoint dello storico | Punti intermedi registrati/ricostruiti e copertura, V06. |
| FIN-08 | Parziale: totali e −39/+11/+22% | Altri periodi/snapshot e grafica EN completa, V07. |
| FIN-09 | Parziale: saving e milestone | Varianti −39,01/snapshot singola e altri detector, V08. |
| fire-standard-baseline | Parziale: target/form IT, grafico EN | Risultati completi e form EN, V09. |
| fire-barista-baseline | Parziale: target IT | Restanti valori, EN e interazioni, V09. |
| fire-live-draft | Assente | Editing live, invalidi e ultimo snapshot valido, V09/F04. |
| fire-zero-return | Assente | Rendimento zero, target irraggiungibile e presentazione, V09. |
| poker-hourly-weighted | Parziale: annuale/hourly IT | Mesi/anni/form e altri deal, V10. |
| poker-quarter-tie | Assente | Quarter ties positivi/negativi nel form e display, V10. |
| poker-next-cut | Assente | Riepiloghi e soglie dal form, V10. |
| poker-median-spending | Assente | Mediane su mesi popolati e vuoti, V10. |
| poker-rakeback-years | Assente nel campione nativo | Confronto delle due annualità e submit/concorrenza, V10. |
| fumo-core-baseline | Assente | Valori core nel percorso di schermata, V11. |
| fumo-liquid-cycle | Assente | Salvataggio e ricalcolo del ciclo, V11. |
| fumo-legacy-liquid | Parziale: lettura derivati IT/EN | Editor e altri record reali legacy, V11. |
| fumo-legacy-open-quantity | Assente | Record aperto e fallback indipendenti dalla durata, V11. |
| fumo-zero-monthly-placeholder | Assente | Zero salvato/costo zero→placeholder nel chiamante attivo, V11. |
| tcg-mixed-currency | Parziale: riepiloghi e torta IT/EN | Categorie/form, vuoto e filtri, V12. |
| tcg-price-unavailable | Assente | Aggiunta con prezzo null/errore, valore fallback, V12/S02. |
| tcg-manual-usd | Assente come azione | Prezzo USD manuale e refresh automatico EUR, V12/S02. |
| library-null-prices | Parziale: panoramica IT | Modifica con prezzi null e tutte le categorie/EN, V13. |
| manga-cover-volume | Assente | Cover, range volumi e serie interrotta, V13/F04. |
| csv-revolut-original-usd | Assente | Preview/commit con valuta originale e rate, F02. |
| bank-isin-position | Assente | ISIN→mapping→posizione quantità 1, F02. |
| bank-category-rewrite | Assente | Keyword/nota, tipo categoria e mapping, F02. |
| csv-duplicate-identity | Assente | Identità completa, duplicati tra valute/tipi e concorrenza, F02. |
| csv-roundtrip-transaction | Assente | Escaping/export/reimport delle sole transazioni, F02/F03. |
| export-owner-snapshot | Assente | Snapshot, tutte le pagine e save vincolato al login, F03. |
| bank2csv-external | Assente | Estratto autorizzato→CSV esterno→preview/commit, F02. |
| PERM-BOOL | Parziale: Admin/diniego | Permessi non booleani e mapping di tutte le destinazioni, V02/D01. |
| AUTH-REGISTRATION | Parziale: Auth senza submit | Toggle/consenso, signup positivo e negativo, A01/A02. |
| AUTH-MFA-GATE | Parziale: stato AAL2 mostrato | Challenge UI e gate prima dell'accesso dati, A04. |
| AUTH-MFA-PENDING | Parziale: elenco pending/verified | Enrollment/rimozione/annulla, A04. |
| AUTH-OWNER | Assente come azione | Cambio utente e nuovo login stesso UID durante richieste, A03/A05/F02/F03. |
| AUTH-LATE-FAILURE | Assente come azione | Risposta tardiva non invalida sessione sostitutiva, A01/A05. |
| AUTH-SESSIONS | Parziale: elenco e session ID | Revoca reale individuale e refresh della sessione bersaglio, A05. |
| AUTH-ACCOUNT | Parziale: profilo IT in lettura | Bozza ricreata, update credenziali/delete, A03/SC02. |
| AUTH-CALLBACK | Assente | Email/PKCE positivo e negativi sul dispositivo, A02. |
| CPL-INVITE | Assente come azione | Invito/accettazione/rifiuto/cancellazione UI, C01. |
| CPL-ODD-CENT | Parziale: quote nelle fixture | Split dai form e view SQL attiva, C02. |
| CPL-MULTICURRENCY | Parziale: mediane/Budget B | Scritture/rimozioni nelle due valute, C02. |
| CPL-REVOKED | Assente | Revoca UI, archive e metadata/RLS, C02. |
| ADM-DENY | Parziale: diniego A | Revoca ruolo/cache/conferme durante azioni, D01. |
| ADM-MUTATE | Assente | Salvataggio permessi, reset/delete/toggle autorizzati, D01/D02. |

## 6. Lacune dimostrate e patch non verificate

| ID | Causa statica e trigger | Modifica minima | Verifica ancora richiesta |
|---|---|---|---|
| SC01 — Opzioni import | `TransferScreen` conservava formato/valuta in `remember`. La callback OpenDocument usa quei valori: ricreazione mentre il picker è aperto→default Revolut/EUR; anche una preview BANK già nel ViewModel può apparire con selettore Revolut. | Formato come nome enum e valuta in `rememberSaveable(owner)`; entrambi i selettori scrivono il nome. Conferma, preview, ledger e scritture restano nel percorso esistente. | F01: scegliere BANK/USD, ricreare prima del ritorno picker, poi controllare preview e valuta; ripetere con NORMALIZED e annulla. Nessun INSERT fino a conferma nuova. |
| SC02 — Bozza profilo | Nome/valuta erano già saveable, ma `LaunchedEffect(api)` rileggeva il profilo a ogni ricreazione e li riassegnava dal server, cancellando edit non salvati. I chip valuta consentivano anche modifiche durante caricamento/salvataggio. | Flag saveable di inizializzazione: il primo profilo non null inizializza i campi; le letture successive aggiornano disponibilità senza sostituire la bozza. Nome e chip valuta sono disabilitati finché il profilo non è disponibile o l'operazione è occupata. Resta il `key(userId, authSessionId)` esistente. | A03: modificare nome/valuta senza save, ricreare e attendere GET; conservare draft e zero PATCH. Primo caricamento, profilo null/errore, controlli disabilitati, salvataggio, cambio utente e nuovo login stesso UID vanno controllati separatamente. |

Queste modifiche non aggiungono checkpoint al ledger import, non persistono password/conferme e non chiudono il gate process death. Non sono stati modificati schema, RLS, trigger, account, servizi, dataset master o sorgenti web. La lettura dei confini di sessione in Settings/Admin/Coppia/Transfer non ha dimostrato una nuova lacuna che giustifichi riscriverli: restano i percorsi dinamici della matrice.

## Ordine per la prossima verifica autorizzata

Preparati tre nuovi casi in [TransferDocumentScreenTest.kt](../app/src/androidTest/java/cc/savely/android/transfer/TransferDocumentScreenTest.kt): risultato OpenDocument differito fino a dopo la ricreazione con BANK/USD e NORMALIZED, più annullamento senza letture/scritture. Rafforzato il caso esistente di preview ricreata con un assert sul formato selezionato. La fixture usa il registry controllato e MediaStore locale; non certifica picker di sistema, provider cloud o process death.

Preparati cinque casi in [SettingsProfileRecreationTest.kt](../app/src/androidTest/java/cc/savely/android/ui/SettingsProfileRecreationTest.kt): bozza dopo ricreazione e GET con PATCH solo al salvataggio esplicito, reset fra utenti/login, caricamento iniziale, profilo assente ed errore 503. La ricreazione usa l'Activity; il backend è una fixture TLS locale che rifiuta richieste estranee al profilo dell'account. Nessuno degli otto casi è compilato o eseguito. Questa preparazione non cambia la produzione né chiude verifiche della matrice.

1. Compilare ed eseguire le regressioni preparate SC01/SC02, poi V01 sul codice risultante.
2. Coprire form, stati vuoti/errori e casi del dataset senza campione, collegando ogni risultato all'ID.
3. Qualificare percorsi QA/file/servizi disponibili e conservare separatamente mailbox e telefono non disponibili.
4. Verificare candidata firmata e aggiornamento autenticato; consolidare baseline e rilascio solo con evidenze finali.

Non viene assegnata una data della stabile: mailbox, dispositivo e disponibilità dei servizi impediscono oggi una stima fondata. Questa attività conclude la mappatura statica; tutte le esecuzioni sopra attendono una nuova autorizzazione ai test.
