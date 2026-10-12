# Matrice delle verifiche mancanti per la stabile

## Aggiornamento candidata locale 0.1.10-alpha

Produzione `459bbac`, release immutabile SHA `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa`, fixture Android `74d7d0d`. Passano 536 JVM per variante e 170/170 debug API 35 in 562,616 s; API 31 passa 170/170 in 551,778 s, senza failure/skip. Lint entrambe zero errori/32 warning/6 hint. Il [campione release R8](SIGNED_RELEASE_QUALIFICATION.md) verifica upgrade autenticato API 35, export locale e recovery esplicito dopo process death. Il nuovo run firmato API 31 passa login, upgrade, profilo, English/Light e logout con continuità della stessa sessione server e zero sessioni finali. Export/BANK/FX restano nel precedente run separato, chiuso manualmente; quattro tentativi driver falliti conservati.

Conservati 81 PNG e 81 JSON per API; raccolta conclusa ed emulatori spenti, come confermato dal responsabile dell’integrazione.

V01 e R01 sono qualificati localmente; le altre 31 righe rimangono aperte, con nuove evidenze parziali in A01/A03/F01/F03/F04/S02/R02/R04. **R01 è PASS LOCALE nel criterio originale**: build ottimizzata/firma/lint, suite debug API 31/35 e campione R8 osservato, senza richiedere la qualifica di ogni funzione nel release. Nessun PASS debug è esteso a tutti i flussi minificati; mailbox, telefono e cloud non sono gli unici residui: form/stati e percorsi autonomi della tabella restano da qualificare. Le sezioni storiche sotto conservano sorgente ed esiti originali.

12 ottobre 2026. Matrice nata dalla revisione statica, base documentale `5b79e75`, patch `de53ac70d46ab567766f57298501dbdf5d7fdf55`. La [verifica locale successiva](RECREATION_REGRESSION_VERIFICATION.md), autorizzata, compila le patch e passa le suite complete API 31/35 sul sorgente `f60ab38`. Web invariato `1cfcf6297e85d56bfafe3522362ceecef6f6b281`; nessuna CI o nuova prova backend.

La matrice collega 33 verifiche a implementazioni, fixture e criteri di chiusura. Nel consuntivo storico seguente soltanto V01 era qualificata e restavano 32 righe aperte; oggi V01 e R01 sono PASS LOCALE e ne restano 31. Non è un inventario di funzioni mancanti. Una fixture citata indica dove riprendere la verifica; la sua presenza non dimostra l'esito sul nuovo sorgente.

## Evidenze storiche e criterio delle righe

Il [confronto visuale precedente](COMMON_VISUAL_PARITY_REVIEW.md) riguarda il sorgente `16c7011`: 530 JVM, 28/28 casi mirati API 31, lint senza errori, 81 PNG nativi e 114 web appaiati. Sono campioni di lettura e presentazione, più quattro regressioni locali Sicurezza. Le suite complete API 31/35 dell'[audit precedente](STATIC_AUDIT_VERIFICATION.md) e i percorsi reali di [QA_QUALIFICATION.md](QA_QUALIFICATION.md) conservano i propri sorgenti e limiti.

Le patch successive interessano Impostazioni e Dati. Preparati otto nuovi casi in `5f058dc`, poi corretto il montaggio delle fixture in `f60ab38`: primo run mirato 14 casi/sei failure conservato, finale 14/14; suite complete 164/164 su entrambe le API. Passano 530 JVM e 36 host Python, lint senza errori. I [risultati e limiti](RECREATION_REGRESSION_VERIFICATION.md) qualificano queste regressioni locali. `globalParityVerified=false`, `verifiedApkVersion=null` e `datasetCasesExecuted=false` restano invariati.

