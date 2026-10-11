# Parità fra Savely web e Android

Stato al 12 ottobre 2026: app nativa 0.1.8-alpha implementata e testata per i casi registrati in [TESTING.md](TESTING.md). La verifica dei [cinque blocchi](ROADMAP_CLOSURE.md) distingue il codice presente dalle prove mancanti; la parità globale resta aperta.

Baseline web: [commit 1cfcf6297e85d56bfafe3522362ceecef6f6b281](https://github.com/GoryNickel9/Savely/commit/1cfcf6297e85d56bfafe3522362ceecef6f6b281), verificato sul checkout locale e sul ramo remoto `main`. L'inventario in `src/App.tsx` comprende 41 rotte: 6 pubbliche, 13 finanza/account, 21 con permesso specifico e una wildcard. Una rotta non corrisponde necessariamente a una singola funzione.

## Inventario iniziale

I package `ui`, `data`, `finance`, `transfer` e `modules` contengono le schermate e i contratti nativi. Per ogni area, i documenti `API_CONTRACTS_*` e `IMPORT_EXPORT.md` registrano API, calcoli e lacune rispetto al web.

| ID | Area e percorsi web | Comportamenti da verificare | Destinazione Android | Stato |
|---|---|---|---|---|
| AUTH | `/auth`, `/auth/callback`, `/reset-password` | Login, registrazione, registrazioni disabilitate, conferma, reset, rinnovo e logout | Account | PKCE, callback e recupero nativi implementati e verificati con fixture; sessione reale QA verificata; password ed email/PKCE reali aperti |
| DOCS | `/privacy`, `/cookies`, `/terms`, wildcard | Contenuti informativi applicabili e gestione schermate non disponibili | Documenti/navigazione | Testi nativi estratti dal web e addendum storage Android; confronto editoriale completo aperto |
| DASH | `/` | Dati riassuntivi, intervalli e collegamenti alle operazioni | Dashboard | Periodi, confronto, andamento, Budget, donut categorie e collegamenti presenti; fixture JVM/UI superate e lettura backend reale; confronto visivo completo aperto |
| TX | `/transactions` | CRUD, filtri, categorie, importi, date e risultati | Transazioni | Fixture e form QA storico verificati; 0.1.8 genera UUID nel client nativo (senza injector) e passa CREATE/UPDATE/DELETE isolati. Lettura MainActivity/sito storica; nuovo percorso completo del form sul backend ancora distinto. |
| REC | `/recurring` | CRUD, periodicità, suggerimenti, ignore e comportamento delle ricorrenze | Ricorrenze | CRUD, suggerimenti, ignore per account, pausa/ripresa ed elaborazione personale presenti; calendario mensile di sola lettura aggiunto, con clamp ripetuto e proiezioni dalla prossima scadenza; fixture locali 0.1.7 superate |
| BUD | `/budget` | Periodi, limiti e spesa raggiunta | Budget | Mediana mensile e arrotondamenti con fixture; lettura, inserimento/modifica QA isolati e cleanup reali verificati nella 0.1.8. |
| CAT | `/categories` | Categorie e compatibilità con transazioni/importazioni | Categorie | CRUD e detach presenti; lettura, inserimento QA isolato e cleanup reali verificati; detach con dati condivisi non provato dal run isolato. |
| CHART | `/charts`, `/charts/income-expense`, `/charts/expense`, `/charts/income` | Aggregazioni, filtri e rappresentazione dei valori | Grafici | Periodi, filtri tipo/categoria/valuta, granularità, barre/torta/linee e confronti cumulativi presenti, con righe numeriche e valute separate; fixture locali 0.1.7 superate; confronto completo web aperto |
| PORT | `/portfolio` | Posizioni, chiusure e operazioni disponibili, prezzi e cambi | Portafoglio | Allocazioni, rendimento, prezzi e storico presenti; chiusure singole/collettive CAS e blocco fino a refresh. Letture e due chiusure QA reali superate; confronto completo con lo stesso dataset web aperto. |
| NW | `/net-worth` | Componenti del patrimonio, storico e totali | Patrimonio | Formula corrente per valuta e storico server con periodi, dettagli, metriche e confronti presenti; snapshot senza valuta mantenute grezze, TCG escluso come sul web; fixture locali 0.1.7 superate |
| INS | `/insights` | Metriche e calcoli sullo stesso dataset | Insights | Otto rilevatori nel sorgente, severità/badge/filtri e template IT/EN presenti; soglie decimali e valute separate dichiarate, milestone sulla serie server senza valuta; fixture locali 0.1.7 superate; parità globale aperta |
| POK | `/poker`, `/poker/next-cut`, `/poker/hourly-earnings`, `/poker/rakeback` | Contabilità e calcoli esistenti; permesso `poker` | Poker | Guadagno orario, submit e arrotondamento con fixture; dettagli orari/rakeback filtrati per anno corrente. Lettura e CRUD QA isolato reali verificati; matrice temporale completa resta distinta. |
| FIRE | `/fire`, `/fire/standard`, `/fire/barista` | Parametri e risultati Standard/Barista; permesso `fire` | FIRE | Formule, commit su disco, ricreazione, isolamento e reset testati; URL Standard/Barista, chooser e importazione con anteprima verificati con fixture locali; process death FIRE e backend aperti |
| FUM | `/fumo`, `/fumo/liquido-sigaretta`, `/fumo/cbd`, `/fumo/thc` | Gestione dei dati e calcoli esistenti; permesso `fumo` | Fumo | CRUD e formule JVM/UI verificati; lettura e inserimento/modifica/cleanup QA reali nella 0.1.8. |
| TCG | `/tcg`, `/tcg/magic`, `/tcg/pokemon`, `/tcg/yugioh` | Collezioni, ricerche e prezzi; permesso `tcg` | TCG | CRUD, immagini e panoramica per valuta presenti; fixture set/ricerca; CRUD QA, catalogo CardTrader e contratto prezzo reali superati nella 0.1.8. |
| LIB | `/libreria`, `/libreria/libri`, `/libreria/fumetti`, `/libreria/manga` | Collezioni, ricerca e servizi esterni; permesso `libreria` | Libreria | CRUD, copertine e panoramica presenti; Manga, ripresa e filtri con fixture; CRUD QA reale superato. Google Books HTTP 429 per quota esaurita, ricerca reale non superata. |
| CPL | `/couple-budget` | Inviti, accettazione/rifiuto/cancellazione, revoca, budget comuni, split, condivisione e storico; permesso `couple_expenses` | Coppia | Split, quote, mediane, budget e storico con fixture; archivio revocati di sola lettura e contratto reale verificati, senza aggirare RLS su spese/budget; prova con due account aperta |
| ADMIN | `/admin` | Utenti, permessi, registrazioni abilitate/disabilitate, reset ed eliminazione utente; permesso `admin` | Admin | Diniego e cache con fixture; diniego reale dell’account QA senza ruolo Admin verificato. Operazioni autorizzate del ruolo Admin ancora non provate sul servizio reale. |
| SET | `/settings` | Tema, IT/EN, valuta, profilo, email/password ed eliminazione account | Impostazioni | IT/EN, persistenza, branding web e navigazione a cinque voci presenti; cambio lingua/tema senza reset con fixture. Flussi reali di modifica account aperti. |
| MFA | Componenti security e `src/hooks/useMfa.ts` | Enrollment TOTP, verifica/challenge, rimozione e stato MFA | Sicurezza | Contratti e UI testati; enrollment reale non eseguito |
| SESS | Componenti security e hook sessioni | Attività, sessioni attive e revoca secondo comportamento backend | Sicurezza | Audit login e revoca tramite auth session ID testati con fixture; ripristino reale in un nuovo processo verificato, revoca RPC reale aperta |
| IMP | Dialog import in `src/components/settings/` | Revolut, CSV BBVA/Trade Republic, anteprima, duplicati, mapping ISIN | Importazioni | Parser, preview e deduplica; sei fixture SAF/lifecycle/TLS passano su API31. Contesto import reale letto; import massivo reale, mapping ISIN/categorie e XLS/XLSX non completati. |
| EXP | `ImportExportSection.tsx`, `src/lib/constants.ts` | Export completo delle tabelle previste dal web e portabilità | Esportazioni | 25 tabelle e serializzazione implementate, letture backend reali e salvataggio/cancel/errori MediaStore con fixture. Picker di sistema e round-trip backend completo aperti. |
| BANK | Link `https://bank.savely.cc` in impostazioni | Convertitore PDF bancario nel browser e importazione dei CSV risultanti | Impostazioni → Dati | Audit concluso in BANK_INVENTORY.md: nessuna API nel sorgente, CSV interoperabile con i limiti di mapping dichiarati; PDF nativo è un’estensione separata |

L'esistenza di un hook o di una tabella non implica una schermata attiva. Ad esempio, i dati `savings_goals` rientrano nell'export del web; non va inventata una nuova pagina Obiettivi per dichiarare parità.

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
