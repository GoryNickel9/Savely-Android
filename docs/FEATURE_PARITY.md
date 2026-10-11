# Parità fra Savely web e Android

Stato al 11 ottobre 2026: inventario iniziale. Tutte le funzioni Android sono da implementare e verificare. Questo documento registra l'ambito richiesto; non certifica una parità già raggiunta.

Baseline web: [commit 1cfcf6297e85d56bfafe3522362ceecef6f6b281](https://github.com/GoryNickel9/Savely/commit/1cfcf6297e85d56bfafe3522362ceecef6f6b281), verificato sul checkout locale e sul ramo remoto `main`. L'inventario in `src/App.tsx` comprende 41 rotte: 6 pubbliche, 13 finanza/account, 21 con permesso specifico e una wildcard. Una rotta non corrisponde necessariamente a una singola funzione.

## Inventario iniziale

Il modulo Android indicato è una destinazione progettuale, non un file esistente. Il riferimento web va esteso agli hook e alle utility usati da ciascuna pagina.

| ID | Area e percorsi web | Comportamenti da verificare | Destinazione Android | Stato |
|---|---|---|---|---|
| AUTH | `/auth`, `/auth/callback`, `/reset-password` | Login, registrazione, registrazioni disabilitate, conferma, reset, rinnovo e logout | Account | Da implementare |
| DOCS | `/privacy`, `/cookies`, `/terms`, wildcard | Contenuti informativi applicabili e gestione schermate non disponibili | Documenti/navigazione | Da implementare |
| DASH | `/` | Dati riassuntivi, intervalli e collegamenti alle operazioni | Dashboard | Da implementare |
| TX | `/transactions` | CRUD, filtri, categorie, importi, date e risultati | Transazioni | Da implementare |
| REC | `/recurring` | CRUD, periodicità, suggerimenti, ignore e comportamento delle ricorrenze | Ricorrenze | Da implementare |
| BUD | `/budget` | Periodi, limiti e spesa raggiunta | Budget | Da implementare |
| CAT | `/categories` | Categorie e compatibilità con transazioni/importazioni | Categorie | Da implementare |
| CHART | `/charts`, `/charts/income-expense`, `/charts/expense`, `/charts/income` | Aggregazioni, filtri e rappresentazione dei valori | Grafici | Da implementare |
| PORT | `/portfolio` | Posizioni, chiusure e operazioni disponibili, prezzi e cambi | Portafoglio | Da implementare |
| NW | `/net-worth` | Componenti del patrimonio, storico e totali | Patrimonio | Da implementare |
| INS | `/insights` | Metriche e calcoli sullo stesso dataset | Insights | Da implementare |
| POK | `/poker`, `/poker/next-cut`, `/poker/hourly-earnings`, `/poker/rakeback` | Contabilità e calcoli esistenti; permesso `poker` | Poker | Da implementare |
| FIRE | `/fire`, `/fire/standard`, `/fire/barista` | Parametri e risultati Standard/Barista; permesso `fire` | FIRE | Da implementare |
| FUM | `/fumo`, `/fumo/liquido-sigaretta`, `/fumo/cbd`, `/fumo/thc` | Gestione dei dati e calcoli esistenti; permesso `fumo` | Fumo | Da implementare |
| TCG | `/tcg`, `/tcg/magic`, `/tcg/pokemon`, `/tcg/yugioh` | Collezioni, ricerche e prezzi; permesso `tcg` | TCG | Da implementare |
| LIB | `/libreria`, `/libreria/libri`, `/libreria/fumetti`, `/libreria/manga` | Collezioni, ricerca e servizi esterni; permesso `libreria` | Libreria | Da implementare |
| CPL | `/couple-budget` | Inviti, accettazione/rifiuto/cancellazione, revoca, budget comuni, split, condivisione e storico; permesso `couple_expenses` | Coppia | Da implementare |
| ADMIN | `/admin` | Utenti, permessi, registrazioni abilitate/disabilitate, reset ed eliminazione utente; permesso `admin` | Admin | Da implementare |
| SET | `/settings` | Tema, IT/EN, valuta, profilo, email/password ed eliminazione account | Impostazioni | Da implementare |
| MFA | Componenti security e `src/hooks/useMfa.ts` | Enrollment TOTP, verifica/challenge, rimozione e stato MFA | Sicurezza | Da implementare |
| SESS | Componenti security e hook sessioni | Attività, sessioni attive e revoca secondo comportamento backend | Sicurezza | Da implementare |
| IMP | Dialog import in `src/components/settings/` | Revolut, CSV BBVA/Trade Republic, anteprima, duplicati, mapping ISIN | Importazioni | Da implementare |
| EXP | `ImportExportSection.tsx`, `src/lib/constants.ts` | Export completo delle tabelle previste dal web e portabilità | Esportazioni | Da implementare |
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

Questi pattern sono il punto di partenza del futuro mapping; non sono un workflow già attivo. Verificare i nomi e coprire le dipendenze transitive durante la fase 1.

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

Il futuro report deve indicare revisioni, percorsi, moduli, severità proposta, lavoro Android richiesto e test. Per una modifica solo al colore di un pulsante può bastare registrare "nessun impatto funzionale"; per una formula FIRE cambiata serve verificare e aggiornare la formula Kotlin.

Il controllo sarà eseguibile manualmente e periodicamente attraverso un workflow esclusivamente Android. La schedulazione GitHub può subire ritardi: il controllo manuale resta disponibile prima di ogni release. Nessun watcher, script o workflow di confronto viene attivato con questa documentazione.

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
