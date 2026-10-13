# Parità fra Savely web e Android

La [qualifica V02](NAVIGATION_FORM_QUALIFICATION.md) passa sulla candidata locale **0.1.13-alpha/code 14**: tutte le destinazioni mappate nei ruoli Admin/A/B, Back, «Altro» via azione nativa e revoca/ripristino FIRE, con quindici checkpoint nello stesso percorso firmato API 31/EN. Le fixture coprono IT/chiaro ed EN/scuro, 320dp/font 200%, sette dinieghi, cambio UID/SID e cache TCG come caso rappresentativo. Passano 552 JVM per variante e 13 navigazione più 33 sicurezza su ciascuna API 31/35. QA ripristinati e AVD di qualifica spenti. **Otto righe chiuse localmente, 25 aperte**; CRUD interni, servizi e parità globale mantengono i propri criteri aperti. Nessuna CI o pubblicazione.

Consuntivo precedente:

La [qualifica D01](ADMIN_FORM_QUALIFICATION.md) passa sui form Admin del release locale **0.1.12-alpha/code 13**: undici checkpoint API 31/EN, salvataggio e ripristino dei soli permessi QA, diniego dopo revoca del ruolo e rimozione di cache/dialogo. Le fixture IT/EN coprono anche chiavi sconosciute, errori, busy e login sostitutivo. Passano 545 JVM per variante, 12/12 fixture per ciascuna API 31/35 e 24 guardie host offline; lint senza errori. Permessi QA iniziali ripristinati, fattori/sessioni zero, emulatori spenti. **Sette righe chiuse nei rispettivi perimetri locali, 26 aperte**; parità globale non qualificata, nessuna CI o pubblicazione.

Consuntivo precedente:

La [qualifica A05](SESSION_FORM_QUALIFICATION.md) passa sullo stesso release locale 0.1.11-alpha/code 12: nove checkpoint dai form API 31/EN, refresh respinto del bersaglio, ritorno al login dopo revoca corrente e refresh positivo di una sessione superstite host. Client secondari in RAM, non telefoni; la lista degli accessi è storica. 543 JVM per variante e 7/7 fixture su ciascuna API 31/35, lint senza errori; fallimenti iniziali conservati, fattori/sessioni QA zero. **Sei righe chiuse nei rispettivi perimetri locali, 27 aperte**; produzione invariata e nessuna parità globale.

Consuntivo precedente: la [qualifica A04](MFA_FORM_QUALIFICATION.md) passa nei form MFA con chiave manuale sul release locale 0.1.11-alpha/code 12: 14 checkpoint nello stesso run, 14/14 fixture per API 31/35, 540 JVM per variante e lint senza errori. Setup, pending, codice errato, gate del nuovo login, annulla e rimozione sono osservati; renderer QR e fallback passano nelle fixture, QR del provider non visualizzato nel campione. Fattori/sessioni QA zero, AVD spenti. **Cinque righe chiuse nei rispettivi perimetri locali, 28 aperte**; parità globale non qualificata e suite complete precedenti legate ai propri sorgenti.

Consuntivo precedente: la [qualifica C02](COUPLE_FORM_QUALIFICATION.md) verifica dai form QA sul release firmato API 31 quote EUR/USD, budget, quattro rimozioni e revoca con archivio in sola lettura. Passano 14/14 fixture su ciascuna API 31/35 e 31 guardie host; 220 letture REST coincidono e i soli record del run sono puliti. Il crash iniziale Compose API 35 resta documentato, con causa non dimostrata e successive prove sullo stesso APK superate. Al termine di C02 erano chiuse V01/R01/C01/C02 e restavano **29 verifiche aperte**, parità globale non qualificata.

Consuntivo precedente: la [qualifica C01](COUPLE_INVITATION_QUALIFICATION.md) verifica rifiuto e cancellazione degli inviti dai form QA sul release firmato API 31, riletture per entrambi i ruoli, pulizia e sette fixture su ciascuna API 31/35. V01/R01/C01 erano chiuse localmente; restavano 30 verifiche aperte. Il consuntivo firmato precedente riportava 31 prima di questa prova.

Qualifica locale successiva della candidata 0.1.10-alpha: 536 JVM per variante e 170/170 debug su API 31/35, con APK release firmato e campioni reali di login, aggiornamento, logout, picker/export e FX. Il [rapporto](SIGNED_RELEASE_QUALIFICATION.md) associa hash e sorgenti; la [matrice](STABLE_VERIFICATION_MATRIX.md) chiude V01/R01 localmente e conserva 31 verifiche aperte. I risultati storici sotto mantengono il proprio perimetro. La candidata non è pubblicata e la parità globale resta non qualificata.

