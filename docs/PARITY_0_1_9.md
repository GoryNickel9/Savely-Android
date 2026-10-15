# Candidata 0.1.9 e qualifica della 1.0

Riferimento: Savely web `1cfcf6297e85d56bfafe3522362ceecef6f6b281`. La 0.1.9-alpha completa le lacune di codice individuate nell'audit del 12 ottobre 2026. La parità verificata e la qualifica stabile richiedono anche le prove indicate sotto. La disposizione delle schermate segue Android; comportamenti, calcoli e autorizzazioni restano confrontabili con il riferimento.

| Area | Codice aggiunto o corretto |
|---|---|
| Finanza | Movimenti propri e condivisi, quote personali/partner, filtro e rimozione confermata; cambio automatico corrente o manuale; palette categorie, ordine budget, date opzionali e collegamenti tra viste. |
| Importazioni | CSV bancari/Revolut, investimenti ISIN, gestione mapping, nuove categorie, keyword e note. Anteprima senza scritture, conferma esplicita, UUID conservati e verifica delle risposte ambigue. Collegamento esterno a Bank2CSV. |
| FIRE e moduli | Grafico nominale/reale con target, anno selezionabile e contributi; input mensili/annuali; reset selettivo dal database. Fumo conserva i valori legacy e i derivati indipendenti dalla durata. L'editor Poker conserva il mese del record. |
| Account e documenti | Consenso alla registrazione, stato delle registrazioni, QR TOTP locale, chiusura delle altre sessioni e conferma del recupero Admin. Privacy, termini e cookie IT/EN estratti dal web, con collegamenti nativi tra documenti. |
| Integrità delle operazioni | Scritture associate all'utente e alla revisione della sessione iniziale; retry 401 senza cambio di login; mapping con confronto atomico dei valori precedenti. Serie Manga associate anche all'UUID del login originario. |

CSV è il formato supportato dal web. XLS/XLSX, ripristino globale dell'account e convertitore PDF nativo non sono lacune di parità: la precedente indicazione era errata. Il convertitore Bank è un prodotto distinto, aperto nel browser anche dal sito.

L'[audit statico successivo](STATIC_PARITY_AUDIT_2026_10_12.md) corregge ulteriori problemi di sessione, elaborazione parziale, valuta TCG e presentazione. Queste modifiche sono da verificare: nessun test o build eseguito durante l'audit, nessuna nuova release. Le prove storiche restano associate ai rispettivi sorgenti.

## Verifiche locali

I risultati definitivi, i log delle esecuzioni fallite e i limiti delle fixture sono registrati in [TESTING.md](TESTING.md). Build, test, lint e firma vengono eseguiti sul computer locale; nessuna CI GitHub. Le fixture HTTP/TLS usano dati sintetici e verificano il client. Non dimostrano da sole che email, ruoli e policy del servizio remoto funzionino come previsto.

La firma resta quella delle versioni precedenti. Le vecchie APK pubblicate rimangono immutabili; il codice di versione della candidata è 10. Il registro `web-baseline.json` mantiene `globalParityVerified=false` e non associa ancora un'APK a una certificazione globale.

## Condizioni per la 1.0.0

La prima versione stabile, senza alpha/beta e con tutte le funzioni, sarà la stessa milestone **1.0.0**. Richiede:

1. Prove reali di accesso con password, email/PKCE, MFA, revoca sessioni e modifiche account.
2. Due utenti sintetici per Coppia: invito, quote, rimozione, budget e revoca con verifica della visibilità su entrambi gli account.
3. Un ruolo Admin sintetico per le operazioni consentite e un account sacrificabile per l'eliminazione. Nessun cambio ai dati degli utenti reali.
4. Confronto finale web/Android sui casi obbligatori, aggiornamento firmato e verifica dei servizi esterni; Google Books aveva risposto HTTP 429 nella precedente prova.

Il 12 ottobre, dopo autorizzazione esplicita dell'utente, sono stati creati tre nuovi account QA: due utenti standard con tutti i moduli abilitati e un Admin. La sequenza di apertura temporanea, creazione e ripristino delle registrazioni è durata 2,249 secondi; lo stato finale `false` è verificato. Accesso con password e lettura autenticata dei ruoli passano per tutti e tre tramite API dal computer. Le tre sessioni di verifica sono state chiuse. Le password casuali sono cifrate con Windows CurrentUser DPAPI fuori dalla repository e dall'APK.

Il rapporto locale `artifacts/qa-account-provisioning-result.json`, run `5ef2838c-ae5b-47d9-ac05-72dc5362b6a9`, registra UUID e risultati senza segreti. Nessun utente precedente, schema, trigger o RLS è stato modificato. Il ruolo Admin ha i permessi del backend: i test limitano le scritture ai nuovi UUID QA. Gli indirizzi sintetici non hanno una casella email e non verificano consegna o callback PKCE; l'utente non dispone di una mailbox dedicata.

Le prove successive passano per accesso nativo con password, TOTP positivo, revoca delle altre sessioni con refresh effettivamente respinto, invito/quote/budget/revoca Coppia e lettura/diniego/ripristino permessi Admin. Hanno scoperto e corretto il parser dei timestamp su Android 12; queste modifiche non sono ancora nell'APK pubblico. [QA_QUALIFICATION.md](QA_QUALIFICATION.md) descrive esiti, pulizie, scope e percorsi ancora esclusi. Il gate email/PKCE e gli altri casi obbligatori restano aperti.

La stima iniziale per questa integrazione era **12–22 ore operative**, con finestra indicativa **14–15 ottobre 2026**. È una stima condizionata alla disponibilità degli account e all'esito delle prove reali, non una data di pubblicazione garantita. Il superamento dei test locali consente una nuova candidata alpha; non sostituisce i gate della 1.0.

## Differenze dichiarate

Il cambio automatico usa l'ultima quotazione disponibile, anche se la transazione ha una data passata; il form lo dichiara e permette il cambio manuale. L'importazione conserva invece il tasso fornito per la data della transazione. La valuta non viene sostituita implicitamente con EUR.

Grafici e riepiloghi mantengono valute separate e criteri coerenti nei periodi aperti. Gli scostamenti intenzionali dai predicati incoerenti del web sono descritti in API_CONTRACTS_FINANCE.md e INSIGHTS_GAPS.md. I journal delle importazioni e delle serie Manga supportano la ricreazione Android, senza promettere una ripresa automatica dopo un arresto arbitrario del processo. Le operazioni in più richieste non costituiscono una transazione atomica del backend.
