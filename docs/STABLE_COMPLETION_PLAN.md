# Piano di completamento della stabile

## Base all'avvio di V02 — aggiornamento del 13 ottobre 2026

Prevedere **40–60 ore di lavoro attivo di base**, più **8–12 ore di riserva**, quindi **48–72 ore** per preparare le prove residue, correggere i difetti emersi, integrare le regressioni e chiudere il pacchetto. La stima presuppone che le risorse esterne siano disponibili quando servono; la loro attesa è esclusa e non consente una promessa di data.

La stima provvisoria di 40–60 ore resta plausibile **senza riserva**, se le implementazioni attuali superano i nuovi scenari e non cambiano i contratti distribuiti. È ottimista come impegno per una stabile completa: gli ultimi rapporti conservano errori del driver, blocchi Compose e recuperi. La riserva copre questo lavoro aggiuntivo, non una riscrittura o nuove funzionalità.

Il punto di partenza della stima è il consuntivo D01: produzione `a104e8c`, APK locale 0.1.12-alpha/code 13, SHA-256 `3e5252bb4c4ff0b3b7c1630c92598cd8d909c1e27b48077699c1fea33fc74bd6`. All'avvio di V02 la [matrice](STABLE_VERIFICATION_MATRIX.md) conta **7 righe chiuse localmente e 26 aperte**: V01/R01/C01/C02/A04/A05/D01 sono chiuse nei rispettivi perimetri. Questa è la base storica della stima, non un nuovo consuntivo dell'APK in verifica V02. `globalParityVerified=false`, `verifiedApkVersion=null` e `datasetCasesExecuted=false` restano invariati.

V02 è **non qualificata**. Il coordinatore comunica un primo gruppo debug di dodici casi con **sette successi e cinque fallimenti**, oltre al primo driver nativo fallito, con pulizia in corso al momento dell'aggiornamento. Questi tentativi non chiudono V02 e non riducono le 26 righe della base. Gli esiti conclusivi e la pulizia verificata saranno registrati separatamente; questo piano non ne anticipa il risultato.

Le 26 righe sono criteri di prova, non 26 funzioni da implementare. Più criteri condividono fixture, attori QA, dati e controlli. Non occorre rifare i blocchi già qualificati nel loro perimetro, ma i loro PASS non si trasferiscono automaticamente a nuovi byte dell'app.

## Cinque blocchi di lavoro

Le ore sono cumulative tra collaboratori e comprendono integrazione, evidenze e pulizia QA. Build e percorsi backend coordinati restano sequenziali: quattro agenti non dividono automaticamente il budget per quattro.

| Blocco e righe coperte | Lavoro autonomo | Lavoro dopo prerequisiti esterni | Totale di base |
|---|---:|---:|---:|
| **1. Navigazione e finanza: V02–V08, V14** — 8 righe. Mapping, back/cambio account e ruoli; CRUD/filtri, ricorrenze, grafici, portafoglio, patrimonio e insights; errori/rete/vuoto e input. | 14–20 h | 0 h | 14–20 h |
| **2. Moduli: V09–V13** — 5 righe. FIRE preferenze/import e conferma; Poker mese/concorrenza; Fumo tipi/legacy; TCG condizioni/valute; Libreria/Manga null, volumi e ripresa. | 8–12 h | 0 h | 8–12 h |
| **3. Documenti: F01–F04** — 4 righe. CSV, ISIN/mapping, duplicati/esiti ambigui; export multipagina con entità popolate; process death e storage; picker e catena Bank2CSV. | 7–10 h | 1–2 h | 8–12 h |
| **4. Account, servizi e telefono: A01–A03, D02, S01–S02, R03** — 7 righe. Rami Auth/profilo; email/PKCE e identità sacrificabile; azioni Admin escluse; Books/CardTrader/FX positivi; campione fisico. | 1–3 h | 5–7 h | 6–10 h |
| **5. Continuità e decisione: R02, R04** — 2 righe. Regressione finale, upgrade autenticato, recovery fuori computer, revisione editoriale/addendum e pacchetto firmato. | 3–4 h | 1–2 h | 4–6 h |
| **Somma senza riserva: 26 righe distinte** | **33–49 h** | **7–11 h** | **40–60 h** |

La riserva di 8–12 ore si aggiunge una sola volta, non per ogni riga. Con giornate di otto ore dedicate al progetto, 48–72 ore corrispondono a sei–nove giornate cumulative di lavoro. Non sono un calendario di uscita: attese esterne e disponibilità delle risorse possono allungare il tempo trascorso senza aumentare altrettanto le ore attive.

