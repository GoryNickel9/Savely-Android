# Contratti account e accesso dati Android

Audit in sola lettura del repository web `Savely`, commit `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, 11 ottobre 2026. I sorgenti web e lo schema Supabase non sono stati modificati. Questo documento descrive il client implementato; non dimostra che il deploy attivo esponga gli stessi contratti.

## Configurazione e sessione

`BackendConfig(url, anonKey, webUrl = "https://savely.cc")` accetta origini HTTPS; HTTP è ammesso soltanto per localhost nei test. La chiave è pubblicabile/anon. Le chiavi `sb_secret_*` e i JWT con ruolo diverso da `anon` vengono rifiutati. `apikey` va al backend Supabase; `/api/*` riceve soltanto il token dell'utente quando richiesto.

`Session` contiene access token, refresh token, scadenza Unix in secondi, ID utente ed email. La rappresentazione testuale di sessione e configurazione esclude le chiavi e i token. `AndroidSessionStore` cifra il JSON con AES-GCM; la chiave vive in Android Keystore, il ciphertext nelle preferenze private. Il salvataggio usa `commit()` su dispatcher I/O. Il ripristino su un altro dispositivo o una chiave invalidata richiede un nuovo accesso. La configurazione Android deve escludere queste preferenze dal backup.

`session()` rinnova i token 60 secondi prima della scadenza. Un mutex serializza rinnovo, login, signup, aggiornamento credenziali, verifica TOTP e logout. Dopo un 401, le richieste autenticate tentano un solo rinnovo e un solo retry. Non vengono ritentate scritture dopo timeout, disconnessioni o errori 5xx. Un errore di rete, un 5xx o una risposta malformata non elimina la sessione. Il refresh la elimina soltanto per codici espliciti di token/sessione revocati o scaduti, oppure `user_banned`/`user_not_found` con HTTP 400, 401 o 403. Questi casi richiedono una nuova autenticazione; un 403 generico non cancella le credenziali.

## Autenticazione

| Metodo Kotlin | Richiesta | Risposta e comportamento |
|---|---|---|
| `signIn(email, password): Session` | `POST /auth/v1/token?grant_type=password`, email/password | Salva la sessione solo dopo risposta valida; conserva la sessione precedente se il login fallisce |
| `signUp(email, password, fullName = ""): String` | RPC pubblica `get_registrations_enabled`, poi `POST /auth/v1/signup?redirect_to=<web>/auth/callback` | Conserva il toggle di registrazione; salva eventuale sessione oppure invita a confermare via email |
| `requestPasswordReset(email)` | `POST <web>/api/reset-password`, `{email}`, senza token | Usa l'email branded esistente, con `Origin` uguale all'origine web configurata |
| `signOut()` | `POST /auth/v1/logout?scope=local` | Revoca la sessione corrente; cancella le credenziali locali anche se la revoca fallisce; restituisce l'errore di rete/server |
| `updateAccount(email?, password?): JSONObject` | `PUT /auth/v1/user` | Restituisce utente aggiornato; aggiorna l'email locale secondo la risposta server |
| `revokeSession(sessionId): Boolean` | RPC `delete_session`, `{p_session_id: <UUID>}` | Accetta solo un UUID canonico; `true` indica sessione corrente eliminata anche dallo store locale |

Riferimenti: `src/hooks/useAuth.tsx`, `api/reset-password.ts`, `supabase/migrations/20260815100000_registration_toggle.sql` nel repository web.

`PasswordPolicy` applica alle nuove password gli stessi requisiti di `src/lib/passwordValidation.ts`: almeno 12 caratteri, una lettera ASCII maiuscola, una minuscola, un numero ASCII e un simbolo dell'insieme dichiarato nel sorgente web. Signup e cambio password validano prima di qualsiasi richiesta HTTP, inclusi toggle e refresh. L'accesso conserva le password esistenti senza imporre questa policy. Registrazione e impostazioni chiedono una conferma identica; password e conferma usano soltanto stato Compose in memoria e non vengono salvate nel ripristino dell'Activity.

Dopo un login valido, o uno signup che restituisce subito una sessione, il client registra `login_activity` con `event_type=sign_in`, `user_id`, `session_id` ricavato dal claim JWT omonimo e agente `Savely Android · session <UUID>`. La decodifica JWT serve solo a identificare la sessione: non concede permessi e non sostituisce la verifica del backend. Non usa il claim `sid`. L'agente distingue gli accessi Android perché la RPC dell'elenco deduplica per `user_agent`. L'inserimento usa esclusivamente il token utente appena ricevuto; nessun token viene inserito nel payload o nei log.

La registrazione del dispositivo è asincrona e non viene ritentata automaticamente. Un suo errore lascia valido il login e pubblica un messaggio fisso tramite `accessAuditWarning: StateFlow<String?>`; `dismissAccessAuditWarning()` lo chiude. Un claim mancante o malformato produce lo stesso avviso senza inserire un ID inventato. `cancelPendingAudit()` cancella il job e invalida i risultati del precedente account; `close()` cancella anche lo scope posseduto dal client. Epoch e proprietario impediscono a una risposta tardiva di ripubblicare un avviso dopo logout o nuovo login. Il ViewModel chiude il client alla fine del proprio ciclo di vita. Non viene aggiunto un evento di logout.

La route reset genera un link di recovery con la Admin API, invia via Resend e usa un redirect `<origine consentita>/reset-password`. Una risposta `{ok:true}` non conferma che l'account esista o che l'email sia stata inviata: il server restituisce volutamente lo stesso risultato. Limite dichiarato nel sorgente: tre richieste ogni dieci minuti per email e IP, in memoria per istanza.

**Registrazione e reset non hanno ancora callback nativi Android.** L'app può inviare le richieste; conferma e recovery proseguono sul sito esistente. Non è stata aggiunta una callback Supabase né modificato l'endpoint web. La parità nativa completa richiede un flusso Android dedicato e verifica di allow-list, token scaduti, link riutilizzati e recovery.

Il logout Android riguarda la sessione corrente; il default del SDK web revoca globalmente. Le altre sessioni si gestiscono con le RPC sotto. Questa differenza deve restare registrata nella matrice di parità.

## MFA TOTP

| Metodo | Endpoint e risultato |
|---|---|
| `listMfaFactors(): JSONArray` | `GET /auth/v1/user`; estrae `factors` con `factor_type=totp`, `status=verified` |
| `enrollTotp(): JSONObject` | `POST /auth/v1/factors`, `{factor_type:"totp",friendly_name:"Savely Android"}`; risposta `{id,totp:{uri,secret,qr_code}}` |
| `verifyTotp(factorId, code): Session` | Crea `POST /auth/v1/factors/<id>/challenge`, poi `POST .../verify`, `{challenge_id,code}`; salva i token elevati restituiti |
| `unenrollTotp(factorId)` | `DELETE /auth/v1/factors/<id>` con token utente |

La verifica accetta sei cifre, rimuovendo spazi e trattini. Segreti TOTP e URI non vengono registrati nei log. Il gate UI deve impedire la navigazione ai dati durante una verifica richiesta; il backend conserva la responsabilità dell'autorizzazione. I test JVM verificano challenge, corpo della verifica e sostituzione token, senza iscrivere fattori reali.

Riferimenti: `src/hooks/useMfa.ts`, `src/lib/mfa.ts` e implementazione HTTP del SDK `@supabase/auth-js` installato nel repository web.

## Profili, permessi e amministrazione

`profile()` legge `profiles?select=*&user_id=eq.<utente corrente>` e ammette zero o una riga. `permissions()` riconosce soltanto booleani JSON veri per `admin`, `poker`, `fumo`, `fire`, `tcg`, `libreria`, `couple_expenses`. Campo assente, stringa `"true"`, numeri e profilo assente producono false; errori di rete/RLS restano errori e non vengono trasformati in permessi mancanti. Android non mantiene una cache dei permessi tra utenti.

`updateProfile(body)` filtra sul `user_id` corrente. Non concede privilegi lato client. Il database protegge il campo `permissions` con trigger che rifiutano escalation non admin, anche in inserimento. Per modificare i permessi di altri utenti, la UI admin deve leggere il profilo, fondere i booleani esistenti e aggiornare tramite il suo ID con token admin; RLS e trigger decidono se l'operazione è consentita.

| Operazione | Contratto esistente | Autorizzazione |
|---|---|---|
| Elenco account amministrativo | `webPost("/api/admin", {action:"get-users"})` → `{users:[...]}` | Token utente; endpoint verifica `profiles.permissions.admin === true` |
| Elimina altro account | `webPost("/api/admin", {action:"delete-user",userId})` → `{ok:true}` | Admin server; rifiuta autoeliminazione; UUID richiesto |
| Legge toggle signup | RPC `get_registrations_enabled`, body `{}` → boolean | Pubblico; signup usa chiave anon |
| Cambia toggle signup | RPC `set_registrations_enabled`, `{p_enabled:boolean}` → null | Solo admin; enforcement server |
| Elimina proprio account | `invokeFunction("delete-account")` → `{success:true}` | Edge Function verifica token corrente, poi elimina l'utente; cascade secondo schema esistente |
| Elenco sessioni | RPC `get_active_sessions`, `{}` → array `{id,created_at,user_agent,ip,session_id?}` | La migrazione più recente legge gli eventi `login_activity` di accesso negli ultimi 30 giorni e deduplica per agente; `id` identifica l'evento, non la sessione Auth |
| Revoca sessione | `revokeSession(sessionId)` → RPC `delete_session`, `{p_session_id}` → null | Solo proprietario della sessione; la UI usa esclusivamente `session_id` UUID e disabilita righe legacy senza ID valido |
| Storico accessi | `select("login_activity", "select=id,event_type,user_agent,ip,created_at&order=created_at.desc&limit=50")` | RLS dell'utente corrente |

L'elenco Android è etichettato «Accessi registrati»: la RPC basata sullo storico non garantisce che tutte le righe corrispondano ancora a sessioni attive. Dopo una revoca dalla schermata, la riga è nascosta localmente; la revoca del dispositivo corrente cancella subito lo store e riporta all'accesso. Le righe prive di `session_id` restano visibili ma non revocabili. Non viene mai usato `login_activity.id` come ripiego. La revoca elimina la sessione server e impedisce il rinnovo; un access token già emesso segue la propria scadenza. Nessuna operazione amministrativa o cancellazione reale è stata eseguita per questo audit.

Riferimenti: `src/lib/permissions.ts`, `src/hooks/useActiveSessions.ts`, `src/hooks/useLoginActivity.ts`, `api/admin.ts`, `supabase/functions/delete-account/index.ts`, migrazioni `20260702160000_protect_permissions_on_insert.sql` e `20260702190000_raise_on_permission_escalation.sql`.

La forma dell'elenco e il significato dei due ID derivano dalle migrazioni `20260707000000` e `20260709000000` presenti nel repository web. Gli otto test iniziali `AuthSecurityTest` e i tre nuovi flussi Compose `AuthScreenTest`/`SettingsSecurityTest` hanno superato le build integrate dell'11 ottobre. Coprono policy, zero HTTP su input invalido, compatibilità di password precedenti, audit non bloccante/fallito/cancellato, claim corretto, ID evento differente, conferma in memoria e revoca del dispositivo corrente. Passano anche i due ulteriori test JVM per account bloccato/assente e conservazione su errori temporanei.

## Coppia: inviti, quote e confronto budget

`CoupleRepository` riusa `profiles.couple_code`, `couple_connections`, `couple_connection_requests`, `shared_expenses_view`, `shared_expenses` e `couple_budgets`. Richiede `permissions.couple_expenses === true` prima di leggere i dati della coppia. La schermata mantiene l'ultimo caricamento completo in caso di errore temporaneo e cancella i dati mostrati dopo un errore di autenticazione o permesso.

Il codice partner contiene otto caratteri dell'alfabeto `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, normalizzati in maiuscolo con locale indipendente. La RPC `find_user_by_couple_code({p_code})` restituisce soltanto l'UUID del destinatario. Un codice invalido viene rifiutato prima del network; auto-invito e un invito uscente già pendente impediscono l'inserimento. Accettazione, rifiuto, annullamento e revoca riusano i contratti web; nessun elenco globale di profili viene interrogato.

Prima di condividere una spesa, il repository ricontrolla la connessione attiva con filtro sui partecipanti, la transazione personale di tipo `expense` non eliminata e l'assenza di una condivisione per la stessa connessione/transazione. Sono controlli contro dati diventati obsoleti; RLS, trigger e vincoli restano l'autorità finale. Il payload conserva la categoria comune come testo e non espone `category_id` del conto personale. Una quota personalizzata deve essere strettamente maggiore di zero e minore del totale, con al massimo due decimali e entro `DECIMAL(15,2)`. Le scritture non vengono ritentate dopo errori di rete o server.

**Discrepanza rilevata nel riferimento web.** `src/lib/coupleExpenses.ts` assegna il centesimo dispari al creatore, ma la vista SQL della migrazione `20260703070000_security_fixes_round3.sql` applica `ROUND(total_amount / 2, 2)` a entrambe le quote uguali: su EUR 1,01 restituisce EUR 0,51 per ciascuno. L'anteprima Android segue questa vista e mostra l'eccedenza di EUR 0,01, suggerendo una quota personalizzata se si vuole conservare il totale. Nessuna regola del backend è stata modificata. Il deploy effettivo della vista non è stato verificato con una coppia reale.

La stessa vista definisce `my_share_amount` come alias della quota del partner, indipendentemente dall'utente che legge. Android etichetta quindi il campo «Quota partner», riferito a chi ha creato la spesa; non lo presenta come «La tua quota». Il confronto budget riproduce il web usando questo campo: esclude le transazioni originali eliminate, raggruppa per mese e categoria comune, somma le quote e calcola la mediana dei soli mesi con movimenti, arrotondata a due decimali con `HALF_UP`. Non aggiunge mesi a zero.

Android separa le valute, mentre `CoupleBudget.tsx` somma i valori grezzi e li presenta come EUR. Questa differenza impedisce che una quota in USD o GBP venga sommata a una quota in EUR senza conversione. Ogni limite viene confrontato con le spese nella propria valuta; i nuovi budget conservano EUR e il periodo sentinella `month=1/year=2000`. Le categorie selezionabili provengono dalle spese condivise non eliminate e senza budget esistente, come `useCoupleBudgets.ts`.

Inviti pendenti, condivisioni e budget vengono letti con paginazione per UUID crescente: una pagina corta non prova che il dataset sia completo, il caricamento termina soltanto su una pagina vuota. Oltre 10.000 righe per tabella il caricamento fallisce e non produce nuovi totali parziali. La lista visualizza al massimo le 1.000 condivisioni più recenti, con il limite esplicito; le mediane usano tutte le righe caricate. La selezione per nuove condivisioni resta limitata alle 500 uscite personali più recenti, come dichiarato nella schermata. I dati cambiano durante la paginazione senza un isolamento snapshot: il caricamento non promette una fotografia atomica del database.

Test aggiunti: sei casi puri `CoupleCalculationsTest`, sette casi con MockWebServer `CoupleRepositoryTest` e due flussi Compose/TLS locali `CoupleScreenTest`. Coprono alfabeto, precisione, quote limite, arrotondamento SQL, mediane, valute, categorie, permessi, connessione obsoleta, duplicati, pagine corte, inviti e condivisione nativa. Le build integrate dell'11 ottobre hanno superato tutti i 13 nuovi test JVM e i due flussi Compose. Non sono state create connessioni o spese su un backend reale per questo blocco. Restano aperti archivio delle connessioni revocate e validazione RLS con due account distinti.

## PostgREST e trasporto

| API Kotlin | Risultato |
|---|---|
| `select(table, query = "")` | `JSONArray`; query PostgREST già codificata, senza `?` obbligatorio |
| `insert(table, body)` | Una sola riga `JSONObject`, con `Prefer: return=representation` |
| `update(table, id, body, query = "")` | Una sola riga; aggiunge `id=eq.<id>`; query può aggiungere filtro proprietario |
| `updateMinimal(table, id, body, query = "")` | PATCH `return=minimal,count=exact`; verifica `Content-Range` uguale a una riga, senza SELECT per soft delete |
| `delete(table, id, query = "")` | Richiede esattamente una riga eliminata; zero righe diventa errore di record assente/non autorizzato |
| `rpc(name, body = JSONObject())` | `Any`: `JSONObject`, `JSONArray`, stringa, numero, boolean o `JSONObject.NULL`, secondo il contratto RPC |
| `webPost(path, body, authenticated = true)` | `JSONObject`; solo percorso `/api/<nome>` nell'origine configurata |
| `invokeFunction(name, body = JSONObject())` | Valore JSON della Edge Function; solo origine Supabase configurata |
| `getFunction(name, query = "")` | GET autenticato della Edge Function; supporta `cardtrader-proxy` senza mutare il suo contratto |

Nomi di tabella/RPC/funzione e ID vengono validati prima del network. L'ID non può essere sovrascritto da una query opzionale. Il chiamante codifica i valori dei filtri forniti in `query`; la UI non deve interpolare input utente non codificato. I filtri client non sostituiscono RLS. Redirect HTTP sono rifiutati; nessun token è inoltrato a un'altra origine. Risposte superiori a 32 MiB vengono rifiutate: per grandi dataset servono query paginate. Non viene configurata una coda offline di scritture.

`ApiException` distingue configurazione, validazione, rete, autenticazione, permessi, rate limit, server, risposta e storage. Conserva soltanto status HTTP e codice formato identificatore; non espone il corpo server nella UI. Le richieste asincrone rispettano la cancellazione coroutine e chiudono le risposte.

`ExactJson` analizza le risposte senza convertire i numeri in Double: interi rappresentabili usano Long, altri numeri BigDecimal direttamente dal token. Evita la perdita di precisione del JSONTokener Android per quantità DECIMAL(20,8). Sintassi JSON stretta, chiavi duplicate rifiutate, profondità massima 64, precisione numerica massima 128 cifre e scala tra -1000 e 1000. Le stringhe usano il decoder della piattaforma solo dopo validazione delle escape. Risposte invalide producono un errore sicuro e non cancellano le credenziali.

## Verifica

`SupabaseApiTest` usa un server locale MockWebServer e sessioni sintetiche. Copre login, toggle signup, redirect web, reset senza credenziali, refresh concorrente, refresh revocato/errore server, retry 401, filtri delle scritture, injection nei percorsi, parsing dei permessi, risultati RPC, verifica TOTP, logout fallito, redirect HTTP e payload malformato.

La build integrata ha eseguito `:app:testDebugUnitTest` l'11 ottobre 2026: 22 test `SupabaseApiTest` e 4 `ExactJsonTest`, zero errori e zero fallimenti nei report XML. Fixture su dispositivo hanno verificato cifratura Keystore, ripristino e cancellazione. Gli smoke opt-in sul solo account QA esistente hanno verificato identità reale, UI nativa e ripristino dopo arresto completo: le due fasi process death sono passate in 1,723 e 8,702 secondi, con PID distinti e pulizia confermata. Il bootstrap usa una sessione temporanea, quindi non dimostra login con password. Restano aperti MFA reale, RLS con ruoli distinti, callback native, revoca remota e gli altri flussi di eliminazione. I test locali non chiamano Supabase, Vercel o Resend.

`RealProcessDeathSmokeTest.cleanupQaSessionAfterInterruptedProcessDeath` è un metodo opt-in di pulizia dopo un'interruzione: richiede flag, UUID dell'esecuzione, checkpoint privato e corrispondenza esatta di progetto, account QA e `session_id` dello store. Preserva un proprietario o una sessione diversi. L'helper lo invoca in modo sequenziale solo dopo aver tentato lo smoke process death e senza logout già verificato; non elimina preferenze dal terminale. Il flag per rimuovere l'input viene impostato prima del trasferimento stdin, così anche una scrittura parziale fallita riceve un tentativo di pulizia. Se il dispositivo è disconnesso o il checkpoint non coincide, l'helper segnala `cleanup_pending=true`; la revoca host della sola sessione appena generata resta separata dalla cancellazione locale. La prova di interruzione dopo la prima fase passa con pulizia locale e logout confermati; la successiva ripetizione completa passa in 1,317 e 9,225 secondi. Risultati e limiti sono in `TESTING.md`.
