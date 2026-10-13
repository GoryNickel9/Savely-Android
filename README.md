# Savely Android

La [qualifica Admin D01](docs/ADMIN_FORM_QUALIFICATION.md) passa sulla candidata locale **0.1.12-alpha/code 13**: undici checkpoint sul release firmato API 31/EN, inclusi salvataggio, perdita del ruolo, rimozione di cache/dialogo e ripristino. Corrette le letture del ruolo e i controlli prima della pubblicazione dei dati, vincolati alla sessione. Passano **545 JVM per variante e 12/12 fixture per ciascuna API 31/35**; lint senza errori. Conservati i tentativi falliti e i blocchi delle fixture, poi corretti nel driver e nella regola Compose. Permessi QA ripristinati, fattori/sessioni zero ed emulatori spenti. **Sette righe chiuse nei rispettivi perimetri locali, 26 verifiche aperte**; nessuna CI o pubblicazione, parità globale ancora aperta.

## Consuntivi precedenti

La [qualifica sessioni A05](docs/SESSION_FORM_QUALIFICATION.md) passa sullo stesso release firmato **0.1.11-alpha/code 12**: nove checkpoint dai form QA, refresh della sessione revocata respinto e sessione superstite rinnovabile dopo la revoca corrente. I client secondari sono host in RAM; il campione nativo è API 31/EN. Passano 7/7 fixture su ciascuna API 31/35 e 543 JVM per variante; lint senza errori. Corretto soltanto il teardown di una fixture JVM, conservando i primi fallimenti. Fattori/sessioni QA zero. **Sei righe chiuse nei rispettivi perimetri locali, 27 verifiche aperte**; produzione invariata, nessuna CI o pubblicazione e parità globale ancora aperta.

Consuntivo precedente: la [qualifica MFA A04](docs/MFA_FORM_QUALIFICATION.md) passa sulla candidata locale **0.1.11-alpha/code 12**: 14 checkpoint dai form QA sul release firmato, 14/14 fixture su ciascuna API 31/35 e 540 JVM per variante debug/release; lint senza errori. Corretti gate riferito a un login sostitutivo, risposte enrollment malformate e controlli busy. Setup manuale, codice errato, pending, nuovo login, annulla e rimozione hanno prove osservate; il QR del provider non è visualizzato nel campione, mentre renderer e fallback passano nelle fixture locali. Fattori/sessioni QA zero ed emulatori spenti. **V01/R01/C01/C02/A04 chiuse nei rispettivi perimetri locali: 28 verifiche aperte.** Nessuna CI o nuova pubblicazione; le suite complete precedenti non qualificano automaticamente questa candidata.

Consuntivo precedente: la [qualifica C02](docs/COUPLE_FORM_QUALIFICATION.md) verifica sul release firmato API 31 i form di quote EUR/USD, budget, quattro rimozioni e revoca con archivio in sola lettura. Passano 14/14 fixture su Android 12 e 15 e 31 guardie host; 220 letture REST dei due ruoli coincidono. Eliminati i soli 16 record residui del run, sessioni QA attive e fattori zero, emulatori spenti. Il primo crash Compose API 35 resta documentato, con causa non dimostrata e successive esecuzioni sullo stesso APK superate. Al termine di C02 erano chiuse V01/R01/C01/C02 e restavano **29 verifiche aperte**. La candidata 0.1.10-alpha resta locale, senza nuova pubblicazione o parità globale.

Consuntivo precedente: la [qualifica degli inviti Coppia](docs/COUPLE_INVITATION_QUALIFICATION.md) verifica rifiuto e cancellazione dai form QA sullo stesso APK firmato, quattro riletture REST per ruolo e pulizia dei soli sei record temporanei. Passano 7/7 casi Coppia su entrambe le API e 18 guardie host offline. La matrice conservava 30 verifiche aperte.

## Candidata locale 0.1.10-alpha — qualifica locale conclusa

La candidata locale 0.1.10-alpha/code 11 usa produzione `459bbac` e APK release immutabile SHA-256 `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa`. Passano 536 JVM per variante debug/release; lint di entrambe: zero errori, 32 avvertenze e 6 suggerimenti. La suite debug API 35 passa 170/170 in 562,616 s sul test source `74d7d0d`; API 31 passa 170/170 in 551,778 s, senza failure/skip, in una singola esecuzione completa. Il campione sul release firmato R8 è una prova distinta dalla suite debug.

Il campione firmato verifica aggiornamento autenticato su API 35, export locale di 11 categorie/1.696 byte e il messaggio di nuova preparazione dopo process death, con rimozione del solo nuovo documento vuoto. Il nuovo run firmato API 31 passa login, profilo, upgrade, English/Light e logout, con la stessa sessione server prima/dopo l’upgrade e zero sessioni al termine. Export e preview BANK/USD dopo process death appartengono al precedente run separato, chiuso con logout UI e server zero. Il form FX nativo API 31 salva 0,01 USD con cambio EUR 0,894370; l’eliminazione UI lascia zero righe QA. Google Books risponde ancora HTTP 429.