### Come sono costruite le ore

| Blocco | Sottoattività, evitando di contare due volte i controlli condivisi |
|---|---|
| 1 | V02 **2–4 h**; CRUD finanza/ricorrenze **4–5 h**; grafici/patrimonio/insights **3–4 h**; portafoglio **3–4 h**; stati trasversali aggiuntivi **2–3 h**. Totale 14–20 h. |
| 2 | FIRE **1,5–2,5 h**; Poker **2–3 h**; Fumo **1–1,5 h**; TCG **1,5–2,5 h**; Libreria/Manga **2–2,5 h**. Totale 8–12 h, esclusi i servizi reali del blocco 4. |
| 3 | Import/ISIN/riconciliazione **3–4 h**; export multipagina e altre entità **2–3 h**; interruzioni/storage **2–3 h**; provider cloud **1–2 h**. Totale 8–12 h. Picker locale e Bank2CSV sono compresi nei primi tre punti. |
| 4 | Rami e preparazione già disponibili **1–3 h**; email/identità dedicate/azioni escluse **3–4 h**, servizi positivi **1–2 h**, campione fisico **1 h**, dopo disponibilità delle risorse. Totale 6–10 h, senza attesa del fornitore. |
| 5 | Regressione e upgrade della candidata finale **2–3 h**; consolidamento editoriale/pacchetto **1 h**; recovery su altro ambiente/supporto autorizzato **1–2 h**. Totale 4–6 h, senza trasporto o ottenimento del supporto. |

Sono ipotesi di pianificazione, non benchmark. Comprendono nuove fixture o adattamenti del driver e correzioni limitate. Un difetto di persistenza o contratto che richieda modifiche a più componenti impone di ristimare il lavoro prima del blocco aggiuntivo.

La dichiarazione min SDK 26 introduce una verifica ulteriore: [TESTING.md](TESTING.md) distingue le prove API 31/35 dalle API 26–28 non qualificate; sei casi documenti richiedono API 29+. Se la stabile mantiene il supporto dichiarato dal manifest, riservare **altre 2–4 ore autonome** per compatibilità sul minimo e percorsi documenti applicabili. Non sono comprese nelle prove API 31/35: il budget prudente diventa **50–76 ore**, sempre esclusa l'attesa esterna.

## Evidenze esistenti e prove mancanti

Il [confronto visivo](COMMON_VISUAL_PARITY_REVIEW.md) conserva 81 PNG nativi, 114 web appaiati, 28 casi mirati e il dataset comune. Osserva valori e presentazione; non copre tutti i form, filtri, stati vuoti e azioni dei primi due blocchi. `NavigationScreenTest`, `SavelyNavigationAccessibilityTest`, `FinanceScreenTest`, `CommonDatasetVisualTest` e le fixture di ciascun modulo sono punti di ripresa, non prove del nuovo percorso firmato.

V02 richiede **tutte le destinazioni del mapping [FEATURE_PARITY.md](FEATURE_PARITY.md)**, back, cambio login e destinazioni negate, conservando lingua e tema. Includere IT/EN, font grande e ruoli A/B/Admin; verificare assenza di dati precedenti dopo cambio ruolo/sessione. Il campione firmato non deve conservare la lista completa degli utenti Admin. Le **2–4 ore sono una previsione all'avvio**, comprendente preparazione, percorsi, correzioni limitate, evidenze e pulizia: non indicano il tempo ancora necessario dopo i tentativi falliti. Le ore aggiuntive vanno registrate nella riserva o ristimate con il consuntivo del blocco.

`FirePersistenceTest`, `FireSharingScreenTest`, `PokerScreenTest`, `FumoScreenTest`, `TcgScreenTest`, `LibraryScreenTest`, `CollectionScreenTest` e le fixture delle panoramiche coprono già casi significativi. V09–V13 chiedono i rami residui: form EN completo, account diversi, default DB, concorrenza sul mese Poker, CBD/THC, categorie e ripresa Manga. S01/S02 sono conteggiate a parte per evitare di ripetere il costo dei servizi.

Le [evidenze release](SIGNED_RELEASE_QUALIFICATION.md) del code 11 osservano export locale di undici categorie, annulla/process death del picker, preview BANK/USD e NORMALIZED/EUR, più un form FX positivo. Le altre tabelle erano vuote: non dimostrano pagine reali multiple, export di altre entità popolate, import committato o provider cloud. `TransferDocumentScreenTest`, `TransferMutationRepositoryTest`, `TransferExportSessionTest` e `TransferParityScreenTest` permettono di preparare i rami senza rete; F02/F03 richiedono anche riletture dopo scrittura e riconciliazione QA.

