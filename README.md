# Savely Android

App Android nativa di Savely in Kotlin e Jetpack Compose, con account e dati condivisi con il sito.

Stato al 11 ottobre 2026: APK 0.1.4-alpha firmato e ottimizzato, aggiornamento dalla 0.1.3 installato su emulatore Android 15; 232 test JVM e 43 Android superati, lint debug/release senza errori. Admin cancella dati e dialoghi sul diniego; Insights copre cinque rilevatori su otto. Il flusso TCG conserva il prezzo assente e usa il costo di acquisto se il servizio prezzi è indisponibile. La 0.1.2 ha verificato sincronizzazione Android→sito e ripristino della sessione in un nuovo processo. Login con password, telefono fisico e parità completa restano aperti.

Sono presenti accesso e sessione cifrata, dashboard, transazioni, categorie, budget, ricorrenze, grafici, portafoglio, patrimonio, import/export CSV, FIRE, Poker, Fumo, TCG, Libreria, Coppia e impostazioni. La presenza di una schermata non certifica tutti i flussi: i contratti e le lacune sono registrati nei documenti delle singole aree.

Per compilare: JDK 17 o superiore, Android SDK 36 e Gradle wrapper incluso. Copiare `savely.properties.example` in `savely.properties` e inserire esclusivamente URL e chiave pubblica Supabase. Eseguire `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. L'APK viene prodotto in `app/build/outputs/apk/debug/`. I token utente sono cifrati con una chiave Android Keystore; nessuna chiave amministrativa appartiene alla build.

| Documento | Contenuto |
|---|---|
| [ROADMAP.md](ROADMAP.md) | Fasi, attività, dipendenze, subagent, stime e criteri di completamento |
| [docs/FEATURE_PARITY.md](docs/FEATURE_PARITY.md) | Inventario delle funzioni e procedura per confrontare Android e web |
| [docs/DEVELOPMENT_AND_TESTING.md](docs/DEVELOPMENT_AND_TESTING.md) | Processi di sviluppo, test e informazioni documentate su PokerStars |
| [docs/TESTING.md](docs/TESTING.md) | Comandi, risultati eseguiti e verifiche ancora aperte |
| [docs/RELEASE.md](docs/RELEASE.md) | Firma, installazione e continuità degli aggiornamenti |
| [docs/API_CONTRACTS_AUTH.md](docs/API_CONTRACTS_AUTH.md) | Auth, MFA, sessioni e API |
| [docs/API_CONTRACTS_FINANCE.md](docs/API_CONTRACTS_FINANCE.md) | Finanza, calcoli e differenze note |
| [docs/API_CONTRACTS_MODULES.md](docs/API_CONTRACTS_MODULES.md) | FIRE, Poker, Fumo, TCG e Libreria |
| [docs/IMPORT_EXPORT.md](docs/IMPORT_EXPORT.md) | Formati CSV, anteprima, duplicati ed export |

La repository [Savely](https://github.com/GoryNickel9/Savely) resta invariata. L'app accede alle API senza WebView. Il reset password e la conferma email utilizzano ancora il callback web esistente.
