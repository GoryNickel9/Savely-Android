# Savely Android

App Android nativa di Savely in Kotlin e Jetpack Compose, con account e dati condivisi con il sito.

Stato al 12 ottobre 2026: candidata 0.1.9-alpha implementata e APK firmato; 503 test JVM e due singole suite complete di 130 casi ciascuna su Android 12 e 15 superati. Lint debug/release: zero errori, 32 avvertenze e 6 suggerimenti. Completate le lacune individuate per condivisione, cambi, import ISIN/mapping, grafico FIRE, account e documenti IT/EN. Risultati e limiti sono in [TESTING.md](docs/TESTING.md); le condizioni della 1.0 stabile sono in [PARITY_0_1_9.md](docs/PARITY_0_1_9.md). La parità globale e i flussi reali di password/email/MFA, Coppia con due account e Admin restano aperti.

Sono presenti accesso e sessione cifrata, dashboard, transazioni, categorie, budget, ricorrenze, grafici, portafoglio, patrimonio, import/export CSV, FIRE, Poker, Fumo, TCG, Libreria, Coppia e impostazioni. La presenza di una schermata non certifica tutti i flussi: i contratti e le lacune sono registrati nei documenti delle singole aree.

Per compilare: JDK 17 o superiore, Android SDK 36 e Gradle wrapper incluso. Copiare `savely.properties.example` in `savely.properties` e inserire esclusivamente URL e chiave pubblica Supabase. Eseguire `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. L'APK viene prodotto in `app/build/outputs/apk/debug/`. I token utente sono cifrati con una chiave Android Keystore; nessuna chiave amministrativa appartiene alla build.

| Documento | Contenuto |
|---|---|
| [ROADMAP.md](ROADMAP.md) | Fasi, attività, dipendenze, subagent, stime e criteri di completamento |
| [docs/FEATURE_PARITY.md](docs/FEATURE_PARITY.md) | Inventario delle funzioni e procedura per confrontare Android e web |
| [docs/PARITY_0_1_9.md](docs/PARITY_0_1_9.md) | Lacune completate nella candidata e condizioni della 1.0 stabile |
| [docs/DEVELOPMENT_AND_TESTING.md](docs/DEVELOPMENT_AND_TESTING.md) | Processi di sviluppo, test e informazioni documentate su PokerStars |
| [docs/TESTING.md](docs/TESTING.md) | Comandi, risultati eseguiti e verifiche ancora aperte |
| [docs/RELEASE.md](docs/RELEASE.md) | Firma, installazione e continuità degli aggiornamenti |
| [docs/API_CONTRACTS_AUTH.md](docs/API_CONTRACTS_AUTH.md) | Auth, MFA, sessioni e API |
| [docs/API_CONTRACTS_FINANCE.md](docs/API_CONTRACTS_FINANCE.md) | Finanza, calcoli e differenze note |
| [docs/API_CONTRACTS_MODULES.md](docs/API_CONTRACTS_MODULES.md) | FIRE, Poker, Fumo, TCG e Libreria |
| [docs/IMPORT_EXPORT.md](docs/IMPORT_EXPORT.md) | Formati CSV, anteprima, duplicati ed export |

La repository [Savely](https://github.com/GoryNickel9/Savely) resta invariata. L'app accede alle API senza WebView. Conferma email e recupero password usano PKCE e il callback Android; la configurazione aggiuntiva preserva i callback web. Il completamento tramite email sul servizio reale resta da verificare.

Gli APK sono distribuiti nella repository pubblica [Savely-Android-Releases](https://github.com/GoryNickel9/Savely-Android-Releases); i sorgenti restano privati. L’aggiornamento richiede download e conferma dell’installazione da parte di Android.

Download [0.1.8-alpha](https://github.com/GoryNickel9/Savely-Android-Releases/releases/tag/v0.1.8-alpha), con APK firmato, checksum e rapporto di verifica. Il primo passaggio dalla 0.1.7 richiede il download manuale.
