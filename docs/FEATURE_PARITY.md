# Parità fra Savely web e Android

Stato al 11 ottobre 2026: app nativa alpha implementata e testata per i casi registrati in [TESTING.md](TESTING.md). L'inventario seguente distingue il codice presente dalle verifiche mancanti; la parità globale resta aperta.

Baseline web: [commit 1cfcf6297e85d56bfafe3522362ceecef6f6b281](https://github.com/GoryNickel9/Savely/commit/1cfcf6297e85d56bfafe3522362ceecef6f6b281), verificato sul checkout locale e sul ramo remoto `main`. L'inventario in `src/App.tsx` comprende 41 rotte: 6 pubbliche, 13 finanza/account, 21 con permesso specifico e una wildcard. Una rotta non corrisponde necessariamente a una singola funzione.

## Inventario iniziale

I package `ui`, `data`, `finance`, `transfer` e `modules` contengono le schermate e i contratti nativi. Per ogni area, i documenti `API_CONTRACTS_*` e `IMPORT_EXPORT.md` registrano API, calcoli e lacune rispetto al web.

| ID | Area e percorsi web | Comportamenti da verificare | Destinazione Android | Stato |
|---|---|---|---|---|
| AUTH | `/auth`, `/auth/callback`, `/reset-password` | Login, registrazione, registrazioni disabilitate, conferma, reset, rinnovo e logout | Account | Contratti e UI testati; sessione reale E2E verificata; password reale e callback nativo mancanti |
| DOCS | `/privacy`, `/cookies`, `/terms`, wildcard | Contenuti informativi applicabili e gestione schermate non disponibili | Documenti/navigazione | Testi nativi estratti dal web e addendum storage Android; confronto editoriale completo aperto |
| DASH | `/` | Dati riassuntivi, intervalli e collegamenti alle operazioni | Dashboard | Calcoli e schermata presenti; caricamento reale E2E verificato |
| TX | `/transactions` | CRUD, filtri, categorie, importi, date e risultati | Transazioni | Fixture e CRUD UI verificati; CRUD repository reale E2E e lettura UI dopo ricreazione verificati |
| REC | `/recurring` | CRUD, periodicità, suggerimenti, ignore e comportamento delle ricorrenze | Ricorrenze | CRUD, suggerimenti e ignore per account implementati e testati; calendario completo ancora aperto |
| BUD | `/budget` | Periodi, limiti e spesa raggiunta | Budget | Implementato con fixture; flusso reale da verificare |
| CAT | `/categories` | Categorie e compatibilità con transazioni/importazioni | Categorie | CRUD e detach presenti; flusso reale da verificare |
| CHART | `/charts`, `/charts/income-expense`, `/charts/expense`, `/charts/income` | Aggregazioni, filtri e rappresentazione dei valori | Grafici | Aggregazioni e grafici nativi presenti; confronto di tutti i filtri aperto |
| PORT | `/portfolio` | Posizioni, chiusure e operazioni disponibili, prezzi e cambi | Portafoglio | CRUD, chiusura, prezzi e componente TCG presenti; cambio/storico avanzato da completare |
| NW | `/net-worth` | Componenti del patrimonio, storico e totali | Patrimonio | Formula finanziaria presente; storico avanzato da completare. Il web esclude TCG dal net worth |
| INS | `/insights` | Metriche e calcoli sullo stesso dataset | Insights | Due rilevatori implementati; parità con tutti i rilevatori aperta |
| POK | `/poker`, `/poker/next-cut`, `/poker/hourly-earnings`, `/poker/rakeback` | Contabilità e calcoli esistenti; permesso `poker` | Poker | CRUD e formule presenti con test; backend/limiti temporali da verificare |
| FIRE | `/fire`, `/fire/standard`, `/fire/barista` | Parametri e risultati Standard/Barista; permesso `fire` | FIRE | Formule, commit su disco, ricreazione, isolamento e reset testati; share URL e process death FIRE aperti |
| FUM | `/fumo`, `/fumo/liquido-sigaretta`, `/fumo/cbd`, `/fumo/thc` | Gestione dei dati e calcoli esistenti; permesso `fumo` | Fumo | CRUD e formule testate su fixture; backend da verificare |
| TCG | `/tcg`, `/tcg/magic`, `/tcg/pokemon`, `/tcg/yugioh` | Collezioni, ricerche e prezzi; permesso `tcg` | TCG | CRUD, ricerca, immagini, prezzi e filtri per set presenti; backend reale da verificare |
| LIB | `/libreria`, `/libreria/libri`, `/libreria/fumetti`, `/libreria/manga` | Collezioni, ricerca e servizi esterni; permesso `libreria` | Libreria | CRUD, ricerca, copertine e filtri presenti; intervalli manga mancanti |
| CPL | `/couple-budget` | Inviti, accettazione/rifiuto/cancellazione, revoca, budget comuni, split, condivisione e storico; permesso `couple_expenses` | Coppia | Split, quote, mediane, budget e storico implementati con fixture; verifica con due account aperta |
| ADMIN | `/admin` | Utenti, permessi, registrazioni abilitate/disabilitate, reset ed eliminazione utente; permesso `admin` | Admin | Endpoint e autorizzazioni client presenti; ruoli reali da verificare |
| SET | `/settings` | Tema, IT/EN, valuta, profilo, email/password ed eliminazione account | Impostazioni | Implementato in italiano; inglese e flussi reali account da verificare |
| MFA | Componenti security e `src/hooks/useMfa.ts` | Enrollment TOTP, verifica/challenge, rimozione e stato MFA | Sicurezza | Contratti e UI testati; enrollment reale non eseguito |
| SESS | Componenti security e hook sessioni | Attività, sessioni attive e revoca secondo comportamento backend | Sicurezza | Audit login e revoca tramite auth session ID testati con fixture; ripristino reale in un nuovo processo verificato, revoca RPC reale aperta |
| IMP | Dialog import in `src/components/settings/` | Revolut, CSV BBVA/Trade Republic, anteprima, duplicati, mapping ISIN | Importazioni | Parser, preview e deduplica testati; import massivo reale non eseguito |
| EXP | `ImportExportSection.tsx`, `src/lib/constants.ts` | Export completo delle tabelle previste dal web e portabilità | Esportazioni | Tabelle previste e serializzazione implementate; round-trip backend completo aperto |
| BANK | Link `https://bank.savely.cc` in impostazioni | Individuare operazioni esterne e API per uso nativo | Da definire dopo audit | Ambito da chiarire |

L'esistenza di un hook o di una tabella non implica una schermata attiva. Ad esempio, i dati `savings_goals` rientrano nell'export del web; non va inventata una nuova pagina Obiettivi per dichiarare parità.

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