I rapporti [MFA](MFA_FORM_QUALIFICATION.md), [sessioni](SESSION_FORM_QUALIFICATION.md) e [Admin](ADMIN_FORM_QUALIFICATION.md) qualificano i propri criteri locali, non tutta l'app minificata. La vecchia suite completa API 31/35 e R01 conservano sorgente/APK; 545 JVM e dodici test Admin recenti non sono una nuova suite Android completa. Il pacchetto stabile deve associare regressione finale, firma/checksum, sorgente, fixture e campioni R8 allo stesso candidato, senza presentare tutti i test debug come test R8.

## Dipendenze che impediscono la chiusura completa oggi

| Risorsa mancante o non qualificata | Criteri e conseguenza |
|---|---|
| **Mailbox controllata assente** | A02, rami A01/A03 e reset reale D02. I tre QA `example.invalid` non ricevono email. SMTP dichiarato riuscito, conferma forzata o callback sintetico non qualificano consegna, link ricevuto e recovery reale. Preparare le fixture resta autonomo; serve un destinatario autorizzato per concludere. |
| **Telefono fisico indisponibile per prove dettagliate** | R03 resta aperta. La conferma generale del Doogee S100 sulla 0.1.7 non qualifica i nuovi byte. L'utente ha escluso ulteriori prove dettagliate sul proprio telefono: occorre un altro dispositivo autorizzato o una decisione esplicita sul perimetro. L'AVD non sostituisce il gate né l'interoperabilità scanner MFA fisica non osservata. |
| **Identità sacrificabile e finestra globale autorizzata** | Delete reale A03/D02 e switch registrations D02. Non cancellare i tre QA riutilizzabili. Occorrono identità dedicata e finestra esplicita per lo switch, con ripristino delle registrazioni disabilitate. D01 non copre queste azioni. |
| **Provider cloud e servizi positivi** | F01/F03 richiedono un provider controllato, non ancora attestato. S01 conserva HTTP 429, senza ricerca Books riuscita. S02 ha un esempio FX USD e contratti CardTrader storici, senza chiudere form EUR, altre date/valute o indisponibilità. Bank2CSV è separato nel browser: provare CSV prodotto→picker→preview→commit, senza introdurre XLS/XLSX o convertitore PDF nativo assenti dal web. |
| **Recovery fuori computer non qualificata** | R02: archivio AES e chiave separata sono recuperati/usati sullo stesso computer. Mancano copia protetta ed esecuzione su altro ambiente autorizzato, conservando gli originali. Un secondo percorso dello stesso disco non chiude il criterio. |

Non è disponibile **alcuna durata d'attesa garantita**. Le 7–11 ore della tabella indicano le azioni quando le risorse saranno disponibili, non il tempo per ottenere mailbox, dispositivo, quota Books o supporto recovery. Senza queste risorse è possibile migliorare la qualifica locale; una stabile con tutte le funzioni qualificate non può essere attestata in 40, 60 o 76 ore dal solo lavoro sul codice.

## Sequenza e controllo del budget

1. Concludere V02: mapping, policy reali A/B/Admin, cambio login, IT/EN e lingua/tema/font; conservare risultati e perimetro senza acquisire utenti reali.
2. Qualificare finanza/moduli dalle fixture esistenti, con le sole scritture QA necessarie; registrare divergenze prima delle correzioni e rileggere UUID/valori dopo ogni scrittura.
3. Provare documenti con dati popolati e interruzioni reali; aprire servizi e account soltanto quando i prerequisiti autorizzati sono pronti.
4. Dopo ogni blocco confrontare ore attive e budget, ristimando solo i residui. Non sommare successi parziali di run interrotti; conservare fallimenti e recuperi.
5. Congelare la fonte finale, completare regressione/upgrade autenticato, recovery/editoria e compatibilità dichiarata; produrre APK/checksum/rapporto. Pubblicazione ed eventuali esclusioni richiedono una decisione propria, senza CI necessaria.

Fonti: [matrice dei 26 residui](STABLE_VERIFICATION_MATRIX.md), [gate non di codice](STABLE_NON_CODE_GATES.md), [qualifica firmata](SIGNED_RELEASE_QUALIFICATION.md), [release](RELEASE.md), [inventario Bank](BANK_INVENTORY.md) e rapporti locali citati. I numeri 29/31/32 nelle sezioni storiche non sostituiscono il head D01 con 26 residui. Questo piano non aggiorna roadmap, baseline o qualifiche; durante la redazione non vengono eseguiti test, build, dispositivi, backend o CI.