Nel consuntivo storico le righe diverse da V01 restavano aperte. Oggi anche R01 è PASS LOCALE nel suo criterio originale; le altre 31 restano aperte, incluse quelle con campioni parziali successivi. Per chiuderle occorre registrare sorgente, APK/checksum quando applicabile, input e ruolo, atteso, osservato, errori, pulizia ed evidenza. Una indisponibilità o un'esclusione si documenta separatamente, senza convertirla in PASS.

## 1. Schermate, dati e parità funzionale

| ID / perimetro | Codice e fixture da riprendere | Verifica mancante e criterio di chiusura | Dipendenza |
|---|---|---|---|
| V01 — Suite locali qualificate | `app/src/test/`, `app/src/androidTest/`; [rapporto](RECREATION_REGRESSION_VERIFICATION.md) | **PASS LOCALE:** 536 JVM per variante, 36 Python e 9 guard backup; suite debug complete API 31 170/170 in 551,778 s e API 35 170/170 in 562,616 s, zero failure/skip, app `459bbac` e fixture `74d7d0d`. Lint zero errori. Storico 530 JVM/164 per API e failure conservati separatamente. | Qualifica delle suite debug conclusa; campione release R8 distinto in R01. |
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
| A01 — Form Auth | [AuthScreen.kt](../app/src/main/java/cc/savely/android/ui/AuthScreen.kt), `AppViewModel.kt`; `AuthScreenTest` | **Parziale:** login dal form sul release firmato con credenziali QA osservato nei campioni API 35 e nuovo API 31, profilo/upgrade/logout e pulizia finali verificati. Restano registrazioni disabilitate/consenso e rami errore/policy nel servizio reale; nessuna registrazione positiva o email qualificata. | QA; registrazione positiva richiede condizioni dedicate. |
| A02 — Email/PKCE | `AuthCallbacks.kt`, `AndroidAuthLinkStore.kt`, `AuthCallbackInbox.kt`, `PasswordRecoveryScreen.kt`; `NativeAuthCallbackTest`, `PasswordRecoveryScreenTest` | Consegna, conferma signup, recupero completo e callback sul dispositivo; link scaduto/usato, state errato, riapertura e cancellazione preservano la sessione corretta. | **Bloccata:** nessuna mailbox controllata; destinatari autorizzati necessari. |
| A03 — Profilo e credenziali | [SettingsScreen.kt](../app/src/main/java/cc/savely/android/ui/SettingsScreen.kt), `SupabaseApi.updateAccount/deleteAccount`; `SettingsProfileRecreationTest`, `SettingsSecurityTest`, `AccountOwnerBoundaryTest` | SC02 storica qualificata API 31/35. Tre nuove fixture GET nullo/503 dopo ricreazione e PATCH trattenuto passano nel finale API 35 da 170 casi; le stesse fixture passano anche nel run completo API 31 da 170 casi. Restano credenziali/email reali ed eliminazione solo di identità sacrificabile; fixture non chiudono gate reali. | Mailbox per email, identità sacrificabile per delete; nessun gate reale chiuso dai PASS delle fixture. |
| A04 — MFA | `SettingsScreen.kt`, `SupabaseApi.kt`; `SettingsMfaTest`, `AuthSecurityTest` | Enrollment/challenge UI, codice errato, fattore pendente, rimozione e annulla; QR locale senza cache/segreti nei log. Qualificare il ruolo SDK dei fattori pending/verified. | Fixture e fattori QA creati per il run. |
| A05 — Sessioni | `AuthSessionIds.kt`, `SupabaseApi.revokeSession`, `SettingsScreen.kt`; `SettingsSecurityTest` | Revoca individuale reale, corrente/altro dispositivo, righe legacy non revocabili, sessione sostitutiva e server failure. Verificare refresh respinto della sola sessione revocata. | Due sessioni QA; UUID registrati, nessuna sessione di utenti reali. |
| C01 — Inviti | [CoupleScreen.kt](../app/src/main/java/cc/savely/android/ui/CoupleScreen.kt), [CoupleRepository.kt](../app/src/main/java/cc/savely/android/data/CoupleRepository.kt); `CoupleScreenTest`, `CoupleRepositoryTest` | Rifiuto/cancellazione via schermata, codice invalido, richieste già pending e risposte tardive; rilettura su entrambi gli account e pulizia dei soli record creati. | A/B QA. Invito/accettazione storici non chiudono questi rami. |
| C02 — Quote, Budget e archivio | `CoupleScreen.kt`, `FinanceSharedTransactions.kt`, `CoupleRepository.kt`; `FinanceSharingScreenTest` | Altri percorsi di rimozione quote, modifica limite, revoca e archivio dai form; validazioni split e valuta, isolamento e metadata dopo revoca. Non cambiare il rounding della view SQL. | A/B QA più fixture. Scope storico in QA_QUALIFICATION.md. |
| D01 — Permessi Admin | [AdminScreen.kt](../app/src/main/java/cc/savely/android/ui/AdminScreen.kt); `AdminScreenTest`, `AdminOperationOwnerTest` | Dialog completo IT/EN, selezione/salvataggio e perdita ruolo durante il preflight; diniego non mostra cache. Rileggere e ripristinare solo permessi QA modificati. | Admin QA; utenti bersaglio A/B dedicati. |
| D02 — Azioni Admin escluse | `AdminScreen.kt`, `SupabaseApi.webPost/requestPasswordReset`; `AdminScreenTest` | Reset email reale, delete di identità sacrificabile e toggle globale dalla UI, con annulla/errori e stato finale registrato. La precedente finestra di provisioning non qualifica lo switch UI. | Mailbox/identità dedicate; toggle globale solo entro autorizzazione specifica al run. |