Conservati 81 PNG e 81 JSON per API; raccolta conclusa ed emulatori spenti, come confermato dal responsabile dell’integrazione.

[Qualifica firmata](docs/SIGNED_RELEASE_QUALIFICATION.md), [test](docs/TESTING.md) e [matrice](docs/STABLE_VERIFICATION_MATRIX.md) conservano i perimetri. R01 è **PASS LOCALE** nel criterio originale di build/firma/lint, suite debug API 31/35 sullo stesso sorgente e comportamento osservato del campione R8; nessuna CI, nuova pubblicazione o parità globale. La release pubblica resta 0.1.9-alpha.

Precedente [verifica locale CSV/profilo](docs/RECREATION_REGRESSION_VERIFICATION.md): 530 JVM, 36 host Python e due suite complete da 164/164 casi su Android 12/15 passano; lint debug/release senza errori. Le otto nuove regressioni qualificano le patch `de53ac7`; corretto il montaggio delle sole fixture in `f60ab38`, conservando sei failure iniziali. Emulatori spenti, nessuna CI, prova backend o nuova release pubblica. In quel consuntivo la [matrice](docs/STABLE_VERIFICATION_MATRIX.md) qualificava V01 localmente e conservava 32 verifiche aperte e i 50 casi del dataset.

App Android nativa di Savely in Kotlin e Jetpack Compose, con account e dati condivisi con il sito.

Evidenza storica di distribuzione al 12 ottobre 2026: 0.1.9-alpha pubblicata con APK firmato, 503 test JVM e due suite complete da 130 casi su Android 12 e 15. Il codice successivo corregge il parser dei timestamp su Android 12, le operazioni Admin durante un cambio account e lo stato occupato del recupero MFA. Passano 514 test JVM e 132 test ordinari su Android 12; i nuovi account QA verificano accesso nativo con password, TOTP, revoca delle altre sessioni, un flusso Coppia con due utenti e lettura/diniego/ripristino dei permessi Admin. Queste modifiche non sono ancora in un nuovo APK pubblico. [QA_QUALIFICATION.md](docs/QA_QUALIFICATION.md) riporta prove e limiti; [TESTING.md](docs/TESTING.md) conserva i risultati delle release. Email/PKCE reali e gli altri gate della [1.0 stabile](docs/PARITY_0_1_9.md) restano aperti.

Sono presenti accesso e sessione cifrata, dashboard, transazioni, categorie, budget, ricorrenze, grafici, portafoglio, patrimonio, import/export CSV, FIRE, Poker, Fumo, TCG, Libreria, Coppia e impostazioni. La presenza di una schermata non certifica tutti i flussi: i contratti e le lacune sono registrati nei documenti delle singole aree.

L'[audit statico Android/web](docs/STATIC_PARITY_AUDIT_2026_10_12.md) è stato seguito dalla [verifica locale](docs/STATIC_AUDIT_VERIFICATION.md) autorizzata: 528 JVM, 36 host Python e due suite complete da 132 casi su Android 12/15 passano. Lint debug/release senza errori; documenti IT/EN riproducibili. Corrette due attese delle fixture, le chiavi del generatore e le note FIRE/Libreria. Sorgente finale `4d6a376`, nessuna CI o nuova release pubblica. I [gate senza nuove funzionalità](docs/STABLE_NON_CODE_GATES.md) distinguono prove reali e ostacoli ancora aperti.

Il [confronto osservato Android/web](docs/COMMON_VISUAL_PARITY_REVIEW.md) sul dataset comune registra una nuova esecuzione di 530 test JVM e un run completo di 28/28 test mirati su Android 12/API 31, con 81 PNG nativi e 114 PNG web appaiati. Corretti layout Sicurezza/storico e accesso a Dati con testo grande, tastiera dei grafici e percentuali/scala FIRE; aggiunta la distribuzione TCG per quantità. Lint debug senza errori; risultati circoscritti a fixture locali, senza CI, backend reale o nuova release. Il confronto di tutti i flussi e i gate della stabile restano aperti.

