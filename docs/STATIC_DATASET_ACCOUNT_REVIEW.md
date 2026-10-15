# Dataset comune — account, sicurezza, Admin e Coppia

## Riferimenti e stato

Confronto per lettura del codice, 12 ottobre 2026. Web: `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. Android: checkpoint documentale `614bdbf`; produzione e test JVM `935c7ba`. Il successivo `4d6a376` modifica soltanto lo scroll di una fixture Android FIRE. Il documento non registra nuove osservazioni delle schermate o del backend.

I tre profili A, B e Admin qui proposti sono sintetici e non corrispondono agli account QA esistenti. Gli UUID servono a rendere distinguibili i proprietari; non autorizzano operazioni reali. Un oggetto `permissions.admin=true` in un dataset non crea un amministratore sul server. Le verifiche RLS, le risposte degli endpoint e la consegna email richiedono prove separate con identità effettivamente autorizzate.

Le fonti web restano in sola lettura. Non vengono creati account, inviate email, generate credenziali o eseguite scritture API per questo confronto. Il JSON seguente è una proposta per il dataset master del root: riferimenti simbolici, nessun token, password o payload pronto per un import remoto.

## Proposta JSON

Gli importi sono stringhe decimali; valuta e proprietario sono espliciti. La data fissa evita che «questo mese» cambi tra due acquisizioni. `observed: null` significa che il risultato è ricavato dal sorgente, non osservato in un nuovo run.

```json
{
  "schemaVersion": 1,
  "scope": "account-security-admin-couple",
  "references": {
    "web": "1cfcf6297e85d56bfafe3522362ceecef6f6b281",
    "androidProduction": "935c7ba",
    "androidDocumentation": "614bdbf"
  },
  "clock": "2026-06-15T12:00:00+02:00",
  "timezone": "Europe/Rome",
  "languages": ["it", "en"],
  "actors": {
    "A": {
      "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      "name": "Profilo sintetico A",
      "coupleCode": "ABCD2345",
      "permissions": {
        "admin": false, "poker": true, "fumo": false, "fire": true,
        "tcg": true, "libreria": false, "couple_expenses": true
      }
    },
    "B": {
      "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      "name": "Profilo sintetico B",
      "coupleCode": "EFGH6789",
      "permissions": {
        "admin": false, "poker": false, "fumo": true, "fire": false,
        "tcg": true, "libreria": true, "couple_expenses": true
      }
    },
    "Admin": {
      "id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      "name": "Profilo sintetico Admin",
      "permissions": {
        "admin": true, "poker": false, "fumo": false, "fire": false,
        "tcg": false, "libreria": false, "couple_expenses": false
      }
    }
  },
  "sessionMetadata": {
    "A-original": {"actor": "A", "sessionRef": "SID-A-1", "revision": 20, "aal": "aal1"},
    "A-rotation": {"actor": "A", "sessionRef": "SID-A-1", "revision": 20, "aal": "aal1"},
    "A-new-login": {"actor": "A", "sessionRef": "SID-A-2", "revision": 21, "aal": "aal1"},
    "B-login": {"actor": "B", "sessionRef": "SID-B-1", "revision": 22, "aal": "aal1"}
  },
  "resources": {
    "CONNECTION-A-B": {"userA": "A", "userB": "B", "revokedAt": null},
    "TX-A-EUR": {"owner": "A", "type": "expense", "amount": "10.01", "currency": "EUR", "date": "2026-06-15", "category": "Casa"},
    "TX-A-USD": {"owner": "A", "type": "expense", "amount": "7.00", "currency": "USD", "date": "2026-06-15", "category": "Casa"},
    "SHARE-EQUAL": {"transaction": "TX-A-EUR", "connection": "CONNECTION-A-B", "creator": "A", "mode": "equal"},
    "SHARE-CUSTOM": {"transaction": "TX-A-USD", "connection": "CONNECTION-A-B", "creator": "A", "mode": "custom", "partnerAmount": "2.01"},
    "BUDGET-EUR": {"connection": "CONNECTION-A-B", "category": "Casa", "currency": "EUR", "amount": "4.00"}
  },
  "scenarios": [
    {"id": "PERM-BOOL", "variant": "missing-null-string-true", "expectedFromSource": "deny-unless-boolean-true", "observed": null},
    {"id": "AUTH-REGISTRATION", "variant": "disabled-and-consent-missing", "expectedFromSource": "no-native-signup-submit", "observed": null},
    {"id": "AUTH-MFA-GATE", "variant": "aal1-verified-factor", "expectedFromSource": "native-profile-not-requested-before-aal2", "observed": null},
    {"id": "AUTH-MFA-PENDING", "variant": "unverified-factor", "expectedFromSource": "explicit-pending-removal-only", "observed": null},
    {"id": "AUTH-OWNER", "from": "A-original", "to": "A-new-login", "expectedFromSource": "old-native-actions-rejected", "observed": null},
    {"id": "AUTH-LATE-FAILURE", "from": "A-original", "to": "B-login", "expectedFromSource": "old-failure-preserves-new-session", "observed": null},
    {"id": "AUTH-SESSIONS", "variant": "row-id-not-session-id", "expectedFromSource": "revoke-canonical-session-id", "observed": null},
    {"id": "AUTH-ACCOUNT", "variant": "invalid-password-or-delete-error-body", "expectedFromSource": "no-invalid-write-or-false-local-logout", "observed": null},
    {"id": "AUTH-CALLBACK", "variant": "state-mismatch-expired-reused-cold-warm", "expectedFromSource": "reject-invalid-link-preserve-unrelated-owner", "observed": null},
    {"id": "CPL-INVITE", "actor": "A", "receiver": "B", "expectedFromSource": "pending-accept-cancel-reject-owner-filtered", "observed": null},
    {"id": "CPL-ODD-CENT", "transaction": "TX-A-EUR", "expectedFromSource": {"webPureHelper": ["5.01", "5.00"], "webEqualNumericPreview": "not-rendered", "sqlViewAndNativeEqual": ["5.01", "5.01"], "nativeWarningDifference": "0.01", "customPartner5.00": ["5.01", "5.00"]}, "observed": null},
    {"id": "CPL-MULTICURRENCY", "shares": ["SHARE-EQUAL", "SHARE-CUSTOM"], "expectedFromSource": {"nativePartnerMedian": {"EUR": "5.01", "USD": "2.01"}, "webUnfilteredNumericMedian": "7.02"}, "observed": null},
    {"id": "CPL-REVOKED", "connection": "CONNECTION-A-B", "expectedFromSource": "archive-metadata-visible-shares-budgets-audit-denied", "observed": null},
    {"id": "ADM-DENY", "actors": ["A", "B"], "expectedFromSource": "server-403-native-clears-admin-state", "observed": null},
    {"id": "ADM-MUTATE", "actor": "Admin", "target": "B", "variant": "tcg-false-then-restore", "expectedFromSource": "preserve-other-permissions-owner-bound", "observed": null}
  ],
  "policyProof": "not-run-for-this-dataset"
}
```

`SID-*` sono etichette per spiegare la proprietà della sessione, non JWT o UUID da passare a `delete_session`. Un futuro adapter deve risolvere riferimenti e istanti prima della prova, registrare la corrispondenza con le sole identità autorizzate e mantenere separati gli attesi web, SQL e Android. Il dataset non propone di assegnare ruoli o modificare le registrazioni sul backend.

## Attesi e fonti per caso

I percorsi web sono relativi al checkout `../Savely`; quelli Android alla root di questa repository. Le righe si riferiscono ai sorgenti sopra indicati. «Zero richieste» descrive il contratto del client per l'input non valido, non un conteggio misurato in questa revisione.

| Caso | Atteso dalla lettura | Fonte web | Fonte Android |
|---|---|---|---|
| PERM-BOOL | Profilo assente, `null`, stringa `"true"` e chiave mancante non concedono un permesso. Solo booleano `true`. La UI visibile non sostituisce l'autorizzazione server. | `src/lib/permissions.ts:19,71,88`; `api/admin.ts:31,56` | `data/SupabaseApi.kt:344,354,812` |
| AUTH-REGISTRATION | Signup disabilitato e consenso legale mancante bloccano il submit nativo; il login di password già esistenti non applica la policy delle nuove password. Il campo consenso riguarda la registrazione. | `src/hooks/useAuth.tsx:167,171`; `src/pages/Auth.tsx:150,444,465` | `ui/AuthScreen.kt:56,94,104`; `data/PasswordPolicy.kt:8`; `data/SupabaseApi.kt:120` |
| AUTH-MFA-GATE | Sessione `aal1` con fattore TOTP verified mantiene il gate nativo e permessi vuoti; profilo caricato soltanto dopo elevazione. Un codice errato o 503 non diventa logout globale e non provoca DELETE del fattore. | `src/hooks/useMfa.ts`; `src/components/settings/SecuritySection.tsx:59,87` | `ui/AppViewModel.kt:103–119`; `data/SupabaseApi.kt:466,484`; `ui/SettingsScreen.kt:303–304` |
| AUTH-MFA-PENDING | Un fattore unverified è distinto da un verified: Sicurezza lo mostra e ne chiede la rimozione esplicita. Uscita/rientro non conserva il segreto QR, né cancella automaticamente il fattore. Rimozione verified richiede codice fresco. | `src/hooks/useMfa.ts`; `src/components/settings/SecuritySection.tsx:48,87` | `ui/SettingsScreen.kt:186–190,242,246`; `data/SupabaseApi.kt:505` |
| AUTH-OWNER | Rotazione dei token della stessa identità conserva la revisione. Nuovo login dello stesso UID con nuovo `session_id`, oppure cambio A→B, invalida le conferme e le operazioni legate alla vecchia schermata. | Sessione gestita dall'SDK in `src/hooks/useAuth.tsx`; non equiparare cache dei permessi e revisione Android | `ui/SettingsScreen.kt:42,49,81`; `ui/CoupleScreen.kt:68`; `ui/AdminScreen.kt:55`; `data/SupabaseApi.kt:571` |
| AUTH-LATE-FAILURE | Revoca definitiva dell'owner corrente svuota store e stato in primo piano; rete/5xx conservano la sessione. AUTH/STORAGE tardivo dell'owner A non svuota il nuovo owner B. Non serve un nuovo ON_RESUME per l'invalidazione centrale. | `src/hooks/useAuth.tsx` per l'osservazione della sessione; non prova la stessa gestione degli errori | `ui/AppViewModel.kt:38,125,134`; `data/SupabaseApi.kt:615` |
| AUTH-SESSIONS | Revoca individuale usa `session_id`, non l'ID della riga di login. Revoca della corrente affida il reset al segnale centrale senza secondo logout. «Altre sessioni» mantiene la corrente e non è replay automatico. | `src/hooks/useActiveSessions.ts:25`; `src/components/settings/SecuritySection.tsx:108,122` | `data/AuthSessionIds.kt:20`; `data/SupabaseApi.kt:262,386`; `ui/SettingsScreen.kt:252,265,330` |
| AUTH-ACCOUNT | Nuove password: 12 caratteri e composizione, conferma coerente. Risposta di eliminazione con body di errore non equivale a successo e conserva la sessione. Sul web il campo password corrente è validazione del form, senza ri-autenticazione server nel relativo handler. | `src/components/settings/AccountSection.tsx:36,42,62`; `src/hooks/useAuth.tsx:219,224`; `src/lib/passwordValidation.ts:16–20` | `data/PasswordPolicy.kt:8`; `data/SupabaseApi.kt:203,273,368`; `ui/SettingsScreen.kt:129,143,166` |
| AUTH-CALLBACK | URI esatto, stato associato al pending cifrato, parametri ammessi senza duplicati o frammento; link scaduto/riusato o estraneo rifiutato. Activity cold/warm converge sul medesimo handler. Recovery non mostra il pannello ordinario prima della nuova password e dell'eventuale MFA. | `src/hooks/useAuth.tsx:178,201`; il reset web usa il servizio branded | `data/AuthCallbacks.kt:18,23`; `data/SupabaseApi.kt:140,158,203`; `MainActivity.kt:15,22`; `ui/AppViewModel.kt:69,79` |
| CPL-INVITE | Codice A→B, vietato A→A; pending da inviare/accettare/rifiutare/annullare distinto da connessione attiva. Cancel filtra sender e pending; reject receiver e pending; accept RPC vincola il ricevente. Nessuna email di invito in questo percorso. | `src/hooks/useCouplePairStatus.ts:89,114,130,146` | `data/CoupleRepository.kt:162`; `ui/CoupleScreen.kt:108,122,126` |
| CPL-ODD-CENT | Oracoli distinti sotto. Non usare il helper puro web come misura della UI o della view. | `src/lib/coupleExpenses.ts:66,96,117`; `src/pages/Transactions.tsx:377,387,565`; migration `20260703070000_security_fixes_round3.sql:229–248` | `data/CoupleRepository.kt:63`; `ui/CoupleScreen.kt:217–220` |
| CPL-MULTICURRENCY | Android calcola mediane e budget per valuta, esclude originali eliminati e usa i soli mesi con movimenti. Il helper web somma i valori senza filtro valuta: non è una conversione. Il dataset deve conservarne la differenza. | `src/lib/coupleExpenses.ts:156,172`; `src/pages/CoupleBudget.tsx:51,187,212` | `data/CoupleRepository.kt:50,91,144` |
| CPL-REVOKED | Archivio dei metadata di connessione visibile ai partecipanti; quote, budget e audit della connessione revocata non diventano un archivio finanziario leggibile. Timestamp offset preserva istante/microsecondi. | migration `20260701040000_create_couple_connections.sql:46`; `20260702180000_privacy_and_consistency_fixes.sql:12,46,54`; view finale `20260703070000_security_fixes_round3.sql:248` | `data/CoupleRepository.kt:150–158`; `data/BackendInstant.kt` |
| ADM-DENY | Profilo non-admin/403 cancella elenco, registrazioni, ID e conferme Admin; errore rete/5xx non li cancella e non distrugge la sessione. Fake profile Admin non dimostra l'accesso all'endpoint reale. | `api/admin.ts:31,56,143`; `src/pages/Admin.tsx:97` | `ui/AdminScreen.kt:33,41,64` |
| ADM-MUTATE | Modifica di `tcg` conserva gli altri permessi; cambio revisione dopo apertura impedisce una scrittura con il token del nuovo owner. Reset email richiede conferma esplicita; auto-eliminazione Admin impedita anche lato server. Gli attesi non autorizzano l'esecuzione di reset o delete. | `src/lib/permissions.ts:108`; `src/pages/Admin.tsx:149,170,224`; `api/admin.ts:156–164` | `ui/AdminScreen.kt:55,120,136,146`; `data/SupabaseApi.kt:368` |

## Oracoli Coppia da tenere separati

### Centesimo dispari

Per una spesa di **EUR 10,01**:

| Punto di calcolo | Creatore | Partner | Totale quote | Significato |
|---|---:|---:|---:|---|
| Helper puro web `calculateSharedSplit(..., equal)` | 5,01 | 5,00 | 10,01 | Arrotondamento in centesimi con metà partner per difetto e resto al creatore. |
| UI web prima di salvare, modalità equal | — | — | — | `Transactions.tsx` presenta la scelta 50/50, senza preview numerica delle due quote. |
| View SQL finale, modalità equal | 5,01 | 5,01 | 10,02 | `ROUND(total/2, 2)` calcolato separatamente per entrambe le quote. |
| Preview Android, modalità equal | 5,01 | 5,01 | 10,02 | Riproduce la view e mostra l'avviso sul centesimo eccedente. |
| Modalità custom con partner 5,00 | 5,01 | 5,00 | 10,01 | Scelta esplicita che conserva la somma, sia nel calcolo custom web sia in quello nativo. |

La ricerca dei callsite nel riferimento web non trova `calculateSharedAmount` o `calculateSharedSplit` in un file `.tsx`: sono utility e test. Il form realmente usato calcola numericamente solo la modalità custom (`Transactions.tsx:387–405`). Dopo il salvataggio usa `creator_share_amount` della view (`:293,565–568`). Non è dimostrato un bug di preview equal nella UI web, né è corretto dichiarare i quattro oracoli identici. La view finale, non una migration intermedia, è il riferimento del dato restituito.

### Valute e budget

Nel solo giugno sintetico il partner B ha EUR 5,01 dalla quota equal e USD 2,01 dalla quota custom. La mediana nativa è EUR 5,01 e USD 2,01, separatamente. Il helper web chiamato da `CoupleBudget.tsx:51,212` restituisce il numero 7,02 perché somma `my_share_amount` senza filtrare valuta; la pagina presenta il riepilogo con il simbolo EUR. **7,02 non è una conversione valutaria né un totale EUR economicamente valido.**

Con il budget «Casa» EUR 4,00, la differenza nativa è EUR 1,01. Per il medesimo campione il calcolo numerico web non filtrato dà 3,02. Si tratta di una differenza dimostrabile nei sorgenti, da registrare nella matrice come scelta nativa di separazione delle valute. Non viene corretta o nascosta usando dati tutti in EUR. La cancellazione logica della transazione originale esclude quella quota dalla mediana di entrambi i helper.

### Disconnessione

La pagina web può descrivere il collegamento cessato come archivio in sola lettura, ma la visibilità effettiva deriva da RLS e view. Il dataset non deve attendersi spese e budget leggibili dopo la revoca: la migration privacy richiede connessione attiva. Android mostra i soli metadata archiviati consentiti e non tenta di ricostruire le spese private del partner.

## Differenze account da non dichiarare equivalenti

Il pulsante Esci nativo usa `scope=local` (`SupabaseApi.kt:240`); «Chiudi altre sessioni» usa `scope=others` (`:262`). Il web chiama `supabase.auth.signOut()` senza scope (`src/hooks/useAuth.tsx:197–198`); nel sorgente SDK locale consultato il default è `global` (`node_modules/@supabase/auth-js/src/GoTrueClient.ts:2137`). Il dataset deve distinguere uscita dal dispositivo, uscita dagli altri dispositivi e uscita globale. Nessuna nuova prova remota misura questi effetti in questa revisione.

La recovery nativa usa GoTrue e callback PKCE; il reset del sito e quello avviato da Admin conservano `/api/reset-password`, con email branded. Il contenuto e il recapito non diventano equivalenti perché entrambi i client accettano un indirizzo email. Analogamente, il campo password corrente del form web non costituisce da solo una nuova verifica d'identità: `AccountSection.tsx` controlla l'input e poi chiama `updateUser` tramite le funzioni di `useAuth.tsx`, senza passarlo a una nuova autenticazione. Android conserva l'autorizzazione della sessione e non aggiunge una verifica fittizia.

## Prove già disponibili e loro perimetro

Questi risultati sono riportati dai rapporti esistenti; non sono nuove esecuzioni del dataset proposto.

| Sorgente | Evidenza già documentata | Cosa dimostra | Cosa non dimostra |
|---|---|---|---|
| `0714f39` | [QA_QUALIFICATION.md](QA_QUALIFICATION.md), rapporti opt-in Auth/Coppia/Admin | Login password tramite API nativa e Keystore; TOTP fino ad aal2; altre sessioni revocate con refresh corrente conservato; invito/accettazione e quote EUR/USD, budget, isolamento RLS, revoca e archivio; Admin 403 e permesso QA modificato/ripristinato; cleanup dei soli record di prova. | Submit reale di tutti i form, mailbox/PKCE positivo, cancel/reject/rimozioni quote complete, account update/delete, tutti i percorsi Admin, nuova produzione successiva. |
| `935c7ba`, harness `4d6a376`, rapporto `614bdbf` | [STATIC_AUDIT_VERIFICATION.md](STATIC_AUDIT_VERIFICATION.md): 528/528 JVM; 132/132 Android API 31 e 132/132 API 35; sorgenti/hash verificati | Fixture locali del client, compresi owner/revisioni e dinieghi; run finali completi e separati. | Non riesegue i contratti remoti sul nuovo sorgente, né confronta questo dataset comune con le schermate web distribuite. |
| Stessa verifica locale | 12/12 documenti legali riprodotti dal riferimento web dopo normalizzazione CRLF/LF | Corrispondenza editoriale degli asset IT/EN nel checkout di riferimento. | Validità giuridica, nuova infrastruttura o revisione effettivamente distribuita del sito. |

I PASS remoti del primo rigo non si trasferiscono automaticamente a `935c7ba`. I PASS delle fixture locali non attribuiscono un PASS al campo `observed` dei quindici scenari. La proposta non contiene un `deploySHA`: checkout web e sito effettivamente distribuito sono evidenze diverse.

### Nuovo controllo host offline autorizzato

Dopo la preparazione del confronto, è stato autorizzato ed eseguito `python -m unittest discover -s tools -p 'test_*.py' -v`: **36/36 PASS**, 0,088 s, exit code 0. Log: `artifacts/common-dataset-verification/host-python.log`. Le fixture coprono guardie dei runner, identità sintetiche, riserve/CAS, mancato replay, rifiuto di segreti nei report, configurazione callback e classificazione dei percorsi web. Non invocano i launcher principali, il vault delle credenziali, HTTP o ADB. Questo esito non valida il JSON proposto, le schermate né le policy reali; i campi `observed` restano `null`.

## Scope coperto staticamente e residui

- [x] Tre profili distinti; sette permessi con booleani stretti; dati utente separati dai ruoli effettivi.
- [x] Sessioni con token ruotato, nuovo login dello stesso UID, cambio owner, errore definitivo e temporaneo; callback cold/warm, pending e recovery; consenso e policy delle nuove password.
- [x] Inviti, accettazione/rifiuto/cancellazione, split equal/custom, centesimo dispari, mediane per valuta, budget, revoca e limiti dell'archivio.
- [x] Diniego Admin, conferme e revisioni, modifica/ripristino dei permessi, distinzione reset branded e recovery GoTrue nativo, auto-eliminazione impedita.
- [ ] Osservazioni del dataset comune su web e Android, con identità e record dedicati, periodo/lingua/valuta fissi ed evidenza per ogni oracolo.

Restano i gate non-code di [STABLE_NON_CODE_GATES.md](STABLE_NON_CODE_GATES.md). Per questo ambito: mailbox controllata per consegna/conferma/recovery PKCE; submit dei form reali e percorsi Coppia/Admin esclusi dalle prove QA; revoca individuale e modifiche account; eliminazione soltanto di un'identità sacrificabile. Servono inoltre il controllo della candidata firmata con sessione autenticata e i nuovi flussi sul telefono fisico. Nessuno di questi gate è chiuso dalla lettura del codice o dai profili fittizi.

Il confronto statico non aggiunge un nuovo difetto di produzione da correggere in questo blocco. Espone differenze verificabili di calcolo e presentazione, soprattutto gli oracoli del centesimo dispari e l'aggregazione multivaluta, affinché il dataset master non trasformi una differenza nota in un falso PASS di parità.