## 3. File, interruzioni e servizi

| ID / perimetro | Codice e fixture da riprendere | Verifica mancante e criterio di chiusura | Dipendenza |
|---|---|---|---|
| F01 — Picker e opzioni import | [TransferScreen.kt](../app/src/main/java/cc/savely/android/transfer/TransferScreen.kt); `TransferDocumentScreenTest` | **Parziale:** registry controllato storico API 31/35; sul release finale API 31 DocumentsUI locale verifica BANK/USD 12,50 dopo process death, NORMALIZED/EUR 12,50 e annulla, senza commit import. Restano provider cloud e interoperabilità Bank2CSV completa; non estendere il campione a ogni URI/provider. | Picker reale locale/cloud. |
| F02 — Import e riconciliazione | [TransferViewModel.kt](../app/src/main/java/cc/savely/android/transfer/TransferViewModel.kt), `TransferRepository.kt`, `TransferCsv.kt`, `TransferRules.kt`; `TransferMutationRepositoryTest`, `TransferParityScreenTest` | CSV QA Revolut/BBVA/TR, ISIN, categorie/keyword/note, duplicati e risposta persa. Un UUID incerto richiede rilettura/confronto completo prima di conferma; nessun replay dei confermati. Documentare scritture parziali. | Fixture; record QA, provider; Bank esterno per estratto→CSV→import. |
| F03 — Export | `TransferRepository.export`, `TransferViewModel.saveExport`, `TransferScreen.kt`; `TransferExportSessionTest`, `TransferDocumentScreenTest` | **Parziale:** sul nuovo release finale API 31/35 export 11 categorie proprie/1.696 byte, 25 tabelle lette, annulla e process death col nuovo documento vuoto rimosso e messaggio di preparazione esplicita. Nessuno snapshot ricostruito o retry automatico. Restano pagine reali multiple, altre entità popolate, errori disco/provider cloud e login durante scrittura; test debug da 170 casi distinti. | Provider reali, QA e storage. |
| F04 — Process death/disco | `TransferViewModel.kt`, `FireParameters.kt`, `MangaVolumes.kt`, `AndroidSessionStore.kt`; `FirePersistenceTest`, `CollectionScreenTest` | **Parziale:** terminazione reale con picker export e BANK API 31 verificata nel campione finale, recupero esplicito e nessun import automatico. Restano FIRE/Manga, storage fallito e import parziale dopo process death; il ledger import non è un checkpoint su disco. | Ambiente dedicato con controllo processo/storage. |
| S01 — Google Books | [CatalogRepository.kt](../app/src/main/java/cc/savely/android/modules/CatalogRepository.kt), `ModuleScreen.kt`; `CatalogRepositoryTest` | Ricerca reale riuscita e selezione con metadata/copertina; distinguere successo da errore/rate limit gestito. La prova storica HTTP 429 resta fallita per il percorso positivo. | Rete e disponibilità del fornitore. |
| S02 — CardTrader e FX | `CatalogRepository.kt`, `ModuleScreen.kt`, `FinanceExchangeRates.kt`; `TcgScreenTest`, `FinanceExchangeRatesTest` | **Parziale FX:** form nativo API 31 su release finale salva 0,01 USD/cambio EUR 0,894370, rilettura conferma payload e DELETE UI lascia zero righe QA. Non chiude CardTrader EUR, altre valute/date o indisponibilità. Il 200 host curl resta una prova distinta dai precedenti 301/403. | Servizi attivi e record QA. |