Per compilare: JDK 17 o superiore, Android SDK 36 e Gradle wrapper incluso. Copiare `savely.properties.example` in `savely.properties` e inserire esclusivamente URL e chiave pubblica Supabase. Eseguire `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. L'APK viene prodotto in `app/build/outputs/apk/debug/`. I token utente sono cifrati con una chiave Android Keystore; nessuna chiave amministrativa appartiene alla build.

| Documento | Contenuto |
|---|---|
| [ROADMAP.md](ROADMAP.md) | Fasi, attività, dipendenze, subagent, stime e criteri di completamento |
| [docs/FEATURE_PARITY.md](docs/FEATURE_PARITY.md) | Inventario delle funzioni e procedura per confrontare Android e web |
| [docs/PARITY_0_1_9.md](docs/PARITY_0_1_9.md) | Lacune completate nella candidata e condizioni della 1.0 stabile |
| [docs/QA_QUALIFICATION.md](docs/QA_QUALIFICATION.md) | Account dedicati, prove reali e correzioni successive alla release pubblica |
| [docs/STATIC_PARITY_AUDIT_2026_10_12.md](docs/STATIC_PARITY_AUDIT_2026_10_12.md) | Confronto dei sorgenti, correzioni e verifiche rinviate |
| [docs/STATIC_AUDIT_VERIFICATION.md](docs/STATIC_AUDIT_VERIFICATION.md) | Esiti locali finali e fallimenti conservati dell'audit |
| [docs/STABLE_NON_CODE_GATES.md](docs/STABLE_NON_CODE_GATES.md) | Checklist delle prove e revisioni residue per la 1.0 |
| [docs/STABLE_VERIFICATION_MATRIX.md](docs/STABLE_VERIFICATION_MATRIX.md) | Qualifica locale, verifiche aperte, sorgenti e dipendenze |
| [docs/MFA_FORM_QUALIFICATION.md](docs/MFA_FORM_QUALIFICATION.md) | Setup, gate e rimozione MFA sul release firmato, fixture, pulizia e limiti del QR |
| [docs/SESSION_FORM_QUALIFICATION.md](docs/SESSION_FORM_QUALIFICATION.md) | Revoca singola dai form, refresh dei client QA, fixture, pulizia e limiti del campione |
| [docs/COUPLE_FORM_QUALIFICATION.md](docs/COUPLE_FORM_QUALIFICATION.md) | Quote, budget, quattro rimozioni e revoca dai form QA sul release firmato, fixture e tentativi conservati |
| [docs/RECREATION_REGRESSION_VERIFICATION.md](docs/RECREATION_REGRESSION_VERIFICATION.md) | Regressioni CSV/profilo, failure iniziali, suite complete API 31/35 e checksum degli APK debug |
| [docs/COMMON_DATASET_PARITY_REVIEW.md](docs/COMMON_DATASET_PARITY_REVIEW.md) | Dataset sintetico comune, attese dai sorgenti e regressioni locali rieseguite con tre subagent |
| [docs/COMMON_VISUAL_PARITY_REVIEW.md](docs/COMMON_VISUAL_PARITY_REVIEW.md) | Confronto osservato, correzioni UI e risultati locali sul dataset comune |
| [docs/DEVELOPMENT_AND_TESTING.md](docs/DEVELOPMENT_AND_TESTING.md) | Processi di sviluppo, test e informazioni documentate su PokerStars |
| [docs/TESTING.md](docs/TESTING.md) | Comandi, risultati eseguiti e verifiche ancora aperte |
| [docs/RELEASE.md](docs/RELEASE.md) | Firma, installazione e continuità degli aggiornamenti |
| [docs/API_CONTRACTS_AUTH.md](docs/API_CONTRACTS_AUTH.md) | Auth, MFA, sessioni e API |
| [docs/API_CONTRACTS_FINANCE.md](docs/API_CONTRACTS_FINANCE.md) | Finanza, calcoli e differenze note |
| [docs/API_CONTRACTS_MODULES.md](docs/API_CONTRACTS_MODULES.md) | FIRE, Poker, Fumo, TCG e Libreria |
| [docs/IMPORT_EXPORT.md](docs/IMPORT_EXPORT.md) | Formati CSV, anteprima, duplicati ed export |

La repository [Savely](https://github.com/GoryNickel9/Savely) resta invariata. L'app accede alle API senza WebView. Conferma email e recupero password usano PKCE e il callback Android; la configurazione aggiuntiva preserva i callback web. Il completamento tramite email sul servizio reale resta da verificare.

Gli APK sono distribuiti nella repository pubblica [Savely-Android-Releases](https://github.com/GoryNickel9/Savely-Android-Releases); i sorgenti restano privati. L’aggiornamento richiede download e conferma dell’installazione da parte di Android.

Download [0.1.9-alpha](https://github.com/GoryNickel9/Savely-Android-Releases/releases/tag/v0.1.9-alpha), con APK firmato, checksum, note e rapporto di verifica. Il primo passaggio dalla 0.1.7 richiede il download manuale; dalla 0.1.8 il controllo aggiornamenti consulta la repository pubblica.