Stato al 12 ottobre 2026: 0.1.9-alpha pubblicata con 503 JVM e due suite complete da 130 casi su Android 12/15. Il sorgente `0714f39` ha le prove reali delimitate in [QA_QUALIFICATION.md](QA_QUALIFICATION.md). L'[audit statico successivo](STATIC_PARITY_AUDIT_2026_10_12.md) è seguito dalla [verifica locale](STATIC_AUDIT_VERIFICATION.md): 528 JVM e due suite complete da 132 casi su Android 12/15 passano sul codice corretto. Questo non estende i PASS backend ai nuovi percorsi. La [parità globale e i gate reali](STABLE_NON_CODE_GATES.md) restano aperti.

Baseline web: [commit 1cfcf6297e85d56bfafe3522362ceecef6f6b281](https://github.com/GoryNickel9/Savely/commit/1cfcf6297e85d56bfafe3522362ceecef6f6b281), verificato sul checkout locale e sul ramo remoto `main`. L'inventario in `src/App.tsx` comprende 41 rotte: 6 pubbliche, 13 finanza/account, 21 con permesso specifico e una wildcard. Una rotta non corrisponde necessariamente a una singola funzione.

Il [confronto sul dataset comune](COMMON_DATASET_PARITY_REVIEW.md) fissa 50 casi di base e distingue attesi statici da risultati osservati. Nuove esecuzioni: 528 JVM e 36 host superati; tre gruppi di output FIRE/Fumo originali web identici al golden. Nessun nuovo PASS di schermata, backend o APK deriva da questi risultati. Coppia e Fumo sono confrontati attraverso i chiamanti attivi, oltre agli helper isolati.

Il [confronto osservato Android/web](COMMON_VISUAL_PARITY_REVIEW.md) sul dataset comune registra una nuova esecuzione di 530 test JVM e un run completo di 28/28 test mirati su Android 12/API 31, con 81 PNG nativi e 114 PNG web appaiati. Corretti layout Sicurezza/storico e accesso a Dati con testo grande, tastiera dei grafici e percentuali/scala FIRE; aggiunta la distribuzione TCG per quantità. Lint debug senza errori; risultati circoscritti a fixture locali, senza CI, backend reale o nuova release. Il confronto di tutti i flussi e i gate della stabile restano aperti.

## Inventario iniziale

La [matrice delle verifiche mancanti](STABLE_VERIFICATION_MATRIX.md) collega codice, fixture, dipendenze e criteri di chiusura a 33 righe e ai 50 casi comuni. La [verifica locale successiva](RECREATION_REGRESSION_VERIFICATION.md) qualifica SC01/SC02 e V01: 530 JVM, 36 host Python e 164/164 in ciascun run completo API 31/35, lint senza errori. Restano 32 righe aperte e i percorsi reali; nessuna baseline globale viene promossa da questi PASS. Il campione web precedente conserva il proprio sorgente e scope.

I package `ui`, `data`, `finance`, `transfer` e `modules` contengono le schermate e i contratti nativi. Per ogni area, i documenti `API_CONTRACTS_*` e `IMPORT_EXPORT.md` registrano API, calcoli e lacune rispetto al web.

| ID | Area e percorsi web | Comportamenti da verificare | Destinazione Android | Stato |
|---|---|---|---|---|
| AUTH | `/auth`, `/auth/callback`, `/reset-password` | Login, registrazione, registrazioni disabilitate, conferma, reset, rinnovo e logout | Account | PKCE/callback/recovery con fixture; password nativa, TOTP e revoca con refresh reale nello scope di QA_QUALIFICATION.md. Il campione [release firmato](SIGNED_RELEASE_QUALIFICATION.md) verifica anche submit login QA, aggiornamento con sessione attiva e logout su API 31/35. Email/PKCE e gli altri rami di A01/A02 restano aperti. |
| DOCS | `/privacy`, `/cookies`, `/terms`, wildcard | Contenuti informativi applicabili e gestione schermate non disponibili | Documenti/navigazione | Testi IT/EN estratti dalle pagine web, collegamenti e addendum storage Android presenti; fixture Android comprese nelle suite finali 0.1.9. Confronto editoriale completo delle due lingue aperto. |
| DASH | `/` | Dati riassuntivi, intervalli e collegamenti alle operazioni | Dashboard | Periodi, confronto, andamento, Budget, donut categorie e collegamenti presenti; fixture JVM/UI superate e lettura backend reale; confronto visivo completo aperto |
| TX | `/transactions` | CRUD, filtri, categorie, importi, date e risultati | Transazioni | UUID nativi, CRUD isolato, condivisione, quote, righe ricevute, filtro condivise e cambio automatico/manuale presenti. Fixture finali 0.1.9 superate; quote/isolamento con due QA verificati nello scope QA_QUALIFICATION.md. Confronto completo dei form web/Android aperto. |
| REC | `/recurring` | CRUD, periodicità, suggerimenti, ignore e comportamento delle ricorrenze | Ricorrenze | CRUD, suggerimenti, ignore per account, pausa/ripresa ed elaborazione personale presenti; calendario mensile di sola lettura aggiunto, con clamp ripetuto e proiezioni dalla prossima scadenza; fixture locali 0.1.7 superate |
| BUD | `/budget` | Periodi, limiti e spesa raggiunta | Budget | Mediana mensile e arrotondamenti con fixture; lettura, inserimento/modifica QA isolati e cleanup reali verificati nella 0.1.8. |
| CAT | `/categories` | Categorie e compatibilità con transazioni/importazioni | Categorie | CRUD, detach, selezione icona/colore e palette del riferimento presenti; lettura, inserimento QA isolato e cleanup reali verificati; detach con dati condivisi non provato dal run isolato. |
| CHART | `/charts`, `/charts/income-expense`, `/charts/expense`, `/charts/income` | Aggregazioni, filtri e rappresentazione dei valori | Grafici | Periodi, filtri tipo/categoria/valuta, granularità, barre/torta/linee e confronti cumulativi presenti, con righe numeriche e valute separate; fixture locali 0.1.7 superate; confronto completo web aperto |
| PORT | `/portfolio` | Posizioni, chiusure e operazioni disponibili, prezzi e cambi | Portafoglio | Allocazioni, rendimento, prezzi e storico presenti; chiusure singole/collettive CAS e blocco fino a refresh. Letture e due chiusure QA reali superate; confronto completo con lo stesso dataset web aperto. |
| NW | `/net-worth` | Componenti del patrimonio, storico e totali | Patrimonio | Formula corrente per valuta e storico server con periodi, dettagli, metriche e confronti presenti; snapshot senza valuta mantenute grezze, TCG escluso come sul web; fixture locali 0.1.7 superate |
| INS | `/insights` | Metriche e calcoli sullo stesso dataset | Insights | Otto rilevatori nel sorgente, severità/badge/filtri e template IT/EN presenti; soglie decimali e valute separate dichiarate, milestone sulla serie server senza valuta; fixture locali 0.1.7 superate; parità globale aperta |
| POK | `/poker`, `/poker/next-cut`, `/poker/hourly-earnings`, `/poker/rakeback` | Contabilità e calcoli esistenti; permesso `poker` | Poker | Guadagno orario, submit e arrotondamento con fixture; dettagli orari/rakeback per anno corrente e mese non modificabile, come nel web. Lettura e CRUD QA storici e fixture finali 0.1.9 superati. Gara fra due client sullo stesso mese aperta; nuove correzioni nel rapporto statico restano da verificare. |
| FIRE | `/fire`, `/fire/standard`, `/fire/barista` | Parametri e risultati Standard/Barista; permesso `fire` | FIRE | Formule, disco, isolamento e condivisione; grafico nominale/reale, progresso/obiettivo, selezione dei punti, input mensili/annuali, import a 24 mesi e reset selettivo presenti. Fixture comprese nelle suite finali 0.1.9; process death reale, errore del disco e isolamento dopo login reale restano aperti. |
| FUM | `/fumo`, `/fumo/liquido-sigaretta`, `/fumo/cbd`, `/fumo/thc` | Gestione dei dati e calcoli esistenti; permesso `fumo` | Fumo | CRUD e formule con fixture; durata/derivati salvati, alias e fallback legacy, riepiloghi e derivati indipendenti dalla durata presenti. CRUD QA storico e fixture finali 0.1.9 superati; confronto con righe legacy reali aperto. |
| TCG | `/tcg`, `/tcg/magic`, `/tcg/pokemon`, `/tcg/yugioh` | Collezioni, ricerche e prezzi; permesso `tcg` | TCG | CRUD, immagini e panoramica per valuta presenti; fixture e contratti QA storici superati. CardTrader automatico limitato a EUR; regressioni locali del vincolo e della panoramica superate. Prezzi non-EUR precedenti di origine ignota da verificare manualmente. |
| LIB | `/libreria`, `/libreria/libri`, `/libreria/fumetti`, `/libreria/manga` | Collezioni, ricerca e servizi esterni; permesso `libreria` | Libreria | CRUD, copertine, prezzi null, panoramica, Manga e filtri con fixture finali. Serie e panoramica vincolate al login iniziale; regressioni locali superate. CRUD QA storico superato; Google Books HTTP 429 e ricerca reale non superata. Nota metadati/prezzi corretta. |
| CPL | `/couple-budget` | Inviti, accettazione/rifiuto/cancellazione, revoca, budget comuni, split, condivisione e storico; permesso `couple_expenses` | Coppia | Campioni precedenti in QA_QUALIFICATION.md. [C01](COUPLE_INVITATION_QUALIFICATION.md) qualifica localmente rifiuto/cancellazione dai form e pending/cambio account con fixture. [C02](COUPLE_FORM_QUALIFICATION.md) aggiunge quote EUR/USD, modifica budget dall'altro partner, rimozioni creatore/partner nelle due schermate e revoca/archivio sul release; originali e accessi dei ruoli verificati. Confronto UI completo aperto; eliminazione budget solo fixture, nessun PASS globale. |
| ADMIN | `/admin` | Utenti, permessi, registrazioni abilitate/disabilitate, reset ed eliminazione utente; permesso `admin` | Admin | [D01 PASS LOCALE](ADMIN_FORM_QUALIFICATION.md): undici checkpoint dai form release, annulla/salva/ripristina su QA B, perdita ruolo/cache e ricaricamento; 12/12 fixture IT/EN per API 31/35. Client QA B host; email, eliminazione utente e toggle globale dalla UI restano aperti. |
| SET | `/settings` | Tema, IT/EN, valuta, profilo, email/password ed eliminazione account | Impostazioni | IT/EN, persistenza, branding web e navigazione a cinque voci presenti; cambio lingua/tema senza reset con fixture. Flussi reali di modifica account aperti. |
| MFA | Componenti security e `src/hooks/useMfa.ts` | Enrollment TOTP, verifica/challenge, rimozione e stato MFA | Sicurezza | Contratti/UI con fixture e QR TOTP locale. Enrollment, challenge e verifica aal2 reali, rimozione del solo fattore QA e revoca delle altre sessioni passano su API 31; scope repository e limiti in QA_QUALIFICATION.md. |
| SESS | Componenti security e hook sessioni | Attività, sessioni attive e revoca secondo comportamento backend | Sicurezza | Audit login, auth session ID, chiusura delle altre sessioni e conferme con fixture nelle suite finali. Ripristino in nuovo processo e revoca reale con refresh respinto verificati nello scope QA_QUALIFICATION.md; ogni percorso di revoca individuale e dispositivo fisico restano aperti. |
| IMP | Dialog import in `src/components/settings/` | Revolut, CSV BBVA/Trade Republic, anteprima, duplicati, mapping ISIN | Importazioni | CSV, preview, deduplica, ISIN→posizioni, mapping per account, nuove categorie, keyword/note e source presenti; UUID stabili, CAS e riconciliazione esplicita dopo errori. Fixture JVM/Android comprese nei run finali 0.1.9; import reale e picker/provider cloud aperti. XLS/XLSX e restore globale non sono requisiti del web. |
| EXP | `ImportExportSection.tsx`, `src/lib/constants.ts` | Export completo delle tabelle previste dal web e portabilità | Esportazioni | 25 tabelle e serializzazione, letture backend storiche e fixture MediaStore. Pagine e salvataggio legati al login iniziale con regressioni locali superate. Picker di sistema e round-trip backend completo aperti. |
| BANK | Link `https://bank.savely.cc` in impostazioni | Convertitore PDF bancario nel browser e importazione dei CSV risultanti | Impostazioni → Dati | Audit concluso in BANK_INVENTORY.md: nessuna API nel sorgente; link Bank2CSV nel browser esterno presente nella candidata e CSV interoperabile con il mapping nativo. Conversione esterna ed estratto reale→import restano prove aperte; PDF nativo è un’estensione separata |

L'esistenza di un hook o di una tabella non implica una schermata attiva. Ad esempio, i dati `savings_goals` rientrano nell'export del web; non va inventata una nuova pagina Obiettivi per dichiarare parità.

## Audit di implementazione della candidata 0.1.9

Le funzioni seguenti sono presenti nel sorgente e confrontate con il riferimento indicato. Questa tabella aggiunge uno scope di implementazione alla storia delle prove; non estende i PASS backend della 0.1.8 ai nuovi flussi.

| Blocco | Fonte web | Sorgente Android | Verifica ancora richiesta |
|---|---|---|---|
| FIRE | `useFireCalculatorParams.ts`, `useFireDefaultsFromDB.ts`, pagine Standard/Barista e `components/fire/charts/ProjectionChart.tsx` | `ModuleScreen.kt`, `FireDatabaseDefaults.kt`, `FireProjectionChart.kt` | Process death reale, errore del disco e login reale; fixture finali 0.1.9 superate |
| Poker/Fumo/Manga | `PokerHourlyEarnings.tsx`, `PokerRakeback.tsx`, `FumoCrudPage.tsx`, `fumoCrud.ts`, `Manga.tsx` | `ModuleScreen.kt`, `ModuleRecordValues.kt`, `MangaVolumes.kt` | Due client sul mese Poker, righe legacy reali e ripresa Manga dopo cambio login; fixture finali 0.1.9 superate |
| Import/Bank2CSV | Dialog Revolut/Bank/ISIN, `importFileSecurity.ts`, `csv.ts`, `ImportExportSection.tsx` | `transfer/`, `TransferScreen.kt` | Provider cloud, picker di sistema e import QA con riconciliazione completa; fixture finali 0.1.9 superate |
| Finanza | `Transactions.tsx`, `useSharedExpenses.ts`, form valuta e categorie | `FinanceSharedTransactions.kt`, `FinanceExchangeRates.kt`, `FinanceScreen.kt` | Confronto completo sullo stesso dataset e servizio FX nel flusso reale; quote Coppia nello scope QA e fixture finali superate |
| Sicurezza/documenti | `SecuritySection.tsx`, `useActiveSessions.ts`, Privacy/Cookies/Terms e catalogo EN | `SettingsScreen.kt`, `SupabaseApi.kt`, `LegalScreen.kt`, asset `legal/` e `legal/en/` | Confronto editoriale e percorsi account esclusi; fixture finali e TOTP/revoca nel solo scope QA superati |

Il validatore web accetta CSV: la precedente lacuna XLS/XLSX era un errore d'inventario. Non è presente un ripristino globale dell'account nel riferimento; l'export delle tabelle resta distinto da tale funzione. Il registro import è conservato dal ViewModel durante la ricreazione, senza checkpoint su disco: una terminazione del processo può interrompere il lavoro e richiede una nuova lettura dei dati già salvati. La suite JVM non verifica picker, backend distribuito o UI sul telefono. `parity/web-baseline.json` mantiene `globalParityVerified=false` e `verifiedApkVersion=null`.

Icona, palette, font e navigazione mobile sono allineati al riferimento web, come registrato in [UI_ALIGNMENT.md](UI_ALIGNMENT.md). Tre screenshot di fixture della navigazione sono stati ispezionati; confronto visivo di ogni pagina e launcher del telefono restano verifiche distinte.

I contratti dei blocchi finanziari recenti sono in [API_CONTRACTS_FINANCE.md](API_CONTRACTS_FINANCE.md); le formule e le differenze Insights sono in [INSIGHTS_GAPS.md](INSIGHTS_GAPS.md). Lo storico ricostruito interpola prezzi salvati e considera acquisti ancora aperti, senza feed di quotazioni storiche. Lo storico registrato può avere copertura diversa fra date. I valori server del patrimonio non hanno valuta e non sono confrontabili con i totali nativi per valuta. Il calendario proietta scadenze, senza creare movimenti. Le chiusure collettive sono scritture separate, con esiti parziali possibili; il refresh obbligatorio riconcilia la lista prima di altre scritture. Questi limiti restano visibili anche quando una fixture passa.

I moduli Poker di questo inventario sono contabilità manuale e rendicontazione. Un solver GTO, un HUD o l'integrazione con un client poker non appartengono al progetto Android.

## Dati da aggiungere per ogni caso

Espandere le righe in casi con ID stabile, operazione, input, risultato, ruolo e test. Mantenere la versione web implementata e l'evidenza del test anche quando altri moduli sono ancora indietro.

| Campo | Contenuto richiesto |
|---|---|
| Identificatore | ID funzione e caso, ad esempio `IMP-REV-DUPLICATE` |
| Fonte | File web, dipendenze e SHA di riferimento |
| Backend | Tabelle, RPC, endpoint e autorizzazione |
| Android | Modulo/schermata e commit implementato |
| Verifica | Fixture, test o prova manuale con esito |
| Parità | Da implementare / in sviluppo / da verificare / verificata / differenza intenzionale |
| Revisione | SHA web implementata, SHA web verificata e APK associato |

## Mapping sorgenti da monitorare

Il mapping eseguibile è in `parity/source-map.json`. Il workflow manuale `.github/workflows/web-parity-audit.yml` è presente ma non è stato eseguito su GitHub. Il test locale del classificatore e il report senza cambiamenti sono passati; non provano equivalenza semantica.

| Ambito | Percorsi web |
|---|---|
| Navigazione/permessi | `src/App.tsx`, `src/lib/permissions.ts`, `src/hooks/usePermissions.ts`, tipi condivisi |
| Auth/account | `src/hooks/useAuth.tsx`, `src/hooks/useMfa.ts`, hook sessioni, `src/components/settings/` |
| Finanza | Pagine Dashboard/Transactions/RecurringExpenses/Budget/Categories/Charts/Portfolio/NetWorth/Insights e relativi hook |
| Calcoli | `src/lib/statistics/`, utility patrimonio, insights, ricorrenze e FIRE |
| Import/export | Dialog in `src/components/settings/`, `src/lib/csv.ts`, `importFileSecurity.ts`, costanti e tipi import |
| Moduli | Pagine/componenti/hook Poker, FIRE, Fumo, TCG e Libreria |
| Coppia | `src/pages/CoupleBudget.tsx`, hook couple/shared expenses e utility split |
| Backend | `api/`, `supabase/functions/`, `supabase/migrations/`, `src/integrations/supabase/types.ts` |
| Traduzioni e componenti comuni | Cataloghi lingua, utility comuni e componenti usati dalle funzioni mappate |

## Procedura quando cambia il sito

1. Leggere il nuovo commit web dalla repo Android e confrontarlo con la baseline verificata. Registrare anche la revisione distribuita se si sta confrontando il sito in produzione.
2. Generare un report di file e funzioni coinvolte. Una rotta nuova, un file senza mapping o una modifica di auth/permessi richiedono analisi esplicita.
3. Classificare l'impatto: presentazione, dati, comportamento compatibile, contratto incompatibile o indeterminato. Anche un componente visivo può contenere una nuova validazione.
4. Affidare il porting al subagent del modulo e la verifica al subagent parità. Confrontare fixture numeriche e flussi, oltre alla compilazione.
5. Aggiornare baseline per funzione e versione APK dopo verifica; aggiornare la baseline globale solo quando tutte le funzioni richieste sono allineate.

Il report indica revisioni, percorsi, moduli e test da rieseguire. La severità e il lavoro Android richiesto restano da valutare. Per una modifica solo al colore di un pulsante può bastare registrare "nessun impatto funzionale"; per una formula FIRE cambiata serve verificare e aggiornare la formula Kotlin.

Il controllo è eseguibile manualmente dal workflow esclusivamente Android. Non è stata attivata una schedulazione periodica; prima di una release resta necessario un confronto esplicito.

Il diff Git non rileva tutte le modifiche al servizio attivo: callback Supabase, configurazioni, policy o funzioni distribuite possono essere cambiati fuori dai commit osservati. Affiancare al report verifiche periodiche e prima della release dei contratti realmente esposti, tramite letture e account di test. Una SHA invariata non certifica che il backend sia invariato.

## Fixture e test di parità previsti

| Area | Casi minimi |
|---|---|
| Importi | Zero, valori negativi, decimali, arrotondamenti e grandi importi |
| Date/ricorrenze | Fine mese, bisestili, timezone, periodi e duplicazioni |
| Importazioni | CSV validi/incompleti, BOM, separatori, righe errate e duplicati |
| Calcoli | Statistiche, patrimonio, cambi, FIRE, Poker e split di coppia |
| Contratti | Null, campi opzionali/ignoti, errori e risposta senza dati |
| Auth/permessi | Sessione scaduta/revocata, MFA, ruolo non autorizzato e cambio permessi |
| Eliminazione account | Conferma, autorizzazione, invocazione `delete-account`, rimozione dati prevista e chiusura sessione; soltanto account sintetici |
| Coppia/admin | Due utenti, revoche, storico e rifiuto di operazioni privilegiate |

I risultati attesi devono derivare dal riferimento web verificato, usando dati sintetici. Un errore individuato nel web va registrato e discusso: non correggerlo silenziosamente solo in Android e chiamarlo parità.