## 4. Candidata firmata e rilascio

| ID / perimetro | Codice e documenti | Verifica mancante e criterio di chiusura | Dipendenza |
|---|---|---|---|
| R01 — APK release finale | `app/build.gradle.kts`, `AndroidManifest.xml`; [RELEASE.md](RELEASE.md) | **PASS LOCALE nel criterio originale:** release code11/hash `d6c4ccb0…e12f0daa`, build ottimizzata, firma v2/certificato continuo, manifest/R8/asset e 536 JVM per variante; lint zero errori. Suite debug API 31/35 170/170 sullo stesso app `459bbac`, fixture `74d7d0d`; campioni release R8 autenticati/upgrade/export osservati. Non sono 170 test sul release R8; gli altri flussi restano nelle 31 righe aperte. | Qualifica locale conclusa; nessuna parità globale o pubblicazione. |
| R02 — Aggiornamento autenticato | `ReleaseRepository.kt`, `ReleaseScreen.kt`, `AndroidSessionStore.kt`; `ReleaseTest` | **Parziale:** upgrade autenticato code10→11 sul release finale API 35 conserva sessione, preferenze e dati di lettura, poi logout/server zero. Nuovo run API 31 passa la stessa continuità, logout e server zero; precedente export/BANK/FX e logout manuale appartengono a un’altra sessione/run. Quattro tentativi driver falliti conservati. Backup recuperato e usato per firma sullo stesso computer; recovery fuori computer resta aperta. | Due APK firmati, QA autenticato e ambiente dedicato. |
| R03 — Telefono | [QA_QUALIFICATION.md](QA_QUALIFICATION.md), [STABLE_NON_CODE_GATES.md](STABLE_NON_CODE_GATES.md) | Registrare prova della candidata su dispositivo fisico separatamente dall'AVD. La conferma generale della 0.1.7 sul Doogee S100 non qualifica queste patch. | **Non disponibile:** utente ha escluso ulteriori prove dettagliate sul proprio telefono. |
| R04 — Documenti e decisione release | `LegalScreen.kt`, asset `legal/`, [FEATURE_PARITY.md](FEATURE_PARITY.md), baseline e note release | **Parziale:** inventario runtime 80 artefatti/110 POM, licenze/font/NOTICE/MPL presenti e asset byte-esatti nel release finale; POM e normalizzazione CRLF documentati. Restano revisione editoriale completa/addendum e decisione release sul perimetro qualificato; globalParityVerified=false e verifiedApkVersion=null. | Evidenze finali; questa matrice non è approvazione della stabile. |

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

## 6. Lacune dimostrate e qualifica locale delle patch

| ID | Causa statica e trigger | Modifica minima | Verifica locale e limiti |
|---|---|---|---|
| SC01 — Opzioni import | `TransferScreen` conservava formato/valuta in `remember`. La callback OpenDocument usa quei valori: ricreazione mentre il picker è aperto→default Revolut/EUR; anche una preview BANK già nel ViewModel può apparire con selettore Revolut. | Formato come nome enum e valuta in `rememberSaveable(owner)`; entrambi i selettori scrivono il nome. Conferma, preview, ledger e scritture restano nel percorso esistente. | PASS delle fixture API 31/35 per BANK/USD, NORMALIZED, risultato differito e preview ricreata, annulla senza GET/INSERT e nuova conferma. F01 resta aperta per picker/provider reali. |
| SC02 — Bozza profilo | Nome/valuta erano già saveable, ma `LaunchedEffect(api)` rileggeva il profilo a ogni ricreazione e li riassegnava dal server, cancellando edit non salvati. I chip valuta consentivano anche modifiche durante caricamento/salvataggio. | Flag saveable di inizializzazione: il primo profilo non null inizializza i campi; le letture successive aggiornano disponibilità senza sostituire la bozza. Nome e chip valuta sono disabilitati finché il profilo non è disponibile o l'operazione è occupata. Resta il `key(userId, authSessionId)` esistente. | Cinque casi locali PASS API 31/35: draft dopo ricreazione/GET, PATCH esplicito, reset UID/login e primo caricamento nullo/503. Restano GET nullo/503 su draft ricreato, controlli durante PATCH trattenuto e percorsi reali A03. |

Queste modifiche non aggiungono checkpoint al ledger import, non persistono password/conferme e non chiudono il gate process death. Non sono stati modificati schema, RLS, trigger, account, servizi, dataset master o sorgenti web. La lettura dei confini di sessione in Settings/Admin/Coppia/Transfer non ha dimostrato una nuova lacuna che giustifichi riscriverli: restano i percorsi dinamici della matrice.

## Ordine per la prossima verifica autorizzata

Preparati tre nuovi casi in [TransferDocumentScreenTest.kt](../app/src/androidTest/java/cc/savely/android/transfer/TransferDocumentScreenTest.kt): risultato OpenDocument differito fino a dopo la ricreazione con BANK/USD e NORMALIZED, più annullamento senza letture/scritture. Rafforzato il caso esistente di preview ricreata con un assert sul formato selezionato. La fixture usa il registry controllato e MediaStore locale; non certifica picker di sistema, provider cloud o process death.

Preparati cinque casi in [SettingsProfileRecreationTest.kt](../app/src/androidTest/java/cc/savely/android/ui/SettingsProfileRecreationTest.kt): bozza dopo ricreazione e GET con PATCH solo al salvataggio esplicito, reset fra utenti/login, caricamento iniziale, profilo assente ed errore 503. La ricreazione usa l'Activity; il backend è una fixture TLS locale che rifiuta richieste estranee al profilo dell'account. Gli otto casi sono ora compilati e passano nelle suite complete API 31/35. La verifica corregge solo le fixture, qualifica V01 e lascia aperti i percorsi reali di A03/F01.

1. Riprendere le 31 verifiche residue; V01/R01 sono qualificate localmente nel rapporto firmato, con i limiti riportati sopra.
2. Coprire form, stati vuoti/errori e casi del dataset senza campione, collegando ogni risultato all'ID.
3. Qualificare percorsi QA/file/servizi disponibili e conservare separatamente mailbox e telefono non disponibili.
4. Completare i percorsi ancora aperti di aggiornamento/dati e recupero della firma fuori dal computer; consolidare parità e rilascio soltanto nel perimetro verificato.

Non viene assegnata una data della stabile: restano verifiche funzionali autonome e gate di mailbox, dispositivo e servizi. La qualifica locale è conclusa; non sostituisce i percorsi reali ancora mancanti.
